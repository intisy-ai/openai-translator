package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;

public final class OpenaiTranslator implements Translator {
    private final JsonCodec json;

    public OpenaiTranslator(JsonCodec json) {
        this.json = json;
    }

    @Override
    public IrRequest decodeRequest(String wireJson) {
        throw new UnsupportedOperationException("openai decodeRequest not implemented yet");
    }

    @Override
    public String encodeRequest(IrRequest request) {
        throw new UnsupportedOperationException("openai encodeRequest not implemented yet");
    }

    @Override
    public IrResponse decodeResponse(String wireJson) {
        throw new UnsupportedOperationException("openai decodeResponse not implemented yet");
    }

    @Override
    public String encodeResponse(IrResponse response) {
        throw new UnsupportedOperationException("openai encodeResponse not implemented yet");
    }

    @Override
    public StreamDecoder newStreamDecoder() {
        throw new UnsupportedOperationException("openai newStreamDecoder not implemented yet");
    }

    @Override
    public StreamEncoder newStreamEncoder() {
        throw new UnsupportedOperationException("openai newStreamEncoder not implemented yet");
    }
}
