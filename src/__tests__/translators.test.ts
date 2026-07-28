import { describe, it, expect } from "vitest";
import { openaiTranslator } from "../translators.js";

const REQUEST = {
  model: "gpt-4o",
  messages: [
    { role: "system", content: "You are terse." },
    { role: "user", content: "Add 2 and 3." },
    { role: "assistant", content: null, tool_calls: [{ id: "call_1", type: "function", function: { name: "add", arguments: "{\"a\":2,\"b\":3}" } }] },
    { role: "tool", tool_call_id: "call_1", content: "5" },
    { role: "user", content: [{ type: "text", text: "and describe this" }, { type: "image_url", image_url: { url: "data:image/png;base64,AAAA" } }] },
  ],
  tools: [{ type: "function", function: { name: "add", description: "add two numbers", parameters: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } } } } }],
  tool_choice: "auto",
  max_tokens: 256,
  temperature: 0.2,
  top_p: 0.9,
  stop: ["\n\n"],
  stream: false,
};

describe("openai request codec", () => {
  it("decodes to IR and re-encodes losslessly (parsed-equal)", async () => {
    const ir = await openaiTranslator.decodeRequest(JSON.stringify(REQUEST));
    expect(ir.model).toBe("gpt-4o");
    expect(Array.isArray(ir.messages)).toBe(true);
    expect(ir.messages.length).toBe(4); // system is lifted out of messages
    const back = JSON.parse(await openaiTranslator.encodeRequest(ir));
    expect(back.model).toBe("gpt-4o");
    expect(back.messages[0]).toEqual({ role: "system", content: "You are terse." });
    const toolCallMsg = back.messages.find((m: { tool_calls?: unknown }) => m.tool_calls);
    expect(toolCallMsg.tool_calls[0].function).toEqual({ name: "add", arguments: "{\"a\":2,\"b\":3}" });
    const toolResult = back.messages.find((m: { role: string }) => m.role === "tool");
    expect(toolResult).toMatchObject({ role: "tool", tool_call_id: "call_1", content: "5" });
    expect(back.tools[0].function.name).toBe("add");
    expect(back.max_tokens).toBe(256);
    expect(back.stop).toEqual(["\n\n"]);
  });
});

const RESPONSE = {
  id: "chatcmpl-1",
  model: "gpt-4o",
  choices: [{ index: 0, finish_reason: "tool_calls", message: { role: "assistant", content: "sure", tool_calls: [{ id: "call_9", type: "function", function: { name: "add", arguments: "{\"a\":1,\"b\":2}" } }] } }],
  usage: { prompt_tokens: 11, completion_tokens: 7 },
};

describe("openai response codec", () => {
  it("decodes to IR and re-encodes losslessly (parsed-equal)", async () => {
    const ir = await openaiTranslator.decodeResponse(JSON.stringify(RESPONSE));
    expect(ir.model).toBe("gpt-4o");
    const back = JSON.parse(await openaiTranslator.encodeResponse(ir));
    expect(back.choices[0].finish_reason).toBe("tool_calls");
    expect(back.choices[0].message.tool_calls[0].function.name).toBe("add");
    expect(back.usage).toMatchObject({ prompt_tokens: 11, completion_tokens: 7 });
  });
});

const OPENROUTER_REQUEST = {
  model: "anthropic/claude-3-opus",
  messages: [
    { role: "system", content: "Respond in one word." },
    { role: "user", content: "What is the capital of France?" },
    { role: "assistant", content: null, tool_calls: [{ id: "call_lookup", type: "function", function: { name: "lookup", arguments: "{\"query\":\"capital of France\"}" } }] },
    { role: "tool", tool_call_id: "call_lookup", content: "Paris" },
  ],
  tools: [{ type: "function", function: { name: "lookup", description: "look up a fact", parameters: { type: "object", properties: { query: { type: "string" } } } } }],
  max_tokens: 16,
  stop: "STOP",
  stream: false,
};

const OPENROUTER_RESPONSE = {
  id: "gen-openrouter-1",
  model: "anthropic/claude-3-opus",
  choices: [{ index: 0, finish_reason: "length", message: { role: "assistant", content: "Paris is the capital of Fra" } }],
  usage: { prompt_tokens: 42, completion_tokens: 16 },
};

