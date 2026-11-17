import { test } from "node:test";
import assert from "node:assert/strict";

import worker, { handleRequest } from "../src/index.js";

const INSTALL_ID = "3f2a9c1e-8b7d-4e6f-9a1b-2c3d4e5f6a7b";
const VALID_BODY = { occasion: "name_day", relationship: "friend", tone: "funny", language: "el", recipientName: "Νίκη" };
const FAKE_KEY = "test-key-not-real";

function fakeLimiter(allow = true) {
  const keys = [];
  return {
    keys,
    async limit({ key }) {
      keys.push(key);
      return { success: allow };
    },
  };
}

function makeEnv(overrides = {}) {
  return {
    GEMINI_API_KEY: FAKE_KEY,
    GEMINI_MODEL: "gemini-3.5-flash-lite",
    GREETING_ENABLED: "true",
    RL_INSTALL: fakeLimiter(),
    RL_IP: fakeLimiter(),
    RL_GLOBAL: fakeLimiter(),
    ...overrides,
  };
}

function makeRequest({ body = VALID_BODY, method = "POST", path = "/generate-greeting", headers = {} } = {}) {
  const merged = new Headers({
    "Content-Type": "application/json",
    "X-Gifty-Install-Id": INSTALL_ID,
    "CF-Connecting-IP": "203.0.113.7",
  });
  for (const [name, value] of Object.entries(headers)) {
    if (value === null) merged.delete(name);
    else merged.set(name, value);
  }
  return new Request(`https://gifty-ai-api.example.workers.dev${path}`, {
    method,
    headers: merged,
    body: method === "GET" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
}

function geminiReturning(text, calls = []) {
  return async (url, init) => {
    calls.push({ url, init });
    return new Response(JSON.stringify({
      candidates: [{ finishReason: "STOP", content: { parts: [{ text }] } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
}

function geminiFailing(status, body = {}, headers = {}) {
  return async () => new Response(JSON.stringify(body), { status, headers });
}

function mustNotCallGemini() {
  return async () => {
    throw new Error("Gemini must not be called");
  };
}

async function errorCode(response) {
  return (await response.json()).error.code;
}

test("returns the generated message in the shape the Android app expects", async () => {
  const calls = [];
  const env = makeEnv();
  const response = await handleRequest(makeRequest(), env, {
    fetchImpl: geminiReturning("Γεια σου Νίκη!\n\nΧρόνια πολλά για τη γιορτή σου!"),
  });

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("Access-Control-Allow-Origin"), null);
  assert.deepEqual(await response.json(), {
    message: "Γεια σου Νίκη!\n\nΧρόνια πολλά για τη γιορτή σου!",
    source: "gemini",
    model: "gemini-3.5-flash-lite",
    language: "el",
    mock: false,
  });

  // All three limiters were consulted with the right keys
  assert.deepEqual(env.RL_INSTALL.keys, [`install:${INSTALL_ID}`]);
  assert.deepEqual(env.RL_IP.keys, ["ip:203.0.113.7"]);
  assert.deepEqual(env.RL_GLOBAL.keys, ["global"]);

  // And Gemini got the key in a header, never in the URL
  await handleRequest(makeRequest(), makeEnv(), { fetchImpl: geminiReturning("Hi!", calls) });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");
  assert.ok(!calls[0].url.includes(FAKE_KEY));
  assert.equal(calls[0].init.headers["x-goog-api-key"], FAKE_KEY);
  const sent = JSON.parse(calls[0].init.body);
  assert.match(sent.contents[0].parts[0].text, /Recipient name: "Νίκη"/);
});

test("default export delegates to the handler", async () => {
  const response = await worker.fetch(makeRequest({ method: "GET", path: "/health" }), makeEnv());
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
});

test("unknown paths and wrong methods are rejected", async () => {
  const env = makeEnv();
  const deps = { fetchImpl: mustNotCallGemini() };

  assert.equal((await handleRequest(makeRequest({ path: "/other" }), env, deps)).status, 404);

  const getResponse = await handleRequest(makeRequest({ method: "GET" }), env, deps);
  assert.equal(getResponse.status, 405);
  assert.equal(getResponse.headers.get("Allow"), "POST");
});

test("browser requests (with an Origin header) are refused", async () => {
  const response = await handleRequest(makeRequest({ headers: { Origin: "https://evil.example" } }), makeEnv(), {
    fetchImpl: mustNotCallGemini(),
  });
  assert.equal(response.status, 403);
  assert.equal(await errorCode(response), "FORBIDDEN");
});

test("header problems are rejected before any work", async () => {
  const deps = { fetchImpl: mustNotCallGemini() };

  const wrongType = await handleRequest(makeRequest({ headers: { "Content-Type": "text/plain" } }), makeEnv(), deps);
  assert.equal(wrongType.status, 415);

  const env = makeEnv();
  const noInstall = await handleRequest(makeRequest({ headers: { "X-Gifty-Install-Id": null } }), env, deps);
  assert.equal(noInstall.status, 400);
  assert.equal(await errorCode(noInstall), "INVALID_INSTALL_ID");
  assert.equal(env.RL_INSTALL.keys.length, 0);
});

test("oversized bodies are rejected with 413", async () => {
  const deps = { fetchImpl: mustNotCallGemini() };
  const big = JSON.stringify({ ...VALID_BODY, padding: "x".repeat(2000) });

  const declared = await handleRequest(makeRequest({ body: big, headers: { "Content-Length": String(big.length) } }), makeEnv(), deps);
  assert.equal(declared.status, 413);

  // Without a Content-Length header the stream is still capped
  const streamed = await handleRequest(makeRequest({ body: big }), makeEnv(), deps);
  assert.equal(streamed.status, 413);
});

test("invalid JSON and invalid fields return 400 with details", async () => {
  const deps = { fetchImpl: mustNotCallGemini() };

  const badJson = await handleRequest(makeRequest({ body: "{not json" }), makeEnv(), deps);
  assert.equal(badJson.status, 400);
  assert.equal(await errorCode(badJson), "INVALID_JSON");

  const badInput = await handleRequest(makeRequest({ body: { ...VALID_BODY, tone: "rude" } }), makeEnv(), deps);
  assert.equal(badInput.status, 400);
  const payload = await badInput.json();
  assert.equal(payload.error.code, "INVALID_INPUT");
  assert.equal(payload.error.retryable, false);
  assert.equal(payload.error.details[0].field, "tone");
});

test("our own rate limit returns 429 with Retry-After and skips Gemini", async () => {
  const env = makeEnv({ RL_IP: fakeLimiter(false) });
  const response = await handleRequest(makeRequest(), env, { fetchImpl: mustNotCallGemini() });

  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Retry-After"), "60");
  const payload = await response.json();
  assert.equal(payload.error.code, "RATE_LIMITED");
  assert.equal(payload.error.retryable, true);
  assert.equal(payload.error.details.scope, "ip");
  assert.equal(env.RL_GLOBAL.keys.length, 0);
});

test("fails closed when the secret or limiters are missing, or the limiter errors", async () => {
  const deps = { fetchImpl: mustNotCallGemini() };

  const noKey = await handleRequest(makeRequest(), makeEnv({ GEMINI_API_KEY: undefined }), deps);
  assert.equal(noKey.status, 503);
  assert.equal(await errorCode(noKey), "SERVER_MISCONFIGURED");

  const noLimiter = await handleRequest(makeRequest(), makeEnv({ RL_GLOBAL: undefined }), deps);
  assert.equal(noLimiter.status, 503);

  const broken = { async limit() { throw new Error("boom"); } };
  const limiterDown = await handleRequest(makeRequest(), makeEnv({ RL_INSTALL: broken }), deps);
  assert.equal(limiterDown.status, 503);
  assert.equal(await errorCode(limiterDown), "RATE_LIMITER_UNAVAILABLE");
});

test("kill switch disables generation", async () => {
  const response = await handleRequest(makeRequest(), makeEnv({ GREETING_ENABLED: "false" }), {
    fetchImpl: mustNotCallGemini(),
  });
  assert.equal(response.status, 503);
  assert.equal(await errorCode(response), "SERVICE_DISABLED");
});

test("Gemini 429 maps to a retryable 503 with the upstream retry hint", async () => {
  const response = await handleRequest(makeRequest(), makeEnv(), {
    fetchImpl: geminiFailing(429, {
      error: { code: 429, status: "RESOURCE_EXHAUSTED", details: [{ "@type": "type.googleapis.com/google.rpc.RetryInfo", retryDelay: "17s" }] },
    }),
  });
  assert.equal(response.status, 503);
  assert.equal(response.headers.get("Retry-After"), "17");
  assert.equal(await errorCode(response), "UPSTREAM_RATE_LIMITED");
});

test("Gemini server errors and config errors map to 502", async () => {
  const overloaded = await handleRequest(makeRequest(), makeEnv(), { fetchImpl: geminiFailing(503) });
  assert.equal(overloaded.status, 502);
  const overloadedPayload = await overloaded.json();
  assert.equal(overloadedPayload.error.code, "UPSTREAM_UNAVAILABLE");
  assert.equal(overloadedPayload.error.retryable, true);

  const badKey = await handleRequest(makeRequest(), makeEnv(), { fetchImpl: geminiFailing(403) });
  assert.equal(badKey.status, 502);
  const badKeyPayload = await badKey.json();
  assert.equal(badKeyPayload.error.code, "UPSTREAM_ERROR");
  assert.equal(badKeyPayload.error.retryable, false);
  // The upstream body is never forwarded
  assert.ok(!JSON.stringify(badKeyPayload).includes(FAKE_KEY));
});

test("blocked content maps to 422", async () => {
  const response = await handleRequest(makeRequest(), makeEnv(), {
    fetchImpl: async () => new Response(JSON.stringify({ promptFeedback: { blockReason: "SAFETY" } }), { status: 200 }),
  });
  assert.equal(response.status, 422);
  assert.equal(await errorCode(response), "CONTENT_BLOCKED");
});

test("slow Gemini responses time out with 504", async () => {
  const hanging = (url, init) => new Promise((_, reject) => {
    init.signal.addEventListener("abort", () => reject(init.signal.reason));
  });
  // AbortSignal.timeout does not keep Node's event loop alive on its own
  const keepAlive = setInterval(() => {}, 1000);
  let response;
  try {
    response = await handleRequest(makeRequest(), makeEnv(), { fetchImpl: hanging, timeoutMs: 20 });
  } finally {
    clearInterval(keepAlive);
  }
  assert.equal(response.status, 504);
  assert.equal(await errorCode(response), "UPSTREAM_TIMEOUT");
});

test("network failures map to a retryable 502", async () => {
  const response = await handleRequest(makeRequest(), makeEnv(), {
    fetchImpl: async () => { throw new TypeError("fetch failed"); },
  });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error.retryable, true);
});
