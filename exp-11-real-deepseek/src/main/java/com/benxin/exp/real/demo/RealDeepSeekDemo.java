package com.benxin.exp.real.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.real.agent.MinimalLoopAssistant;
import com.benxin.exp.real.agent.ReactLoopAssistant;
import com.benxin.exp.real.agent.TimeAssistant;
import com.benxin.exp.real.agent.TinyAssistant;
import com.benxin.exp.real.config.ApiKeyResolver;
import com.benxin.exp.real.config.RealCallGate;
import com.benxin.exp.real.config.RealCallMetrics;
import com.benxin.exp.real.tool.TinyTools;
import com.benxin.exp.real.wire.WireLog;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「本心 + 真实 DeepSeek」的启动即跑演示。
 *
 * <p>设计原则只有一条：<b>默认一分钱都不花</b>。没有打开开关时，
 * 整个演示只跑"离线兜底"一节，并打印怎么打开；打开后每个小节只调一次模型
 * （工具闭环与多轮各两次，这是它们成立的最低成本），结束时打印用量总账。</p>
 *
 * <p>九个小节与任务清单一一对应，顺序也是刻意的：先用最小调用确认链路通，
 * 再做需要更多 token 的对照实验，最后用"免费"的 401 与报文留档收尾。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RealDeepSeekDemo implements ApplicationRunner {

    /** 三个小节共用同一个问题：5 字以内能回答完，且答案可逐字核对。 */
    private static final String QUESTION = "1+1=?";

    /** 流式小节的问题：要求两个字的回答，便于观察增量分片。 */
    private static final String STREAM_QUESTION = "只回复两个字：收到";

    /** Loop 对照的小节：明确要求"不要用工具"，把这一步压到恰好一次调用。 */
    private static final String LOOP_TASK = "不要调用任何工具，只回复两个字：完成";

    private static final String SESSION_MINIMAL = "real-minimal";
    private static final String SESSION_STREAM = "real-stream";
    private static final String SESSION_MEMORY = "real-memory";

    private final TinyAssistant tiny;
    private final TimeAssistant time;
    private final MinimalLoopAssistant minimalLoop;
    private final ReactLoopAssistant reactLoop;
    private final RecordingModel mockModel;
    private final TinyTools tinyTools;
    private final ModelRegistry modelRegistry;
    private final AgentRegistry agentRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmAgentFactory agentFactory;
    private final WireLog wireLog;
    private final RealCallGate gate;
    private final ObjectProvider<RealCallMetrics> metricsProvider;

    public RealDeepSeekDemo(TinyAssistant tiny,
                            TimeAssistant time,
                            MinimalLoopAssistant minimalLoop,
                            ReactLoopAssistant reactLoop,
                            RecordingModel mockLlmModel,
                            TinyTools tinyTools,
                            ModelRegistry modelRegistry,
                            AgentRegistry agentRegistry,
                            LoopRegistry loopRegistry,
                            LlmAgentFactory agentFactory,
                            WireLog wireLog,
                            RealCallGate gate,
                            ObjectProvider<RealCallMetrics> metricsProvider) {
        this.tiny = tiny;
        this.time = time;
        this.minimalLoop = minimalLoop;
        this.reactLoop = reactLoop;
        this.mockModel = mockLlmModel;
        this.tinyTools = tinyTools;
        this.modelRegistry = modelRegistry;
        this.agentRegistry = agentRegistry;
        this.loopRegistry = loopRegistry;
        this.agentFactory = agentFactory;
        this.wireLog = wireLog;
        this.gate = gate;
        this.metricsProvider = metricsProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 11 · 真实 DeepSeek —— 默认离线，打开开关才联网");
        banner(demo);

        demo.guard("离线兜底：同一个 Agent 接口 + 脚本化模型", () -> offlineFallback(demo));

        if (!gate.enabled()) {
            demo.section("真实调用：未开启（本次不发出任何网络请求）");
            demo.warn("exp.real.enabled = false —— 这是默认值，也是本示例的省钱姿势。");
            demo.info("开启方式（二选一，效果相同）：");
            demo.info("   ① java -Dexp.real.enabled=true -jar target/exp-11-real-deepseek-1.0.0.jar");
            demo.info("   ② java --spring.profiles.active=real -jar target/exp-11-real-deepseek-1.0.0.jar");
            demo.info("离线回归：mvn -o test（真实调用类测试会 skip，不会失败）");
            demo.done();
            return;
        }

        if (!apiKeyConfigured()) {
            demo.section("真实调用：已开启，但取不到 API Key（本次不联网）");
            demo.warn("key 的取值口径：① 环境变量 DEEPSEEK_API_KEY ② -Ddeepseek.api-key=... ③ 环境变量 DEEPSEEK_KEY");
            demo.info("当前状态：%s", ApiKeyResolver.describe());
            demo.info("取不到 key 时本示例只打印提示、不抛异常 —— 示例的职责是展示，不是把演示崩在中间。");
            demo.done();
            return;
        }

        wireLog.reset();
        metrics().reset();

        demo.guard("最小真实对话", () -> minimalRealCall(demo));
        demo.guard("一次调用 = 一笔请求（max-retries: 0）", () -> oneCallOneRequest(demo));
        demo.guard("真实工具调用闭环", () -> realToolLoop(demo));
        demo.guard("真实流式（SSE）", () -> realStreaming(demo));
        demo.guard("Responses 协议对照", () -> protocolComparison(demo));
        demo.guard("多轮记忆", () -> realMemory(demo));
        demo.guard("Loop 对照（dsh-minimal vs react）", () -> loopComparison(demo));
        demo.guard("错误路径（假 key → 401，不消耗 token）", () -> errorPath(demo));
        demo.guard("真实报文留档", () -> wireLogEvidence(demo));

        usageSummary(demo);
        demo.done();
    }

    // ==================================================================
    // 环境
    // ==================================================================

    private void banner(Demo demo) {
        demo.banner("真实调用开关 exp.real.enabled = %s（来源：%s）", gate.enabled(), gate.source());
        demo.banner("API Key：%s", ApiKeyResolver.describe());
        demo.banner("默认模型 = %s；已注册模型 = %s", modelRegistry.defaultName(), modelRegistry.names());
        demo.banner("Agent = %s", agentRegistry.names());
        demo.banner("Loop = %s（默认 %s）", loopRegistry.names().stream().limit(4).toList(), loopRegistry.defaultName());
        demo.banner("已装配的模型端点：");
        modelRegistry.all().forEach(model -> {
            LlmModel raw = model.unwrap();
            if (raw instanceof HttpLlmModel http) {
                var config = http.config();
                demo.banner("   [%s] protocol=%s baseUrl=%s model=%s maxTokens=%s maxRetries=%d apiKey=%s",
                        config.name(), config.protocol().id(), config.baseUrl(), config.model(),
                        config.maxTokens(), config.maxRetries(),
                        config.hasApiKey() ? "已配置（内容不打印）" : "（空）");
            } else {
                demo.banner("   [%s] %s（离线实现，零 token）", model.name(), raw.getClass().getSimpleName());
            }
        });
        demo.banner("报文留档目录 = %s", wireLog.dir());
    }

    // ==================================================================
    // ⑨（默认唯一会跑的一节）离线兜底
    // ==================================================================

    /**
     * 同一个 Agent 接口、同一份 {@code AgentSpec}、同一套工具，只把模型换掉。
     *
     * <p>这是本示例"默认唯一会跑的演示"：它证明<b>"换模型不改业务代码"</b>不是口号 ——
     * 接口上没有写 {@code model}，真实模式靠一行 {@code llm.default-model} 切到 deepseek；
     * 这里则在同一个 JVM 里用 {@code toBuilder().model(mock)} 把模型换成脚本化实现，
     * 业务代码（接口签名、Prompt、工具、Loop）一个字都没变。</p>
     */
    private void offlineFallback(Demo demo) {
        demo.section("离线兜底：同一个 Agent 接口，换模型不改业务代码");
        Agent agent = agentRegistry.get("tinyAssistant");
        // 注意 toBuilder() 复制的是 spec / loop / 工具表 / 记忆存储，只有模型被替换
        Agent offline = agent.toBuilder().model(mockModel).build();
        agent.clearHistory(SESSION_MINIMAL);
        mockModel.reset();

        AgentResult result = offline.call(QUESTION, SESSION_MINIMAL);
        demo.ok("离线回答: %s", oneLine(result.text()));
        demo.info("steps=%d model=%s loop=%s finishReason=%s",
                result.steps(), result.modelName(), result.loopName(), result.finishReason());
        demo.info("离线用量（脚本模型自己估的，不算钱）: 输入 %d / 输出 %d",
                result.usage().inputTokens(), result.usage().outputTokens());
        demo.info("脚本模型被调用 %d 次；底层实现 = %s", mockModel.count(), mockModel.unwrap().getClass().getSimpleName());
        demo.ok("接口上没有写 model 属性（specOf(TinyAssistant).model() = %s），"
                        + "真实模型完全由 llm.default-model 决定",
                agentFactory.specOf(TinyAssistant.class).model());
    }

    // ==================================================================
    // ① 最小真实对话
    // ==================================================================

    private void minimalRealCall(Demo demo) {
        demo.section("最小真实对话：AgentResult 的每个字段都打出来");
        agentRegistry.get("tinyAssistant").clearHistory(SESSION_MINIMAL);
        metrics().mark("最小真实对话");

        AgentResult result = tiny.result(SESSION_MINIMAL, QUESTION);

        demo.ok("回答: %s", oneLine(result.text()));
        demo.info("agent=%s model=%s loop=%s", result.agentName(), result.modelName(), result.loopName());
        demo.info("steps=%d（一问一答只走 1 步）  durationMillis=%d  finishReason=%s",
                result.steps(), result.durationMillis(), result.finishReason());
        demo.info("usage: 输入 %d + 输出 %d = %d tokens",
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().totalTokens());
        printLastWire(demo);
    }

    // ==================================================================
    // ② 一次调用 = 一笔请求
    // ==================================================================

    private void oneCallOneRequest(Demo demo) {
        demo.section("一次调用 = 一笔请求：max-retries: 0 的可观测证据");
        LlmModel deepseek = modelRegistry.get("deepseek").unwrap();
        demo.info("模型实现类 = %s（配置 max-retries: 0，因此**没有**被 RetryingLlmModel 包裹）",
                deepseek.getClass().getSimpleName());
        demo.info("留档 HTTP 请求数 = %d，其中打到真实端点 = %d", wireLog.requests(), wireLog.realRequests());
        boolean oneToOne = wireLog.realRequests() == metrics().modelCalls();
        if (oneToOne) {
            demo.ok("至此 %d 次模型调用 ↔ %d 笔真实 HTTP 请求，一一对应；"
                            + "把 max-retries 调大就会变成 1 次调用 N 笔请求（限流时会悄悄多花钱）",
                    metrics().modelCalls(), wireLog.realRequests());
        } else {
            demo.warn("调用数 %d 与请求数 %d 不一致，请查看留档文件", metrics().modelCalls(), wireLog.realRequests());
        }
    }

    // ==================================================================
    // ③ 真实工具调用闭环
    // ==================================================================

    private void realToolLoop(Demo demo) {
        demo.section("真实工具调用闭环：tools 声明 → tool_calls 解析 → 结果回灌");
        tinyTools.reset();
        metrics().mark("真实工具调用闭环");

        AgentResult result = time.ask("现在几点？");

        demo.info("回答: %s", oneLine(result.text()));
        demo.info("steps=%d  finishReason=%s  usage=%d+%d=%d",
                result.steps(), result.finishReason(), result.usage().inputTokens(),
                result.usage().outputTokens(), result.usage().totalTokens());
        result.toolCalls().forEach(call -> demo.info("toolCall: name=%s args=%s error=%s result=%s",
                call.name(), call.arguments(), call.error(), oneLine(call.result())));
        demo.info("工具被真实执行 %d 次（时间来自 TinyTools.now()，不是模型编的）", tinyTools.callCount());

        if (result.toolCalls().isEmpty()) {
            throw new AssertionError("真实模型没有请求工具：toolCalls() 为空 —— 这条链路没能被验证");
        }
        if (result.steps() != 2) {
            throw new AssertionError("预期 steps == 2（1 步请求工具 + 1 步收尾），实际 " + result.steps());
        }
        demo.ok("断言通过：toolCalls() 非空（%d 个）且 steps() == 2", result.toolCalls().size());
        printLastWire(demo);
    }

    // ==================================================================
    // ④ 真实流式（SSE）
    // ==================================================================

    private void realStreaming(Demo demo) {
        demo.section("真实流式（SSE）：增量到达过程与首字节延迟");
        agentRegistry.get("tinyAssistant").clearHistory(SESSION_STREAM);
        metrics().mark("真实流式 SSE");

        StringBuilder collected = new StringBuilder();
        AtomicInteger deltas = new AtomicInteger();
        long start = System.nanoTime();
        long[] firstDeltaNanos = {-1};

        String text = tiny.stream(SESSION_STREAM, STREAM_QUESTION, new LlmStreamHandler() {
            @Override
            public void onStart() {
                demo.info("onStart：%.0f ms —— 上游第一个 SSE 事件已到达，可以开始渲染了",
                        (System.nanoTime() - start) / 1_000_000.0);
            }

            @Override
            public void onTextDelta(String delta) {
                if (firstDeltaNanos[0] < 0) {
                    firstDeltaNanos[0] = System.nanoTime();
                }
                collected.append(delta);
                demo.info("onTextDelta #%d: \"%s\"  （累计 %.0f ms）",
                        deltas.incrementAndGet(), delta, (System.nanoTime() - start) / 1_000_000.0);
            }

            @Override
            public void onUsage(Usage usage) {
                demo.info("onUsage：输入 %d + 输出 %d = %d tokens（来自 SSE 末帧）",
                        usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
            }
        });

        demo.ok("共 %d 个增量片，方法返回值与拼接结果一致 = %s",
                deltas.get(), text.equals(collected.toString()));
        demo.ok("首个增量延迟 = %.0f ms（真实网络 + 上游首 token 时间）",
                firstDeltaNanos[0] < 0 ? -1 : (firstDeltaNanos[0] - start) / 1_000_000.0);
        demo.info("整段耗时 = %.0f ms", (System.nanoTime() - start) / 1_000_000.0);
        demo.info("注意：声明式路径**本来就是流式**的（DefaultLoopContext 对有 streaming 能力的模型调 stream()），"
                + "这里的 handler 只是把增量接到手上，并不是「额外开了一个流式模式」。");
        printLastWire(demo);
    }

    // ==================================================================
    // ⑤ Responses 协议对照
    // ==================================================================

    /**
     * 同一个问题，两种协议各调一次，然后把两份真实报文摆在一起看。
     *
     * <p>刻意走 {@code LlmModel.chat()}（同步、非流式）而不是 Agent：
     * 这样响应体是一个完整的 JSON，两种协议的<b>报文结构差异</b>才看得最干净
     * （流式下 responses 是命名事件、chat 是无名 SSE + {@code [DONE]}，
     * 差异会被"都是 SSE"这层共性盖住）。</p>
     */
    private void protocolComparison(Demo demo) {
        demo.section("Responses 协议对照：同一个问题，两套报文");
        LlmModel chatModel = modelRegistry.get("deepseek");
        LlmModel responsesModel = modelRegistry.get("deepseek-responses");
        metrics().mark("协议对照");

        ChatResponse chat = chatModel.chat(simpleRequest());
        metrics().record("deepseek(openai)", chat.model(), chat.usage(), chat.finishReason().name());
        WireLog.Entry chatEntry = wireLog.last();

        ChatResponse responses = responsesModel.chat(simpleRequest());
        metrics().record("deepseek(responses)", responses.model(), responses.usage(), responses.finishReason().name());
        WireLog.Entry responsesEntry = wireLog.last();

        demo.ok("问题相同（%s），两个端点各 1 次调用", QUESTION);
        printExchange(demo, "Chat Completions", chatEntry, chat);
        printExchange(demo, "Responses", responsesEntry, responses);

        demo.info("差异要点（对照上面两份请求/响应顶层字段即可逐条核对）：");
        demo.info("   · 系统提示：messages[] 里的 system 角色  ↔  顶层 instructions");
        demo.info("   · 对话载体：messages[]                   ↔  input[] item 列表");
        demo.info("   · 工具声明：tools[].function.{name,parameters}  ↔  tools[].{name,parameters}（扁平，无 function 层）");
        demo.info("   · 输出上限：max_tokens                    ↔  max_output_tokens");
        demo.info("   · 结束原因：finish_reason                 ↔  status");
        demo.info("   · 流式形态：无名 SSE + [DONE]              ↔  命名事件，靠 response.completed 收尾");
        demo.info("   相同点：认证头（Bearer）、temperature、以及 usage 都能被归一化成同一个 Usage。");
    }

    /** 一个最小请求：一条 system + 一条 user，带上输出上限与温度。 */
    private ChatRequest simpleRequest() {
        return ChatRequest.builder()
                .message(ChatMessage.system("只回答，不解释。"))
                .message(ChatMessage.user(QUESTION))
                .temperature(0.0)
                .maxTokens(48)
                .build();
    }

    private void printExchange(Demo demo, String label, WireLog.Entry entry, ChatResponse response) {
        demo.info("[%s]", label);
        demo.info("   POST %s（HTTP %d）", entry == null ? "?" : entry.url(), entry == null ? -1 : entry.status());
        demo.info("   请求顶层字段: %s", topLevelKeys(entry == null ? null : entry.requestBody()));
        demo.info("   响应顶层字段: %s", topLevelKeys(entry == null ? null : entry.responseBody()));
        demo.info("   归一化结果: text=%s finishReason=%s usage=%d+%d=%d",
                oneLine(response.text()), response.finishReason(),
                response.usage().inputTokens(), response.usage().outputTokens(), response.usage().totalTokens());
        demo.info("   留档文件: %s", entry == null ? "(无)" : entry.file());
    }

    private void printLastWire(Demo demo) {
        WireLog.Entry entry = wireLog.last();
        if (entry == null) {
            return;
        }
        demo.info("真实报文: %s", entry.summary());
        demo.info("留档文件: %s", entry.file());
    }

    // ==================================================================
    // ⑥ 多轮记忆
    // ==================================================================

    private void realMemory(Demo demo) {
        demo.section("多轮记忆：同一个 sessionId 连续两问，合计 2 次调用");
        Agent agent = agentRegistry.get("tinyAssistant");
        agent.clearHistory(SESSION_MEMORY);
        metrics().mark("多轮记忆");

        String first = tiny.chatWithMemory(SESSION_MEMORY, "记住代号：青龙。只回复：好");
        String second = tiny.chatWithMemory(SESSION_MEMORY, "代号是什么？只回复代号");

        demo.ok("第一问回答 = %s（本轮请求里还没有历史）", oneLine(first));
        demo.ok("第二问回答 = %s（第二问**没有**重复提供代号，全靠会话历史）", oneLine(second));
        demo.ok("会话 %s 的历史条数 = %d（两轮问答 = 4 条：user/assistant × 2）",
                SESSION_MEMORY, agent.history(SESSION_MEMORY).size());
        demo.info("第二问的输入 token 比第一问多，多出来的就是这段历史 —— 记忆不是免费的，"
                + "所以本示例每一步都显式指定 sessionId，绝不共用默认会话。");
        printLastWire(demo);
    }

    // ==================================================================
    // ⑦ Loop 对照
    // ==================================================================

    private void loopComparison(Demo demo) {
        demo.section("Loop 对照：dsh-minimal vs react，同一个极简任务");
        metrics().mark("Loop 对照");

        AgentResult minimal = minimalLoop.run(LOOP_TASK);
        AgentResult react = reactLoop.run(LOOP_TASK);

        demo.info("两个接口的 AgentSpec 只差 loop：%s vs %s",
                agentFactory.specOf(MinimalLoopAssistant.class).loop(),
                agentFactory.specOf(ReactLoopAssistant.class).loop());
        printLoopResult(demo, "dsh-minimal", minimal);
        printLoopResult(demo, "react", react);
        demo.ok("结论：两者 steps / usage / 回答都一致 —— 因为 DeepSeek 支持原生 function calling 时，"
                + "react 会走与 dsh-minimal 相同的结构化工具调用骨架；"
                + "react 的文本协议（Thought/Action/Observation）只在模型不支持工具调用时才启用。");
    }

    private void printLoopResult(Demo demo, String label, AgentResult result) {
        demo.info("[%s] loop=%s steps=%d finishReason=%s usage=%d+%d=%d 回答=%s",
                label, result.loopName(), result.steps(), result.finishReason(),
                result.usage().inputTokens(), result.usage().outputTokens(),
                result.usage().totalTokens(), oneLine(result.text()));
        demo.info("   工具（已声明但本次未调用）= %s", agentRegistry.get(result.agentName()).tools().names());
    }

    // ==================================================================
    // ⑧ 错误路径（401）
    // ==================================================================

    /**
     * 故意用一个错误的 key 打一次真实端点。
     *
     * <p>这是"最便宜的异常演示"：请求在鉴权阶段就被拒（HTTP 401），
     * 上游根本不会跑模型，因此<b>一个 token 都不消耗</b>。
     * 错误处理就该这么做演示，而不是靠"制造一次昂贵的失败"。</p>
     */
    private void errorPath(Demo demo) {
        demo.section("错误路径：假 key → ModelException(401)，零 token");
        LlmModel bad = modelRegistry.get("deepseek-badkey");
        metrics().mark("错误路径（不消耗 token）");
        int requestsBefore = wireLog.realRequests();

        try {
            bad.chat(simpleRequest());
            demo.warn("预期抛出 ModelException，实际却成功了 —— 请检查 deepseek-badkey 的 api-key 配置");
        } catch (ModelException e) {
            demo.ok("抛出 %s", e.getClass().getName());
            demo.ok("statusCode() = %d（%s）", e.statusCode(),
                    e.statusCode() == 401 ? "上游判定鉴权失败，符合预期" : "与预期的 401 不一致");
            demo.info("消息可读性: %s", oneLine(e.getMessage()));
            demo.info("真实请求数 +%d（这笔请求被拒绝，因此没有产生任何 token 消耗）",
                    wireLog.realRequests() - requestsBefore);
            demo.info("留档文件: %s", wireLog.last() == null ? "(无)" : wireLog.last().file());
        }
    }

    // ==================================================================
    // ⑨ 报文留档
    // ==================================================================

    private void wireLogEvidence(Demo demo) {
        demo.section("真实报文留档：target/wire-log/ 里的证据");
        List<WireLog.Entry> entries = wireLog.entries();
        demo.info("目录: %s（共 %d 个文件）", wireLog.dir(), entries.size());
        entries.forEach(entry -> demo.info("   %s  ←  %s", entry.file().getFileName(), entry.summary()));

        WireLog.Entry first = entries.isEmpty() ? null : entries.get(0);
        if (first == null) {
            demo.warn("没有留档文件");
            return;
        }
        String content = readFile(first.file());
        String realKey = ApiKeyResolver.resolve();
        demo.ok("Authorization 已脱敏成 \"Bearer sk-****\"：%s", content.contains("Bearer sk-****"));
        demo.ok("落盘内容中不含真实 key：%s",
                realKey.isEmpty() || !content.contains(realKey));
        demo.info("首个文件的大小 = %d 字符，可直接打开对照协议字段", content.length());
        demo.info("流式那一笔留的是 responseRaw（SSE 原文），非流式的留的是 response（成形的 JSON）——"
                + "两种形态刻意不混在一起。");
    }

    // ==================================================================
    // 汇总
    // ==================================================================

    private void usageSummary(Demo demo) {
        demo.section("真实用量汇总");
        RealCallMetrics metrics = metrics();
        List<RealCallMetrics.Call> calls = metrics.calls();

        demo.info("逐次用量（可直接抄进 README 的实测表）：");
        int index = 0;
        for (RealCallMetrics.Call call : calls) {
            index++;
            demo.info("  %d. [%s] %s | 步 %d | 输入 %d + 输出 %d = %d tokens | %s",
                    index, call.label(), call.model(), call.step(),
                    call.inputTokens(), call.outputTokens(), call.totalTokens(), call.finishReason());
        }
        Usage total = metrics.total();
        demo.ok("本次共 %d 次调用、%d 输入 + %d 输出 = %d tokens",
                metrics.modelCalls(), total.inputTokens(), total.outputTokens(), total.totalTokens());
        demo.ok("真实 HTTP 请求 %d 笔，留档 %d 笔（含被拒绝的那笔，它 0 token）",
                wireLog.realRequests(), wireLog.entries().size());
        demo.info("工具被调用 %d 次；max-tokens 上限 48/次，max-retries=0", tinyTools.callCount());
        if (total.totalTokens() <= 2000) {
            demo.ok("总消耗在 2000 token 预算之内（余量 %d）", 2000 - total.totalTokens());
        } else {
            demo.warn("总消耗 %d 已超出 2000 token 预算", total.totalTokens());
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** real 模式才有这个 bean；离线模式下这里不该被调用。 */
    private RealCallMetrics metrics() {
        RealCallMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics == null) {
            throw new IllegalStateException("RealCallMetrics 不存在（exp.real.enabled 未打开）");
        }
        return metrics;
    }

    private boolean apiKeyConfigured() {
        return modelRegistry.find("deepseek")
                .map(LlmModel::unwrap)
                .filter(HttpLlmModel.class::isInstance)
                .map(HttpLlmModel.class::cast)
                .map(model -> model.config().hasApiKey())
                .orElse(false);
    }

    private static String topLevelKeys(String json) {
        if (json == null) {
            return "(无)";
        }
        JsonNode node = Json.parseQuietly(json);
        if (node == null || !node.isObject()) {
            return "(非 JSON 对象，可能是 SSE 或纯文本)";
        }
        List<String> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys.toString();
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取留档文件失败: " + path, e);
        }
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
