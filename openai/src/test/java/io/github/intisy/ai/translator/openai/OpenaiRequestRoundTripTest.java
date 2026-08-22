package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrMessage;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrToolChoice;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolResultBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.json.JsonUtil;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for {@link OpenaiTranslator#decodeRequest}/{@code encodeRequest}: a
 * real-shaped OpenAI chat-completions request exercising a system message lifted out of
 * {@code messages}, a plain-string user turn, an assistant tool call, a {@code tool} role
 * result message, and a multimodal (text + data-URI image) user turn. Fidelity is asserted by
 * comparing the JSON parsed as maps, not raw strings, since key order is not semantically
 * meaningful.
 */
class OpenaiRequestRoundTripTest {

    private static final String GOLDEN_REQUEST = "{"
            + "\"model\":\"gpt-4o\","
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":\"You are terse.\"},"
            + "{\"role\":\"user\",\"content\":\"Add 2 and 3.\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}"
            + "]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"5\"},"
            + "{\"role\":\"user\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"and describe this\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}"
            + "]}"
            + "],"
            + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"add\",\"description\":\"add two numbers\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}}}],"
            + "\"tool_choice\":\"auto\","
            + "\"max_tokens\":256,"
            + "\"temperature\":0.2,"
            + "\"top_p\":0.9,"
            + "\"stop\":[\"\\n\\n\"],"
            + "\"stream\":false"
            + "}";

    @Test
    void requestRoundTripsToSemanticallyEqualJson() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(GOLDEN_REQUEST);
        String reEncoded = translator.encodeRequest(decoded);

        assertEquals(json.parse(GOLDEN_REQUEST), json.parse(reEncoded),
                "decode->encode must reproduce a semantically-equal OpenAI request");

        // Spot-check the IR shape.
        assertEquals("gpt-4o", decoded.model);
        assertEquals(256, decoded.maxTokens);
        assertEquals(0.2, decoded.temperature);
        assertEquals(0.9, decoded.topP);
        assertNull(decoded.topK, "OpenAI has no top_k field");
        assertEquals(IrToolChoice.Type.AUTO, decoded.toolChoice.type);
        assertEquals(1, decoded.tools.size());
        assertEquals("add", decoded.tools.get(0).name);
        assertEquals(java.util.Collections.singletonList("\n\n"), decoded.stopSequences);

        assertEquals(1, decoded.system.size());
        assertTrue(decoded.system.get(0) instanceof TextBlock);
        assertEquals("You are terse.", ((TextBlock) decoded.system.get(0)).text);

        assertEquals(4, decoded.messages.size(), "the system message is lifted out of messages");

        List<Block> firstTurn = decoded.messages.get(0).content;
        assertEquals(1, firstTurn.size());
        assertTrue(firstTurn.get(0) instanceof TextBlock);
        assertEquals("Add 2 and 3.", ((TextBlock) firstTurn.get(0)).text);

        List<Block> assistantTurn = decoded.messages.get(1).content;
        assertEquals(1, assistantTurn.size());
        assertTrue(assistantTurn.get(0) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) assistantTurn.get(0);
        assertEquals("call_1", toolUse.id);
        assertEquals("add", toolUse.name);
        assertTrue(toolUse.input instanceof Map);
        assertEquals(2L, ((Map<?, ?>) toolUse.input).get("a"));
        assertEquals(3L, ((Map<?, ?>) toolUse.input).get("b"));

        assertEquals("tool", decoded.messages.get(2).role);
        List<Block> toolTurn = decoded.messages.get(2).content;
        assertEquals(1, toolTurn.size());
        assertTrue(toolTurn.get(0) instanceof ToolResultBlock);
        ToolResultBlock toolResult = (ToolResultBlock) toolTurn.get(0);
        assertEquals("call_1", toolResult.toolUseId);
        assertEquals("5", ((TextBlock) toolResult.content.get(0)).text);

        List<Block> lastTurn = decoded.messages.get(3).content;
        assertEquals(2, lastTurn.size());
        assertTrue(lastTurn.get(0) instanceof TextBlock);
        assertEquals("and describe this", ((TextBlock) lastTurn.get(0)).text);
        assertTrue(lastTurn.get(1) instanceof io.github.intisy.ai.ir.ImageBlock);
        io.github.intisy.ai.ir.ImageBlock image = (io.github.intisy.ai.ir.ImageBlock) lastTurn.get(1);
        assertEquals("image/png", image.mediaType);
        assertEquals("AAAA", image.data);
    }

    @Test
    void plainStringSystemRoundTrips() {
        String wire = "{\"model\":\"gpt-4o\","
                + "\"messages\":[{\"role\":\"system\",\"content\":\"be terse\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(1, decoded.system.size());
        assertEquals("be terse", ((TextBlock) decoded.system.get(0)).text);
        assertEquals(1, decoded.messages.size());

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void reasoningContentBecomesThinkingBlockAndRoundTrips() {
        String wire = "{\"model\":\"deepseek-chat\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":\"hello\",\"reasoning_content\":\"thinking it through\"}],"
                + "\"stream\":false}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        List<Block> assistantContent = decoded.messages.get(1).content;
        assertTrue(assistantContent.get(0) instanceof ThinkingBlock);
        assertEquals("thinking it through", ((ThinkingBlock) assistantContent.get(0)).text);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void unknownTopLevelFieldsSurviveViaExtensions() {
        String wire = "{\"model\":\"gpt-4o\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"seed\":42,\"user\":\"user-123\"}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(42L, decoded.extensions.get("seed"));
        assertEquals("user-123", decoded.extensions.get("user"));

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void toolChoiceRequiredMapsToIrAny() {
        String wire = "{\"model\":\"gpt-4o\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"tool_choice\":\"required\"}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(IrToolChoice.Type.ANY, decoded.toolChoice.type);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void namedToolChoiceRoundTrips() {
        String wire = "{\"model\":\"gpt-4o\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"add\"}}}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(IrToolChoice.Type.TOOL, decoded.toolChoice.type);
        assertEquals("add", decoded.toolChoice.name);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void offSpecContentBlockIsOmittedNotEncodedAsNull() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        // A ToolResultBlock has no OpenAI content-part shape and is only expected on a "tool"
        // role message; here it turns up as a stray block on a "user" message content list,
        // exactly the off-spec shape a cross-provider IR (built by another translator) can produce.
        ToolResultBlock strayToolResult = new ToolResultBlock();
        strayToolResult.toolUseId = "call_1";
        strayToolResult.content = Collections.<Block>singletonList(new TextBlock("5"));

        IrMessage userMessage = new IrMessage();
        userMessage.role = "user";
        userMessage.content = Arrays.asList((Block) new TextBlock("hi"), strayToolResult);

        IrRequest request = new IrRequest();
        request.model = "gpt-4o";
        request.stream = false;
        request.messages = Collections.singletonList(userMessage);

        String reEncoded = translator.encodeRequest(request);
        Map<String, Object> root = JsonUtil.asMap(json.parse(reEncoded));
        List<Object> messages = JsonUtil.asList(root.get("messages"));
        Map<String, Object> encodedUserMessage = JsonUtil.asMap(messages.get(0));
        List<Object> content = JsonUtil.asList(encodedUserMessage.get("content"));

        assertEquals(1, content.size(), "the off-spec ToolResultBlock must be omitted, not encoded as null");
        assertFalse(content.contains(null), "content array must never contain a literal null entry");
    }
}
