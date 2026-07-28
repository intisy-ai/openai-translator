# openai-translator

[![npm version](https://img.shields.io/npm/v/openai-translator.svg)](https://www.npmjs.com/package/openai-translator)
[![npm downloads](https://img.shields.io/npm/dm/openai-translator.svg)](https://www.npmjs.com/package/openai-translator)
[![CI](https://github.com/intisy-ai/openai-translator/actions/workflows/publish.yml/badge.svg)](https://github.com/intisy-ai/openai-translator/actions/workflows/publish.yml)

OpenAI Chat Completions vendor translator for the canonical IR (internal representation) used
across the intisy AI-tooling ecosystem. Java + TeaVM single-source, so the exact same request,
response, and streaming codecs compile to a JVM jar and to a JS module: any front-door or provider
that needs to speak OpenAI's wire format converts it to and from `core-ir`'s neutral IR through one
shared, tested implementation instead of a bespoke per-app reimplementation.

## Under-the-Hood Architecture

```mermaid
flowchart LR
  WIRE[OpenAI Chat Completions wire JSON] --> REQ[OpenaiRequestCodec]
  WIRE --> RESP[OpenaiResponseCodec]
  WIRE --> SSE[OpenaiStreamDecoder / OpenaiStreamEncoder]
  REQ --> TR[OpenaiTranslator]
  RESP --> TR
  SSE --> TR
  IR[core-ir: IrRequest / IrResponse / IrStreamEvent] --> TR
  TR -->|":openai" module| OPENAI[java/openai]
  OPENAI -->|TeaVM generateJavaScript| GEN[java/teavm-openai build/generated/teavm/js]
  GEN -->|teavm-build.mjs stage| STAGED[src/generated/openai-translator.teavm.js]
  STAGED -->|tsc + esbuild| DIST[dist/index.js]
  DIST --> API["src/translators.ts: openaiTranslator"]
```

`OpenaiTranslator` implements `core-ir`'s `Translator` SPI: `decodeRequest`/`encodeRequest`,
`decodeResponse`/`encodeResponse`, and stateful `newStreamDecoder()`/`newStreamEncoder()` for true
streaming (no buffer-and-reconvert). The `:openai` module holds the codecs and is zero-dependency,
Java-8-clean; `:teavm-openai` is the TeaVM export surface over `:openai` and the nested `:ir`
module, transpiled to a single JS bundle. The TS surface (`openaiTranslator`) is a thin async
wrapper over that generated JS, so callers never touch the TeaVM handle directly.

## Structure

- `src/index.ts` — `loadOpenaiTranslator()`, a lazily-memoized dynamic import of the TeaVM ESM
  bundle, plus the public barrel re-exporting `translators.ts` and `core-ir`'s IR types.
- `src/translators.ts` — the public, typed TS API: `openaiTranslator`, with
  `decodeRequest`/`encodeRequest`/`decodeResponse`/`encodeResponse` (thin async wrappers over the
  TeaVM exports) and `decodeStream()`/`encodeStream()`, which return a real `TransformStream`
  driven chunk-by-chunk by the stateful Java handle.
- `src/driver.ts` — a small CLI driver (`node dist/driver.js <payload.json>`) that decodes a wire
  request to IR and re-encodes it, useful for manual smoke checks.
- `src/generated/openai-translator.teavm.d.ts` — hand-authored ambient types for the staged JS (the
  `.js` itself is gitignored build output).
- `src/__tests__/` — `smoke.test.ts` (toolchain round trip) and `translators.test.ts` (request,
  response, and streamed-response round trips through the `TransformStream` helpers).
- `java/openai/` — the OpenAI codecs (`OpenaiRequestCodec`, `OpenaiResponseCodec`,
  `OpenaiStreamDecoder`, `OpenaiStreamEncoder`, `OpenaiBlockCodec`, `OpenaiUsageCodec`,
  `OpenaiFinishReason`) plus `OpenaiTranslator`, the `Translator` implementation that ties them
  together. Depends on the nested `core-ir`'s `:ir` module for the IR types and the codec SPI.
- `java/teavm-openai/` — the TeaVM JS export surface (`OpenaiTranslatorJs`), transpiling `:openai`
  and `:ir` to `openai-translator.js`.
- `java/settings.gradle` / `java/build.gradle` / `java/gradlew*` — self-contained Gradle build
  (Java 8 for `:openai`, Java 17 override for `:teavm-openai`), re-declaring the nested `:ir`
  module's project path (Gradle settings do not nest across submodules).

## Installation

Via git submodule (the ecosystem convention for a `*-translator` repo consumed by a plugin):

```bash
git submodule add https://github.com/intisy-ai/openai-translator.git openai-translator
git submodule update --init --recursive
```

`openai-translator` itself nests `core-ir` as a submodule, so a recursive submodule update is
required (`--init --recursive`, or `git submodule update --init --recursive` from the consuming
repo's root) to pull both levels before building.

Via npm (once published):

```bash
npm install openai-translator
```

## Usage

```ts
import { openaiTranslator } from "openai-translator";

const ir = await openaiTranslator.decodeRequest(wireJson);
const backToWire = await openaiTranslator.encodeRequest(ir);

const response = await openaiTranslator.decodeResponse(responseWireJson);
const wireResponse = await openaiTranslator.encodeResponse(response);

const decodeStream = await openaiTranslator.decodeStream();
const irEvents = upstreamSseBody.pipeThrough(decodeStream); // ReadableStream<IrStreamEvent>

const encodeStream = await openaiTranslator.encodeStream();
const wireSse = irEventStream.pipeThrough(encodeStream); // ReadableStream<string>
```

`openaiTranslator` satisfies `core-ir`'s `VendorTranslator` interface, so any front-door that
already speaks that interface for another vendor can adopt OpenAI support by swapping in this
translator.

## Testing

Java: `cd java && ./gradlew test` (JUnit 5, `:openai` module: request, response, and streaming
round-trip tests against fixture payloads).

TS: `npm run build && npx vitest run` (`build` stages the TeaVM JS, `tsc`s, then bundles with
esbuild; `test` round-trips the translator from TS, including a full streamed response through the
`TransformStream` helpers). Both layers use the same round-trip fixture approach: a captured OpenAI
wire payload decoded to IR and re-encoded, asserting the result matches the original shape rather
than a byte-identical string.

## License

MIT
