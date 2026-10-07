package com.benxin.exp.models.custom;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个<b>完全自造</b>的协议编解码器 —— "加一种协议"这条路的完整示范。
 *
 * <p>报文刻意长得与四家都不同，这样"协议名一换，线上跑的报文就整套换掉"这件事才看得出来：</p>
 *
 * <table border="1">
 *   <caption>demo 协议 vs 四种内置协议</caption>
 *   <tr><th></th><th>demo 协议</th></tr>
 *   <tr><td>端点</td><td>{@code POST {base}/demo/v2/invoke}</td></tr>
 *   <tr><td>认证</td><td>{@code X-Demo-Key: <key>} + {@code X-Demo-Api: 2026-01-01}</td></tr>
 *   <tr><td>系统提示</td><td>{@code directive.system}（嵌套一层）</td></tr>
 *   <tr><td>对话载体</td><td>{@code turns[].who / turns[].says}</td></tr>
 *   <tr><td>工具声明</td><td>{@code skills[].signature}</td></tr>
 *   <tr><td>工具结果</td><td>{@code turns[]} 里 {@code who:"skill"} 的 {@code says} 文本</td></tr>
 *   <tr><td>输出上限</td><td>{@code directive.maxTokens}</td></tr>
 *   <tr><td>结束原因</td><td>{@code outcome} = {@code done} / {@code truncated} / {@code needs_skill}</td></tr>
 *   <tr><td>流式</td><td>{@code data:} 帧，收到 {@code {"event":"done"}} 收尾</td></tr>
 * </table>
 *
 * <p>实现要点只有一条：<b>把协议差异全部关在这个类里</b>。它对上层暴露的仍然是
 * 统一的 {@link ChatRequest} / {@link ChatResponse}，因此 {@code protocol: demo}
 * 一写，Agent、Loop、工具、监听器一行代码都不用改 —— 这正是"协议与厂商正交"的兑现方式。</p>
 */
public class DemoProtocolCodec implements ProtocolCodec {

    /** 协议名可以随便取；取不到对应 Codec 时启动期就会失败并列出已注册协议。 */
    public static final String PROTOCOL_ID = "demo";

    public static final Protocol PROTOCOL = Protocol.of(PROTOCOL_ID);

    /** 工具调用 id 的前缀：demo 协议里 id 是可选字段，缺省由本心合成。 */
    private static final String CALL_ID_PREFIX = "skill_";

    @Override
    public Protocol protocol() {
        return PROTOCOL;
    }

    /**
     * 能力声明：demo 协议支持流式与工具调用、不支持并行工具、不支持视觉、支持思考。
     *
     * <p>这些值不是装饰品 —— {@code LoopContext.callModel} 会读 {@code streaming()}
     * 决定走 {@code stream()} 还是 {@code chat()}，{@code react} Loop 会读
     * {@code toolCalling()} 决定是否降级到文本协议。</p>
     */
    public static final ModelCapabilities CAPABILITIES = ModelCapabilities.builder()
            .streaming(true)
            .toolCalling(true)
            .parallelToolCalls(false)
            .vision(false)
            .thinking(true)
            .maxContextTokens(64_000)
            .build();

    @Override
    public ModelCapabilities capabilities() {
        return CAPABILITIES;
    }

