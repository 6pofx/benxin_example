/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test：
 *   benxin_test/test-01-protocol-transport/src/main/java/com/benxin/test/protocol/wire/ResponsesWire.java
 * 改编内容仅限包名与注释措辞，报文字段一个都没动 —— 这些字段名本身就是"协议契约"的可执行记录，
 * 示例工程照抄它们，才谈得上"对着真实报文演示协议"。
 */
package com.benxin.exp.common.wire;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI <b>Responses API</b> 的报文构造工具。
 *
 * <p>这是本心内置的第四个协议，与 Chat Completions 的差异是"整包换形"级别的，
 * 对照 {@code ResponsesApiCodec} / {@code ResponsesStreamDecoder}：</p>
 *
 * <table border="1">
 *   <caption>与 Chat Completions 的对照</caption>
 *   <tr><th></th><th>Chat Completions</th><th>Responses</th></tr>
 *   <tr><td>端点</td><td>{@code /v1/chat/completions}</td><td>{@code /v1/responses}</td></tr>
 *   <tr><td>系统提示</td><td>{@code messages} 里的 {@code system} 角色</td>
 *       <td><b>顶层 {@code instructions}</b></td></tr>
 *   <tr><td>对话载体</td><td>{@code messages[]}</td><td><b>{@code input[]} item 列表</b></td></tr>
 *   <tr><td>工具调用</td><td>{@code choices[].message.tool_calls[]}</td>
 *       <td><b>{@code output[]} 里独立的 {@code function_call} item</b></td></tr>
 *   <tr><td>工具结果</td><td>{@code role:tool} 消息</td>
 *       <td><b>独立的 {@code function_call_output} item</b></td></tr>
 *   <tr><td>流式结束</td><td>{@code data: [DONE]}</td>
 *       <td><b>{@code response.completed} 等命名终态事件，没有 {@code [DONE]}</b></td></tr>
 * </table>
 *
 * <p>流式侧的事件是<b>命名</b>的（{@code event: response.output_text.delta}），
 * 工具参数按 {@code output_index} 分片累积。因此本类除了构造整包响应，
 * 还提供逐帧构造能力 —— 否则"分片拼装"这条最容易被写错的路径就测不到。</p>
 */
public final class ResponsesWire {

    /** 桩模型 id。 */
    public static final String MODEL_ID = "gpt-stub-001";

    /** 固定响应 id。 */
    public static final String RESPONSE_ID = "resp-t01";

    private ResponsesWire() {
    }

    // ------------------------------------------------------------------ 非流式响应

