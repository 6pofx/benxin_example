package com.benxin.exp.context.demo;

import com.benxin.exp.common.db.SimpleDataSource;
import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.context.agent.ClaudeCodeAssistant;
import com.benxin.exp.context.agent.CompactAssistant;
import com.benxin.exp.context.agent.MemoryAssistant;
import com.benxin.exp.context.agent.PromptAssistant;
import com.benxin.exp.context.agent.StatelessAssistant;
import com.benxin.exp.context.config.ContextPipelineConfiguration;
import com.benxin.exp.context.config.OfflineModelConfiguration;
import com.benxin.exp.context.context.Exp06ContextManager;
import com.benxin.exp.context.context.Exp06TokenEstimator;
import com.benxin.exp.context.context.HardLimitCompactor;
import com.benxin.exp.context.memory.JdbcMemoryStore;
import com.benxin.exp.context.prompt.JdbcPromptPlatform;
import com.benxin.exp.context.tool.LongDocTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SlidingWindowCompactor;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.prompt.PromptTemplates;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.util.TokenEstimator;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动即跑的演示：把「记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态」六个切面各打一遍。
 *
 * <p>输出刻意保持「看得见结论」：不只打印回答，还打印<b>真正发给模型的消息序列</b>、
 * 每个可插拔点最终装的是谁、以及压缩前后条数与体积的变化 ——
 * 这几件事平时全在框架内部，恰恰是最容易猜错的地方。</p>
 *
 * <p>全部离线：模型来自 {@code exp-common} 的 {@link ScriptedLlmModel}，
 * 因此这个演示既不联网也不消耗 token，却完整跑过了压缩、裁剪、多模态这些真实路径。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContextMemoryDemo implements ApplicationRunner {

    /** 假设的 base64 图片数据（真实场景里是 png/jpg 的字节流编码）。 */
    private static final String DEMO_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AL+2gAAAABJRU5ErkJggg==";

    private final MemoryAssistant memoryAssistant;
    private final PromptAssistant promptAssistant;
    private final CompactAssistant compactAssistant;
    private final ClaudeCodeAssistant claudeCodeAssistant;
    private final StatelessAssistant statelessAssistant;

    private final AgentRegistry agentRegistry;
    private final com.benxin.llm.core.model.ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmAgentFactory agentFactory;

    private final RecordingModel mockModel;
    private final RecordingModel tinyModel;
    private final RecordingModel visionModel;

    private final MemoryStore memoryStore;
    private final JdbcMemoryStore jdbcStore;
    private final ContextManager contextManager;
    private final Exp06ContextManager exp06ContextManager;
    private final ContextCompactor contextCompactor;
    private final Exp06TokenEstimator tokenEstimator;
    private final JdbcPromptPlatform promptPlatform;
    private final LongDocTools longDocTools;

    private final String storeUrl;
    private final String storeUsername;
    private final String storePassword;

    public ContextMemoryDemo(MemoryAssistant memoryAssistant,
                             PromptAssistant promptAssistant,
                             CompactAssistant compactAssistant,
                             ClaudeCodeAssistant claudeCodeAssistant,
                             StatelessAssistant statelessAssistant,
                             AgentRegistry agentRegistry,
                             com.benxin.llm.core.model.ModelRegistry modelRegistry,
                             LoopRegistry loopRegistry,
                             LlmAgentFactory agentFactory,
                             @Qualifier("mockLlmModel") RecordingModel mockModel,
                             @Qualifier("tinyLlmModel") RecordingModel tinyModel,
                             @Qualifier("visionLlmModel") RecordingModel visionModel,
                             MemoryStore memoryStore,
                             JdbcMemoryStore jdbcStore,
                             ContextManager contextManager,
                             Exp06ContextManager exp06ContextManager,
                             ContextCompactor contextCompactor,
                             Exp06TokenEstimator tokenEstimator,
                             JdbcPromptPlatform systemPromptProvider,
                             LongDocTools longDocTools,
                             @Value("${exp.store.url}") String storeUrl,
                             @Value("${exp.store.username}") String storeUsername,
                             @Value("${exp.store.password}") String storePassword) {
        this.memoryAssistant = memoryAssistant;
        this.promptAssistant = promptAssistant;
        this.compactAssistant = compactAssistant;
        this.claudeCodeAssistant = claudeCodeAssistant;
        this.statelessAssistant = statelessAssistant;
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.agentFactory = agentFactory;
        this.mockModel = mockModel;
        this.tinyModel = tinyModel;
        this.visionModel = visionModel;
        this.memoryStore = memoryStore;
        this.jdbcStore = jdbcStore;
        this.contextManager = contextManager;
        this.exp06ContextManager = exp06ContextManager;
        this.contextCompactor = contextCompactor;
        this.tokenEstimator = tokenEstimator;
        this.promptPlatform = systemPromptProvider;
        this.longDocTools = longDocTools;
        this.storeUrl = storeUrl;
        this.storeUsername = storeUsername;
        this.storePassword = storePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 06 · 记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态消息");
        demo.banner("模型: %s（默认 %s）", modelRegistry.names(), modelRegistry.defaultName());
        demo.banner("Loop: %s", loopRegistry.names().stream().limit(6).toList());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("全程离线：三个模型都是脚本化模型，不联网、零 token");

        demo.guard("可插拔点装配总览", () -> assembly(demo));
        demo.guard("MemoryStore 三种来源", () -> memoryBackends(demo));
        demo.guard("@Memory 多轮续接与隔离", () -> multiTurn(demo));
        demo.guard("关掉记忆的两种写法", () -> withoutMemory(demo));
        demo.guard("自定义 ContextManager", () -> customContextManager(demo));
        demo.guard("ContextCompactor 三种", () -> compactors(demo));
        demo.guard("TokenEstimator 与压缩阈值", () -> tokenEstimators(demo));
        demo.guard("SystemPromptProvider 三条路径", () -> promptSources(demo));
        demo.guard("PromptTemplates 分段提示词", () -> promptTemplates(demo));
        demo.guard("多模态消息", () -> multimodal(demo));
        demo.guard("上下文与记忆的边界", () -> boundary(demo));

        demo.done();
    }

    // ==================================================================
    // ① 装配总览
    // ==================================================================

    private void assembly(Demo demo) {
        demo.section("可插拔点装配总览 —— 每个切面装的都是谁");
        // 这一节要读 JDBC 表里的会话数，所以也得先清一遍：否则第二次 java -jar 打印的就是
        // 上一轮留下的会话，"从零开始"这件事本身也就不可复现了
        resetState();
        demo.info("插件 LlmAutoConfiguration 里的每个 @Bean 都带 @ConditionalOnMissingBean：");
        demo.info("用户定义同类型 bean，默认实现自动让位，不需要开关、也不需要排包。");
        demo.info("");
        demo.info("MemoryStore          → %s", memoryStore.getClass().getName());
        demo.info("                       %s；当前表里有 %d 个会话",
                jdbcStore.describe(), jdbcStore.sessionCount());
        demo.info("ContextManager       → %s", contextManager.getClass().getName());
        demo.info("                       %s", exp06ContextManager.describe());
        demo.info("ContextCompactor     → %s", contextCompactor.getClass().getName());
        demo.info("TokenEstimator       → %s", tokenEstimator.describe());
        demo.info("SystemPromptProvider → %s", promptPlatform.describe());
        demo.info("模型                  → mock(窗口 %d) / tiny(窗口 %d) / vision(窗口 %d, vision=%s)",
                OfflineModelConfiguration.WIDE_CONTEXT_TOKENS,
                OfflineModelConfiguration.TINY_CONTEXT_TOKENS,
                visionModel.capabilities().maxContextTokens(),
                visionModel.capabilities().vision());
        demo.ok("Agent 全部来自 @LlmAgent 接口，注入即用：%s", agentRegistry.names());
    }

    // ==================================================================
    // ② MemoryStore 三种来源
    // ==================================================================

    private void memoryBackends(Demo demo) {
        demo.section("MemoryStore 三种来源 —— 换一个 bean 就换一套记忆");
        resetState();

        // ① 默认的内存实现：带 LRU 上限
        InMemoryMemoryStore small = new InMemoryMemoryStore(2);
        Agent lruAgent = agentRegistry.get("memoryAssistant").toBuilder().memoryStore(small).build();
        lruAgent.call("你好", "lru-a");
        lruAgent.call("你好", "lru-b");
        lruAgent.call("你好", "lru-c");
        demo.info("① InMemoryMemoryStore(2)：依次写 lru-a / lru-b / lru-c 三个会话");
        demo.ok("   exists(a)=%s、exists(b)=%s、exists(c)=%s —— 容量是 LRU 上限，最久未访问的被淘汰",
                small.exists("lru-a"), small.exists("lru-b"), small.exists("lru-c"));
        demo.info("   MemoryStore.inMemory() 就是 new InMemoryMemoryStore() 的工厂别名（默认容量 256）");

        // ② 容器里装的 JDBC 实现
        memoryAssistant.chat("jdbc-demo", "你好");
        memoryAssistant.chat("jdbc-demo", "第二轮");
        demo.info("② 容器里的 MemoryStore 是自定义 JDBC 实现（H2 文件库 %s）", storeUrl);
        jdbcStore.rows().forEach(row -> demo.info("   %s", row));
        JdbcMemoryStore reopened = new JdbcMemoryStore(new JdbcTemplate(
                new SimpleDataSource(storeUrl, storeUsername, storePassword)));
        demo.ok("   换一个全新 DataSource 重新构造 MemoryStore 读同一会话：%d 条（历史真的落库了）",
                reopened.load("jdbc-demo").size());

        // ③ disabled：语义上「完全不落盘」
        Agent noStore = agentRegistry.get("memoryAssistant")
                .toBuilder().memoryStore(MemoryStore.disabled()).build();
        noStore.call("第一轮", "no-store");
        noStore.call("第二轮", "no-store");
        demo.info("③ MemoryStore.disabled()：load 恒为空、save 是空操作、clear 无状态");
        demo.ok("   连续两轮之后 history(no-store)=%d，且这一轮下发的对话消息只有 %d 条（没有历史可装载）",
                noStore.history("no-store").size(), conversationSize(mockModel.lastRequest()));
        demo.warn("   注意 disabled() 与 memory=false 不是一回事：前者只是「存不进去」，"
                + "Agent 仍然会去 load/save（只是永远为空）；后者连 load/save 都不调用");
    }

    // ==================================================================
    // ③ @Memory 多轮续接与隔离
    // ==================================================================

    private void multiTurn(Demo demo) {
        demo.section("@Memory 多轮续接与隔离 —— sessionId 决定和谁续聊");
        resetState();

        memoryAssistant.chat("s-1", "你好");
        memoryAssistant.chat("s-1", "第二轮");
        memoryAssistant.chat("s-2", "你好");

        Agent agent = agentRegistry.get("memoryAssistant");
        demo.ok("s-1 历史 %d 条（两轮 = 一问一答 ×2）", agent.history("s-1").size());
        demo.ok("s-2 历史 %d 条 —— 不同 sessionId 完全隔离", agent.history("s-2").size());
        demo.info("   最后一轮真正下发的消息：");
        printMessages(demo, mockModel.lastRequest());

        agent.clearHistory("s-2");
        demo.ok("clearHistory(s-2) 之后 = %d 条", agent.history("s-2").size());

        AgentSession session = agent.session("s-3");
        session.chat("打个招呼");
        demo.info("AgentSession 视图：id=%s，历史 %d 条", session.id(), session.history().size());
        session.clear();
        demo.ok("AgentSession.clear() 之后 = %d 条（等价于 clearHistory(session.id())）",
                session.history().size());
    }

    // ==================================================================
    // ④ 关掉记忆
    // ==================================================================

    private void withoutMemory(Demo demo) {
        demo.section("关掉记忆的两种写法 —— 注解级 vs 派生级");
        resetState();

        statelessAssistant.ask("stateless-1", "你好");
        statelessAssistant.ask("stateless-1", "第二轮");
        demo.ok("@LlmAgent(memory = false)：即使传了 sessionId，history=%d（连 save 都不调用）",
                agentRegistry.get("statelessAssistant").history("stateless-1").size());

        Agent builder = agentRegistry.get("memoryAssistant").toBuilder().memory(false).build();
        builder.call("第一轮", "builder-nomem");
        builder.call("第二轮", "builder-nomem");
        demo.ok("AgentBuilder.memory(false) 派生的实例：history=%d，第二轮只下发 %d 条对话消息",
                builder.history("builder-nomem").size(), conversationSize(mockModel.lastRequest()));

        memoryAssistant.chat("builder-mem", "第一轮");
        memoryAssistant.chat("builder-mem", "第二轮");
        demo.ok("对照（memory = true）：第二轮下发 %d 条对话消息 —— 多出来的 2 条就是第一轮的历史",
                conversationSize(mockModel.lastRequest()));
        demo.info("   两种写法的区别只在生效范围：注解影响该接口的所有调用，派生只影响这一个实例");
    }

    // ==================================================================
    // ⑤ 自定义 ContextManager
    // ==================================================================

    private void customContextManager(Demo demo) {
        demo.section("自定义 ContextManager —— 真正下发的消息序列由它决定");
        resetState();

        for (int i = 0; i < 6; i++) {
            memoryAssistant.chat("ctx-demo", i % 2 == 0 ? "你好" : "第二轮");
        }
        Agent agent = agentRegistry.get("memoryAssistant");
        List<ChatMessage> history = new ArrayList<>(agent.history("ctx-demo"));
        String systemPrompt = agent.spec().systemPrompt();
        demo.info("会话 ctx-demo 攒了 %d 条历史；本示例的 ContextManager 窗口是 %d 条",
                history.size(), exp06ContextManager.historyWindow());

        List<ChatMessage> mine = exp06ContextManager.prepare(systemPrompt, history, mockModel);
        ContextManager plain = new DefaultContextManager(contextCompactor, tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);
        List<ChatMessage> theirs = plain.prepare(systemPrompt, history, mockModel);

        demo.ok("自定义 ContextManager 下发 %d 条；DefaultContextManager 下发 %d 条 —— 序列确实变了",
                mine.size(), theirs.size());
        demo.info("   自定义下发的第一条（系统提示词由本管理器注入，带来源标记）：");
        demo.info("   %s", oneLine(mine.get(0).text()));
        demo.info("   DefaultContextManager 下发的第一条：");
        demo.info("   %s", oneLine(theirs.get(0).text()));
        demo.info("   afterStep 回调次数 = %d，累计裁掉 %d 条（默认方法是空实现，拿它做统计最省事）",
                exp06ContextManager.afterStepCount(), exp06ContextManager.trimmedMessages());
        demo.warn("   被裁掉的是「这一次下发的副本」，MemoryStore 里仍然是 %d 条（记忆没有被删）",
                agent.history("ctx-demo").size());

        demo.info("");
        demo.info("DefaultContextManager 的构造参数语义 (compactor, estimator, threshold, keepRecent,"
                + " reservedOutputTokens)：");
        demo.info("   tiny 窗口 %d − 预留输出 %d = 输入预算 %d token",
                OfflineModelConfiguration.TINY_CONTEXT_TOKENS,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS,
                OfflineModelConfiguration.TINY_CONTEXT_TOKENS
                        - ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);
        demo.info("   threshold 是「占模型窗口的比例」：0.8 → 触发线 %d token", triggerLine(0.8));
        demo.info("   keepRecent 是「压缩时最多保留的近期消息条数」（本示例配的是 %d）",
                ContextPipelineConfiguration.KEEP_RECENT);
        demo.info("   同一份 %d 条历史估算 %d token，而这里是 mock（窗口 %d）→ 触发线 %d，"
                        + "离得还很远，所以默认实现一条不丢",
                theirs.size(), tokenEstimator.estimate(theirs),
                OfflineModelConfiguration.WIDE_CONTEXT_TOKENS,
                (int) ((OfflineModelConfiguration.WIDE_CONTEXT_TOKENS
                        - ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                        * ContextPipelineConfiguration.THRESHOLD));
    }

    private int triggerLine(double threshold) {
        int budget = OfflineModelConfiguration.TINY_CONTEXT_TOKENS
                - ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS;
        return (int) (budget * threshold);
    }

    // ==================================================================
    // ⑥ 三种压缩器
    // ==================================================================

    private void compactors(Demo demo) {
        demo.section("ContextCompactor 三种 —— 摘要式会额外花一次模型调用");
        resetState();
        tinyModel.reset();

        // 先把 compactAssistant 的会话撑到必然触发压缩：4 轮 × 900 字符英文
        for (int i = 0; i < 4; i++) {
            compactAssistant.chat("compact-demo", DemoText.ascii(900, "round" + i));
        }
        List<ChatMessage> history = new ArrayList<>(
                agentRegistry.get("compactAssistant").history("compact-demo"));
        demo.info("历史 %d 条，自定义估算 %d token；tiny 窗口 %d、预留输出 %d、阈值 %.2f → 触发线 %d token",
                history.size(), tokenEstimator.estimate(history),
                OfflineModelConfiguration.TINY_CONTEXT_TOKENS,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS,
                ContextPipelineConfiguration.THRESHOLD,
                triggerLine(ContextPipelineConfiguration.THRESHOLD));

        // 测这一次调用到底发生了什么：先清空录制，再说一句话
        tinyModel.reset();
        compactAssistant.chat("compact-demo", "继续");
        List<ChatRequest> recorded = tinyModel.requests();
        // 摘要式这一轮真正下发的条数必须在这里取：下面 ② 会 reset 录制，之后 lastRequest() 就是 null
        int summarizedSize = tinyModel.lastRequest().messages().size();
        demo.ok("① SummarizingCompactor：一次 Agent 运行里模型被调用了 %d 次（1 次压缩 + 1 次正常请求）",
                recorded.size());
        demo.info("   第 1 次就是压缩请求，它注入的系统提示词是：");
        demo.info("   %s", oneLine(recorded.get(0).systemText()));
        demo.info("   压缩后真正下发的消息（注意那条 [本心历史摘要]）：");
        printMessages(demo, tinyModel.lastRequest());

        // ② 滑动窗口：不调用模型，成本可预测
        tinyModel.reset();
        ContextCompactor sliding = new SlidingWindowCompactor(tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);
        List<ChatMessage> bySliding = new DefaultContextManager(sliding, tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("（演示用系统提示词）", history, tinyModel);
        demo.ok("② SlidingWindowCompactor：下发 %d 条，模型被调用 %d 次（纯丢弃，成本为 0）",
                bySliding.size(), tinyModel.count());
        demo.info("   被丢弃的位置会插一条说明：%s", oneLine(bySliding.get(1).text()));
        demo.info("   keepRecent 是**下限**：预算再紧也不会丢到少于它，宽裕时还会多留。"
                + "（1.1.0 时这一格是「只留最后 1 条」，1.1.1 已修复 —— 见 README「插件问题」）");

        // ③ none()：什么都不做
        List<ChatMessage> byNone = new DefaultContextManager(ContextCompactor.none(), tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("（演示用系统提示词）", history, tinyModel);
        demo.ok("③ ContextCompactor.none()：下发 %d 条（= 1 条系统 + %d 条历史），一条不丢",
                byNone.size(), history.size());

        // ④ 自定义压缩器：只按条数硬砍
        List<ChatMessage> byHardLimit = new DefaultContextManager(new HardLimitCompactor(), tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, 4,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("（演示用系统提示词）", history, tinyModel);
        demo.ok("④ 自定义 HardLimitCompactor（只按条数硬砍）：下发 %d 条，占位提示 = %s",
                byHardLimit.size(), oneLine(byHardLimit.get(1).text()));
        demo.info("");
        demo.info("   同一份 %d 条历史，四种策略的下发条数：摘要式 %d / 滑动窗口 %d / none %d / 硬上限 %d",
                history.size(), summarizedSize, bySliding.size(), byNone.size(), byHardLimit.size());
        demo.info("   关键差别是要不要多花一次模型调用：只有摘要式会；另外三种都是常数成本");
        demo.warn("   还有一条容易猜错的：摘要式压缩后仍超预算时会「退化为滑动窗口」，"
                + "而当前插件版本的滑动窗口只留最后 1 条 —— 摘要有可能在第二轮丢弃里被丢掉");
    }

    // ==================================================================
    // ⑦ TokenEstimator
    // ==================================================================

    private void tokenEstimators(Demo demo) {
        demo.section("TokenEstimator —— 估算口径决定压缩阈值，阈值决定花多少钱");
        resetState();

        String sample = DemoText.ascii(900, "estimator");
        demo.info("同一段 900 字符的英文：");
        demo.info("   默认估算（4 字符 1 token）  → %d token", TokenEstimator.DEFAULT.estimate(sample));
        demo.info("   自定义估算（2 字符 1 token）→ %d token", tokenEstimator.estimate(sample));

        // 同一份历史 + 同一个压缩器策略，只有估算器不同
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(ChatMessage.user(DemoText.ascii(900, "msg" + i)));
        }
        String system = "（演示用系统提示词）";
        DefaultContextManager withMine = new DefaultContextManager(
                new SlidingWindowCompactor(tokenEstimator, ContextPipelineConfiguration.THRESHOLD,
                        ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS),
                tokenEstimator, ContextPipelineConfiguration.THRESHOLD,
                ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);
        DefaultContextManager withDefault = new DefaultContextManager(
                new SlidingWindowCompactor(TokenEstimator.DEFAULT, ContextPipelineConfiguration.THRESHOLD,
                        ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS),
                TokenEstimator.DEFAULT, ContextPipelineConfiguration.THRESHOLD,
                ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);

        int line = triggerLine(ContextPipelineConfiguration.THRESHOLD);
        int defaultTokens = workingTokens(TokenEstimator.DEFAULT, system, history);
        int myTokens = workingTokens(tokenEstimator, system, history);
        List<ChatMessage> theirs = withDefault.prepare(system, history, tinyModel);
        List<ChatMessage> mine = withMine.prepare(system, history, tinyModel);

        demo.info("触发线 = (窗口 %d − 预留 %d) × %.2f = %d token",
                OfflineModelConfiguration.TINY_CONTEXT_TOKENS,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS,
                ContextPipelineConfiguration.THRESHOLD, line);
        demo.ok("默认估算：%d token %s %d → %s，下发 %d 条",
                defaultTokens, defaultTokens > line ? ">" : "≤", line,
                defaultTokens > line ? "触发压缩" : "不压缩", theirs.size());
        demo.ok("自定义估算：%d token %s %d → %s，下发 %d 条",
                myTokens, myTokens > line ? ">" : "≤", line,
                myTokens > line ? "触发压缩" : "不压缩", mine.size());
        demo.info("   同一份历史、同一个压缩策略，只有估算器不同 —— 结果就出现了分叉");
        demo.warn("   但默认装配的 SummarizingCompactor 自己 new 的是 TokenEstimator.DEFAULT，"
                + "不会读容器里的这个 bean —— 本示例显式注入，才让两者口径一致");
    }

    /** 系统提示词 + 全部历史（= 压缩前 DefaultContextManager 手里的那份工作列表）的估算值。 */
    private int workingTokens(TokenEstimator which, String system, List<ChatMessage> history) {
        List<ChatMessage> all = new ArrayList<>();
        all.add(ChatMessage.system(system));
        all.addAll(history);
        return which.estimate(all);
    }

    // ==================================================================
    // ⑧ 提示词来源
    // ==================================================================

    private void promptSources(Demo demo) {
        demo.section("SystemPromptProvider —— 默认模板渲染 vs 提示词平台 vs 方法级覆盖");
        resetState();

        // 默认实现：模板插值
        AgentSpec spec = agentFactory.specOf(PromptAssistant.class);
        AgentSpec withPrompt = spec.toBuilder()
                .systemPrompt("你是 {agentName}（Loop={loop}，工具=[{tools}]，日期={today}）。"
                        + "拼错的占位符保留原样：{notDefined}。")
                .toolNames(List.of("read_document", "get_weather"))
                .build();
        TemplateSystemPromptProvider template = new TemplateSystemPromptProvider();
        demo.info("① 默认 TemplateSystemPromptProvider 的渲染规则（{变量} 插值 + 内置变量）：");
        demo.info("   %s", oneLine(template.render(withPrompt.systemPrompt(), withPrompt,
                Map.of("question", "示例变量"))));
        demo.info("   内置变量：agentName / loop / tools / today / cwd / os；@Ctx 与普通参数名也会进变量表");
        demo.info("   取不到的占位符原样保留（{notDefined} 没有被替换成空串），便于一眼看出拼写错误");

        // 平台 provider
        int version = promptPlatform.put("promptAssistant",
                "你是提示词管理平台下发的助手（第 1 版模板，agent={agentName}，loop={loop}）。只回答一句话。");
        demo.info("");
        demo.info("② 自定义 SystemPromptProvider（表驱动提示词平台）：给 Agent [promptAssistant] 写入 v%d",
                version);
        int before = promptPlatform.invocations();
        String answer = promptAssistant.fromProvider("打个招呼");
        demo.ok("   方法上没有 @SystemPrompt → provider 生效，回答：%s", oneLine(answer));
        demo.info("   真正下发的系统提示词：%s", oneLine(mockModel.lastRequest().systemText()));
        demo.ok("   provider 确实被调用了 %d 次（最近一次请求的 Agent = %s）",
                promptPlatform.invocations() - before, promptPlatform.lastAgent());

        // 平台改一行立即生效
        promptPlatform.put("promptAssistant",
                "你是提示词管理平台下发的助手（第 2 版模板，已热更新，agent={agentName}）。只回答一句话。");
        promptAssistant.fromProvider("打个招呼");
        demo.ok("   平台侧改一行（v2）后立刻生效，无需重启：%s",
                oneLine(mockModel.lastRequest().systemText()));

        // 方法级覆盖
        int beforeOverride = promptPlatform.invocations();
        promptAssistant.methodOverride("打个招呼");
        demo.ok("③ 方法上写了 @SystemPrompt(inherit = false) → 直接覆盖 provider，provider 调用次数 +%d",
                promptPlatform.invocations() - beforeOverride);
        demo.info("   实际下发的系统提示词：%s", oneLine(mockModel.lastRequest().systemText()));

        promptAssistant.withAppend("打个招呼");
        demo.info("   @SystemPrompt(value, append) 默认 inherit：方法级 + append 两段用空行拼接 → %s",
                oneLine(mockModel.lastRequest().systemText()));

        promptAssistant.withVars("介绍一下", "严谨");
        demo.info("   方法级提示词里的 {变量} 也会渲染（普通参数名 + @Ctx + 内置变量）→ %s",
                oneLine(mockModel.lastRequest().systemText()));

        promptPlatform.delete("promptAssistant");
        promptAssistant.fromProvider("打个招呼");
        demo.warn("   平台删掉这一行后：promptAssistant 的注解里没有 systemPrompt → 系统提示词%s",
                mockModel.lastRequest().systemText().isEmpty() ? "为空（模型裸奔）" : "非空");
        demo.info("   provider 的回落顺序：平台 → @LlmAgent(systemPrompt) → null");
        demo.warn("   顺带一条边界：方法级 @SystemPrompt 的 {变量} 渲染用的是插件内置的渲染器，"
                + "不是容器里的 provider —— 平台侧的能力（版本、灰度）管不到方法级提示词");
    }

    // ==================================================================
    // ⑨ PromptTemplates
    // ==================================================================

    private void promptTemplates(Demo demo) {
        demo.section("PromptTemplates —— 内置 Loop 的分段提示词，可以被逐段替换");
        demo.info("① 通用模板常量：");
        demo.info("   MINIMAL（DSH 极简）→ %s", oneLine(PromptTemplates.MINIMAL));
        demo.info("   REACT_TEXT_PROTOCOL（不支持原生 tool calling 的模型）→ %s",
                oneLine(PromptTemplates.REACT_TEXT_PROTOCOL));

        demo.info("② claude-code 的分段常量（想改哪段就复制哪段，不必动 Loop 代码）：");
        demo.info("   IDENTITY / TASK_MANAGEMENT / TOOL_USAGE / CODING / SUBAGENT / COMPACTION / ENVIRONMENT");
        demo.info("   CLAUDE_CODE_IDENTITY → %s", oneLine(PromptTemplates.CLAUDE_CODE_IDENTITY));

        demo.info("③ 工厂方法：按「真实存在的工具」决定提示词里说什么");
        List<String> withAll = List.of("read_file", "write_file", "apply_patch", "bash", "todo_write");
        List<String> readOnly = List.of("read_file", "grep_search");
        demo.info("   工具 %s → hasCommandTool=%s, hasWriteTool=%s, hasReadTool=%s, hasTodoTool=%s",
                withAll, PromptTemplates.hasCommandTool(withAll), PromptTemplates.hasWriteTool(withAll),
                PromptTemplates.hasReadTool(withAll), PromptTemplates.hasTodoTool(withAll));
        demo.info("   工具 %s → hasCommandTool=%s, hasWriteTool=%s, 推荐改文件的工具=%s",
                readOnly, PromptTemplates.hasCommandTool(readOnly), PromptTemplates.hasWriteTool(readOnly),
                PromptTemplates.preferredPatchTool(readOnly));
        demo.info("   describeTools(%s) → %s", withAll, PromptTemplates.describeTools(withAll));
        demo.info("   describeTools(空) → %s", PromptTemplates.describeTools(List.of()));

        PromptTemplates.PromptContext ctx = PromptTemplates.PromptContext.of(readOnly, false);
        demo.info("④ claudeCode(PromptContext)（只读工具集）→ %s",
                oneLine(PromptTemplates.claudeCode(ctx)));
        demo.info("⑤ codex(PromptContext, 沙箱写权限, 命令权限, 审批策略) → %s",
                oneLine(PromptTemplates.codex(ctx, false, false, ApprovalPolicy.ON_REQUEST)));
        demo.info("   这两段是「系统提示词兜底」用的：只有 ctx.systemPrompt() 为空时 Loop 才注入");
    }

    // ==================================================================
    // ⑩ 多模态
    // ==================================================================

    private void multimodal(Demo demo) {
        demo.section("多模态消息 —— ChatMessage 与 ContentPart 全谱");
        resetState();

        ImagePart inline = ImagePart.ofBase64("image/png", DEMO_PNG_BASE64);
        ImagePart remote = ImagePart.ofUrl("https://example.com/diagram.png");
        demo.info("① ImagePart 两种来源：");
        demo.info("   ofBase64：isInline=%s，toDataUri=%s", inline.isInline(),
                abbreviate(inline.toDataUri(), 48));
        demo.info("   ofUrl   ：isInline=%s，url=%s", remote.isInline(), remote.url());

        demo.info("② 其余内容块的形态：");
        TextPart text = new TextPart("你好");
        ThinkingPart thinking = new ThinkingPart("（思考）先看图片再回答", null);
        ToolUsePart toolUse = new ToolUsePart("call_1", "read_document", "{\"topic\":\"memory\"}");
        ToolResultPart ok = ContentPart.toolResult("call_1", "read_document", "文档前 20 字…");
        ToolResultPart failed = ContentPart.toolError("call_2", "read_document", "文件不存在");
        demo.info("   TextPart        type=%s asText=%s", text.type(), text.asText());
        demo.info("   ThinkingPart    type=%s text=%s signature=%s", thinking.type(),
                thinking.asText(), thinking.signature());
        demo.info("   ToolUsePart     type=%s asText=%s", toolUse.type(), toolUse.asText());
        demo.info("   ToolResultPart  type=%s error=%s asText=%s", ok.type(), ok.error(), ok.asText());
        demo.info("   ContentPart.toolError 造出来的是 error=%s 的结果（工具失败回灌给模型）",
                failed.error());

        // 真正发一条带图片的消息出去
        List<ContentPart> parts = List.of(new TextPart("看一下这张图"), inline, thinking);
        List<ChatMessage> messages = List.of(
                ChatMessage.user(parts.toArray(new ContentPart[0])),
                ChatMessage.user(remote));
        Agent visionAgent = agentRegistry.get("memoryAssistant").toBuilder().model(visionModel).build();
        visionAgent.call(messages);

        ChatRequest request = visionModel.lastRequest();
        long images = request.messages().stream()
                .flatMap(m -> m.parts().stream())
                .filter(ImagePart.class::isInstance).count();
        demo.ok("③ 真正下发的请求里带着 %d 个 ImagePart（1 个内联 base64 + 1 个外链 URL）", images);
        demo.info("   逐条形态：");
        request.messages().forEach(m -> demo.info("   [%s] parts=%s", m.role(),
                m.parts().stream().map(ContentPart::type).toList()));

        demo.info("");
        demo.info("④ ModelCapabilities.vision：mock=%s，tiny=%s，vision=%s",
                mockModel.capabilities().vision(), tinyModel.capabilities().vision(),
                visionModel.capabilities().vision());
        demo.info("   （ScriptedLlmModel 的 builder 没有 vision 开关，vision 模型的能力位是靠装饰器补的）");
        demo.warn("   本心不做自动降级：无论 vision 是 true 还是 false，消息里的 ImagePart 都原样下发");
        demo.warn("   把图发给不支持视觉的模型，结果是上游报错或模型无视图片 —— 框架不能替你决定，"
                + "要么按 capabilities().vision() 自行判断，要么换视觉模型");
    }

    // ==================================================================
    // ⑪ 上下文与记忆的边界
    // ==================================================================

    private void boundary(Demo demo) {
        demo.section("上下文与记忆的边界 —— ContextManager 不改历史，Loop 才会");
        resetState();

        for (int i = 0; i < 6; i++) {
            memoryAssistant.chat("boundary-demo", i % 2 == 0 ? "你好" : "第二轮");
        }
        Agent agent = agentRegistry.get("memoryAssistant");
        demo.ok("① ContextManager 只在组装请求时生效：记忆里 %d 条 → 本次只下发 %d 条",
                agent.history("boundary-demo").size(), mockModel.lastRequest().messages().size());
        demo.info("   （窗口 %d 条 + 1 条系统提示词，其余仍在 MemoryStore 里，下一轮还会被装载）",
                exp06ContextManager.historyWindow());
        demo.warn("   所以压缩在这一层不会让历史收敛：长任务里历史与用量仍会一直涨");

        // claude-code：压缩发生在 Loop 层，改的是历史本身
        Agent claudeCode = agentRegistry.get("claudeCodeAssistant").toBuilder()
                .contextManager(new DefaultContextManager(ContextCompactor.none(), TokenEstimator.DEFAULT,
                        ContextPipelineConfiguration.THRESHOLD, ContextPipelineConfiguration.KEEP_RECENT,
                        ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS))
                .build();
        longDocTools.reset();
        AgentResult result = claudeCode.call("#tool:read_document {} 请读取文档并汇总", "cc-demo");
        List<ChatMessage> stored = claudeCode.history("cc-demo");
        long toolMessages = stored.stream().filter(m -> m.role() == com.benxin.llm.core.message.Role.TOOL)
                .count();
        long compacted = stored.stream().filter(m -> m.text().contains("…[已压缩]")).count();
        demo.ok("② claude-code Loop 运行 %d 步，工具被调用 %d 次，历史 %d 条",
                result.steps(), longDocTools.callCount(), stored.size());
        demo.ok("   AgentResult.messages() = %d 条，Agent.history() = %d 条 —— 长度一样"
                        + "（就地改写，不是丢消息）",
                result.messages().size(), stored.size());
        demo.ok("   其中 %d 条较早的工具结果已被替换成占位摘要（条数不变、体积变小）", compacted);
        stored.stream().filter(m -> m.text().contains("…[已压缩]")).findFirst()
                .ifPresent(m -> demo.info("   压缩后的工具结果尾部：…%s",
                        abbreviate(m.text().substring(Math.max(0, m.text().length() - 80)), 80)));
        demo.info("   体积口径：%d 条工具结果 × %d 字 ≈ %d 字符 → 压缩后整份历史估算 %d token",
                toolMessages, LongDocTools.DOC_CHARS, toolMessages * LongDocTools.DOC_CHARS,
                TokenEstimator.DEFAULT.estimate(stored));
        demo.info("   两层压缩的分工：ContextManager 改「下发的副本」（可逆），Loop 改「历史本身」（不可逆）");
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /**
     * 每一节演示前把状态清干净。
     *
     * <p>三个容易踩的点：</p>
     * <ol>
     *   <li><b>H2 是文件库，会跨进程留数据</b>：不清表的话第二次 {@code java -jar} 读到的就是
     *       上一轮的历史，"历史 8 条""下发 3 条"这类结论会整体翻倍，看起来像是框架行为变了。
     *       提示词平台同理（版本号会从 v2 开始累加）。</li>
     *   <li>Agent 默认开着记忆，且不传 sessionId 时都落在 {@code <agentName>-default} 这个会话上，
     *       所以要把每个 Agent 的默认会话也清一遍；注册表里是惰性条目，
     *       必须走 {@code names() + find()}（{@code resolved()} 只会列出已经解析过的，遍历一个都清不掉）。</li>
     *   <li>上一节的对话会进入下一节的消息体，打印出来的"这一轮到底发了什么"就不好读了。</li>
     * </ol>
     */
    private void resetState() {
        mockModel.reset();
        tinyModel.reset();
        visionModel.reset();
        longDocTools.reset();
        exp06ContextManager.resetStats();
        promptPlatform.resetStats();
        // 文件库 + 提示词表整体清空：让每一节都从"零历史"开始，输出才可复现
        jdbcStore.clearAll();
        promptPlatform.clear();
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            agent.clearHistory("s-1");
            agent.clearHistory("s-2");
            agent.clearHistory("s-3");
        }));
    }

    private void printMessages(Demo demo, ChatRequest request) {
        if (request == null) {
            demo.warn("没有录制到请求");
            return;
        }
        request.messages().forEach(m -> demo.info("   [%s] %s", m.role(), oneLine(m.text())));
    }

    /**
     * 真正下发的"对话"消息数：<b>不含</b>系统提示词。
     *
     * <p>数消息条数时把系统提示词一起算进去，是读这类演示输出最常见的误会 ——
     * 本示例的 {@code ContextManager} 每轮都会注入一条带来源标记的系统提示词，
     * 于是"这一轮只发了 1 条"实际会打印成 2 条。这里统一按对话口径数。</p>
     */
    private static int conversationSize(ChatRequest request) {
        if (request == null) {
            return 0;
        }
        return (int) request.messages().stream()
                .filter(m -> m.role() != com.benxin.llm.core.message.Role.SYSTEM)
                .count();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 140 ? flat : flat.substring(0, 140) + "...";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