describe("openai captured-payload round-trip (OpenRouter shape, bare-string stop + length finish)", () => {
  it("round-trips a bare-string stop through request decode/encode", async () => {
    const ir = await openaiTranslator.decodeRequest(JSON.stringify(OPENROUTER_REQUEST));
    expect(ir.model).toBe("anthropic/claude-3-opus");
    expect(ir.messages.map((m: { role: string }) => m.role)).toEqual(["user", "assistant", "tool"]);
    expect(ir.stopSequences).toEqual(["STOP"]);

    const back = JSON.parse(await openaiTranslator.encodeRequest(ir));
    expect(back.model).toBe("anthropic/claude-3-opus");
    expect(back.stop).toBe("STOP");
    expect(back.messages[0]).toEqual({ role: "system", content: "Respond in one word." });
    const toolCallMsg = back.messages.find((m: { tool_calls?: unknown }) => m.tool_calls);
    expect(toolCallMsg.tool_calls[0].function).toEqual({ name: "lookup", arguments: "{\"query\":\"capital of France\"}" });
    const toolResult = back.messages.find((m: { role: string }) => m.role === "tool");
    expect(toolResult).toMatchObject({ role: "tool", tool_call_id: "call_lookup", content: "Paris" });
  });

  it("maps a length finish_reason to the IR max_tokens stop reason and back", async () => {
    const ir = await openaiTranslator.decodeResponse(JSON.stringify(OPENROUTER_RESPONSE));
    expect(ir.model).toBe("anthropic/claude-3-opus");
    expect(ir.stopReason).toBe("max_tokens");

    const back = JSON.parse(await openaiTranslator.encodeResponse(ir));
    expect(back.model).toBe("anthropic/claude-3-opus");
    expect(back.choices[0].finish_reason).toBe("length");
    expect(back.choices[0].message).toMatchObject({ role: "assistant", content: "Paris is the capital of Fra" });
    expect(back.usage).toMatchObject({ prompt_tokens: 42, completion_tokens: 16 });
  });
});

async function collect(stream: ReadableStream<unknown>): Promise<unknown[]> {
  const out: unknown[] = [];
  const reader = stream.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    out.push(value);
  }
  return out;
}

describe("openai stream codec", () => {
  it("decodes OpenAI SSE into IR stream events across a mid-frame chunk split", async () => {
    const sse =
      'data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}\n\n' +
      'data: {"choices":[{"delta":{"content":"lo"}}]}\n\n' +
      'data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}\n\n' +
      'data: [DONE]\n\n';
    const ts = await openaiTranslator.decodeStream();
    const events = collect(ts.readable);
    const writer = ts.writable.getWriter();
    // split the first frame mid-way to force cross-chunk buffering
    await writer.write(sse.slice(0, 40));
    await writer.write(sse.slice(40));
    await writer.close();
    const got = (await events) as Array<{ event?: string; text?: string; stopReason?: string }>;

    const kinds = got.map((e) => e.event);
    expect(kinds).toEqual([
      "message_start",
      "content_block_start",
      "text_delta",
      "text_delta",
      "content_block_stop",
      "message_delta",
      "message_stop",
    ]);
    const text = got.filter((e) => e.event === "text_delta").map((e) => e.text).join("");
    expect(text).toBe("Hello");
    const messageDelta = got.find((e) => e.event === "message_delta") as { stopReason?: string };
    expect(messageDelta.stopReason).toBe("end_turn");
  });

  it("captures usage from a trailing choices-empty frame after finish_reason (stream_options.include_usage)", async () => {
    const sse =
      'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n' +
      'data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":null}\n\n' +
      'data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}\n\n' +
      'data: [DONE]\n\n';
    const ts = await openaiTranslator.decodeStream();
    const events = collect(ts.readable);
    const writer = ts.writable.getWriter();
    await writer.write(sse);
    await writer.close();
    const got = (await events) as Array<{ event?: string; stopReason?: string; usage?: { inputTokens?: number; outputTokens?: number } }>;

    const messageDeltas = got.filter((e) => e.event === "message_delta");
    expect(messageDeltas.length).toBe(1);
    const messageDelta = messageDeltas[0] as { stopReason?: string; usage?: { inputTokens?: number; outputTokens?: number } };
    expect(messageDelta.stopReason).toBe("end_turn");
    expect(messageDelta.usage).toMatchObject({ inputTokens: 11, outputTokens: 7 });
  });

  it("accumulates streamed tool_call argument fragments keyed by index", async () => {
    const sse =
      'data: {"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"add","arguments":"{\\"a\\":"}}]}}]}\n\n' +
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1,\\"b\\":2}"}}]}}]}\n\n' +
      'data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}\n\n' +
      'data: [DONE]\n\n';
    const ts = await openaiTranslator.decodeStream();
    const events = collect(ts.readable);
    const writer = ts.writable.getWriter();
    await writer.write(sse);
    await writer.close();
    const got = (await events) as Array<{ event?: string; partialJson?: string; toolName?: string }>;

    const toolStart = got.find((e) => e.event === "content_block_start" && e.toolName === "add");
    expect(toolStart).toBeDefined();
    const argsFragments = got.filter((e) => e.event === "tool_input_delta").map((e) => e.partialJson).join("");
    expect(JSON.parse(argsFragments)).toEqual({ a: 1, b: 2 });
  });
});
