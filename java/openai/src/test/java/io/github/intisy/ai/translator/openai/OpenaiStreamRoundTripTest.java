package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.stream.ContentBlockKind;
import io.github.intisy.ai.ir.stream.ContentBlockStartEvent;
import io.github.intisy.ai.ir.stream.ContentBlockStopEvent;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import io.github.intisy.ai.ir.stream.MessageDeltaEvent;
import io.github.intisy.ai.ir.stream.MessageStartEvent;
import io.github.intisy.ai.ir.stream.MessageStopEvent;
import io.github.intisy.ai.ir.stream.TextDeltaEvent;
import io.github.intisy.ai.ir.stream.ToolInputDeltaEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for a streamed OpenAI chat-completions response: a text delta pair (opening
 * the text content block), a tool call whose {@code function.arguments} stream in as two
 * fragments keyed by the same {@code tool_calls[].index} (only the first fragment carries the
 * tool's {@code id}/{@code name}), then a {@code finish_reason} + trailing {@code usage}, then
 * {@code [DONE]}. The raw SSE text is fed to the decoder split mid-frame across two chunks to
 * exercise cross-chunk line buffering.
 */
class OpenaiStreamRoundTripTest {

    private static String frame(String data) {
        return "data: " + data + "\n\n";
    }

    private static String buildSse() {
        StringBuilder sb = new StringBuilder();
        sb.append(frame("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}"));
        sb.append(frame("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}"));
        sb.append(frame("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":\"}}]}}]}"));
        sb.append(frame("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"2,\\\"b\\\":3}\"}}]}}]}"));
        sb.append(frame("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}"));
        sb.append(frame("[DONE]"));
        return sb.toString();
    }

    @Test
    void streamedResponseRoundTripsFrameByFrame() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);
        StreamDecoder decoder = translator.newStreamDecoder();

        String sse = buildSse();
        // Split mid-stream (not on a frame boundary) to exercise cross-chunk line buffering.
        int splitPoint = sse.length() / 2;
        List<IrStreamEvent> events = new ArrayList<>();
        events.addAll(decoder.decode(sse.substring(0, splitPoint)));
        events.addAll(decoder.decode(sse.substring(splitPoint)));

        assertEquals(11, events.size());

        assertTrue(events.get(0) instanceof MessageStartEvent);
        MessageStartEvent messageStart = (MessageStartEvent) events.get(0);
        assertEquals("chatcmpl-1", messageStart.id);
        assertEquals("gpt-4o", messageStart.model);
        assertEquals("assistant", messageStart.role);

        assertTrue(events.get(1) instanceof ContentBlockStartEvent);
        ContentBlockStartEvent textStart = (ContentBlockStartEvent) events.get(1);
        assertEquals(0, textStart.index);
        assertEquals(ContentBlockKind.TEXT, textStart.blockKind);

        assertTrue(events.get(2) instanceof TextDeltaEvent);
        assertEquals("Hel", ((TextDeltaEvent) events.get(2)).text);
        assertTrue(events.get(3) instanceof TextDeltaEvent);
        assertEquals("lo", ((TextDeltaEvent) events.get(3)).text);

        assertTrue(events.get(4) instanceof ContentBlockStartEvent);
        ContentBlockStartEvent toolStart = (ContentBlockStartEvent) events.get(4);
        assertEquals(1, toolStart.index);
        assertEquals(ContentBlockKind.TOOL_USE, toolStart.blockKind);
        assertEquals("call_1", toolStart.toolUseId);
        assertEquals("add", toolStart.toolName);

        assertTrue(events.get(5) instanceof ToolInputDeltaEvent);
        ToolInputDeltaEvent argFragment1 = (ToolInputDeltaEvent) events.get(5);
        assertEquals(1, argFragment1.index);
        assertTrue(events.get(6) instanceof ToolInputDeltaEvent);
        ToolInputDeltaEvent argFragment2 = (ToolInputDeltaEvent) events.get(6);
        assertEquals(1, argFragment2.index);
        // Fragments arrive keyed only by tool_calls[].index (no id/name repeated); concatenating
        // them in that order must reassemble valid JSON.
        String accumulatedArgs = argFragment1.partialJson + argFragment2.partialJson;
        assertEquals(json.parse("{\"a\":2,\"b\":3}"), json.parse(accumulatedArgs));

        assertTrue(events.get(7) instanceof ContentBlockStopEvent);
        assertEquals(0, ((ContentBlockStopEvent) events.get(7)).index);
        assertTrue(events.get(8) instanceof ContentBlockStopEvent);
        assertEquals(1, ((ContentBlockStopEvent) events.get(8)).index);

        assertTrue(events.get(9) instanceof MessageDeltaEvent);
        MessageDeltaEvent messageDelta = (MessageDeltaEvent) events.get(9);
        assertEquals(IrStopReason.TOOL_USE, messageDelta.stopReason);
        assertEquals(5, messageDelta.usage.inputTokens);
        assertEquals(3, messageDelta.usage.outputTokens);

        assertTrue(events.get(10) instanceof MessageStopEvent);
    }

    @Test
    void reEncodedStreamDecodesBackToTheSameTextAndToolArguments() {
        JsonCodec json = new TestJsonCodec();
        OpenaiTranslator translator = new OpenaiTranslator(json);

        List<IrStreamEvent> events = new ArrayList<>();
        events.addAll(translator.newStreamDecoder().decode(buildSse()));

        // One encoder instance for the whole stream: it carries id/model forward from
        // message_start into every later frame, the way a real connection would.
        StreamEncoder encoder = translator.newStreamEncoder();
        StringBuilder reEncodedSse = new StringBuilder();
        for (IrStreamEvent event : events) {
            reEncodedSse.append(encoder.encode(event));
        }

        List<IrStreamEvent> reDecoded = translator.newStreamDecoder().decode(reEncodedSse.toString());

        StringBuilder text = new StringBuilder();
        StringBuilder args = new StringBuilder();
        String stopReason = null;
        for (IrStreamEvent event : reDecoded) {
            if (event instanceof TextDeltaEvent) text.append(((TextDeltaEvent) event).text);
            if (event instanceof ToolInputDeltaEvent) args.append(((ToolInputDeltaEvent) event).partialJson);
            if (event instanceof MessageDeltaEvent) stopReason = ((MessageDeltaEvent) event).stopReason;
        }

        assertEquals("Hello", text.toString());
        assertEquals(json.parse("{\"a\":2,\"b\":3}"), json.parse(args.toString()));
        assertEquals(IrStopReason.TOOL_USE, stopReason);
    }
}
