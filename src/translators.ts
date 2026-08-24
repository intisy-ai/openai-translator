import { loadOpenaiTranslator } from "./index.js";
import { makeVendorTranslator } from "@intisy-ai/core-ir";

export const openaiTranslator = makeVendorTranslator(loadOpenaiTranslator, {
  decodeRequest: (m) => m.openaiDecodeRequest,
  encodeRequest: (m) => m.openaiEncodeRequest,
  decodeResponse: (m) => m.openaiDecodeResponse,
  encodeResponse: (m) => m.openaiEncodeResponse,
  newStreamDecoder: (m) => m.openaiNewStreamDecoder,
  newStreamEncoder: (m) => m.openaiNewStreamEncoder,
});
