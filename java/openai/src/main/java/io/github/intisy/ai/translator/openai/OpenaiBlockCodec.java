package io.github.intisy.ai.translator.openai;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.ImageBlock;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.UnknownBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat-completions content-part {@code Map} tree <-> {@link Block} hierarchy. OpenAI
 * content parts are {@code {type:"text",text}} and {@code {type:"image_url",image_url:{url}}};
 * anything else (a future content-part type) decodes as {@link UnknownBlock} rather than failing
 * the whole request. An {@code image_url.url} is either a {@code data:<mediaType>;base64,<data>}
 * URI (inline image bytes) or a plain remote URL; extra {@code image_url} fields (e.g.
 * {@code detail}) round-trip verbatim through {@link #EXT_IMAGE_URL_RAW} since they have no
 * neutral IR home.
 */
final class OpenaiBlockCodec {
    private OpenaiBlockCodec() {
    }

    static final String EXT_IMAGE_URL_RAW = "$imageUrlRaw";
    static final String EXT_CONTENT_IS_STRING = "$contentIsString";

    static void putExtension(Block block, String key, Object value) {
        if (block.extensions == null) block.extensions = new LinkedHashMap<>();
        block.extensions.put(key, value);
    }

    static List<Object> encodeContentList(List<Block> blocks) {
        List<Object> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block b : blocks) {
            Map<String, Object> part = encodeContentPart(b);
            if (part != null) out.add(part);
        }
        return out;
    }

    static List<Block> decodeContentList(Object raw) {
        List<Object> list = OpenaiJsonUtil.asList(raw);
        if (list == null) return null;
        List<Block> out = new ArrayList<>();
        for (Object item : list) out.add(decodeContentPart(OpenaiJsonUtil.asMap(item)));
        return out;
    }

    /** Wraps plain-string content (a message/tool-result {@code content} string) as a single block. */
    static List<Block> wrapStringAsBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new TextBlock(text));
        return blocks;
    }

    /** True when {@code blocks} is exactly the shape produced by {@link #wrapStringAsBlocks}. */
    static boolean isPlainWrappedText(List<Block> blocks) {
        if (blocks == null || blocks.size() != 1) return false;
        Block only = blocks.get(0);
        return only instanceof TextBlock && only.cacheControl == null && only.extensions == null;
    }

    static Block decodeContentPart(Map<String, Object> m) {
        if (m == null) return null;
        String type = OpenaiJsonUtil.asString(m.get("type"));
        if ("text".equals(type)) {
            return new TextBlock(OpenaiJsonUtil.asString(m.get("text")));
        }
        if ("image_url".equals(type)) {
            return decodeImagePart(OpenaiJsonUtil.asMap(m.get("image_url")));
        }
        // An unrecognized content-part type (e.g. "input_audio") -- stash it verbatim rather than
        // throw, mirroring AnthropicBlockCodec's UnknownBlock handling.
        UnknownBlock u = new UnknownBlock();
        u.raw = new LinkedHashMap<>(m);
        return u;
    }

    private static Block decodeImagePart(Map<String, Object> imageUrl) {
        ImageBlock img = new ImageBlock();
        if (imageUrl == null) return img;
        String url = OpenaiJsonUtil.asString(imageUrl.get("url"));
        DataUri dataUri = url == null ? null : DataUri.parse(url);
        if (dataUri != null) {
            img.mediaType = dataUri.mediaType;
            img.data = dataUri.data;
        } else {
            img.url = url;
        }
        Map<String, Object> extra = new LinkedHashMap<>(imageUrl);
        extra.remove("url");
        if (!extra.isEmpty()) {
            putExtension(img, EXT_IMAGE_URL_RAW, extra);
        }
        return img;
    }

    static Map<String, Object> encodeContentPart(Block block) {
        if (block == null) return null;
        if (block instanceof UnknownBlock) {
            return new LinkedHashMap<>(((UnknownBlock) block).raw);
        }
        if (block instanceof TextBlock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "text");
            m.put("text", ((TextBlock) block).text);
            return m;
        }
        if (block instanceof ImageBlock) {
            return encodeImagePart((ImageBlock) block);
        }
        // No OpenAI content-part shape for this block kind (e.g. tool_use/tool_result/thinking are
        // handled at the message level, never as a content part): drop it rather than fail.
        return null;
    }

    private static Map<String, Object> encodeImagePart(ImageBlock img) {
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        if (img.data != null) {
            imageUrl.put("url", "data:" + img.mediaType + ";base64," + img.data);
        } else if (img.url != null) {
            imageUrl.put("url", img.url);
        }
        if (img.extensions != null) {
            Map<String, Object> extra = OpenaiJsonUtil.asMap(img.extensions.get(EXT_IMAGE_URL_RAW));
            if (extra != null) imageUrl.putAll(extra);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "image_url");
        m.put("image_url", imageUrl);
        return m;
    }

    /** A parsed {@code data:<mediaType>;base64,<data>} URI, or null if {@code url} is not one. */
    private static final class DataUri {
        final String mediaType;
        final String data;

        private DataUri(String mediaType, String data) {
            this.mediaType = mediaType;
            this.data = data;
        }

        static DataUri parse(String url) {
            if (!url.startsWith("data:")) return null;
            int comma = url.indexOf(',');
            if (comma < 0) return null;
            String header = url.substring(5, comma);
            int semicolon = header.indexOf(";base64");
            if (semicolon < 0) return null;
            String mediaType = header.substring(0, semicolon);
            String data = url.substring(comma + 1);
            return new DataUri(mediaType, data);
        }
    }
}
