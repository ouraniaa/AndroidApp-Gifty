// Minimal Gemini REST client (generateContent) for greeting card messages.
// The API key is passed in by the caller from env.GEMINI_API_KEY and sent only in the
// x-goog-api-key header, never in the URL, so it cannot leak into URL logs.

export const GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";

// Stable Flash-Lite model with Free Tier availability (verified November 2026).
// Override with the GEMINI_MODEL variable in wrangler.toml if Google retires it.
export const DEFAULT_MODEL = "gemini-3.5-flash-lite";

export const DEFAULT_TIMEOUT_MS = 12000;
export const MAX_OUTPUT_TOKENS = 400;
export const MAX_MESSAGE_CHARS = 900;

const MODEL_PATTERN = /^gemini-[a-z0-9.-]{1,60}$/;

// Finish reasons that mean the output was withheld for policy reasons
const BLOCKED_FINISH_REASONS = new Set([
  "SAFETY",
  "RECITATION",
  "BLOCKLIST",
  "PROHIBITED_CONTENT",
  "SPII",
  "LANGUAGE",
]);

export class GeminiError extends Error {
  // kind: "timeout" | "rate_limited" | "blocked" | "upstream" | "bad_response"
  constructor(kind, { status, retryable = false, retryAfterSeconds } = {}) {
    super(`Gemini request failed: ${kind}${status ? ` (HTTP ${status})` : ""}`);
    this.name = "GeminiError";
    this.kind = kind;
    this.status = status;
    this.retryable = retryable;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export function resolveModel(configured) {
  if (typeof configured === "string" && MODEL_PATTERN.test(configured.trim())) {
    return configured.trim();
  }
  return DEFAULT_MODEL;
}

export function buildGeminiBody(prompt) {
  return {
    systemInstruction: { parts: [{ text: prompt.system }] },
    contents: [{ role: "user", parts: [{ text: prompt.user }] }],
    generationConfig: {
      candidateCount: 1,
      maxOutputTokens: MAX_OUTPUT_TOKENS,
      temperature: 0.9,
      // Short creative text does not need reasoning; keeps latency and token use low
      thinkingConfig: { thinkingLevel: "MINIMAL" },
    },
  };
}

export async function generateWithGemini({ apiKey, model, prompt, fetchImpl = fetch, timeoutMs = DEFAULT_TIMEOUT_MS }) {
  const url = `${GEMINI_BASE_URL}/${encodeURIComponent(model)}:generateContent`;

  let response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": apiKey,
      },
      body: JSON.stringify(buildGeminiBody(prompt)),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    if (err && (err.name === "TimeoutError" || err.name === "AbortError")) {
      throw new GeminiError("timeout", { retryable: true });
    }
    throw new GeminiError("upstream", { retryable: true });
  }

  if (response.status === 429) {
    const retryAfterSeconds = await readRetryAfter(response);
    throw new GeminiError("rate_limited", { status: 429, retryable: true, retryAfterSeconds });
  }

  if (!response.ok) {
    // 5xx (overloaded / unavailable) is worth retrying; 4xx means a config problem
    // such as an invalid key or a retired model, which retrying will not fix.
    const retryable = response.status >= 500;
    throw new GeminiError("upstream", { status: response.status, retryable });
  }

  let data;
  try {
    data = await response.json();
  } catch {
    throw new GeminiError("bad_response", { status: response.status, retryable: true });
  }

  return extractMessage(data);
}

// Pull the message text out of a generateContent response
export function extractMessage(data) {
  if (data && data.promptFeedback && data.promptFeedback.blockReason) {
    throw new GeminiError("blocked");
  }

  const candidate = data && Array.isArray(data.candidates) ? data.candidates[0] : undefined;
  if (!candidate) {
    throw new GeminiError("bad_response", { retryable: true });
  }
  if (BLOCKED_FINISH_REASONS.has(candidate.finishReason)) {
    throw new GeminiError("blocked");
  }

  const parts = candidate.content && Array.isArray(candidate.content.parts) ? candidate.content.parts : [];
  const text = parts
    .filter((part) => part && typeof part.text === "string" && !part.thought)
    .map((part) => part.text)
    .join("");

  const message = cleanMessage(text);
  if (!message) {
    throw new GeminiError("bad_response", { retryable: true });
  }
  return { message, finishReason: candidate.finishReason || null };
}

// Normalize model output into plain card text and enforce the length cap
export function cleanMessage(text) {
  if (typeof text !== "string") return "";

  let cleaned = text
    .normalize("NFC")
    .replace(/\r\n?/g, "\n")
    .replace(/\*\*|__|`/g, "")
    .replace(/^#+\s*/gm, "")
    .split("\n")
    .map((line) => line.trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  // Drop quotation marks wrapped around the whole message
  cleaned = cleaned.replace(/^["“”«]+/, "").replace(/["“”»]+$/, "").trim();

  return truncate(cleaned, MAX_MESSAGE_CHARS);
}

function truncate(text, maxChars) {
  const chars = [...text];
  if (chars.length <= maxChars) return text;

  const head = chars.slice(0, maxChars).join("");
  // Prefer ending on a sentence boundary (";" is the Greek question mark)
  const boundary = Math.max(
    ...[".", "!", "?", ";", "…"].map((mark) => head.lastIndexOf(mark))
  );
  if (boundary > maxChars * 0.5) return head.slice(0, boundary + 1).trim();

  const space = head.lastIndexOf(" ");
  return `${(space > 0 ? head.slice(0, space) : head).trim()}…`;
}

// Retry hint from a 429: the Retry-After header, or a RetryInfo detail in the error body
async function readRetryAfter(response) {
  const header = Number.parseInt(response.headers.get("retry-after") || "", 10);
  if (Number.isFinite(header) && header > 0) return clamp(header, 1, 300);

  try {
    const body = await response.json();
    const details = body && body.error && Array.isArray(body.error.details) ? body.error.details : [];
    const info = details.find((d) => d && typeof d.retryDelay === "string");
    if (info) {
      const seconds = Math.ceil(Number.parseFloat(info.retryDelay));
      if (Number.isFinite(seconds) && seconds > 0) return clamp(seconds, 1, 300);
    }
  } catch {
    // Ignore unreadable bodies and fall back to the default
  }
  return 30;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}
