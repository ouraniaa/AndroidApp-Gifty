// Layered rate limiting with Cloudflare Workers Rate Limiting bindings (see wrangler.toml).
//
//   RL_INSTALL  per app install (X-Gifty-Install-Id)   - stops a single device looping
//   RL_IP       per client IP (CF-Connecting-IP)       - stops one host rotating install IDs
//   RL_GLOBAL   one shared key                         - caps total Gemini spend / free quota
//
// Cloudflare counts are approximate and kept per Cloudflare location, so these are
// abuse brakes, not exact quotas. The IP limit is deliberately looser than the install
// limit because many mobile users can share one carrier IP.

export const RATE_LIMIT_PERIOD_SECONDS = 60;

const LIMITERS = [
  { binding: "RL_INSTALL", scope: "install", key: (ctx) => `install:${ctx.installId.toLowerCase()}` },
  { binding: "RL_IP", scope: "ip", key: (ctx) => `ip:${ctx.ip}` },
  { binding: "RL_GLOBAL", scope: "global", key: () => "global" },
];

export function hasRateLimiters(env) {
  return LIMITERS.every(({ binding }) => env && env[binding] && typeof env[binding].limit === "function");
}

// Returns { allowed: true } or { allowed: false, scope }.
// Throws if a binding fails, so the caller can fail closed.
export async function checkRateLimits(env, ctx) {
  for (const limiter of LIMITERS) {
    const { success } = await env[limiter.binding].limit({ key: limiter.key(ctx) });
    if (!success) {
      return { allowed: false, scope: limiter.scope };
    }
  }
  return { allowed: true };
}
