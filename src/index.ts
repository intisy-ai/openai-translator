let modulePromise: Promise<typeof import("./generated/openai-translator.teavm.js")> | null = null;

export function loadOpenaiTranslator(): Promise<typeof import("./generated/openai-translator.teavm.js")> {
  if (!modulePromise) {
    modulePromise = import("./generated/openai-translator.teavm.js");
  }
  return modulePromise;
}

export * from "./translators.js";
export * from "../core-ir/dist/index.js";
