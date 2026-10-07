/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test：
 *   benxin_test/test-01-protocol-transport/src/main/java/com/benxin/test/protocol/wire/AnthropicWire.java
 * 改编内容仅限包名与注释措辞，报文字段一个都没动 —— 这些字段名本身就是"协议契约"的可执行记录，
 * 示例工程照抄它们，才谈得上"对着真实报文演示协议"。
 */
package com.benxin.exp.common.wire;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages API 的报文构造工具。
 *
 * <p>对照 {@code AnthropicCodec#decode} 与 {@code AnthropicStreamDecoder}：
 * 响应侧只有 {@code content} 内容块数组（{@code text} / {@code thinking} / {@code tool_use}），
 * 结束原因叫 {@code stop_reason}，用量叫 {@code input_tokens} / {@code output_tokens} /
 * {@code cache_read_input_tokens}；流式侧是"强类型事件流"，
 * 每条 SSE 都带 {@code event:} 名，且 {@code input_json_delta.partial_json} 需要按 block index 拼装。</p>
 */
public final class AnthropicWire {

    /** 桩模型 id。 */
    public static final String MODEL = "claude-stub";

    /** 固定响应 id。 */
    public static final String MESSAGE_ID = "msg_t01";

    private AnthropicWire() {
    }

    // ------------------------------------------------------------------ 非流式响应

    /** 纯文本响应。 */
    public static String textResponse(String text, int inputTokens, int outputTokens) {
        return textResponse(MODEL, text, "end_turn", inputTokens, outputTokens, 0);
    }

    /** 完整文本响应，含 prompt cache 命中量。 */
    public static String textResponse(String model, String text, String stopReason,
                                      int inputTokens, int outputTokens, int cacheReadInputTokens) {
        ObjectNode root = envelope(model, stopReason, inputTokens, outputTokens, cacheReadInputTokens);
        ArrayNode content = root.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        return WireJson.write(root);
    }

    /** 工具调用响应：一个或多个 {@code tool_use} 内容块。 */
    public static String toolUseResponse(String model, List<ToolUse> blocks,
                                         int inputTokens, int outputTokens) {
        ObjectNode root = envelope(model, "tool_use", inputTokens, outputTokens, 0);
        ArrayNode content = root.putArray("content");
        for (ToolUse use : blocks) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", use.id());
            block.put("name", use.name());
            // input 是 JSON 对象（不是 OpenAI 那种 arguments 字符串）
            block.set("input", WireJson.parse(use.inputJson()));
        }
        return WireJson.write(root);
    }

    /** extended thinking：thinking 块带 signature，且可与正文块共存。 */
    public static String thinkingResponse(String model, String thinking, String signature,
                                          String text, int inputTokens, int outputTokens) {
        ObjectNode root = envelope(model, "end_turn", inputTokens, outputTokens, 0);
        ArrayNode content = root.putArray("content");
        ObjectNode thinkingBlock = content.addObject();
        thinkingBlock.put("type", "thinking");
        thinkingBlock.put("thinking", thinking);
        thinkingBlock.put("signature", signature);
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        return WireJson.write(root);
    }

    /** 错误信封（Anthropic 出错时返回 {@code {"type":"error","error":{...}}}）。 */
    public static String errorEnvelope(String type, String message) {
        ObjectNode root = WireJson.obj();
        root.put("type", "error");
        ObjectNode error = root.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return WireJson.write(root);
    }

    /** 一次 tool_use 内容块。 */
    public record ToolUse(String id, String name, String inputJson) {
    }

    // ------------------------------------------------------------------ 流式帧

    /** 组装一条 Anthropic 的 {@code event:} + {@code data:} 成对帧。 */
    public static String event(String eventName, String payloadJson) {
        return "event: " + eventName + "\ndata: " + payloadJson + "\n\n";
    }

    /** 流首事件：携带 message id / model 与输入侧用量。 */
    public static String messageStart(String model, int inputTokens, int outputTokens, int cacheReadInputTokens) {
        ObjectNode message = WireJson.obj();
        message.put("id", MESSAGE_ID);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        ObjectNode usage = message.putObject("usage");
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("cache_read_input_tokens", cacheReadInputTokens);
        ObjectNode root = WireJson.obj();
        root.put("type", "message_start");
        root.set("message", message);
        return event("message_start", root.toString());
    }

    /** 文本内容块开始。 */
    public static String textBlockStart(int index) {
        return blockStart(index, textBlock());
    }

    /** thinking 内容块开始。 */
    public static String thinkingBlockStart(int index) {
        return blockStart(index, thinkingBlock());
    }

    /** tool_use 内容块开始：只有 id / name，input 为空对象。 */
    public static String toolUseBlockStart(int index, String id, String name) {
        ObjectNode block = toolUseBlock();
        block.put("id", id);
        block.put("name", name);
        block.putObject("input");
        return blockStart(index, block);
    }

    /** 正文增量。 */
    public static String textDelta(int index, String text) {
        return delta(index, deltaOf("text_delta").put("text", text));
    }

    /** 思维链增量。 */
    public static String thinkingDelta(int index, String thinking) {
        return delta(index, deltaOf("thinking_delta").put("thinking", thinking));
    }

    /** signature 增量：Anthropic 会把它切成多段下发，必须累积。 */
    public static String signatureDelta(int index, String signature) {
        return delta(index, deltaOf("signature_delta").put("signature", signature));
    }

    /** 工具参数增量：任意切分的 JSON 片段。 */
    public static String inputJsonDelta(int index, String partialJson) {
        return delta(index, deltaOf("input_json_delta").put("partial_json", partialJson));
    }

    /** 内容块结束：tool_use 块到此参数才拼完，解码器在这一刻回调 onToolCall。 */
    public static String blockStop(int index) {
        ObjectNode root = WireJson.obj();
        root.put("type", "content_block_stop");
        root.put("index", index);
        return event("content_block_stop", root.toString());
    }

    /**
     * {@code message_delta}：携带最终的 {@code stop_reason} 与<b>累计</b>的 output_tokens。
     *
     * <p>注意这里的 output_tokens 是"截至当前的累计值"而不是增量——解码器负责把它换算成增量，
     * 本方法刻意按真实协议给累计值，用来验证那次换算确实发生了。</p>
     */
    public static String messageDelta(String stopReason, int cumulativeOutputTokens) {
        ObjectNode root = WireJson.obj();
        root.put("type", "message_delta");
        ObjectNode delta = root.putObject("delta");
        delta.put("stop_reason", stopReason);
        delta.putNull("stop_sequence");
        root.putObject("usage").put("output_tokens", cumulativeOutputTokens);
        return event("message_delta", root.toString());
    }

    /** 流终止事件。 */
    public static String messageStop() {
        ObjectNode root = WireJson.obj();
        root.put("type", "message_stop");
        return event("message_stop", root.toString());
    }

    /** 应用层错误事件（HTTP 仍是 200，错误在流里）。 */
    public static String errorEvent(String type, String message) {
        ObjectNode root = WireJson.obj();
        root.put("type", "error");
        ObjectNode error = root.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return event("error", root.toString());
    }

    /** 心跳事件：解析器与解码器都必须忽略它。 */
    public static String ping() {
        ObjectNode root = WireJson.obj();
        root.put("type", "ping");
        return event("ping", root.toString());
    }

    /** 一条完整的纯文本流。 */
    public static List<String> streamingText(String model, int inputTokens, int finalOutputTokens,
                                             String... deltas) {
        List<String> frames = new ArrayList<>();
        frames.add(messageStart(model, inputTokens, 1, 0));
        frames.add(textBlockStart(0));
        for (String delta : deltas) {
            frames.add(textDelta(0, delta));
        }
        frames.add(blockStop(0));
        frames.add(messageDelta("end_turn", finalOutputTokens));
        frames.add(messageStop());
        return frames;
    }

    /** 一条工具调用流：参数按 {@code input_json_delta} 分片下发。 */
    public static List<String> streamingToolUse(String model, int inputTokens, int finalOutputTokens,
                                                String blockId, String toolName, List<String> partialJson) {
        List<String> frames = new ArrayList<>();
        frames.add(messageStart(model, inputTokens, 1, 0));
        frames.add(toolUseBlockStart(1, blockId, toolName));
        for (String fragment : partialJson) {
            frames.add(inputJsonDelta(1, fragment));
        }
        frames.add(blockStop(1));
        frames.add(messageDelta("tool_use", finalOutputTokens));
        frames.add(messageStop());
        return frames;
    }

    /** 把一段 JSON 文本切成 n 份，模拟 {@code partial_json} 的任意切分。 */
    public static List<String> splitInto(String text, int pieces) {
        List<String> parts = new ArrayList<>();
        int size = Math.max(1, (int) Math.ceil((double) text.length() / pieces));
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    // ------------------------------------------------------------------ 内部

    private static ObjectNode envelope(String model, String stopReason,
                                       int inputTokens, int outputTokens, int cacheReadInputTokens) {
        ObjectNode root = WireJson.obj();
        root.put("id", MESSAGE_ID);
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        root.putArray("content");
        root.put("stop_reason", stopReason);
        root.putNull("stop_sequence");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("cache_read_input_tokens", cacheReadInputTokens);
        usage.put("cache_creation_input_tokens", 0);
        return root;
    }

    private static ObjectNode textBlock() {
        ObjectNode block = WireJson.obj();
        block.put("type", "text");
        block.put("text", "");
        return block;
    }

    private static ObjectNode thinkingBlock() {
        ObjectNode block = WireJson.obj();
        block.put("type", "thinking");
        block.put("thinking", "");
        return block;
    }

    private static ObjectNode toolUseBlock() {
        ObjectNode block = WireJson.obj();
        block.put("type", "tool_use");
        return block;
    }

    private static String blockStart(int index, ObjectNode block) {
        ObjectNode root = WireJson.obj();
        root.put("type", "content_block_start");
        root.put("index", index);
        root.set("content_block", block);
        return event("content_block_start", root.toString());
    }

    private static ObjectNode deltaOf(String type) {
        ObjectNode delta = WireJson.obj();
        delta.put("type", type);
        return delta;
    }

    private static String delta(int index, ObjectNode delta) {
        ObjectNode root = WireJson.obj();
        root.put("type", "content_block_delta");
        root.put("index", index);
        root.set("delta", delta);
        return event("content_block_delta", root.toString());
    }
}

