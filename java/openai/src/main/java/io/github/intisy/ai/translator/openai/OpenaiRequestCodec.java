package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrMessage;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrTool;
import io.github.intisy.ai.ir.IrToolChoice;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolResultBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI chat-completions request {@code Map} tree <-> {@link IrRequest}. {@code role:"system"}
 * messages are lifted out of {@code messages} into {@link IrRequest#system} (and reconstructed as
 * a single leading system message on encode); a message's string {@code content} is remembered
 * (a {@code $}-prefixed marker in the owning object's {@code extensions}) so encode reproduces the
 * same shape rather than always widening to an array, and likewise for an assistant message whose
 * {@code content} was JSON {@code null} (the tool-calls-only shape). Any request/message/tool/
 * tool_choice field with no neutral IR home round-trips verbatim through the corresponding
 * {@code extensions} bag.
 */
final class OpenaiRequestCodec {
    private OpenaiRequestCodec() {
    }

    private static final String EXT_SYSTEM_IS_STRING = "$systemIsString";
    private static final String EXT_STOP_IS_STRING = "$stopIsString";
    private static final String EXT_CONTENT_IS_STRING = "$contentIsString";
    private static final String EXT_CONTENT_IS_NULL = "$contentIsNull";

    private static final Set<String> TOP_LEVEL_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "model", "messages", "tools", "tool_choice", "max_tokens", "temperature", "top_p", "stop", "stream"));

    private static final Set<String> MESSAGE_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "role", "content", "tool_calls", "reasoning_content"));

    private static final Set<String> TOOL_MESSAGE_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "role", "content", "tool_call_id"));

    static IrRequest decodeRequest(JsonCodec json, String wireJson) {
        Map<String, Object> root = OpenaiJsonUtil.asMap(json.parse(wireJson));
        IrRequest r = new IrRequest();
        if (root == null) return r;

        r.model = OpenaiJsonUtil.asString(root.get("model"));
        r.maxTokens = OpenaiJsonUtil.asInt(root.get("max_tokens"));
        r.temperature = OpenaiJsonUtil.asDouble(root.get("temperature"));
        r.topP = OpenaiJsonUtil.asDouble(root.get("top_p"));
        decodeStop(root.get("stop"), r);
        Boolean stream = OpenaiJsonUtil.asBoolean(root.get("stream"));
        r.stream = stream != null && stream;

        decodeMessages(json, root.get("messages"), r);
        r.tools = decodeTools(root.get("tools"));
        if (root.get("tool_choice") != null) r.toolChoice = decodeToolChoice(root.get("tool_choice"));

        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!TOP_LEVEL_KNOWN_KEYS.contains(e.getKey())) {
                putRequestExtension(r, e.getKey(), e.getValue());
            }
        }
        return r;
    }

    static String encodeRequest(JsonCodec json, IrRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", r.model);
        m.put("messages", encodeMessages(json, r));
        if (r.tools != null) m.put("tools", encodeTools(r.tools));
        if (r.toolChoice != null) m.put("tool_choice", encodeToolChoice(r.toolChoice));
        if (r.maxTokens != null) m.put("max_tokens", r.maxTokens);
        if (r.temperature != null) m.put("temperature", r.temperature);
        if (r.topP != null) m.put("top_p", r.topP);
        encodeStop(r, m);
        m.put("stream", r.stream);
        encodeLeftoverExtensions(r, m);
        return json.stringify(m);
    }

    // ---- messages ------------------------------------------------------------------------------

    private static void decodeMessages(JsonCodec json, Object raw, IrRequest r) {
        List<Object> list = OpenaiJsonUtil.asList(raw);
        if (list == null) return;
        List<Block> systemBlocks = null;
        List<IrMessage> messages = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> mm = OpenaiJsonUtil.asMap(item);
            if (mm == null) continue;
            if ("system".equals(OpenaiJsonUtil.asString(mm.get("role")))) {
                ContentShape shape = decodeContentShape(mm.get("content"));
                if (systemBlocks == null) systemBlocks = new ArrayList<>();
                systemBlocks.addAll(shape.blocks);
                if (shape.wasString) putRequestExtension(r, EXT_SYSTEM_IS_STRING, Boolean.TRUE);
                continue;
            }
            messages.add(decodeMessage(json, mm));
        }
        r.system = systemBlocks;
        r.messages = messages;
    }

    private static IrMessage decodeMessage(JsonCodec json, Map<String, Object> mm) {
        IrMessage msg = new IrMessage();
        msg.role = OpenaiJsonUtil.asString(mm.get("role"));

        if ("tool".equals(msg.role)) {
            ToolResultBlock result = new ToolResultBlock();
            result.toolUseId = OpenaiJsonUtil.asString(mm.get("tool_call_id"));
            ContentShape shape = decodeContentShape(mm.get("content"));
            result.content = shape.blocks;
            if (shape.wasString) OpenaiBlockCodec.putExtension(result, OpenaiBlockCodec.EXT_CONTENT_IS_STRING, Boolean.TRUE);
            List<Block> content = new ArrayList<>();
            content.add(result);
            msg.content = content;
            copyMessageExtensions(mm, msg, TOOL_MESSAGE_KNOWN_KEYS);
            return msg;
        }

        List<Block> content = new ArrayList<>();
        String reasoning = OpenaiJsonUtil.asString(mm.get("reasoning_content"));
        if (reasoning != null) {
            ThinkingBlock thinking = new ThinkingBlock();
            thinking.text = reasoning;
            content.add(thinking);
        }

        ContentShape shape = decodeContentShape(mm.get("content"));
        content.addAll(shape.blocks);

        List<Object> toolCalls = OpenaiJsonUtil.asList(mm.get("tool_calls"));
        if (toolCalls != null) {
            for (Object tc : toolCalls) {
                Map<String, Object> tcMap = OpenaiJsonUtil.asMap(tc);
                if (tcMap != null) content.add(OpenaiBlockCodec.decodeToolCall(json, tcMap));
            }
        }

        msg.content = content;
        if (shape.wasString) putMessageExtension(msg, EXT_CONTENT_IS_STRING, Boolean.TRUE);
        if (shape.wasNull) putMessageExtension(msg, EXT_CONTENT_IS_NULL, Boolean.TRUE);
        copyMessageExtensions(mm, msg, MESSAGE_KNOWN_KEYS);
        return msg;
    }

    private static List<Object> encodeMessages(JsonCodec json, IrRequest r) {
        List<Object> out = new ArrayList<>();
        if (r.system != null && !r.system.isEmpty()) {
            out.add(encodeSystemMessage(r));
        }
        if (r.messages != null) {
            for (IrMessage msg : r.messages) out.add(encodeMessage(json, msg));
        }
        return out;
    }

    private static Map<String, Object> encodeSystemMessage(IrRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        boolean wasString = r.extensions != null && Boolean.TRUE.equals(r.extensions.get(EXT_SYSTEM_IS_STRING));
        if (wasString && OpenaiBlockCodec.isPlainWrappedText(r.system)) {
            m.put("content", ((TextBlock) r.system.get(0)).text);
        } else {
            m.put("content", OpenaiBlockCodec.encodeContentList(r.system));
        }
        return m;
    }

    private static Map<String, Object> encodeMessage(JsonCodec json, IrMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", msg.role);

        if ("tool".equals(msg.role)) {
            ToolResultBlock result = firstToolResult(msg.content);
            m.put("tool_call_id", result != null ? result.toolUseId : null);
            boolean wasString = result != null && result.extensions != null
                    && Boolean.TRUE.equals(result.extensions.get(OpenaiBlockCodec.EXT_CONTENT_IS_STRING));
            if (wasString && OpenaiBlockCodec.isPlainWrappedText(result.content)) {
                m.put("content", ((TextBlock) result.content.get(0)).text);
            } else {
                m.put("content", OpenaiBlockCodec.encodeContentList(result != null ? result.content : null));
            }
            copyLeftoverMessageExtensions(msg, m);
            return m;
        }

        String reasoningText = null;
        List<Block> textish = new ArrayList<>();
        List<ToolUseBlock> toolUses = new ArrayList<>();
        if (msg.content != null) {
            for (Block b : msg.content) {
                if (b instanceof ThinkingBlock && reasoningText == null) {
                    reasoningText = ((ThinkingBlock) b).text;
                } else if (b instanceof ToolUseBlock) {
                    toolUses.add((ToolUseBlock) b);
                } else {
                    textish.add(b);
                }
            }
        }
        if (reasoningText != null) m.put("reasoning_content", reasoningText);

        boolean wasNull = msg.extensions != null && Boolean.TRUE.equals(msg.extensions.get(EXT_CONTENT_IS_NULL));
        boolean wasString = msg.extensions != null && Boolean.TRUE.equals(msg.extensions.get(EXT_CONTENT_IS_STRING));
        if (wasNull && textish.isEmpty()) {
            m.put("content", null);
        } else if (wasString && OpenaiBlockCodec.isPlainWrappedText(textish)) {
            m.put("content", ((TextBlock) textish.get(0)).text);
        } else {
            m.put("content", OpenaiBlockCodec.encodeContentList(textish));
        }
        if (!toolUses.isEmpty()) {
            m.put("tool_calls", OpenaiBlockCodec.encodeToolCalls(json, toolUses));
        }
        copyLeftoverMessageExtensions(msg, m);
        return m;
    }

    private static ToolResultBlock firstToolResult(List<Block> content) {
        if (content == null) return null;
        for (Block b : content) {
            if (b instanceof ToolResultBlock) return (ToolResultBlock) b;
        }
        return null;
    }

    // ---- content shape ---------------------------------------------------------------------------

    private static ContentShape decodeContentShape(Object raw) {
        ContentShape shape = new ContentShape();
        if (raw instanceof String) {
            shape.blocks = OpenaiBlockCodec.wrapStringAsBlocks((String) raw);
            shape.wasString = true;
        } else if (raw == null) {
            shape.blocks = new ArrayList<>();
            shape.wasNull = true;
        } else {
            List<Block> decoded = OpenaiBlockCodec.decodeContentList(raw);
            shape.blocks = decoded != null ? decoded : new ArrayList<Block>();
        }
        return shape;
    }

    private static final class ContentShape {
        List<Block> blocks;
        boolean wasString;
        boolean wasNull;
    }

    // ---- tools -----------------------------------------------------------------------------------

    private static List<IrTool> decodeTools(Object raw) {
        List<Object> list = OpenaiJsonUtil.asList(raw);
        if (list == null) return null;
        List<IrTool> out = new ArrayList<>();
        for (Object item : list) out.add(decodeTool(OpenaiJsonUtil.asMap(item)));
        return out;
    }

    private static IrTool decodeTool(Map<String, Object> tm) {
        if (tm == null) return null;
        IrTool t = new IrTool();
        Map<String, Object> fn = OpenaiJsonUtil.asMap(tm.get("function"));
        if (fn != null) {
            t.name = OpenaiJsonUtil.asString(fn.get("name"));
            t.description = OpenaiJsonUtil.asString(fn.get("description"));
            t.inputSchema = fn.get("parameters");
            for (Map.Entry<String, Object> e : fn.entrySet()) {
                String k = e.getKey();
                if (!"name".equals(k) && !"description".equals(k) && !"parameters".equals(k)) {
                    putToolExtension(t, k, e.getValue());
                }
            }
        }
        for (Map.Entry<String, Object> e : tm.entrySet()) {
            String k = e.getKey();
            if (!"type".equals(k) && !"function".equals(k)) {
                putToolExtension(t, k, e.getValue());
            }
        }
        return t;
    }

    private static List<Object> encodeTools(List<IrTool> tools) {
        List<Object> out = new ArrayList<>();
        for (IrTool t : tools) out.add(encodeTool(t));
        return out;
    }

    private static Map<String, Object> encodeTool(IrTool t) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", t.name);
        if (t.description != null) fn.put("description", t.description);
        fn.put("parameters", t.inputSchema);
        if (t.extensions != null) {
            for (Map.Entry<String, Object> e : t.extensions.entrySet()) fn.put(e.getKey(), e.getValue());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "function");
        m.put("function", fn);
        return m;
    }

    private static void putToolExtension(IrTool t, String key, Object value) {
        if (t.extensions == null) t.extensions = new LinkedHashMap<>();
        t.extensions.put(key, value);
    }

    // ---- tool_choice ---------------------------------------------------------------------------

    private static IrToolChoice decodeToolChoice(Object raw) {
        IrToolChoice c = new IrToolChoice();
        if (raw instanceof String) {
            String s = (String) raw;
            if ("auto".equals(s)) c.type = IrToolChoice.Type.AUTO;
            else if ("none".equals(s)) c.type = IrToolChoice.Type.NONE;
            else if ("required".equals(s)) c.type = IrToolChoice.Type.ANY;
            else c.type = s;
            return c;
        }
        Map<String, Object> tcMap = OpenaiJsonUtil.asMap(raw);
        if (tcMap == null) return null;
        c.type = IrToolChoice.Type.TOOL;
        Map<String, Object> fn = OpenaiJsonUtil.asMap(tcMap.get("function"));
        if (fn != null) {
            c.name = OpenaiJsonUtil.asString(fn.get("name"));
            for (Map.Entry<String, Object> e : fn.entrySet()) {
                if (!"name".equals(e.getKey())) putToolChoiceExtension(c, e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, Object> e : tcMap.entrySet()) {
            String k = e.getKey();
            if (!"type".equals(k) && !"function".equals(k)) putToolChoiceExtension(c, k, e.getValue());
        }
        return c;
    }

    private static Object encodeToolChoice(IrToolChoice c) {
        if (IrToolChoice.Type.AUTO.equals(c.type)) return "auto";
        if (IrToolChoice.Type.NONE.equals(c.type)) return "none";
        if (IrToolChoice.Type.ANY.equals(c.type)) return "required";
        if (IrToolChoice.Type.TOOL.equals(c.type)) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", c.name);
            if (c.extensions != null) {
                for (Map.Entry<String, Object> e : c.extensions.entrySet()) fn.put(e.getKey(), e.getValue());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "function");
            m.put("function", fn);
            return m;
        }
        return c.type;
    }

    private static void putToolChoiceExtension(IrToolChoice c, String key, Object value) {
        if (c.extensions == null) c.extensions = new LinkedHashMap<>();
        c.extensions.put(key, value);
    }

    // ---- stop ------------------------------------------------------------------------------------

    private static void decodeStop(Object raw, IrRequest r) {
        if (raw instanceof String) {
            List<String> stop = new ArrayList<>();
            stop.add((String) raw);
            r.stopSequences = stop;
            putRequestExtension(r, EXT_STOP_IS_STRING, Boolean.TRUE);
        } else if (raw != null) {
            r.stopSequences = decodeStringList(raw);
        }
    }

    private static void encodeStop(IrRequest r, Map<String, Object> m) {
        if (r.stopSequences == null) return;
        boolean wasString = r.extensions != null && Boolean.TRUE.equals(r.extensions.get(EXT_STOP_IS_STRING));
        if (wasString && r.stopSequences.size() == 1) {
            m.put("stop", r.stopSequences.get(0));
        } else {
            m.put("stop", new ArrayList<Object>(r.stopSequences));
        }
    }

    private static List<String> decodeStringList(Object raw) {
        List<Object> list = OpenaiJsonUtil.asList(raw);
        if (list == null) return null;
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }

    // ---- extensions bookkeeping ------------------------------------------------------------------

    private static void putRequestExtension(IrRequest r, String key, Object value) {
        if (r.extensions == null) r.extensions = new LinkedHashMap<>();
        r.extensions.put(key, value);
    }

    private static void putMessageExtension(IrMessage msg, String key, Object value) {
        if (msg.extensions == null) msg.extensions = new LinkedHashMap<>();
        msg.extensions.put(key, value);
    }

    private static void copyMessageExtensions(Map<String, Object> mm, IrMessage msg, Set<String> knownKeys) {
        for (Map.Entry<String, Object> e : mm.entrySet()) {
            if (!knownKeys.contains(e.getKey())) putMessageExtension(msg, e.getKey(), e.getValue());
        }
    }

    private static void copyLeftoverMessageExtensions(IrMessage msg, Map<String, Object> m) {
        if (msg.extensions == null) return;
        for (Map.Entry<String, Object> e : msg.extensions.entrySet()) {
            if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
        }
    }

    private static void encodeLeftoverExtensions(IrRequest r, Map<String, Object> m) {
        if (r.extensions == null) return;
        for (Map.Entry<String, Object> e : r.extensions.entrySet()) {
            if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
        }
    }
}
