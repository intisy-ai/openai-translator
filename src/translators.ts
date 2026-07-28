// TS-facing translator API: a thin wrapper over the TeaVM-generated JS (see src/index.ts's
// loadOpenaiTranslator()). Non-streaming calls are one round trip through the Java translator per
// call; streaming calls hand back a real TransformStream driven chunk-by-chunk by a stateful
// Java-side handle (newStreamDecoder/newStreamEncoder): all SSE line-buffering and event-building
// decisions run in Java, this shell only owns bytes-in/JSON-out.

import { loadOpenaiTranslator } from "./index.js";
import type { IrRequest, IrResponse, IrStreamEvent, VendorTranslator } from "../core-ir/dist/index.js";

function makeDecodeStream(handle: { decode(chunk: string): string }): TransformStream<Uint8Array | string, IrStreamEvent> {
  const textDecoder = new TextDecoder();
  return new TransformStream({
    transform(chunk, controller) {
      const text = typeof chunk === "string" ? chunk : textDecoder.decode(chunk, { stream: true });
      const events: IrStreamEvent[] = JSON.parse(handle.decode(text));
      for (const event of events) controller.enqueue(event);
    },
  });
}

function makeEncodeStream(handle: { encode(irEventJson: string): string }): TransformStream<IrStreamEvent, string> {
  return new TransformStream({
    transform(event, controller) {
      const wire = handle.encode(JSON.stringify(event));
      if (wire) controller.enqueue(wire);
    },
  });
}

export const openaiTranslator: VendorTranslator = {
  async decodeRequest(wireJson) {
    const mod = await loadOpenaiTranslator();
    return JSON.parse(mod.openaiDecodeRequest(wireJson));
  },
  async encodeRequest(request: IrRequest) {
    const mod = await loadOpenaiTranslator();
    return mod.openaiEncodeRequest(JSON.stringify(request));
  },
  async decodeResponse(wireJson) {
    const mod = await loadOpenaiTranslator();
    return JSON.parse(mod.openaiDecodeResponse(wireJson));
  },
  async encodeResponse(response: IrResponse) {
    const mod = await loadOpenaiTranslator();
    return mod.openaiEncodeResponse(JSON.stringify(response));
  },
  async decodeStream() {
    const mod = await loadOpenaiTranslator();
    return makeDecodeStream(mod.openaiNewStreamDecoder());
  },
  async encodeStream() {
    const mod = await loadOpenaiTranslator();
    return makeEncodeStream(mod.openaiNewStreamEncoder());
  },
};
