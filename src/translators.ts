import { loadOpenaiTranslator } from "./index.js";
import { makeVendorTranslator } from "@intisy-ai/basekit/ir";

/**
 * The OpenAI translator, as every consumer takes it.
 *
 * @remarks
 * Built by basekit/ir's `makeVendorTranslator`, so it loads the TeaVM module lazily on first use and
 * carries the synchronous handles the Java routing engine reaches it through.
 */
export const openaiTranslator = makeVendorTranslator(loadOpenaiTranslator, {
  decodeRequest: (m) => m.openaiDecodeRequest,
  encodeRequest: (m) => m.openaiEncodeRequest,
  decodeResponse: (m) => m.openaiDecodeResponse,
  encodeResponse: (m) => m.openaiEncodeResponse,
  newStreamDecoder: (m) => m.openaiNewStreamDecoder,
  newStreamEncoder: (m) => m.openaiNewStreamEncoder,
});
