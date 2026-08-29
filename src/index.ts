let modulePromise: Promise<typeof import("./generated/openai-translator.teavm.js")> | null = null;

/**
 * Loads the TeaVM-compiled OpenAI translator module, once.
 *
 * @remarks
 * Concurrent callers share one import, so the module is instantiated exactly once per process.
 *
 * @returns the module, whose exports are the translator's own string functions
 */
export function loadOpenaiTranslator(): Promise<typeof import("./generated/openai-translator.teavm.js")> {
  if (!modulePromise) {
    modulePromise = import("./generated/openai-translator.teavm.js");
  }
  return modulePromise;
}

export * from "./translators.js";
export * from "@intisy-ai/basekit/ir";
