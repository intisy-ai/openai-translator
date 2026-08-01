package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.json.JsonUtil;
import io.github.intisy.ai.ir.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI chat-completions (non-streaming) response {@code Map} tree <-> {@link IrResponse}. IR
 * models a single assistant turn, so only {@code choices[0]} feeds {@link IrResponse#content}/
 * {@code stopReason}; that choice's {@code index}, its message's {@code role}, the raw
 * {@code finish_reason} string, any choice/message fields with no neutral IR home, and any
 * additional choices beyond the first all round-trip verbatim through {@link
 * IrResponse#extensions} (mirroring {@code AnthropicResponseCodec}'s raw-value stash), so a
 * same-vendor decode-then-encode stays lossless even though IR has no multi-choice concept.
 */
final class OpenaiResponseCodec {
    private OpenaiResponseCodec() {
    }

    private static final String EXT_CHOICE_INDEX_RAW = "$choiceIndexRaw";
    private static final String EXT_FINISH_REASON_RAW = "$finishReasonRaw";
    private static final String EXT_MESSAGE_ROLE_RAW = "$messageRoleRaw";
    private static final String EXT_CONTENT_IS_STRING = "$contentIsString";
    private static final String EXT_CHOICE_EXTRA_RAW = "$choiceExtraRaw";
    private static final String EXT_MESSAGE_EXTRA_RAW = "$messageExtraRaw";
    private static final String EXT_EXTRA_CHOICES_RAW = "$extraChoicesRaw";

    private static final Set<String> TOP_LEVEL_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "id", "model", "choices", "usage"));
    private static final Set<String> CHOICE_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "index", "finish_reason", "message"));
    private static final Set<String> MESSAGE_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "role", "content", "tool_calls"));

    static IrResponse decodeResponse(JsonCodec json, String wireJson) {
        Map<String, Object> root = JsonUtil.asMap(json.parse(wireJson));
        IrResponse r = new IrResponse();
        if (root == null) return r;

        r.id = JsonUtil.asString(root.get("id"));
        r.model = JsonUtil.asString(root.get("model"));
        r.usage = OpenaiUsageCodec.decode(root.get("usage"));

        List<Object> choices = JsonUtil.asList(root.get("choices"));
        Map<String, Object> firstChoice = choices != null && !choices.isEmpty()
                ? JsonUtil.asMap(choices.get(0)) : null;
        if (firstChoice != null) decodeChoice(json, firstChoice, r);
        if (choices != null && choices.size() > 1) {
            putExtension(r, EXT_EXTRA_CHOICES_RAW, new ArrayList<>(choices.subList(1, choices.size())));
        }

        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!TOP_LEVEL_KNOWN_KEYS.contains(e.getKey())) {
                putExtension(r, e.getKey(), e.getValue());
            }
        }
        return r;
    }

    private static void decodeChoice(JsonCodec json, Map<String, Object> choice, IrResponse r) {
        putExtension(r, EXT_CHOICE_INDEX_RAW, choice.get("index"));
        String finishReasonRaw = JsonUtil.asString(choice.get("finish_reason"));
        r.stopReason = OpenaiFinishReason.toIr(finishReasonRaw);
        putExtension(r, EXT_FINISH_REASON_RAW, finishReasonRaw);

        Map<String, Object> message = JsonUtil.asMap(choice.get("message"));
        r.content = message != null ? decodeMessage(json, message, r) : new ArrayList<Block>();

        Map<String, Object> choiceExtra = leftovers(choice, CHOICE_KNOWN_KEYS);
        if (choiceExtra != null) putExtension(r, EXT_CHOICE_EXTRA_RAW, choiceExtra);
    }

    private static List<Block> decodeMessage(JsonCodec json, Map<String, Object> message, IrResponse r) {
        putExtension(r, EXT_MESSAGE_ROLE_RAW, message.get("role"));

        List<Block> content = new ArrayList<>();
        Object rawContent = message.get("content");
        if (rawContent instanceof String) {
            content.add(new TextBlock((String) rawContent));
            putExtension(r, EXT_CONTENT_IS_STRING, Boolean.TRUE);
        } else if (rawContent != null) {
            List<Block> decoded = OpenaiBlockCodec.decodeContentList(rawContent);
            if (decoded != null) content.addAll(decoded);
        }

        List<Object> toolCalls = JsonUtil.asList(message.get("tool_calls"));
        if (toolCalls != null) {
            for (Object tc : toolCalls) {
                Map<String, Object> tcMap = JsonUtil.asMap(tc);
                if (tcMap != null) content.add(OpenaiBlockCodec.decodeToolCall(json, tcMap));
            }
        }

        Map<String, Object> messageExtra = leftovers(message, MESSAGE_KNOWN_KEYS);
        if (messageExtra != null) putExtension(r, EXT_MESSAGE_EXTRA_RAW, messageExtra);
        return content;
    }

    static String encodeResponse(JsonCodec json, IrResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("model", r.model);
        m.put("choices", encodeChoices(json, r));
        if (r.usage != null) m.put("usage", OpenaiUsageCodec.encode(r.usage));
        encodeLeftoverExtensions(r, m);
        return json.stringify(m);
    }

    private static List<Object> encodeChoices(JsonCodec json, IrResponse r) {
        List<Object> out = new ArrayList<>();
        out.add(encodeFirstChoice(json, r));
        List<Object> extraChoices = JsonUtil.asList(extension(r, EXT_EXTRA_CHOICES_RAW));
        if (extraChoices != null) out.addAll(extraChoices);
        return out;
    }

    private static Map<String, Object> encodeFirstChoice(JsonCodec json, IrResponse r) {
        Map<String, Object> choice = new LinkedHashMap<>();
        Object indexRaw = extension(r, EXT_CHOICE_INDEX_RAW);
        choice.put("index", indexRaw != null ? indexRaw : 0);
        Object finishReasonRaw = extension(r, EXT_FINISH_REASON_RAW);
        choice.put("finish_reason", finishReasonRaw != null ? finishReasonRaw : OpenaiFinishReason.toOpenai(r.stopReason));
        choice.put("message", encodeMessage(json, r));
        Map<String, Object> choiceExtra = JsonUtil.asMap(extension(r, EXT_CHOICE_EXTRA_RAW));
        if (choiceExtra != null) choice.putAll(choiceExtra);
        return choice;
    }

    private static Map<String, Object> encodeMessage(JsonCodec json, IrResponse r) {
        Map<String, Object> message = new LinkedHashMap<>();
        Object roleRaw = extension(r, EXT_MESSAGE_ROLE_RAW);
        message.put("role", roleRaw != null ? roleRaw : "assistant");

        List<Block> textish = new ArrayList<>();
        List<ToolUseBlock> toolUses = new ArrayList<>();
        if (r.content != null) {
            for (Block b : r.content) {
                if (b instanceof ToolUseBlock) {
                    toolUses.add((ToolUseBlock) b);
                } else {
                    textish.add(b);
                }
            }
        }

        boolean wasString = Boolean.TRUE.equals(extension(r, EXT_CONTENT_IS_STRING));
        if (wasString && OpenaiBlockCodec.isPlainWrappedText(textish)) {
            message.put("content", ((TextBlock) textish.get(0)).text);
        } else if (!textish.isEmpty()) {
            message.put("content", OpenaiBlockCodec.encodeContentList(textish));
        } else {
            message.put("content", null);
        }
        if (!toolUses.isEmpty()) {
            message.put("tool_calls", OpenaiBlockCodec.encodeToolCalls(json, toolUses));
        }

        Map<String, Object> messageExtra = JsonUtil.asMap(extension(r, EXT_MESSAGE_EXTRA_RAW));
        if (messageExtra != null) message.putAll(messageExtra);
        return message;
    }

    // ---- extensions bookkeeping ------------------------------------------------------------------

    private static Map<String, Object> leftovers(Map<String, Object> source, Set<String> knownKeys) {
        Map<String, Object> extra = null;
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (!knownKeys.contains(e.getKey())) {
                if (extra == null) extra = new LinkedHashMap<>();
                extra.put(e.getKey(), e.getValue());
            }
        }
        return extra;
    }

    private static Object extension(IrResponse r, String key) {
        return r.extensions == null ? null : r.extensions.get(key);
    }

    private static void putExtension(IrResponse r, String key, Object value) {
        if (r.extensions == null) r.extensions = new LinkedHashMap<>();
        r.extensions.put(key, value);
    }

    private static void encodeLeftoverExtensions(IrResponse r, Map<String, Object> m) {
        if (r.extensions == null) return;
        for (Map.Entry<String, Object> e : r.extensions.entrySet()) {
            if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
        }
    }
}
