package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrUsage;
import io.github.intisy.ai.ir.json.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI {@code usage} object <-> {@link IrUsage}. {@code total_tokens} has no dedicated IR field
 * of its own beyond {@link IrUsage#totalTokens}; it round-trips through that field when the wire
 * carries it rather than being recomputed, so an OpenAI response that omits it stays omitted.
 */
final class OpenaiUsageCodec {
    private OpenaiUsageCodec() {
    }

    static IrUsage decode(Object raw) {
        Map<String, Object> m = JsonUtil.asMap(raw);
        if (m == null) return null;
        IrUsage u = new IrUsage();
        u.inputTokens = JsonUtil.asInt(m.get("prompt_tokens"));
        u.outputTokens = JsonUtil.asInt(m.get("completion_tokens"));
        u.totalTokens = JsonUtil.asInt(m.get("total_tokens"));
        return u;
    }

    static Map<String, Object> encode(IrUsage u) {
        if (u == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (u.inputTokens != null) m.put("prompt_tokens", u.inputTokens);
        if (u.outputTokens != null) m.put("completion_tokens", u.outputTokens);
        if (u.totalTokens != null) m.put("total_tokens", u.totalTokens);
        return m;
    }
}
