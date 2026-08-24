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
        return OpenaiRequestCodec.decodeRequest(json, wireJson);
    }

    @Override
    public String encodeRequest(IrRequest request) {
        return OpenaiRequestCodec.encodeRequest(json, request);
    }

    @Override
    public IrResponse decodeResponse(String wireJson) {
        return OpenaiResponseCodec.decodeResponse(json, wireJson);
    }

    @Override
    public String encodeResponse(IrResponse response) {
        return OpenaiResponseCodec.encodeResponse(json, response);
    }

    @Override
    public StreamDecoder newStreamDecoder() {
        return new OpenaiStreamDecoder(json);
    }

    @Override
    public StreamEncoder newStreamEncoder() {
        return new OpenaiStreamEncoder(json);
    }
}
