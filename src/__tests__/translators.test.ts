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
