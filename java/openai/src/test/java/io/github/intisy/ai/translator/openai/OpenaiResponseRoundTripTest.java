package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.json.JsonUtil;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for {@link OpenaiTranslator#decodeResponse}/{@code encodeResponse}: a
 * real-shaped OpenAI chat-completions response with text plus a {@code tool_calls} entry.
 */
class OpenaiResponseRoundTripTest {

    private static final String GOLDEN_RESPONSE = "{"
            + "\"id\":\"chatcmpl-1\","
            + "\"model\":\"gpt-4o\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"sure\",\"tool_calls\":["
            + "{\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}}"
            + "]}}],"
            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}"
            + "}";

    @Test
    void responseRoundTripsToSemanticallyEqualJson() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrResponse decoded = translator.decodeResponse(GOLDEN_RESPONSE);
        String reEncoded = translator.encodeResponse(decoded);

        assertEquals(json.parse(GOLDEN_RESPONSE), json.parse(reEncoded),
                "decode->encode must reproduce a semantically-equal OpenAI response");

        assertEquals("chatcmpl-1", decoded.id);
        assertEquals("gpt-4o", decoded.model);
        assertEquals(IrStopReason.TOOL_USE, decoded.stopReason);
        assertEquals(2, decoded.content.size());
        assertTrue(decoded.content.get(0) instanceof TextBlock);
        assertEquals("sure", ((TextBlock) decoded.content.get(0)).text);
        assertTrue(decoded.content.get(1) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) decoded.content.get(1);
        assertEquals("call_9", toolUse.id);
        assertEquals("add", toolUse.name);
        assertTrue(toolUse.input instanceof Map);
        assertEquals(1L, ((Map<?, ?>) toolUse.input).get("a"));
        assertEquals(2L, ((Map<?, ?>) toolUse.input).get("b"));

        assertEquals(11, decoded.usage.inputTokens);
        assertEquals(7, decoded.usage.outputTokens);
    }

    @Test
    void stopLengthAndContentFilterFinishReasonsRoundTrip() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        assertFinishReasonRoundTrips(json, translator, "stop", IrStopReason.END_TURN);
        assertFinishReasonRoundTrips(json, translator, "length", IrStopReason.MAX_TOKENS);
    }

    @Test
    void contentFilterFallsBackToErrorButSurvivesViaExtensions() {
        String wire = "{\"id\":\"chatcmpl-2\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"content_filter\",\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"...\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        IrResponse decoded = translator.decodeResponse(wire);
        // OpenAI has no IR-constant equivalent for content_filter (mirroring GeminiFinishReason's
        // treatment of SAFETY), but the exact original string still round-trips via extensions.
        assertEquals(IrStopReason.ERROR, decoded.stopReason);

        String reEncoded = translator.encodeResponse(decoded);
        Object reparsed = json.parse(reEncoded);
        assertTrue(reparsed instanceof Map);
        Map<String, Object> root = JsonUtil.asMap(reparsed);
        Map<String, Object> firstChoice = JsonUtil.asMap(JsonUtil.asList(root.get("choices")).get(0));
        assertEquals("content_filter", firstChoice.get("finish_reason"),
                "the exact OpenAI finish_reason string must survive even though IR has no matching constant");
        assertEquals(json.parse(wire), reparsed);
    }

    private static void assertFinishReasonRoundTrips(JsonCodec json, OpenaiTranslator translator,
            String finishReason, String irStopReason) {
        String wire = "{\"id\":\"chatcmpl-3\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"" + finishReason + "\",\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

        IrResponse decoded = translator.decodeResponse(wire);
        assertEquals(irStopReason, decoded.stopReason);

        String reEncoded = translator.encodeResponse(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }
}
