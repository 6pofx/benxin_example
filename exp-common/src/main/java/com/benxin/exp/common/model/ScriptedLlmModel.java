package com.benxin.exp.common.model;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线脚本化模型：用一个 {@link LlmModel} 实现，把"模型决策"变成可复现的规则。
 *
 * <p>示例工程的所有子项目（除 exp-11）都靠它驱动：<b>不联网、不花一个 token</b>，
 * 却能完整跑通"模型决策 → 工具调用 → 结果回灌 → 收尾"的全链路，
 * 于是 {@code mvn test} 在 CI 与离线环境都能稳定通过。</p>
 *
 * <p><b>规则求值顺序</b>（先命中先返回）——这个顺序本身就是一份"本心怎么跟模型交互"的说明书：</p>
 * <ol>
 *   <li>{@code #tool:<名字> {JSON}} —— 用户在输入里显式指定工具，用来做确定性演示；</li>
 *   <li>使用者注册的关键字规则（{@link Builder#rule}）—— 每个示例自己脚本化的回答；</li>
 *   <li>编排类 Loop 的内建应答：{@code plan-execute} / {@code staged} 的"编号计划"、
 *       {@code staged} 的"验收者"与"验收未通过"、{@code reflexion} 的 {@code SCORE/CRITIQUE}
 *       —— 少了这几条，这些 Loop 会集体降级成普通 tool-calling 循环，示例就演示不到真正的流程；</li>
 *   <li>ReAct 文本协议（{@link Builder#textProtocol}）—— 模拟"模型不支持 function calling"，
 *       验证 {@code react} Loop 的降级路径；</li>
 *   <li>关键字 → 工具（{@link Builder#toolFor}）：还没取过工具结果时按关键词挑一个工具调用；</li>
 *   <li>已有工具结果 → 汇总结果作答；</li>
 *   <li>提示词里要求 JSON（{@link Builder#jsonAnswer}）→ 返回结构化 JSON，用来演示 DTO 返回值；</li>
 *   <li>兜底文本。</li>
 * </ol>
 */
public class ScriptedLlmModel implements LlmModel {

    /** 显式指定工具调用：{@code #tool:get_weather {"city":"北京"}}。 */
    private static final Pattern FORCED_TOOL =
            Pattern.compile("#tool:([A-Za-z0-9_\\-]+)\\s*(\\{.*?})?", Pattern.DOTALL);

    /** Reflexion 的自省请求特征（见 ReflexionLoop 注入的提示词）。 */
    private static final String REFLEXION_HINT = "严格按下面两行输出";

    private static final String DEFAULT_PLAN = """
            1. 明确目标与验收标准
            2. 收集与任务相关的关键信息
            3. 形成结论，并逐条给出依据""";

    private final String name;
    private final ModelCapabilities capabilities;
    private final List<Rule> rules;
    private final Map<String, String> keywordTools;
    private final String fallback;
    private final String jsonAnswer;
    private final String planText;
    private final boolean verifyPass;
    private final double reflexionScore;
    private final String critiqueText;
    private final TextProtocol textProtocol;
    private final boolean thinking;
    private final int chunkSize;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger toolCallRounds = new AtomicInteger();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final List<String> toolCallLog = new ArrayList<>();

    private ScriptedLlmModel(Builder b) {
        this.name = b.name;
        this.capabilities = b.capabilities;
        this.rules = List.copyOf(b.rules);
        this.keywordTools = Map.copyOf(b.keywordTools);
        this.fallback = b.fallback;
        this.jsonAnswer = b.jsonAnswer;
        this.planText = b.planText;
        this.verifyPass = b.verifyPass;
        this.reflexionScore = b.reflexionScore;
        this.critiqueText = b.critiqueText;
        this.textProtocol = b.textProtocol;
        this.thinking = b.thinking;
        this.chunkSize = b.chunkSize;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 一份开箱可用的默认脚本：够跑通工具闭环、编排类 Loop 与 JSON 返回。 */
    public static ScriptedLlmModel of(String name) {
        return builder(name).build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    // ------------------------------------------------------------------
    // 观测：示例与测试都要用到"模型被调了几次、看到了什么"
    // ------------------------------------------------------------------

    public int calls() {
        return calls.get();
    }

    /** 发起过工具调用的轮数（一轮可能有多个工具）。 */
    public int toolCallRounds() {
        return toolCallRounds.get();
    }

    /** 按顺序记录每次工具调用的 {@code 名字(参数)}。 */
    public List<String> toolCallLog() {
        synchronized (toolCallLog) {
            return List.copyOf(toolCallLog);
        }
    }

    public List<ChatRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public ChatRequest lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    /** 重置计数，方便一个应用里跑多组演示而不互相干扰。 */
    public void reset() {
        calls.set(0);
        toolCallRounds.set(0);
        synchronized (requests) {
            requests.clear();
        }
        synchronized (toolCallLog) {
            toolCallLog.clear();
        }
    }

    // ------------------------------------------------------------------
    // 核心：把请求映射成一次"模型决策"
    // ------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        int index = calls.incrementAndGet();
        synchronized (requests) {
            requests.add(request);
        }

        String userText = lastUserText(request);
        String systemText = request.systemText() == null ? "" : request.systemText();
        // "本轮场景" = 最后一条 user 消息 + 系统提示词。
        // 刻意不含历史消息：开了记忆之后，历史里会留着前几轮的输入，
        // 用整段历史做关键字匹配会让"这一轮该回什么"变得不可预测。
        String scene = userText + "\n" + systemText;
        boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == Role.TOOL);

        // ① 用户显式点名工具：#tool:xxx {json}
        Matcher forced = FORCED_TOOL.matcher(userText);
        if (forced.find() && hasTool(request, forced.group(1))) {
            String args = forced.group(2) == null ? "{}" : forced.group(2);
            return toolCall(forced.group(1), args, index);
        }

        // ② 使用者脚本化的关键字规则
        for (Rule rule : rules) {
            if (matches(rule, userText, systemText)) {
                return text(rule.reply(), index);
            }
        }

        // ③ 编排类 Loop 的内建应答
        if (scene.contains("编号计划")) {
            return text(planText, index);
        }
        if (scene.contains("作为验收者") || scene.contains("验收者")) {
            return text(verifyPass
                    ? "结论：通过\n（已逐条对照任务要求检查，未发现遗漏或错误。）"
                    : "结论：不通过\n1. 缺少关键数据的来源说明\n2. 结论没有给出可执行的下一步", index);
        }
        if (scene.contains("验收未通过")) {
            return text("已针对验收提出的问题逐条修正，并补充了来源说明与可执行的下一步。", index);
        }
        if (scene.contains(REFLEXION_HINT)) {
            return text("SCORE: " + formatScore(reflexionScore) + "\nCRITIQUE: " + critiqueText, index);
        }

        // ④ ReAct 文本协议：模拟"模型不支持 function calling"
        if (textProtocol != null) {
            if (scene.contains("Observation:")) {
                return text("Thought: 已经拿到观察结果，可以直接作答了。\n"
                        + "Final Answer: " + textProtocol.finalAnswer(), index);
            }
            if (userText.contains(textProtocol.keyword())) {
                return text("Thought: 这个问题需要调用外部工具才能回答。\n"
                        + "Action: " + textProtocol.tool() + "\n"
                        + "Action Input: " + textProtocol.argumentsJson(), index);
            }
        }

        // ⑤ 需要外部信息、且还没取过 → 按关键词挑一个工具
        if (!hasToolResult && request.hasTools()) {
            ToolSpec picked = pickTool(request.tools(), userText);
            if (picked != null) {
                return toolCall(picked.name(), guessArguments(picked, userText), index);
            }
        }

        // ⑥ 已有工具结果 → 汇总作答（工具闭环的收尾）
        if (hasToolResult) {
            return text(answerFromToolResults(request), index);
        }

        // ⑦ 要求 JSON → 返回结构化内容，用于演示 DTO / 集合 / Optional 返回值
        if (jsonAnswer != null && (containsJsonHint(systemText) || containsJsonHint(userText))) {
            return text(jsonAnswer, index);
        }

        // ⑧ 兜底
        return text(fallback.replace("{input}", abbreviate(userText, 60)), index);
    }

    /**
     * 流式：按固定片长吐出增量，模拟真实模型的打字机效果。
     *
     * <p>顺序刻意与真实厂商一致：{@code onStart} → 思考增量 → 文本增量 → 工具调用 → 用量 → 完成。
     * 示例里的流式演示（含 HTTP SSE 端点）因此能看到"逐字到达"而不是"一次性到达"。</p>
     */
    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        handler.onStart();
        ChatResponse response = chat(request);
        if (thinking && response.message().thinking() != null && !response.message().thinking().isEmpty()) {
            emit(response.message().thinking(), handler::onThinkingDelta);
        }
        String content = response.message().text();
        if (content != null && !content.isEmpty()) {
            emit(content, handler::onTextDelta);
        }
        response.message().toolUses().forEach(handler::onToolCall);
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    private void emit(String content, java.util.function.Consumer<String> sink) {
        for (int i = 0; i < content.length(); i += chunkSize) {
            sink.accept(content.substring(i, Math.min(content.length(), i + chunkSize)));
        }
    }

    // ------------------------------------------------------------------
    // 报文构造
    // ------------------------------------------------------------------

    private ChatResponse toolCall(String toolName, String argumentsJson, int index) {
        toolCallRounds.incrementAndGet();
        synchronized (toolCallLog) {
            toolCallLog.add(toolName + "(" + argumentsJson + ")");
        }
        return ChatResponse.builder()
                .id(name + "-" + index)
                .model(name)
                .message(ChatMessage.assistant("", List.of(
                        new ToolUsePart("call_" + Math.abs(toolName.hashCode()) + "_" + index,
                                toolName, argumentsJson))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(usage(estimateTokens(allTextOf(lastRequest())), 32))
                .build();
    }

    private ChatResponse text(String content, int index) {
        return ChatResponse.builder()
                .id(name + "-" + index)
                .model(name)
                .message(thinking
                        ? ChatMessage.assistant(List.of(
                                new com.benxin.llm.core.message.ThinkingPart("（离线模型的思考占位）"),
                                new com.benxin.llm.core.message.TextPart(content)))
                        : ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(usage(estimateTokens(allTextOf(lastRequest())), estimateTokens(content)))
                .build();
    }

    /** 估一个像样的用量，方便示例展示 token 统计（不是真实 tokenizer，仅供演示）。 */
    private static Usage usage(int input, int output) {
        return new Usage(input, output);
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 3);
    }

    // ------------------------------------------------------------------
    // 请求解析
    // ------------------------------------------------------------------

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return nullToEmpty(messages.get(i).text());
            }
        }
        return "";
    }

    /** 供示例与测试查看"模型这一轮到底看到了什么"。 */
    public static String fullTextOf(ChatRequest request) {
        return allTextOf(request);
    }

    private static String allTextOf(ChatRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(nullToEmpty(request.systemText()));
        request.messages().forEach(m -> sb.append(m.allText()));
        return sb.toString();
    }

    private static boolean hasTool(ChatRequest request, String toolName) {
        return request.tools().stream().anyMatch(t -> t.name().equals(toolName));
    }

    private static boolean matches(Rule rule, String userText, String systemText) {
        if (rule.systemOnly()) {
            return systemText.contains(rule.contains());
        }
        return userText.contains(rule.contains()) || systemText.contains(rule.contains());
    }

    /** 关键词 → 工具名片段；命中第一个就选它。使用者注册的提示优先于内建提示。 */
    private ToolSpec pickTool(List<ToolSpec> tools, String userText) {
        ToolSpec custom = matchByHints(keywordTools, tools, userText);
        return custom != null ? custom : matchByHints(DEFAULT_TOOL_HINTS, tools, userText);
    }

    private static ToolSpec matchByHints(Map<String, String> hints, List<ToolSpec> tools, String userText) {
        for (Map.Entry<String, String> entry : hints.entrySet()) {
            if (userText.contains(entry.getKey())) {
                for (ToolSpec tool : tools) {
                    if (tool.name().toLowerCase().contains(entry.getValue())) {
                        return tool;
                    }
                }
            }
        }
        return null;
    }

    private static final Map<String, String> DEFAULT_TOOL_HINTS = new LinkedHashMap<>();

    static {
        DEFAULT_TOOL_HINTS.put("天气", "weather");
        DEFAULT_TOOL_HINTS.put("气温", "weather");
        DEFAULT_TOOL_HINTS.put("几点", "time");
        DEFAULT_TOOL_HINTS.put("时间", "time");
        DEFAULT_TOOL_HINTS.put("现在", "time");
        DEFAULT_TOOL_HINTS.put("算", "calc");
        DEFAULT_TOOL_HINTS.put("计算", "calc");
        DEFAULT_TOOL_HINTS.put("搜索", "search");
    }

    /** 按工具的 JSON Schema 造一份最小可用参数，让演示无需人工干预即可跑通。 */
    private static String guessArguments(ToolSpec tool, String userText) {
        JsonNode schema = tool.inputSchema();
        var node = Json.object();
        if (schema != null && schema.has("properties")) {
            schema.get("properties").properties().forEach(entry -> {
                String field = entry.getKey();
                String type = entry.getValue().path("type").asText("string");
                switch (type) {
                    case "integer", "number" -> node.put(field, 2);
                    case "boolean" -> node.put(field, true);
                    case "array" -> node.putArray(field).add("离线示例");
                    case "object" -> node.putObject(field);
                    default -> node.put(field, defaultString(field, userText));
                }
            });
        }
        return node.toString();
    }

    private static String defaultString(String field, String userText) {
        String normalized = field.toLowerCase().replace("_", "");
        if (normalized.contains("city") || field.contains("城市")) {
            Matcher city = Pattern.compile("([\\u4e00-\\u9fa5]{2,8}?)(?:的)?(?:天气|气温)").matcher(userText);
            return city.find() ? city.group(1) : "北京";
        }
        String known = FIELD_DEFAULTS.get(normalized);
        if (known != null) {
            return known;
        }
        return userText.isBlank() ? "示例" : abbreviate(userText, 20);
    }

    private static final Map<String, String> FIELD_DEFAULTS = Map.ofEntries(
            Map.entry("unit", "celsius"),
            Map.entry("language", "中文"),
            Map.entry("lang", "中文"),
            Map.entry("targetlanguage", "英文"),
            Map.entry("style", "简洁"),
            Map.entry("audience", "开发者"),
            Map.entry("format", "text"),
            Map.entry("subagenttype", "task"),
            Map.entry("name", "示例"),
            Map.entry("keyword", "示例"));

    private static String answerFromToolResults(ChatRequest request) {
        StringBuilder sb = new StringBuilder("根据工具返回的结果：\n");
        request.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .forEach(m -> sb.append("- ").append(m.text()).append('\n'));
        sb.append("\n综合以上信息给出结论。（本回答由离线脚本化模型生成，"
                + "exp-11 里换成真实 DeepSeek 即可验证同一段代码。）");
        return sb.toString();
    }

    private static boolean containsJsonHint(String text) {
        return text != null && (text.contains("JSON") || text.contains("json"));
    }

    private static String formatScore(double score) {
        String text = String.valueOf(score);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 一条"命中关键字就回这句话"的规则。 */
    public record Rule(String contains, String reply, boolean systemOnly) {
    }

    /** ReAct 文本协议的脚本：模拟一个不会 function calling、只会写 Action 行的模型。 */
    public record TextProtocol(String keyword, String tool, String argumentsJson, String finalAnswer) {
    }

    public static final class Builder {

        private final String name;
        private final List<Rule> rules = new ArrayList<>();
        private final Map<String, String> keywordTools = new LinkedHashMap<>();
        private ModelCapabilities capabilities = ModelCapabilities.builder()
                .streaming(true)
                .toolCalling(true)
                .parallelToolCalls(true)
                .vision(false)
                .thinking(false)
                .maxContextTokens(64_000)
                .build();
        private String fallback = "（离线脚本化模型）我收到了你的请求：{input}。"
                + "想让我调用工具，可以写成 #tool:<工具名> {\"参数\":\"值\"}。";
        private String jsonAnswer;
        private String planText = DEFAULT_PLAN;
        private boolean verifyPass = true;
        private double reflexionScore = 0.92;
        private String critiqueText = "结论正确，但可以补充数据来源与适用范围。";
        private TextProtocol textProtocol;
        private boolean thinking;
        private int chunkSize = 12;

        private Builder(String name) {
            this.name = name;
        }

        /** 命中即可回复的脚本规则。 */
        public Builder rule(String contains, String reply) {
            rules.add(new Rule(contains, reply, false));
            return this;
        }

        /** 只在系统提示词里命中的脚本规则（用来区分"用户问的"与"Loop 注入的"）。 */
        public Builder systemRule(String contains, String reply) {
            rules.add(new Rule(contains, reply, true));
            return this;
        }

        /** 关键字 → 工具名片段（大小写不敏感的子串匹配）。 */
        public Builder toolFor(String keyword, String toolNameFragment) {
            keywordTools.put(keyword, toolNameFragment);
            return this;
        }

        public Builder fallback(String fallback) {
            this.fallback = fallback;
            return this;
        }

        public Builder jsonAnswer(String jsonAnswer) {
            this.jsonAnswer = jsonAnswer;
            return this;
        }

        public Builder plan(String planText) {
            this.planText = planText;
            return this;
        }

        /** {@code staged} / 工作流验收闸门的结论：通过 or 不通过。 */
        public Builder verifyPass(boolean verifyPass) {
            this.verifyPass = verifyPass;
            return this;
        }

        public Builder reflexion(double score, String critique) {
            this.reflexionScore = score;
            this.critiqueText = critique;
            return this;
        }

        public Builder textProtocol(String keyword, String tool, String argumentsJson, String finalAnswer) {
            this.textProtocol = new TextProtocol(keyword, tool, argumentsJson, finalAnswer);
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.capabilities = withStreaming(capabilities, streaming);
            return this;
        }

        public Builder toolCalling(boolean toolCalling) {
            this.capabilities = withToolCalling(capabilities, toolCalling);
            return this;
        }

        public Builder parallelToolCalls(boolean parallel) {
            this.capabilities = withParallel(capabilities, parallel);
            return this;
        }

        public Builder thinking(boolean thinking) {
            this.thinking = thinking;
            this.capabilities = withThinking(capabilities, thinking);
            return this;
        }

        public Builder maxContextTokens(int tokens) {
            this.capabilities = ModelCapabilities.builder()
                    .streaming(capabilities.streaming())
                    .toolCalling(capabilities.toolCalling())
                    .parallelToolCalls(capabilities.parallelToolCalls())
                    .vision(capabilities.vision())
                    .thinking(capabilities.thinking())
                    .maxContextTokens(tokens)
                    .build();
            return this;
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public ScriptedLlmModel build() {
            return new ScriptedLlmModel(this);
        }

        private static ModelCapabilities withStreaming(ModelCapabilities c, boolean value) {
            return ModelCapabilities.builder()
                    .streaming(value).toolCalling(c.toolCalling())
                    .parallelToolCalls(c.parallelToolCalls()).vision(c.vision())
                    .thinking(c.thinking()).maxContextTokens(c.maxContextTokens()).build();
        }

        private static ModelCapabilities withToolCalling(ModelCapabilities c, boolean value) {
            return ModelCapabilities.builder()
                    .streaming(c.streaming()).toolCalling(value)
                    .parallelToolCalls(c.parallelToolCalls()).vision(c.vision())
                    .thinking(c.thinking()).maxContextTokens(c.maxContextTokens()).build();
        }

        private static ModelCapabilities withParallel(ModelCapabilities c, boolean value) {
            return ModelCapabilities.builder()
                    .streaming(c.streaming()).toolCalling(c.toolCalling())
                    .parallelToolCalls(value).vision(c.vision())
                    .thinking(c.thinking()).maxContextTokens(c.maxContextTokens()).build();
        }

        private static ModelCapabilities withThinking(ModelCapabilities c, boolean value) {
            return ModelCapabilities.builder()
                    .streaming(c.streaming()).toolCalling(c.toolCalling())
                    .parallelToolCalls(c.parallelToolCalls()).vision(c.vision())
                    .thinking(value).maxContextTokens(c.maxContextTokens()).build();
        }
    }
}
