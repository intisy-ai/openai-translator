import { describe, it, expect } from "vitest";
import { loadOpenaiTranslator } from "../index.js";

describe("openai-translator toolchain", () => {
  it("loads the TeaVM bundle and round-trips JSON through the Java codec", async () => {
    const mod = await loadOpenaiTranslator();
    const out = mod.jsonRoundTrip('{"a":1,"b":["x",true]}');
    expect(JSON.parse(out)).toEqual({ a: 1, b: ["x", true] });
  });
});
