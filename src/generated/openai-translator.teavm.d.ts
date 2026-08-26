// Generated from Java sources. Do not edit.

/**
 * A stateful handle over one stream decode, as a TypeScript consumer sees it.
 *
 * @remarks
 * Never implemented, only emitted. The Java handle it describes speaks
 * `JSString` and extends `JSObject`, neither of which means anything to a TypeScript
 * caller, which is why this shape is declared apart from it rather than annotated onto it.
 */
export interface JsStreamDecoderHandle {
  /**
   * Feeds one raw vendor chunk and returns the IR stream events it completed, as a JSON array.
   *
   * @remarks
   * Partial lines and frames are buffered inside the handle across calls, so a chunk
   * completing nothing correctly returns an empty array.
   */
  decode(chunk: string): string;
}

/**
 * A stateful handle over one stream encode, as a TypeScript consumer sees it.
 *
 * @remarks
 * Never implemented, only emitted, for the same reason as
 * {@link JsStreamDecoderHandle}.
 */
export interface JsStreamEncoderHandle {
  /**
   * Encodes one IR stream event to this vendor's wire text.
   *
   * @remarks
   * An event with no wire representation for this vendor encodes to the empty string
   * rather than being reported as an error.
   */
  encode(irEventJson: string): string;
}

/** Parse and stringify with no IR type involved, proving the JSON codec crosses TeaVM. */
export declare function jsonRoundTrip(json: string): string;
/** OpenAI wire JSON to an IR request. */
export declare function openaiDecodeRequest(wireJson: string): string;
/** OpenAI wire JSON to an IR response. */
export declare function openaiDecodeResponse(wireJson: string): string;
/** An IR request to OpenAI wire JSON. */
export declare function openaiEncodeRequest(irRequestJson: string): string;
/** An IR response to OpenAI wire JSON. */
export declare function openaiEncodeResponse(irResponseJson: string): string;
/** Opens a decode handle for one connection's stream. */
export declare function openaiNewStreamDecoder(): JsStreamDecoderHandle;
/** Opens an encode handle for one connection's stream. */
export declare function openaiNewStreamEncoder(): JsStreamEncoderHandle;

