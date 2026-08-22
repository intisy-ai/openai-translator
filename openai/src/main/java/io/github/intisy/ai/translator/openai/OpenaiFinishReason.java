package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrStopReason;

/**
 * OpenAI {@code finish_reason} <-> {@link IrStopReason}. {@code stop}/{@code length}/
 * {@code tool_calls} round-trip through {@link IrStopReason#END_TURN}/{@link IrStopReason#MAX_TOKENS}/
 * {@link IrStopReason#TOOL_USE} directly. OpenAI has no other reason with a clean IR analog, so
 * {@code content_filter} and any future value fall back to {@link IrStopReason#ERROR} (mirroring
 * {@code GeminiFinishReason}'s treatment of {@code SAFETY}); {@code OpenaiResponseCodec} stashes
 * the raw string so a same-vendor round trip stays exact regardless of that fallback.
 */
final class OpenaiFinishReason {
    private OpenaiFinishReason() {
    }

    static String toIr(String openaiReason) {
        if ("stop".equals(openaiReason)) return IrStopReason.END_TURN;
        if ("length".equals(openaiReason)) return IrStopReason.MAX_TOKENS;
        if ("tool_calls".equals(openaiReason)) return IrStopReason.TOOL_USE;
        return IrStopReason.ERROR;
    }

    static String toOpenai(String irStopReason) {
        if (IrStopReason.MAX_TOKENS.equals(irStopReason)) return "length";
        if (IrStopReason.TOOL_USE.equals(irStopReason)) return "tool_calls";
        return "stop";
    }
}