    /** 纯文本响应。 */
    public static String textResponse(String text, int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        ArrayNode output = root.putArray("output");
        messageItem(output, text);
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /** 带 reasoning item 的响应：思考与正文共存，用于验证 {@code ChatMessage.thinking()}。 */
    public static String reasoningResponse(String reasoning, String text, int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        ArrayNode output = root.putArray("output");
        ObjectNode reasoningItem = output.addObject();
        reasoningItem.put("type", "reasoning");
        reasoningItem.put("id", "rs_1");
        ArrayNode summary = reasoningItem.putArray("summary");
        summary.addObject().put("type", "summary_text").put("text", reasoning);
        messageItem(output, text);
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /**
     * 单个 {@code function_call} 响应。
     *
     * <p>注意字段名：是 {@code call_id}（而不是 Chat Completions 的 {@code id}）——
     * 它才是把 {@code function_call_output} 对回去的键。</p>
     */
    public static String functionCallResponse(String callId, String name, String argumentsJson,
                                              int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        ArrayNode output = root.putArray("output");
        functionCallItem(output, callId, name, argumentsJson);
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /** 文本与工具调用共存：先正文后 function_call。 */
    public static String textAndFunctionCallResponse(String text, String callId, String name,
                                                     String argumentsJson, int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        ArrayNode output = root.putArray("output");
        messageItem(output, text);
        functionCallItem(output, callId, name, argumentsJson);
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /** 多个 {@code function_call}：验证并行工具调用。 */
    public static String parallelFunctionCalls(String[][] calls, int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        ArrayNode output = root.putArray("output");
        for (String[] call : calls) {
            functionCallItem(output, call[0], call[1], call[2]);
        }
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /** 只给便捷字段 {@code output_text} 的兼容端点响应（部分网关这么发）。 */
    public static String outputTextOnlyResponse(String text, int inputTokens, int outputTokens) {
        ObjectNode root = envelope("completed");
        root.put("output_text", text);
        root.set("usage", usage(inputTokens, outputTokens));
        return WireJson.write(root);
    }

    /** 200 里包一个 error 信封。 */
    public static String errorEnvelope(String message, String type) {
        ObjectNode root = WireJson.obj();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", type);
        return WireJson.write(root);
    }

    // ------------------------------------------------------------------ 流式帧

    /** 一个命名 SSE 帧：{@code event: <type>} + {@code data: <payload>}。 */
    public static String frame(String type, ObjectNode payload) {
        payload.put("type", type);
        return "event: " + type + "\n" + "data: " + WireJson.write(payload) + "\n\n";
    }

    /** {@code response.output_text.delta} 帧。 */
    public static String textDeltaFrame(String delta) {
        ObjectNode payload = WireJson.obj();
        payload.put("delta", delta);
        payload.put("output_index", 0);
        payload.put("content_index", 0);
        return frame("response.output_text.delta", payload);
    }

    /** {@code response.reasoning_text.delta} 帧。 */
    public static String reasoningDeltaFrame(String delta) {
        ObjectNode payload = WireJson.obj();
        payload.put("delta", delta);
        payload.put("output_index", 0);
        payload.put("content_index", 0);
        return frame("response.reasoning_text.delta", payload);
    }

    /** {@code response.created} 开场帧。 */
    public static String createdFrame() {
        ObjectNode payload = WireJson.obj();
        ObjectNode response = payload.putObject("response");
        response.put("id", RESPONSE_ID);
        response.put("model", MODEL_ID);
        return frame("response.created", payload);
    }

    /**
     * {@code response.function_call_arguments.delta} 帧。
     *
     * <p>参数是<b>按 output_index 分片</b>累积的 —— 这正是最该被反复验证的一段：
     * 分片边界可能落在 JSON 的任意位置（引号中间、转义中间），
     * 实现若按帧独立解析就会碎掉。</p>
     *
     * @param outputIndex 该 function_call 在 {@code output[]} 里的下标
     */
    public static String functionArgsDeltaFrame(int outputIndex, String delta) {
        ObjectNode payload = WireJson.obj();
        payload.put("output_index", outputIndex);
        payload.put("delta", delta);
        return frame("response.function_call_arguments.delta", payload);
    }

    /** {@code response.output_item.added} 帧：宣告一个 function_call item 开始。 */
    public static String functionCallAddedFrame(int outputIndex, String callId, String name) {
        ObjectNode payload = WireJson.obj();
        payload.put("output_index", outputIndex);
        ObjectNode item = payload.putObject("item");
        item.put("type", "function_call");
        item.put("id", "fc_" + outputIndex);
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", "");
        return frame("response.output_item.added", payload);
    }

    /** {@code response.output_item.done} 帧：给出完整的 function_call item。 */
    public static String functionCallDoneFrame(int outputIndex, String callId, String name, String argumentsJson) {
        ObjectNode payload = WireJson.obj();
        payload.put("output_index", outputIndex);
        ObjectNode item = payload.putObject("item");
        item.put("type", "function_call");
        item.put("id", "fc_" + outputIndex);
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", argumentsJson);
        item.put("status", "completed");
        return frame("response.output_item.done", payload);
    }

    /** {@code response.completed} 终态帧，携带 usage。 */
    public static String completedFrame(int inputTokens, int outputTokens) {
        ObjectNode payload = WireJson.obj();
        ObjectNode response = payload.putObject("response");
        response.put("id", RESPONSE_ID);
        response.put("status", "completed");
        response.set("usage", usage(inputTokens, outputTokens));
        return frame("response.completed", payload);
    }

    /** {@code response.failed} 终态帧。 */
    public static String failedFrame(String message) {
        ObjectNode payload = WireJson.obj();
        ObjectNode response = payload.putObject("response");
        response.put("status", "failed");
        ObjectNode error = response.putObject("error");
        error.put("message", message);
        error.put("type", "server_error");
        return frame("response.failed", payload);
    }

    // ------------------------------------------------------------------ 内部

    /** 顶层信封。响应体没有 {@code object} 字段之外的强约束，这里按官方形状给全。 */
    private static ObjectNode envelope(String status) {
        ObjectNode root = WireJson.obj();
        root.put("id", RESPONSE_ID);
        root.put("object", "response");
        root.put("model", MODEL_ID);
        root.put("status", status);
        return root;
    }

    /** 一个 {@code message} item，正文放在 {@code content[].output_text}。 */
    private static void messageItem(ArrayNode output, String text) {
        ObjectNode item = output.addObject();
        item.put("type", "message");
        item.put("id", "msg_1");
        item.put("role", "assistant");
        item.put("status", "completed");
        ArrayNode content = item.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", "output_text");
        part.put("text", text);
        part.putArray("annotations");
    }

    /** 一个独立的 {@code function_call} item。 */
    private static void functionCallItem(ArrayNode output, String callId, String name, String argumentsJson) {
        ObjectNode item = output.addObject();
        item.put("type", "function_call");
        item.put("id", "fc_" + callId);
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", argumentsJson);
        item.put("status", "completed");
    }

    private static ObjectNode usage(int inputTokens, int outputTokens) {
        ObjectNode usage = WireJson.obj();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("total_tokens", inputTokens + outputTokens);
        return usage;
    }
}

