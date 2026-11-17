import { test } from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_MODEL,
  GeminiError,
  MAX_MESSAGE_CHARS,
  buildGeminiBody,
  cleanMessage,
  extractMessage,
  resolveModel,
} from "../src/gemini.js";

test("request body uses generateContent fields with capped output and minimal thinking", () => {
  const body = buildGeminiBody({ system: "SYS", user: "USER" });
  assert.equal(body.systemInstruction.parts[0].text, "SYS");
  assert.equal(body.contents[0].parts[0].text, "USER");
  assert.ok(body.generationConfig.maxOutputTokens <= 400);
  assert.equal(body.generationConfig.thinkingConfig.thinkingLevel, "MINIMAL");
});

test("model falls back to the default when the configured value is unsafe", () => {
  assert.equal(resolveModel("gemini-3.5-flash-lite"), "gemini-3.5-flash-lite");
  assert.equal(resolveModel(undefined), DEFAULT_MODEL);
  assert.equal(resolveModel("../../v1/files"), DEFAULT_MODEL);
  assert.equal(resolveModel("gpt-4"), DEFAULT_MODEL);
});

test("extracts text and skips thought parts", () => {
  const { message } = extractMessage({
    candidates: [{
      finishReason: "STOP",
      content: { parts: [{ text: "thinking...", thought: true }, { text: "Dear Mum,\n\nHappy birthday!" }] },
    }],
  });
  assert.equal(message, "Dear Mum,\n\nHappy birthday!");
});

test("blocked prompts and safety stops become 'blocked' errors", () => {
  assert.throws(() => extractMessage({ promptFeedback: { blockReason: "SAFETY" } }),
    (err) => err instanceof GeminiError && err.kind === "blocked");
  assert.throws(() => extractMessage({ candidates: [{ finishReason: "SAFETY", content: { parts: [] } }] }),
    (err) => err instanceof GeminiError && err.kind === "blocked");
});

test("empty or malformed responses are 'bad_response' errors", () => {
  for (const data of [{}, { candidates: [] }, { candidates: [{ content: { parts: [{ text: "   " }] } }] }]) {
    assert.throws(() => extractMessage(data),
      (err) => err instanceof GeminiError && err.kind === "bad_response");
  }
});

test("cleans markdown, wrapping quotes and extra blank lines", () => {
  assert.equal(cleanMessage('"**Dear Anna,**\r\n\r\n\r\n\r\nHappy birthday!"'), "Dear Anna,\n\nHappy birthday!");
  assert.equal(cleanMessage("# Card\nHello"), "Card\nHello");
});

test("caps message length, preferring a sentence boundary", () => {
  const long = "This is a sentence. ".repeat(100);
  const cleaned = cleanMessage(long);
  assert.ok([...cleaned].length <= MAX_MESSAGE_CHARS);
  assert.ok(cleaned.endsWith("."));

  const noPunctuation = "word ".repeat(400);
  const cut = cleanMessage(noPunctuation);
  assert.ok([...cut].length <= MAX_MESSAGE_CHARS + 1);
  assert.ok(cut.endsWith("…"));
});
