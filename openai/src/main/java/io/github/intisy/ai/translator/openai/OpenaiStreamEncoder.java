package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrUsage;
import io.github.intisy.ai.ir.spi.JsonCodec;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful per-connection OpenAI SSE encoder, the inverse of {@link OpenaiStreamDecoder}. OpenAI
 * has no wire frame for opening or closing a content block: a text block's start and every block's
 * stop produce no frame at all (empty string, which the TS shell drops), while a tool-use block's
 * start folds its id/name into that tool call's first {@code tool_calls[]} delta chunk.
 */
final class OpenaiStreamEncoder implements StreamEncoder {
    private final JsonCodec json;
    private String id;
    private String model;

    OpenaiStreamEncoder(JsonCodec json) {
        this.json = json;
    }

    @Override
    public String encode(IrStreamEvent event) {
        if (event instanceof MessageStartEvent) {
            MessageStartEvent ev = (MessageStartEvent) event;
            id = ev.id;
            model = ev.model;
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", ev.role != null ? ev.role : "assistant");
            return frame(delta, null, null);
        }
        if (event instanceof ContentBlockStartEvent) {
            return encodeContentBlockStart((ContentBlockStartEvent) event);
        }
        if (event instanceof TextDeltaEvent) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("content", ((TextDeltaEvent) event).text);
            return frame(delta, null, null);
        }
        if (event instanceof ToolInputDeltaEvent) {
            ToolInputDeltaEvent ev = (ToolInputDeltaEvent) event;
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("arguments", ev.partialJson);
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("index", ev.index);
            toolCall.put("function", function);
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("tool_calls", listOf(toolCall));
            return frame(delta, null, null);
        }
        if (event instanceof ContentBlockStopEvent) {
            return "";
        }
        if (event instanceof MessageDeltaEvent) {
            MessageDeltaEvent ev = (MessageDeltaEvent) event;
            String finishReason = ev.stopReason != null ? OpenaiFinishReason.toOpenai(ev.stopReason) : "stop";
            return frame(new LinkedHashMap<String, Object>(), finishReason, ev.usage);
        }
        if (event instanceof MessageStopEvent) {
            return "data: [DONE]\n\n";
        }
        throw new IllegalArgumentException("unsupported IrStreamEvent type: " + event.getClass());
    }

    private String encodeContentBlockStart(ContentBlockStartEvent ev) {
        if (!ContentBlockKind.TOOL_USE.equals(ev.blockKind)) return "";
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", ev.toolName);
        function.put("arguments", "");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", ev.index);
        toolCall.put("id", ev.toolUseId);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("tool_calls", listOf(toolCall));
        return frame(delta, null, null);
    }

    private static List<Object> listOf(Object o) {
        List<Object> l = new ArrayList<>();
        l.add(o);
        return l;
    }

    private String frame(Map<String, Object> delta, String finishReason, IrUsage usage) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("model", model);
        data.put("choices", listOf(choice));
        if (usage != null) data.put("usage", OpenaiUsageCodec.encode(usage));
        return "data: " + json.stringify(data) + "\n\n";
    }
}
