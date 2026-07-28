// Hand-authored ambient types for the TeaVM-generated ES module staged into this same directory
// by `npm run build:teavm` (teavm-build.mjs), from java/teavm-openai's OpenaiTranslatorJs
// @JSExport surface. The generated openai-translator.teavm.js itself is gitignored (build
// output); this .d.ts is committed source so tsc can type-check consumers of
// `loadOpenaiTranslator()` without needing the build to have run first.

// Bare round-trip smoke export proving the pipeline is wired end to end.
export function jsonRoundTrip(json: string): string;

// Non-streaming translator exports: OpenAI wireJson <-> core-ir's own IrRequest/IrResponse
// JSON shape (the same shape IrJson.serialize*/parse* produce -- see core-ir's src/types.ts).
export function openaiDecodeRequest(wireJson: string): string;
export function openaiEncodeRequest(irRequestJson: string): string;
export function openaiDecodeResponse(wireJson: string): string;
export function openaiEncodeResponse(irResponseJson: string): string;

// Streaming translator exports: stateful per-connection handles. `decode` takes one raw
// vendor chunk (partial lines/frames are buffered internally, across calls) and returns a JSON
// array of IR stream-event objects completed by that chunk (often empty). `encode` takes one IR
// stream-event's JSON and returns the vendor's wire text for it (an SSE frame, or "" when that
// event has no wire representation for this vendor).
export interface JsStreamDecoderHandle {
  decode(chunk: string): string;
}
export interface JsStreamEncoderHandle {
  encode(irEventJson: string): string;
}
export function openaiNewStreamDecoder(): JsStreamDecoderHandle;
export function openaiNewStreamEncoder(): JsStreamEncoderHandle;
