/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test：
 *   benxin_test/test-01-protocol-transport/src/main/java/com/benxin/test/protocol/wire/OpenAiWire.java
 * 改编内容仅限包名与注释措辞，报文字段一个都没动 —— 这些字段名本身就是"协议契约"的可执行记录，
 * 示例工程照抄它们，才谈得上"对着真实报文演示协议"。
 */
package com.benxin.exp.common.wire;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completions 协议的报文构造工具。
 *
 * <p>每个字段都严格对照 {@code OpenAiCodec#decode} 与 {@code OpenAiStreamDecoder} 的读法来写：
 * 解码器读 {@code choices[0].message.content} / {@code choices[0].message.tool_calls[].function.*}、
 * 流式侧读 {@code choices[0].delta.*} 与 {@code delta.tool_calls[].index}、
 * 用量读 {@code usage.prompt_tokens} / {@code completion_tokens} /
 * {@code prompt_tokens_details.cached_tokens} / {@code prompt_cache_hit_tokens} /
 * {@code completion_tokens_details.reasoning_tokens}。字段名写错不会报错，只会静默变成 0 或空串，
 * 所以这些构造方法本身就是"协议契约"的可执行记录。</p>
 */
public final class OpenAiWire {

    /** 桩模型 id；请求体里下发的 {@code model} 必须与它一致，否则说明配置没透传。 */
    public static final String MODEL = "stub-gpt";

    /** 固定的响应 id，便于断言 {@code ChatResponse.id()}。 */
    public static final String RESPONSE_ID = "chatcmpl-t01";

    private OpenAiWire() {
    }

    // ------------------------------------------------------------------ 非流式响应

    /** 最简单的文本响应（默认用量 42/7）。 */
    public static String textResponse(String content) {
        return textResponse(MODEL, content, "stop", 42, 7);
    }

    /** 完整文本响应：id / model / content / finish_reason / usage 全部可指定。 */
    public static String textResponse(String model, String content, String finishReason,
                                      int promptTokens, int completionTokens) {
        ObjectNode root = envelope(model);
        ObjectNode message = WireJson.obj();
        message.put("role", "assistant");
        message.put("content", content);
        root.withArray("choices").add(choice(message, finishReason));
        root.set("usage", usage(promptTokens, completionTokens));
        return WireJson.write(root);
    }

    /**
     * 带 {@code usage} 细节的文本响应：命中缓存的输入 token 与推理 token。
     *
     * <p>覆盖解码器的三条取值路径：{@code prompt_tokens_details.cached_tokens}（OpenAI 官方）、
     * {@code prompt_cache_hit_tokens}（DeepSeek）、{@code completion_tokens_details.reasoning_tokens}。</p>
     */
    public static String textResponseWithUsageDetails(String model, String content,
                                                      int promptTokens, int completionTokens,
                                                      int cachedTokens, int reasoningTokens) {
        ObjectNode root = envelope(model);
        ObjectNode message = WireJson.obj();
        message.put("role", "assistant");
        message.put("content", content);
        root.withArray("choices").add(choice(message, "stop"));
        ObjectNode usage = usage(promptTokens, completionTokens);
        usage.putObject("prompt_tokens_details").put("cached_tokens", cachedTokens);
        usage.putObject("completion_tokens_details").put("reasoning_tokens", reasoningTokens);
        root.set("usage", usage);
        return WireJson.write(root);
    }

    /** DeepSeek 形态：把缓存命中量记在 {@code prompt_cache_hit_tokens} 上。 */
    public static String deepSeekCachedUsageResponse(String model, String content,
                                                     int promptTokens, int completionTokens, int cacheHitTokens) {
        ObjectNode root = envelope(model);
        ObjectNode message = WireJson.obj();
        message.put("role", "assistant");
        message.put("content", content);
        root.withArray("choices").add(choice(message, "stop"));
        ObjectNode usage = usage(promptTokens, completionTokens);
        usage.put("prompt_cache_hit_tokens", cacheHitTokens);
        root.set("usage", usage);
        return WireJson.write(root);
    }

    /** DeepSeek-R1 形态：思维链放在 {@code reasoning_content}。 */
    public static String reasoningResponse(String model, String reasoning, String content,
                                           int promptTokens, int completionTokens) {
        ObjectNode root = envelope(model);
        ObjectNode message = WireJson.obj();
        message.put("role", "assistant");
        message.put("reasoning_content", reasoning);
        message.put("content", content);
        root.withArray("choices").add(choice(message, "stop"));
        root.set("usage", usage(promptTokens, completionTokens));
        return WireJson.write(root);
    }

    /** 工具调用响应（可带多个并行调用）。 */
    public static String toolCallsResponse(String model, ToolCall... calls) {
        ObjectNode root = envelope(model);
        ObjectNode message = WireJson.obj();
        message.put("role", "assistant");
        message.putNull("content");
        ArrayNode array = message.putArray("tool_calls");
        for (ToolCall call : calls) {
            ObjectNode node = array.addObject();
            node.put("id", call.id());
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", call.name());
            // arguments 在协议里是"JSON 文本"而不是 JSON 对象——这里刻意保留字符串形态
            function.put("arguments", call.argumentsJson());
        }
        root.withArray("choices").add(choice(message, "tool_calls"));
        root.set("usage", usage(31, 12));
        return WireJson.write(root);
    }

    /** 一帧错误信封（部分网关即使 200 也会返回它）。 */
    public static String errorEnvelope(String type, String message) {
        ObjectNode root = WireJson.obj();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", type);
        error.put("code", "invalid_request_error");
        return WireJson.write(root);
    }

    /** 一次工具调用的三个字段。 */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    // ------------------------------------------------------------------ 流式帧

    /** 把 payload 包成一帧 {@code data: ...} + 空行。 */
    public static String dataFrame(String payload) {
        return "data: " + payload + "\n\n";
    }

    /** OpenAI 的流终止帧。 */
    public static String doneFrame() {
        return "data: [DONE]\n\n";
    }

    /** 正文增量帧。 */
    public static String textChunk(String model, String content) {
        ObjectNode delta = WireJson.obj();
        delta.put("content", content);
        return dataFrame(chunk(model, delta, null).toString());
    }

    /** DeepSeek-R1 的思维链增量帧。 */
    public static String reasoningChunk(String model, String reasoning) {
        ObjectNode delta = WireJson.obj();
        delta.put("reasoning_content", reasoning);
        return dataFrame(chunk(model, delta, null).toString());
    }

    /** 工具调用的首个分片：带 index / id / name 与第一段 arguments。 */
    public static String toolCallStartChunk(String model, int index, String id, String name, String argumentsFragment) {
        ObjectNode call = WireJson.obj();
        call.put("index", index);
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        function.put("arguments", argumentsFragment);
        return dataFrame(toolCallChunk(model, call).toString());
    }

    /** 工具调用的后续分片：只有 index 与一段 arguments（id / name 不再出现）。 */
    public static String toolCallArgumentsChunk(String model, int index, String argumentsFragment) {
        ObjectNode call = WireJson.obj();
        call.put("index", index);
        ObjectNode function = call.putObject("function");
        function.put("arguments", argumentsFragment);
        return dataFrame(toolCallChunk(model, call).toString());
    }

    /** 结束帧：delta 为空对象、只带 finish_reason。 */
    public static String finishChunk(String model, String finishReason) {
        return dataFrame(chunk(model, WireJson.obj(), finishReason).toString());
    }

    /** {@code stream_options.include_usage} 打开后，末帧会带 usage 且 choices 为空数组。 */
    public static String usageChunk(String model, int promptTokens, int completionTokens) {
        ObjectNode root = WireJson.obj();
        root.put("id", RESPONSE_ID);
        root.put("object", "chat.completion.chunk");
        root.put("model", model);
        root.putArray("choices");
        ObjectNode usage = usage(promptTokens, completionTokens);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 8);
        root.set("usage", usage);
        return dataFrame(root.toString());
    }

    /** 一条完整的文本流：N 个正文分片 + 结束帧 + 用量帧 + [DONE]。 */
    public static List<String> streamingText(String model, int promptTokens, int completionTokens,
                                             String... deltas) {
        List<String> frames = new ArrayList<>();
        for (String delta : deltas) {
            frames.add(textChunk(model, delta));
        }
        frames.add(finishChunk(model, "stop"));
        frames.add(usageChunk(model, promptTokens, completionTokens));
        frames.add(doneFrame());
        return frames;
    }

    /**
     * 一条"工具参数按 index 分片到达"的完整流。
     *
     * <p>这正是 README 明确承诺、也最容易在兼容网关上踩坑的形态：{@code id} / {@code name}
     * 只出现在第一个分片，{@code arguments} 被切成多段，必须按 {@code index} 归并后拼装。</p>
     */
    public static List<String> streamingToolCall(String model, int index, String id, String name,
                                                 List<String> argumentFragments) {
        List<String> frames = new ArrayList<>();
        for (int i = 0; i < argumentFragments.size(); i++) {
            frames.add(i == 0
                    ? toolCallStartChunk(model, index, id, name, argumentFragments.get(i))
                    : toolCallArgumentsChunk(model, index, argumentFragments.get(i)));
        }
        frames.add(finishChunk(model, "tool_calls"));
        frames.add(doneFrame());
        return frames;
    }

    // ------------------------------------------------------------------ 内部

    /** 把一段 JSON 文本切成 n 份（按字符切，模拟"任意切分"）。 */
    public static List<String> splitInto(String text, int pieces) {
        List<String> parts = new ArrayList<>();
        int size = Math.max(1, (int) Math.ceil((double) text.length() / pieces));
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    private static ObjectNode envelope(String model) {
        ObjectNode root = WireJson.obj();
        root.put("id", RESPONSE_ID);
        root.put("object", "chat.completion");
        root.put("created", 1735689600L);
        root.put("model", model);
        root.putArray("choices");
        return root;
    }

    private static ObjectNode choice(ObjectNode message, String finishReason) {
        ObjectNode choice = WireJson.obj();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        return choice;
    }

    private static ObjectNode usage(int promptTokens, int completionTokens) {
        ObjectNode usage = WireJson.obj();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    private static ObjectNode chunk(String model, ObjectNode delta, String finishReason) {
        ObjectNode root = WireJson.obj();
        root.put("id", RESPONSE_ID);
        root.put("object", "chat.completion.chunk");
        root.put("created", 1735689600L);
        root.put("model", model);
        ObjectNode choice = WireJson.obj();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        root.withArray("choices").add(choice);
        return root;
    }

    private static ObjectNode toolCallChunk(String model, ObjectNode call) {
        ObjectNode delta = WireJson.obj();
        delta.putArray("tool_calls").add(call);
        return chunk(model, delta, null);
    }
}

