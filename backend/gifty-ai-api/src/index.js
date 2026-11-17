// gifty-ai-api: Cloudflare Worker that writes greeting card messages with Gemini.
//
//   POST /generate-greeting   body: { occasion, relationship, tone, language, recipientName? }
//                             header: X-Gifty-Install-Id: <UUID>
//   GET  /health              liveness check
//
// Success (200):  { "message": "...", "source": "gemini", "model": "...", "language": "en", "mock": false }
// Failure (4xx/5xx): { "error": { "code": "...", "message": "...", "retryable": bool } }
//
// The Gemini key is read only from env.GEMINI_API_KEY (a Cloudflare Secret).

import { errorResponse, jsonResponse } from "./http.js";
import { MAX_BODY_BYTES, isValidInstallId, validateGreetingRequest } from "./validation.js";
import { buildPrompt } from "./prompt.js";
import { DEFAULT_TIMEOUT_MS, GeminiError, generateWithGemini, resolveModel } from "./gemini.js";
import { RATE_LIMIT_PERIOD_SECONDS, checkRateLimits, hasRateLimiters } from "./rateLimit.js";

export const GREETING_PATH = "/generate-greeting";
export const HEALTH_PATH = "/health";
export const INSTALL_ID_HEADER = "X-Gifty-Install-Id";

class PayloadTooLargeError extends Error {}

export async function handleRequest(request, env, deps = {}) {
  try {
    return await route(request, env || {}, deps);
  } catch (err) {
    // Never echo internals or request data back to the client
    console.error("gifty-ai-api: unexpected error", err && err.name);
    return errorResponse(500, "INTERNAL_ERROR", "Something went wrong. Please try again.", { retryable: true });
  }
}

async function route(request, env, deps) {
  const { pathname } = new URL(request.url);

  if (pathname === HEALTH_PATH) {
    if (request.method !== "GET") {
      return errorResponse(405, "METHOD_NOT_ALLOWED", "Use GET.", { headers: { Allow: "GET" } });
    }
    return jsonResponse(200, { status: "ok" });
  }

  if (pathname !== GREETING_PATH) {
    return errorResponse(404, "NOT_FOUND", "Not found.");
  }

  if (request.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Use POST.", { headers: { Allow: "POST" } });
  }

  return generateGreeting(request, env, deps);
}

async function generateGreeting(request, env, deps) {
  // 1. Browsers always send Origin on cross-site POSTs; the Android app never does.
  if (request.headers.has("Origin")) {
    return errorResponse(403, "FORBIDDEN", "Browser requests are not allowed.");
  }

  // 2. Kill switch: set GREETING_ENABLED = "false" in wrangler.toml to stop all AI calls
  if (String(env.GREETING_ENABLED ?? "true").toLowerCase() === "false") {
    return errorResponse(503, "SERVICE_DISABLED", "Greeting cards are temporarily unavailable.", {
      retryable: true,
      retryAfterSeconds: 300,
    });
  }

  // 3. Fail closed if the secret or the rate limiters are missing
  if (!env.GEMINI_API_KEY || !hasRateLimiters(env)) {
    console.error("gifty-ai-api: missing GEMINI_API_KEY or rate limit bindings");
    return errorResponse(503, "SERVER_MISCONFIGURED", "Greeting cards are temporarily unavailable.", {
      retryable: false,
    });
  }

  // 4. Cheap header checks before doing any work
  const contentType = (request.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase();
  if (contentType !== "application/json") {
    return errorResponse(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
  }

  const installId = request.headers.get(INSTALL_ID_HEADER);
  if (!isValidInstallId(installId)) {
    return errorResponse(400, "INVALID_INSTALL_ID", `${INSTALL_ID_HEADER} must be a UUID.`);
  }

  const declaredLength = Number.parseInt(request.headers.get("Content-Length") || "", 10);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    return errorResponse(413, "PAYLOAD_TOO_LARGE", `Request body must be at most ${MAX_BODY_BYTES} bytes.`);
  }

  // 5. Rate limits run before the body is parsed, so floods of junk are throttled too
  let limit;
  try {
    limit = await checkRateLimits(env, {
      installId,
      ip: request.headers.get("CF-Connecting-IP") || "unknown",
    });
  } catch {
    console.error("gifty-ai-api: rate limiter unavailable");
    return errorResponse(503, "RATE_LIMITER_UNAVAILABLE", "Please try again in a moment.", {
      retryable: true,
      retryAfterSeconds: 30,
    });
  }
  if (!limit.allowed) {
    return errorResponse(429, "RATE_LIMITED", "Too many requests. Please wait a minute and try again.", {
      retryable: true,
      retryAfterSeconds: RATE_LIMIT_PERIOD_SECONDS,
      details: { scope: limit.scope },
    });
  }

  // 6. Read at most MAX_BODY_BYTES, even if Content-Length was missing or wrong
  let body;
  try {
    const text = await readBodyCapped(request, MAX_BODY_BYTES);
    body = JSON.parse(text);
  } catch (err) {
    if (err instanceof PayloadTooLargeError) {
      return errorResponse(413, "PAYLOAD_TOO_LARGE", `Request body must be at most ${MAX_BODY_BYTES} bytes.`);
    }
    return errorResponse(400, "INVALID_JSON", "Request body must be valid JSON.");
  }

  const validation = validateGreetingRequest(body);
  if (!validation.ok) {
    return errorResponse(400, "INVALID_INPUT", "Some of the card details are invalid.", {
      details: validation.errors,
    });
  }

  // 7. Ask Gemini
  const model = resolveModel(env.GEMINI_MODEL);
  try {
    const { message } = await generateWithGemini({
      apiKey: env.GEMINI_API_KEY,
      model,
      prompt: buildPrompt(validation.value),
      fetchImpl: deps.fetchImpl || fetch,
      timeoutMs: deps.timeoutMs || DEFAULT_TIMEOUT_MS,
    });

    return jsonResponse(200, {
      message,
      source: "gemini",
      model,
      language: validation.value.language,
      mock: false,
    });
  } catch (err) {
    if (err instanceof GeminiError) return geminiErrorResponse(err);
    throw err;
  }
}

function geminiErrorResponse(err) {
  // Log only the failure kind and status: no prompt, name or key
  console.error(`gifty-ai-api: gemini ${err.kind}${err.status ? ` ${err.status}` : ""}`);

  switch (err.kind) {
    case "timeout":
      return errorResponse(504, "UPSTREAM_TIMEOUT", "The message took too long to create. Please try again.", {
        retryable: true,
      });
    case "rate_limited":
      return errorResponse(503, "UPSTREAM_RATE_LIMITED", "The card writer is busy right now. Please try again shortly.", {
        retryable: true,
        retryAfterSeconds: err.retryAfterSeconds || 30,
      });
    case "blocked":
      return errorResponse(422, "CONTENT_BLOCKED", "A message could not be created for these details. Try a different tone or occasion.", {
        retryable: false,
      });
    case "upstream":
      return errorResponse(502, err.retryable ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_ERROR",
        "The card writer is unavailable right now. Please try again later.", {
          retryable: err.retryable,
        });
    case "bad_response":
    default:
      return errorResponse(502, "UPSTREAM_ERROR", "Could not create a message. Please try again.", {
        retryable: true,
      });
  }
}

async function readBodyCapped(request, maxBytes) {
  if (!request.body) return "";

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new PayloadTooLargeError();
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  // fatal: invalid UTF-8 throws and is reported as INVALID_JSON
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },
};
