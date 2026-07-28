package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
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
 * Stateful OpenAI chat-completions SSE decoder. OpenAI's wire has no explicit block-open/close
 * frame the way Anthropic does: a text fragment just appears in {@code delta.content} and a tool
 * call's {@code function.arguments} streams in fragments keyed by {@code tool_calls[].index}, with
 * the id/name repeated only on that tool call's first fragment. This codec synthesizes the same
 * canonical event sequence Anthropic's decoder emits (message_start, content_block_start/delta/
 * stop, message_delta, message_stop) by tracking, per connection, which content-block index (one
 * for the text block, one per distinct tool-call index) is currently open, and routes later
 * argument fragments to their already-opened block via that same tool-call index.
 *
 * <p>Buffers partial SSE lines/frames across {@link #decode} calls exactly like the Anthropic
 * decoder (a chunk may split mid-line or mid-frame); frames are {@code data: <json>} lines
 * terminated by a blank line, with {@code data: [DONE]} as the terminal, non-JSON frame.
 */
final class OpenaiStreamDecoder implements StreamDecoder {
    private final JsonCodec json;
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder dataBuffer = new StringBuilder();
    private boolean sawDataLine = false;

    private boolean messageStarted = false;
    private boolean messageDeltaSent = false;
    private int nextBlockIndex = 0;
    private Integer textBlockIndex = null;
    private final Map<Integer, Integer> toolBlockIndexByToolCallIndex = new LinkedHashMap<>();
    private final List<Integer> openBlockOrder = new ArrayList<>();

    OpenaiStreamDecoder(JsonCodec json) {
        this.json = json;
    }

    @Override
    public List<IrStreamEvent> decode(String chunk) {
        List<IrStreamEvent> out = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) return out;
        lineBuffer.append(chunk);
        int newlineIndex;
        while ((newlineIndex = lineBuffer.indexOf("\n")) >= 0) {
            String line = lineBuffer.substring(0, newlineIndex);
            lineBuffer.delete(0, newlineIndex + 1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            processLine(line, out);
        }
        return out;
    }

    private void processLine(String line, List<IrStreamEvent> out) {
        if (line.isEmpty()) {
            flushFrame(out);
            return;
        }
        if (line.startsWith(":")) return; // SSE comment/keepalive
        if (line.startsWith("data:")) {
            String data = line.substring(5);
            if (data.startsWith(" ")) data = data.substring(1);
            if (sawDataLine) dataBuffer.append('\n');
            dataBuffer.append(data);
            sawDataLine = true;
        }
    }

    private void flushFrame(List<IrStreamEvent> out) {
        if (!sawDataLine) return;
        String data = dataBuffer.toString();
        dataBuffer.setLength(0);
        sawDataLine = false;
        if (data.isEmpty()) return;
        if ("[DONE]".equals(data)) {
            flushDone(out);
            return;
        }
        Map<String, Object> frame = OpenaiJsonUtil.asMap(json.parse(data));
        if (frame != null) decodeFrame(frame, out);
    }

    private void decodeFrame(Map<String, Object> frame, List<IrStreamEvent> out) {
        List<Object> choices = OpenaiJsonUtil.asList(frame.get("choices"));
        Map<String, Object> choice = choices != null && !choices.isEmpty()
                ? OpenaiJsonUtil.asMap(choices.get(0)) : null;
        Map<String, Object> delta = choice != null ? OpenaiJsonUtil.asMap(choice.get("delta")) : null;

        ensureMessageStart(frame, delta, out);
        if (delta != null) decodeDelta(delta, out);

        String finishReason = choice != null ? OpenaiJsonUtil.asString(choice.get("finish_reason")) : null;
        if (finishReason != null) {
            closeOpenBlocks(out);
            MessageDeltaEvent ev = new MessageDeltaEvent();
            ev.stopReason = OpenaiFinishReason.toIr(finishReason);
            ev.usage = OpenaiUsageCodec.decode(frame.get("usage"));
            out.add(ev);
            messageDeltaSent = true;
        }
    }

    private void ensureMessageStart(Map<String, Object> frame, Map<String, Object> delta, List<IrStreamEvent> out) {
        if (messageStarted) return;
        messageStarted = true;
        MessageStartEvent ev = new MessageStartEvent();
        ev.id = OpenaiJsonUtil.asString(frame.get("id"));
        ev.model = OpenaiJsonUtil.asString(frame.get("model"));
        String role = delta != null ? OpenaiJsonUtil.asString(delta.get("role")) : null;
        ev.role = role != null ? role : "assistant";
        out.add(ev);
    }

    private void decodeDelta(Map<String, Object> delta, List<IrStreamEvent> out) {
        String content = OpenaiJsonUtil.asString(delta.get("content"));
        if (content != null) {
            if (textBlockIndex == null) {
                textBlockIndex = nextBlockIndex++;
                openBlockOrder.add(textBlockIndex);
                ContentBlockStartEvent start = new ContentBlockStartEvent();
                start.index = textBlockIndex;
                start.blockKind = ContentBlockKind.TEXT;
                out.add(start);
            }
            TextDeltaEvent textDelta = new TextDeltaEvent();
            textDelta.index = textBlockIndex;
            textDelta.text = content;
            out.add(textDelta);
        }

        List<Object> toolCalls = OpenaiJsonUtil.asList(delta.get("tool_calls"));
        if (toolCalls != null) {
            for (Object tc : toolCalls) {
                Map<String, Object> tcMap = OpenaiJsonUtil.asMap(tc);
                if (tcMap != null) decodeToolCallDelta(tcMap, out);
            }
        }
    }

    private void decodeToolCallDelta(Map<String, Object> tc, List<IrStreamEvent> out) {
        Integer toolCallIndex = OpenaiJsonUtil.asInt(tc.get("index"));
        int key = toolCallIndex == null ? 0 : toolCallIndex;
        Integer blockIndex = toolBlockIndexByToolCallIndex.get(key);
        Map<String, Object> function = OpenaiJsonUtil.asMap(tc.get("function"));
        if (blockIndex == null) {
            blockIndex = nextBlockIndex++;
            toolBlockIndexByToolCallIndex.put(key, blockIndex);
            openBlockOrder.add(blockIndex);
            ContentBlockStartEvent start = new ContentBlockStartEvent();
            start.index = blockIndex;
            start.blockKind = ContentBlockKind.TOOL_USE;
            start.toolUseId = OpenaiJsonUtil.asString(tc.get("id"));
            start.toolName = function != null ? OpenaiJsonUtil.asString(function.get("name")) : null;
            out.add(start);
        }
        String argumentsFragment = function != null ? OpenaiJsonUtil.asString(function.get("arguments")) : null;
        if (argumentsFragment != null) {
            ToolInputDeltaEvent delta = new ToolInputDeltaEvent();
            delta.index = blockIndex;
            delta.partialJson = argumentsFragment;
            out.add(delta);
        }
    }

    private void closeOpenBlocks(List<IrStreamEvent> out) {
        for (int index : openBlockOrder) {
            ContentBlockStopEvent stop = new ContentBlockStopEvent();
            stop.index = index;
            out.add(stop);
        }
        openBlockOrder.clear();
    }

    private void flushDone(List<IrStreamEvent> out) {
        // A stream that reaches [DONE] without ever seeing a finish_reason (an abrupt/truncated
        // stream) still needs its blocks closed and a message_delta before message_stop.
        if (!messageDeltaSent) {
            closeOpenBlocks(out);
            out.add(new MessageDeltaEvent());
            messageDeltaSent = true;
        }
        out.add(new MessageStopEvent());
    }
}
