package com.benxin.exp.models.wire;

import com.benxin.exp.common.wire.AnthropicWire;
import com.benxin.exp.common.wire.GeminiWire;
import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.ResponsesWire;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.util.Json;

import java.util.List;
import java.util.Map;

/**
 * 四协议共用的常量、请求构造与"桩模型 id"。
 *
 * <p>放成一个类而不是散在各处，是为了让"同一个请求被四个协议各自翻译了一遍"
 * 这件事在代码里也看得出来：<b>输入只有一份</b>
 * （{@link #request(String, boolean, boolean, boolean, boolean)}），
 * 差异全部由 {@code ChatRequest} → 报文的那一步产生。</p>
 *
 * <p>桩模型 id 与配置里的 {@code model} 严格一致：报文里的 {@code model} 字段
 * 是"配置有没有透传"的第一现场，值对不上就没必要往下看了。</p>
 */
public final class WireConstants {

    /** 四家桩模型对应的模型注册名（{@code llm.models.<key>}）。 */
    public static final String OPENAI_KEY = "openai-demo";
    public static final String ANTHROPIC_KEY = "anthropic-demo";
    public static final String GEMINI_KEY = "gemini-demo";
    public static final String RESPONSES_KEY = "responses-demo";
    public static final String DEMO_KEY = "demo";

    /** 自定义 demo 协议的桩模型 id。 */
    public static final String DEMO_MODEL = "demo-chat";

    /** 演示里固定使用的系统提示词：四个协议把它放在四个完全不同的位置。 */
    public static final String SYSTEM_PROMPT = "你是电路检查助手，回答保持简短。";

    /** 固定使用的用户输入。 */
    public static final String USER_TEXT = "北京今天适合骑车吗？";

    /** 天气工具的 JSON Schema（object 型，三家都能接受）。 */
    public static final String WEATHER_SCHEMA = """
            {"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}""";

    /** 一个 1x1 的透明 PNG，用作"内联图片"，避免演示依赖任何外部文件。 */
    public static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    public static final String IMAGE_MEDIA_TYPE = "image/png";

    /** 演示用的外链图片地址（只出现在报文里，绝不会被真正下载）。 */
    public static final String IMAGE_URL = "https://example.invalid/cat.png";

    private WireConstants() {
    }

    // ------------------------------------------------------------------ 桩服务的响应

    /** OpenAI 协议的桩模型 id（{@code OpenAiWire} 里那个常量就是它）。 */
    public static String openAiStubModel() {
        return OpenAiWire.MODEL;
    }

    public static String anthropicStubModel() {
        return AnthropicWire.MODEL;
    }

    public static String geminiStubModel() {
        return GeminiWire.MODEL_VERSION;
    }

    public static String responsesStubModel() {
        return ResponsesWire.MODEL_ID;
    }

    // ------------------------------------------------------------------ 请求构造

    /** 最简请求：一条系统消息 + 一条用户消息。 */
    public static ChatRequest request() {
        return request(true, false, false, false, false);
    }

    /**
     * 按开关组装统一请求。
     *
     * @param system    是否带系统提示词
     * @param tools     是否声明 {@code get_weather} 工具
     * @param image     是否带一张内联图片（多模态）
     * @param imageUrl  是否带一张外链图片
     * @param toolResult 是否带上"上一轮工具调用 + 结果"（用来演示工具结果在四个协议里的落点）
     */
    public static ChatRequest request(boolean system, boolean tools, boolean image,
                                      boolean imageUrl, boolean toolResult) {
        ChatRequest.Builder builder = ChatRequest.builder();
        if (system) {
            builder.message(ChatMessage.system(SYSTEM_PROMPT));
        }
        if (image || imageUrl) {
            List<com.benxin.llm.core.message.ContentPart> parts = new java.util.ArrayList<>();
            parts.add(new TextPart(USER_TEXT));
            if (image) {
                parts.add(ImagePart.ofBase64(IMAGE_MEDIA_TYPE, PNG_BASE64));
            }
            if (imageUrl) {
                parts.add(ImagePart.ofUrl(IMAGE_URL));
            }
            builder.message(ChatMessage.user(parts.toArray(new com.benxin.llm.core.message.ContentPart[0])));
        } else {
            builder.message(ChatMessage.user(USER_TEXT));
        }
        if (toolResult) {
            builder.message(ChatMessage.assistant("", List.of(
                            new com.benxin.llm.core.message.ToolUsePart("call_1", "get_weather",
                                    "{\"city\":\"北京\"}"))))
                    .message(ChatMessage.toolResult("call_1", "get_weather", "晴，18℃，微风", false));
        }
        if (tools) {
            builder.tools(List.of(weatherTool()));
        }
        return builder
                .temperature(0.3d)
                .maxTokens(256)
                // 一个厂商私有参数：它必须出现在请求体顶层（四个协议都这么透传），
                // 是"配置/请求里的 extra 真的到了线上"的第一现场。
                .extra(Map.of("vendor_flag", "exp-07"))
                .build();
    }

    /** 只要一张内联图片的请求。 */
    public static ChatRequest imageRequest() {
        return request(true, false, true, false, false);
    }

    /** 带工具的请求。 */
    public static ChatRequest toolRequest() {
        return request(true, true, false, false, false);
    }

    /** 带"工具调用 + 结果"的请求，用来演示工具结果在四个协议里的编码差异。 */
    public static ChatRequest toolResultRequest() {
        return request(true, true, false, false, true);
    }

    /** {@code get_weather} 工具声明。 */
    public static ToolSpec weatherTool() {
        return new ToolSpec("get_weather", "查询指定城市的当前天气",
                Json.parseQuietly(WEATHER_SCHEMA), false);
    }
}
