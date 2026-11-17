// Strict validation for POST /generate-greeting.
// The allowed values are the same keys the Android app uses in GreetingCardRequest
// (Occasion / Relationship / Tone / Language getKey()). Nothing else is accepted, so the
// only free text that ever reaches the prompt is a short, letters-only recipient name.

export const OCCASIONS = Object.freeze([
  "birthday",
  "name_day",
  "christmas",
  "new_year",
  "anniversary",
  "graduation",
  "wedding",
  "valentines_day",
  "thank_you",
]);

export const RELATIONSHIPS = Object.freeze([
  "friend",
  "mum",
  "dad",
  "boyfriend",
  "girlfriend",
  "sibling",
  "colleague",
]);

export const TONES = Object.freeze(["warm", "funny", "formal", "romantic"]);

// ISO 639-1 codes: English and Greek
export const LANGUAGES = Object.freeze(["en", "el"]);

export const MAX_BODY_BYTES = 1024;
export const MAX_NAME_LENGTH = 40;

const REQUIRED_FIELDS = ["occasion", "relationship", "tone", "language"];
const ALLOWED_FIELDS = new Set([...REQUIRED_FIELDS, "recipientName"]);

const ALLOWED_VALUES = {
  occasion: OCCASIONS,
  relationship: RELATIONSHIPS,
  tone: TONES,
  language: LANGUAGES,
};

// A name must start with a letter and may then contain letters, combining marks,
// spaces, hyphens, apostrophes and dots (e.g. "Anna-Maria", "O'Brien", "Γιώργος").
// Digits, punctuation like ":" or "{", and control characters are rejected, which keeps
// prompt-injection attempts through this field to a minimum.
const NAME_PATTERN = /^\p{L}[\p{L}\p{M} .'’-]*$/u;

// Control, format (zero-width etc.) and line/paragraph separator characters
const CONTROL_CHARS = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u;

const INSTALL_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isValidInstallId(value) {
  return typeof value === "string" && INSTALL_ID_PATTERN.test(value);
}

// Returns the cleaned name, null when no name was given, or undefined when invalid
export function normalizeRecipientName(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") return undefined;
  // Reject newlines, tabs and other control characters outright instead of rewriting them
  if (CONTROL_CHARS.test(value)) return undefined;

  const cleaned = value.normalize("NFC").trim().replace(/\s+/g, " ");
  if (cleaned === "") return null;
  if ([...cleaned].length > MAX_NAME_LENGTH) return undefined;
  if (!NAME_PATTERN.test(cleaned)) return undefined;
  return cleaned;
}

// Validates a parsed JSON body.
// Returns { ok: true, value } or { ok: false, errors: [{ field, issue }] }
export function validateGreetingRequest(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    return { ok: false, errors: [{ field: "body", issue: "must be a JSON object" }] };
  }

  const errors = [];

  for (const key of Object.keys(body)) {
    if (!ALLOWED_FIELDS.has(key)) {
      errors.push({ field: key, issue: "unknown field" });
    }
  }

  const value = {};
  for (const field of REQUIRED_FIELDS) {
    const raw = body[field];
    if (raw === undefined || raw === null) {
      errors.push({ field, issue: "is required" });
    } else if (typeof raw !== "string" || !ALLOWED_VALUES[field].includes(raw)) {
      errors.push({ field, issue: `must be one of: ${ALLOWED_VALUES[field].join(", ")}` });
    } else {
      value[field] = raw;
    }
  }

  const name = normalizeRecipientName(body.recipientName);
  if (name === undefined) {
    errors.push({
      field: "recipientName",
      issue: `must be letters only (spaces, - ' . allowed), at most ${MAX_NAME_LENGTH} characters`,
    });
  } else {
    value.recipientName = name;
  }

  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, value };
}
