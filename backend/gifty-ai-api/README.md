# gifty-ai-api

A Cloudflare Worker that writes Gifty greeting card messages with the Gemini API.
The Android app calls this Worker; the Gemini key never leaves Cloudflare.

- Model: `gemini-3.5-flash-lite` (stable, available on the Gemini Free Tier, checked November 2026).
  You can change it with `GEMINI_MODEL` in `wrangler.toml`; only `gemini-…` IDs are accepted.
- API: Gemini REST `v1beta/models/{model}:generateContent`, key sent in the `x-goog-api-key` header.
- Secret: `GEMINI_API_KEY`, read only from `env.GEMINI_API_KEY`.

## API

### `POST /generate-greeting`

Headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-Gifty-Install-Id` | A random UUID the app creates once per install and keeps |

Body (at most 1024 bytes, no other fields allowed):

```json
{
  "occasion": "name_day",
  "relationship": "friend",
  "tone": "funny",
  "language": "el",
  "recipientName": "Νίκη"
}
```

| Field | Allowed values |
|---|---|
| `occasion` | `birthday`, `name_day`, `christmas`, `new_year`, `anniversary`, `graduation`, `wedding`, `valentines_day`, `thank_you` |
| `relationship` | `friend`, `mum`, `dad`, `boyfriend`, `girlfriend`, `sibling`, `colleague` |
| `tone` | `warm`, `funny`, `formal`, `romantic` |
| `language` | `en`, `el` |
| `recipientName` | Optional. Letters, spaces, `-`, `'`, `.` only; up to 40 characters |

These are the same keys as `GreetingCardRequest` in the Android app (`getKey()`).

Success, `200`:

```json
{ "message": "…", "source": "gemini", "model": "gemini-3.5-flash-lite", "language": "el", "mock": false }
```

This maps directly to `new GreetingCardResult(message, false, source)`.

Errors use one shape: `{ "error": { "code", "message", "retryable", "details"? } }`.
`message` is safe to show to the user. A `Retry-After` header is set when waiting helps.

| Status | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_INSTALL_ID`, `INVALID_JSON`, `INVALID_INPUT` | Bad request; `details` lists the fields |
| 403 | `FORBIDDEN` | Request came from a browser (`Origin` header present) |
| 404 / 405 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` | Wrong path or method |
| 413 | `PAYLOAD_TOO_LARGE` | Body over 1024 bytes |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not `application/json` |
| 422 | `CONTENT_BLOCKED` | Gemini's safety filters withheld the message |
| 429 | `RATE_LIMITED` | Our limits hit (`details.scope`: `install`, `ip` or `global`) |
| 502 | `UPSTREAM_UNAVAILABLE` / `UPSTREAM_ERROR` | Gemini failed (retryable) / config problem such as a bad key or retired model (not retryable) |
| 503 | `UPSTREAM_RATE_LIMITED` | Gemini quota exhausted |
| 503 | `SERVICE_DISABLED`, `SERVER_MISCONFIGURED`, `RATE_LIMITER_UNAVAILABLE` | Kill switch on, secret/bindings missing, or limiter down (fails closed) |
| 504 | `UPSTREAM_TIMEOUT` | Gemini took longer than 12 seconds |

### `GET /health`

Returns `{ "status": "ok" }`. It does not call Gemini.

## Abuse protection

1. **No free-form prompts.** Only fixed keys are accepted, and the prompt is built server-side from fixed text. The only user text is a short, letters-only name.
2. **Size limits.** The request body is capped at 1024 bytes while it is read (not just by `Content-Length`). Output is capped at 400 tokens and 900 characters.
3. **Three rate limits** (Workers Rate Limiting bindings in `wrangler.toml`), checked before the body is parsed:
   - `RL_INSTALL`: 5 requests per minute per install ID
   - `RL_IP`: 20 requests per minute per IP (looser, because mobile users share IPs)
   - `RL_GLOBAL`: 15 requests per minute in total, to protect the Free Tier quota
4. **Browsers are refused.** There is no CORS, and requests with an `Origin` header get `403`.
5. **Fails closed.** If the secret or a limiter is missing or broken, no Gemini call is made.
6. **Kill switch.** Set `GREETING_ENABLED = "false"` and redeploy to stop all Gemini calls.
7. **Privacy-friendly logs.** Only error kinds and status codes are logged, never prompts, names or keys.

## Run the tests

The tests use Node's built-in test runner (Node 20+). They need no install and make no network calls.

```bash
npm test
```

## Deploy

Not deployed yet. When you are ready:

1. Install Wrangler locally (this creates `node_modules/` and `package-lock.json`):

   ```bash
   npm install
   ```

2. Sign in to Cloudflare:

   ```bash
   npx wrangler login
   ```

3. Check that the secret exists (you already created it):

   ```bash
   npx wrangler secret list
   ```

   If it ever needs replacing, run `npx wrangler secret put GEMINI_API_KEY` and paste the key at the prompt. Never put it in a file that is committed.

4. Check the bundle and config without deploying:

   ```bash
   npx wrangler deploy --dry-run
   ```

5. Deploy:

   ```bash
   npx wrangler deploy
   ```

   Wrangler prints the URL, for example `https://gifty-ai-api.<your-subdomain>.workers.dev`.

### Local development

Copy `.dev.vars.example` to `.dev.vars` (git-ignored), put your key in it, then run `npx wrangler dev`.
This calls the real Gemini API and uses your quota.

## Try it after deploying

Replace the URL with yours:

```bash
curl -s https://gifty-ai-api.<your-subdomain>.workers.dev/health
```

```bash
curl -s -X POST https://gifty-ai-api.<your-subdomain>.workers.dev/generate-greeting -H "Content-Type: application/json" -H "X-Gifty-Install-Id: 3f2a9c1e-8b7d-4e6f-9a1b-2c3d4e5f6a7b" -d "{\"occasion\":\"birthday\",\"relationship\":\"mum\",\"tone\":\"warm\",\"language\":\"el\"}"
```

Send the second command six times within a minute to see the `429 RATE_LIMITED` response.
Watch live logs with `npx wrangler tail`.

## Security limitations

- **No real client authentication.** Anyone who finds the URL and makes up UUIDs can call it. The IP and global limits bound the damage, but they don't prove the caller is the Gifty app. Before a wide public release, add Firebase App Check or Play Integrity and verify the token here.
- **Approximate limits.** Cloudflare rate limits are counted per Cloudflare location and are eventually consistent, so short bursts can go over. The Gemini quota itself is the final hard limit. For strict global limits, use a Durable Object.
- **Free Tier data use.** On the Gemini Free Tier, Google may use prompts and responses to improve its products. Requests include the occasion, relationship and the optional recipient name. Use the paid tier if that is not acceptable, and tell users in the app.
- **Model output is not guaranteed.** Gemini can still write something off-tone or in the wrong gendered form. The app should let users edit or regenerate the message.
- **Public `workers.dev` URL.** Consider a custom domain with Cloudflare WAF rules as well, and keep `GREETING_ENABLED` in mind as an emergency stop.
