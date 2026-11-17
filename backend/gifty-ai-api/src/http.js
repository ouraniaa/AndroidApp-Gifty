// JSON response helpers. Every response is uncacheable and never carries CORS headers:
// the Android app does not need CORS, and leaving it out stops web pages from using
// this Worker from a visitor's browser.

const BASE_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
};

export function jsonResponse(status, payload, extraHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...BASE_HEADERS, ...extraHeaders },
  });
}

// Error shape: { "error": { "code", "message", "retryable", "details"? } }
// "message" is safe to show to the user; "code" is meant for the app's logic.
export function errorResponse(status, code, message, { retryable = false, retryAfterSeconds, details, headers = {} } = {}) {
  const error = { code, message, retryable };
  if (details) error.details = details;

  const extra = { ...headers };
  if (retryAfterSeconds) extra["Retry-After"] = String(retryAfterSeconds);

  return jsonResponse(status, { error }, extra);
}