    // ------------------------------------------------------------------ 端点与认证

    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        String base = config == null ? null : config.baseUrl();
        if (base == null || base.isBlank()) {
            throw new ModelException("demo 协议必须显式配置 base-url（本示例里指向本地桩服务）");
        }
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 已经写好完整路径时不再追加，避免 /demo/v2/invoke/demo/v2/invoke
        return url.endsWith("/demo/v2/invoke") ? url : url + "/demo/v2/invoke";
    }

    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("X-Demo-Api", "2026-01-01");
        if (config != null && config.hasApiKey()) {
            headers.put("X-Demo-Key", config.apiKey());
        }
        // 用户自定义头放最后：可以覆盖上面的默认值，与内置协议的口径一致
        if (config != null) {
            headers.putAll(config.headers());
        }
        return headers;
    }

    // ------------------------------------------------------------------ 编码

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        ObjectNode root = Json.object();
        root.put("model", config == null ? request.model() : config.resolveModel(request));

        ObjectNode directive = root.putObject("directive");
        String system = request.systemText();
        if (system != null && !system.isBlank()) {
            directive.put("system", system);
        }
        if (request.temperature() != null) {
            directive.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            directive.put("maxTokens", request.maxTokens());
        }

        ArrayNode turns = root.putArray("turns");
        for (ChatMessage message : request.messages()) {
            appendTurns(turns, message);
        }

        if (request.hasTools()) {
            ArrayNode skills = root.putArray("skills");
            for (ToolSpec spec : request.tools()) {
                ObjectNode skill = skills.addObject();
                // 工具声明长什么样完全由协议决定：这里用一个"名字, 描述, schema"的三元组
                ObjectNode signature = skill.putObject("signature");
                signature.put("name", spec.name());
                signature.put("description", spec.description() == null ? "" : spec.description());
                signature.set("schema", spec.inputSchema() == null ? Json.object() : spec.inputSchema());
            }
        }
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            root.put("pick", request.toolChoice());
        }
        if (request.stream()) {
            root.put("live", true);
        }
        // extra 覆盖式写入顶层，与内置协议保持同一套"用户说了算"的口径
        request.extra().forEach((key, value) -> {
            if (key != null && value != null) {
                root.set(key, Json.valueToTree(value));
            }
        });
        return Json.write(root);
    }

    /** 把统一消息翻译成 demo 协议的 {@code turns}；工具结果必须与请求它的助手回合相邻。 */
    private void appendTurns(ArrayNode turns, ChatMessage message) {
        switch (message.role()) {
            case SYSTEM -> {
                // 已提到 directive.system，此处不再重复
            }
            case USER -> turns.addObject().put("who", "user").put("says", textOf(message));
            case ASSISTANT -> {
                String text = textOf(message);
                if (!text.isEmpty()) {
                    ObjectNode turn = turns.addObject();
                    turn.put("who", "assistant");
                    turn.put("says", text);
                    for (ContentPart part : message.parts()) {
                        if (part instanceof ThinkingPart thinking) {
                            turn.put("muse", thinking.text());
                        }
                    }
                }
                for (ToolUsePart use : message.toolUses()) {
                    ObjectNode turn = turns.addObject();
                    turn.put("who", "assistant");
                    turn.put("skill", use.name());
                    turn.put("callId", use.id());
                    // demo 协议里参数是"JSON 文本"，与 OpenAI 一致（而不是 Anthropic 的对象）
                    turn.put("args", use.argumentsJson());
                }
            }
            case TOOL -> {
                for (ContentPart part : message.parts()) {
                    if (part instanceof ToolResultPart result) {
                        ObjectNode turn = turns.addObject();
                        // 关键取舍：失败结果用结构化的 "ok": false 表达，
                        // 而不是像 OpenAI 那样加 "[error] " 前缀
                        turn.put("who", "skill");
                        turn.put("callId", result.toolUseId());
                        turn.put("skill", result.name() == null ? "" : result.name());
                        turn.put("says", result.content() == null ? "" : result.content());
                        turn.put("ok", !result.error());
                    }
                }
            }
            default -> {
                // 没有其它角色
            }
        }
    }

    // ------------------------------------------------------------------ 解码

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        JsonNode root = Json.parseQuietly(responseBody);
        if (root == null || !root.isObject()) {
            throw new ModelException("demo 协议响应不是合法 JSON 对象: " + Json.abbreviate(responseBody));
        }
        JsonNode error = root.get("problem");
        if (error != null && !error.isNull()) {
            throw new ModelException("demo 协议返回错误: " + error.path("message").asText(Json.write(error))
                    + " | body=" + Json.abbreviate(responseBody));
        }
        ArrayNode out = root.path("out").isArray() ? (ArrayNode) root.get("out") : Json.array();
        // 用量字段名由协议自己定：demo 协议用 spend.in / spend.out
        JsonNode spend = root.path("spend");
        Usage usage = new Usage(spend.path("in").asInt(0), spend.path("out").asInt(0));
        return decodeOut(out, root.path("outcome").asText(null),
                root.path("id").asText(null), root.path("model").asText(null), usage);
    }

    /** 非流式与流式收尾共用同一个"out 数组 → 统一响应"的翻译。 */
    static ChatResponse decodeOut(ArrayNode out, String outcome, String id, String model, Usage usage) {
        List<ContentPart> parts = new ArrayList<>();
        List<ToolUsePart> tools = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder muse = new StringBuilder();

        for (JsonNode item : out) {
            String type = item.path("type").asText("");
            switch (type) {
                case "text" -> text.append(item.path("value").asText(""));
                case "muse" -> muse.append(item.path("value").asText(""));
                case "skill" -> tools.add(new ToolUsePart(
                        // id 缺失时合成一个稳定值：没有 id 的工具结果回灌时无法对位
                        orDefault(item.path("callId").asText(null), CALL_ID_PREFIX + tools.size()),
                        item.path("skill").asText(""),
                        orDefault(item.path("args").asText(null), "{}")));
                default -> {
                    // 未知类型忽略：与内置协议"单个坏帧不打断整条流"的口径一致
                }
            }
        }
        if (!muse.isEmpty()) {
            parts.add(new ThinkingPart(muse.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(tools);

        return ChatResponse.builder()
                .id(id)
                .model(model)
                .message(ChatMessage.assistant(parts))
                .finishReason(finishReasonOf(outcome, !tools.isEmpty()))
                .usage(usage)
                .build();
    }

    /** {@code outcome} → 统一结束原因；三个额外取值都是本协议的私有约定。 */
    static FinishReason finishReasonOf(String outcome, boolean hasTools) {
        if (outcome == null || outcome.isBlank()) {
            return hasTools ? FinishReason.TOOL_CALLS : FinishReason.UNKNOWN;
        }
        return switch (outcome) {
            case "done" -> hasTools ? FinishReason.TOOL_CALLS : FinishReason.STOP;
            case "truncated" -> FinishReason.LENGTH;
            case "needs_skill" -> FinishReason.TOOL_CALLS;
            case "blocked" -> FinishReason.CONTENT_FILTER;
            case "broken" -> FinishReason.ERROR;
            default -> FinishReason.UNKNOWN;
        };
    }

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        return new DemoStreamDecoder(handler);
    }

    // ------------------------------------------------------------------ 小工具

    /** 只取正文，思维链与工具块不进 says（与内置协议的 textOnly 口径一致）。 */
    private static String textOf(ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart text) {
                sb.append(text.text());
            } else if (part instanceof ImagePart image) {
                // 本协议声明 vision=false：图片降级成一句文字说明，让模型至少知道有这么张图
                sb.append("[图片:").append(image.isInline() ? image.mediaType() : image.url()).append(']');
            }
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
