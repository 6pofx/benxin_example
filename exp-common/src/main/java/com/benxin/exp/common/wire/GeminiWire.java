/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test：
 *   benxin_test/test-01-protocol-transport/src/main/java/com/benxin/test/protocol/wire/GeminiWire.java
 * 改编内容仅限包名与注释措辞，报文字段一个都没动 —— 这些字段名本身就是"协议契约"的可执行记录，
 * 示例工程照抄它们，才谈得上"对着真实报文演示协议"。
 */
package com.benxin.exp.common.wire;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Gemini（{@code generateContent}）的报文构造工具。
 *
 * <p>对照 {@code GeminiCodec#decode} 与 {@code GeminiStreamDecoder}：
 * 响应是 {@code candidates[0].content.parts[]}（{@code text} / {@code thought:true} /
 * {@code functionCall:{name,args}}），结束原因叫 {@code finishReason}，
 * 用量叫 {@code usageMetadata.promptTokenCount / candidatesTokenCount /
 * cachedContentTokenCount / thoughtsTokenCount}；
 * 流式侧每帧结构与整包响应完全一致，且 {@code functionCall} 一次性给全。</p>
 *
 * <p>全部用显式的 {@code putObject} / {@code putArray} 构造，不依赖 Jackson 的
 * {@code with*} 系列（那组方法在 Jackson 2.19 起被标注为废弃，语义也不如显式构造清楚）。</p>
 */
public final class GeminiWire {

    /** 桩模型版本。 */
    public static final String MODEL_VERSION = "gemini-stub-001";

    /** 固定响应 id。 */
    public static final String RESPONSE_ID = "resp-t01";

    private GeminiWire() {
    }

    // ------------------------------------------------------------------ 非流式响应

    /** 纯文本响应。 */
    public static String textResponse(String text, int promptTokens, int candidatesTokens) {
        return textResponse(text, promptTokens, candidatesTokens, 0, 0, "STOP");
    }

    /** 完整文本响应：可指定缓存命中量与思考 token 数。 */
    public static String textResponse(String text, int promptTokens, int candidatesTokens,
                                      int cachedTokens, int thoughtsTokens, String finishReason) {
        ObjectNode root = envelope(finishReason);
        partsOf(root).addObject().put("text", text);
        root.set("usageMetadata", usage(promptTokens, candidatesTokens, cachedTokens, thoughtsTokens));
        return WireJson.write(root);
    }

    /** 带 {@code thought:true} 分片的响应：思维链与正文共存。 */
    public static String thoughtResponse(String thought, String text, int promptTokens, int candidatesTokens) {
        ObjectNode root = envelope("STOP");
        ArrayNode parts = partsOf(root);
        ObjectNode thoughtPart = parts.addObject();
        thoughtPart.put("text", thought);
        thoughtPart.put("thought", true);
        parts.addObject().put("text", text);
        root.set("usageMetadata", usage(promptTokens, candidatesTokens, 0, 12));
        return WireJson.write(root);
    }

    /** 单个 {@code functionCall} 响应。 */
    public static String functionCallResponse(String name, String argsJson,
                                              int promptTokens, int candidatesTokens) {
        return functionCallResponse(new String[][]{{name, argsJson}}, promptTokens, candidatesTokens);
    }

    /**
     * 多个 {@code functionCall} 的响应。
     *
     * <p>Gemini 的 {@code functionCall} <b>没有 id 字段</b>，因此 id 由本心按出现顺序合成
     * {@code call_<n>}；这里故意一次给两个，用来验证合成规则的稳定性与顺序。</p>
     */
    public static String functionCallResponse(String[][] calls, int promptTokens, int candidatesTokens) {
        ObjectNode root = envelope("STOP");
        ArrayNode parts = partsOf(root);
        for (String[] call : calls) {
            ObjectNode functionCall = parts.addObject().putObject("functionCall");
            functionCall.put("name", call[0]);
            functionCall.set("args", WireJson.parse(call[1]));
        }
        root.set("usageMetadata", usage(promptTokens, candidatesTokens, 0, 0));
        return WireJson.write(root);
    }

    /** 安全拦截：没有 candidates，只有 {@code promptFeedback.blockReason}。 */
    public static String safetyBlockResponse(String blockReason) {
        ObjectNode root = WireJson.obj();
        root.put("responseId", RESPONSE_ID);
        ObjectNode feedback = root.putObject("promptFeedback");
        feedback.put("blockReason", blockReason);
        ObjectNode rating = feedback.putArray("safetyRatings").addObject();
        rating.put("category", "HARM_CATEGORY_DANGEROUS_CONTENT");
        rating.put("probability", "HIGH");
        root.putArray("candidates");
        return WireJson.write(root);
    }

    /** Gemini 的错误信封：部分网关即使 HTTP 200 也会返回它。 */
    public static String apiErrorResponse(int code, String status, String message) {
        ObjectNode root = WireJson.obj();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("status", status);
        error.put("message", message);
        return WireJson.write(root);
    }

    // ------------------------------------------------------------------ 流式帧

    /** 一帧 {@code alt=sse} 流（结构与整包响应一致）。 */
    public static String dataFrame(String payloadJson) {
        return "data: " + payloadJson + "\n\n";
    }

    /** 正文增量帧。 */
    public static String textChunk(String text) {
        ObjectNode root = streamEnvelope();
        partsOf(root).addObject().put("text", text);
        return dataFrame(root.toString());
    }

    /**
     * 带 {@code usageMetadata} 的正文增量帧。
     *
     * <p>真实的 {@code streamGenerateContent} 会在<b>每个</b>分片里带上累计的 usageMetadata，
     * 因此需要一个能构造这种形态的入口——它是"用量被重复累加"这类问题的复现前提。</p>
     */
    public static String textChunk(String text, int promptTokens, int candidatesTokens) {
        ObjectNode root = streamEnvelope();
        partsOf(root).addObject().put("text", text);
        root.set("usageMetadata", usage(promptTokens, candidatesTokens, 0, 0));
        return dataFrame(root.toString());
    }

    /** {@code thought:true} 的思维链增量帧。 */
    public static String thoughtChunk(String thought) {
        ObjectNode root = streamEnvelope();
        ObjectNode part = partsOf(root).addObject();
        part.put("text", thought);
        part.put("thought", true);
        return dataFrame(root.toString());
    }

    /**
     * {@code functionCall} 帧：Gemini 流式下一次给全，不像 OpenAI 需要按 index 拼 arguments。
     */
    public static String functionCallChunk(String name, String argsJson) {
        ObjectNode root = streamEnvelope();
        ObjectNode functionCall = partsOf(root).addObject().putObject("functionCall");
        functionCall.put("name", name);
        functionCall.set("args", WireJson.parse(argsJson));
        return dataFrame(root.toString());
    }

    /** 收尾帧：带 {@code finishReason} 与 {@code usageMetadata}。 */
    public static String finishChunk(String finishReason, int promptTokens, int candidatesTokens) {
        ObjectNode root = streamEnvelope();
        ObjectNode candidate = root.putArray("candidates").addObject();
        candidate.putObject("content").put("role", "model").putArray("parts");
        candidate.put("finishReason", finishReason);
        root.set("usageMetadata", usage(promptTokens, candidatesTokens, 0, 0));
        return dataFrame(root.toString());
    }

    /** 一条完整的文本流（Gemini 没有 {@code [DONE]}，靠连接关闭表示结束）。 */
    public static List<String> streamingText(int promptTokens, int candidatesTokens, String... deltas) {
        List<String> frames = new ArrayList<>();
        for (String delta : deltas) {
            frames.add(textChunk(delta));
        }
        frames.add(finishChunk("STOP", promptTokens, candidatesTokens));
        return frames;
    }

    /** 工具调用流：一次给全。 */
    public static List<String> streamingFunctionCall(String name, String argsJson,
                                                     int promptTokens, int candidatesTokens) {
        List<String> frames = new ArrayList<>();
        frames.add(functionCallChunk(name, argsJson));
        frames.add(finishChunk("STOP", promptTokens, candidatesTokens));
        return frames;
    }

    // ------------------------------------------------------------------ 内部

    /** 整包响应的骨架：一个 candidate + content.parts（空数组，交给调用方填充）。 */
    private static ObjectNode envelope(String finishReason) {
        ObjectNode root = WireJson.obj();
        root.put("responseId", RESPONSE_ID);
        root.put("modelVersion", MODEL_VERSION);
        ObjectNode candidate = root.putArray("candidates").addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts");
        candidate.put("finishReason", finishReason);
        candidate.put("index", 0);
        ObjectNode rating = candidate.putArray("safetyRatings").addObject();
        rating.put("category", "HARM_CATEGORY_HARASSMENT");
        rating.put("probability", "NEGLIGIBLE");
        return root;
    }

    /** 流式帧的骨架：candidates 数组由调用方按需填充（收尾帧只有空 parts）。 */
    private static ObjectNode streamEnvelope() {
        ObjectNode root = WireJson.obj();
        root.put("responseId", RESPONSE_ID);
        root.put("modelVersion", MODEL_VERSION);
        ObjectNode candidate = root.putArray("candidates").addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts");
        return root;
    }

    /** 取 {@code candidates[0].content.parts}，用于往骨架里塞内容块。 */
    private static ArrayNode partsOf(ObjectNode root) {
        return (ArrayNode) root.get("candidates").get(0).get("content").get("parts");
    }

    private static ObjectNode usage(int promptTokens, int candidatesTokens, int cachedTokens, int thoughtsTokens) {
        ObjectNode usage = WireJson.obj();
        usage.put("promptTokenCount", promptTokens);
        usage.put("candidatesTokenCount", candidatesTokens);
        usage.put("totalTokenCount", promptTokens + candidatesTokens);
        usage.put("cachedContentTokenCount", cachedTokens);
        usage.put("thoughtsTokenCount", thoughtsTokens);
        return usage;
    }
}

