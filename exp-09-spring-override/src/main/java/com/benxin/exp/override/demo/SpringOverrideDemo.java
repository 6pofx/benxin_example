package com.benxin.exp.override.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.exp.override.agent.SupportAssistant;
import com.benxin.exp.override.agent.ToolAssistant;
import com.benxin.exp.override.component.DemoAgentRegistry;
import com.benxin.exp.override.component.DemoLlmAgentFactory;
import com.benxin.exp.override.component.DemoLoopRegistry;
import com.benxin.exp.override.component.DemoModelRegistry;
import com.benxin.exp.override.component.DoublingTokenEstimator;
import com.benxin.exp.override.component.OrderFollowerInterceptor;
import com.benxin.exp.override.component.PlatformSystemPromptProvider;
import com.benxin.exp.override.component.RecordingAgentListener;
import com.benxin.exp.override.component.RecordingContextManager;
import com.benxin.exp.override.component.RecordingMemoryStore;
import com.benxin.exp.override.component.RecordingNoopCompactor;
import com.benxin.exp.override.component.StubTransport;
import com.benxin.exp.override.component.ToggleApprovalHandler;
import com.benxin.exp.override.component.ToggleToolSandbox;
import com.benxin.exp.override.component.TraceInterceptor;
import com.benxin.exp.override.override.OverridePoint;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.spring.LlmProperties;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即跑的演示：把「装配与覆盖」这件事打到控制台上。
 *
 * <p>演示与测试的分工在这里很明确：</p>
 * <ul>
 *   <li><b>演示</b>负责"看得见" —— 覆盖前的默认实现、覆盖后的实际 bean、
 *       以及一次真实调用留下的证据；</li>
 *   <li><b>测试</b>负责"钉得住" —— 同样这些结论被写成可回归的断言
 *       （{@code SpringOverrideTest} 等 7 个测试类）。</li>
 * </ul>
 *
 * <p>哪些能力故意<b>不</b>在这里演示：{@code llm.enabled=false}、
 * {@code @EnableLlmAgents} 的三种扫描范围、{@code @ConditionalOnMissingBean} 的有/无对照。
 * 它们都需要<b>另起一个容器</b>才能证明，硬塞进同一个上下文只会退化成"复述结论"。
 * 那三块全部在测试里用 {@code ApplicationContextRunner} 做了真实对照实验。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpringOverrideDemo implements ApplicationRunner {

    private final ApplicationContext context;
    private final ConfigurableListableBeanFactory beanFactory;
    private final LlmProperties properties;

    private final StubTransport transport;
    private final DemoModelRegistry modelRegistry;
    private final DemoLoopRegistry loopRegistry;
    private final DoublingTokenEstimator tokenEstimator;
    private final RecordingNoopCompactor compactor;
    private final RecordingContextManager contextManager;
    private final RecordingMemoryStore memoryStore;
    private final PlatformSystemPromptProvider promptProvider;
    private final ToggleApprovalHandler approvalHandler;
    private final ToggleToolSandbox sandbox;
    private final RecordingAgentListener listener;
    private final DemoAgentRegistry agentRegistry;
    private final DemoLlmAgentFactory agentFactory;
    private final TraceInterceptor traceInterceptor;
    private final OrderFollowerInterceptor orderInterceptor;
    private final List<AgentInterceptor> interceptorList;

    private final SupportAssistant support;
    private final ToolAssistant toolAssistant;
    private final RecordingModel stubModel;
    private final CommonTools commonTools;

    private StubHttpServer stubServer;

    public SpringOverrideDemo(ApplicationContext context,
                              ConfigurableListableBeanFactory beanFactory,
                              LlmProperties properties,
                              StubTransport stubTransport,
                              DemoModelRegistry llmModelRegistry,
                              DemoLoopRegistry llmLoopRegistry,
                              DoublingTokenEstimator doublingTokenEstimator,
                              RecordingNoopCompactor recordingCompactor,
                              RecordingContextManager recordingContextManager,
                              RecordingMemoryStore recordingMemoryStore,
                              PlatformSystemPromptProvider platformSystemPromptProvider,
                              ToggleApprovalHandler toggleApprovalHandler,
                              ToggleToolSandbox toggleToolSandbox,
                              RecordingAgentListener recordingAgentListener,
                              DemoAgentRegistry llmAgentRegistry,
                              DemoLlmAgentFactory llmAgentFactory,
                              TraceInterceptor traceInterceptor,
                              OrderFollowerInterceptor orderFollowerInterceptor,
                              List<AgentInterceptor> llmInterceptors,
                              SupportAssistant supportAssistant,
                              ToolAssistant toolAssistant,
                              RecordingModel stubLlmModel,
                              CommonTools commonTools) {
        this.context = context;
        this.beanFactory = beanFactory;
        this.properties = properties;
        this.transport = stubTransport;
        this.modelRegistry = llmModelRegistry;
        this.loopRegistry = llmLoopRegistry;
        this.tokenEstimator = doublingTokenEstimator;
        this.compactor = recordingCompactor;
        this.contextManager = recordingContextManager;
        this.memoryStore = recordingMemoryStore;
        this.promptProvider = platformSystemPromptProvider;
        this.approvalHandler = toggleApprovalHandler;
        this.sandbox = toggleToolSandbox;
        this.listener = recordingAgentListener;
        this.agentRegistry = llmAgentRegistry;
        this.agentFactory = llmAgentFactory;
        this.traceInterceptor = traceInterceptor;
        this.orderInterceptor = orderFollowerInterceptor;
        this.interceptorList = llmInterceptors;
        this.support = supportAssistant;
        this.toolAssistant = toolAssistant;
        this.stubModel = stubLlmModel;
        this.commonTools = commonTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 09 · 装配与覆盖 —— 「一切皆可插拔」在 Spring 层面怎么落地");
        demo.banner("bean 定义总数 = %d", beanFactory.getBeanDefinitionCount());

        demo.guard("覆盖清单：覆盖前 / 覆盖后", () -> overrideTable(demo));
        demo.guard("一次真实调用能看见几个切面", () -> liveEvidence(demo));
        demo.guard("AgentRegistry 的惰性语义", () -> lazySemantics(demo));
        demo.guard("yml → LlmProperties 的全字段对照", () -> propertiesDump(demo));
        demo.guard("程序化装配（不经容器）", () -> programmaticAssembly(demo));

        demo.done();
        closeQuietly();
    }

    // ------------------------------------------------------------------
    // ① 覆盖清单
    // ------------------------------------------------------------------

    /**
     * 14 行对照表 —— 本模块的核心输出。
     *
     * <p>"覆盖前"那一列不是硬编码的期望值，而是从 {@code BeanDefinitionRegistry} 里
     * 真读出来的：用户 bean 让自动配置让位，但那些 {@code BeanDefinition} 仍然躺在注册表里
     * （只是 {@code @ConditionalOnMissingBean} 让它们没被实例化）。读它的好处是：
     * 这张表会跟着插件版本自动更新，而不是随示例一起腐烂。</p>
     *
     * <p>{@link #renderOverrideTable()} 是同一份数据的不打印版本，测试直接断它。</p>
     */
    private void overrideTable(Demo demo) {
        demo.section("14 个可替换切面 —— 覆盖前 / 覆盖后");
        for (OverridePoint point : OverridePoint.values()) {
            demo.info("%-22s %-12s %s  →  %s", point.beanName(), point.type(),
                    defaultImplementationOf(point), visibleClass(point.myBeanName()));
        }
        demo.info("");
        for (OverridePoint point : OverridePoint.values()) {
            demo.info("· %-20s %s", point.type(), point.overrideEffect());
        }
        demo.ok("以上 14 行没有一行用了开关、@Primary 或 exclude —— 机制只有 @ConditionalOnMissingBean 一条。");
        demo.warn(OverridePoint.INFRASTRUCTURE_NOTE);
    }

    /**
     * 与 {@link #overrideTable(Demo)} 同一份数据，但返回字符串、不打印 —— 供测试使用。
     *
     * <p>这样"覆盖前 / 覆盖后"这张表在演示与测试里是<b>同一份实现</b>，
     * 不会出现"演示里那么写、测试里另写一遍"的漂移。</p>
     */
    public String renderOverrideTable() {
        StringBuilder sb = new StringBuilder();
        for (OverridePoint point : OverridePoint.values()) {
            sb.append(String.format("%-22s %-12s %s  →  %s%n", point.beanName(), point.type(),
                    defaultImplementationOf(point), visibleClass(point.myBeanName())));
        }
        return sb.toString();
    }

    /**
     * "覆盖前"那一列：优先从 bean 定义里读真实类型，读不到就退回切面清单里登记的默认实现。
     *
     * <p><b>为什么会读不到</b>（这一条是实测出来的，值得记一笔）：
     * {@code @ConditionalOnMissingBean} 是在<b>自动配置处理阶段</b>判定的，
     * 条件不成立时那个 {@code @Bean} 方法连<b>定义</b>都不会进注册表 ——
     * 所以"把默认实现从注册表里读出来"这条思路，恰恰在"被成功覆盖"的切面上失效。
     * 能读到的那几行反而是用户<b>照抄了同名 bean 名</b>的（读到的还是用户自己那份）。</p>
     *
     * <p>正确的做法就是退回 {@link OverridePoint#defaultImplementation()}：
     * 它本来就登记着每个切面的默认实现，才是"覆盖前"这个语义的事实来源。
     * 两条来源叠起来，这张表才既有权威值、又能在用户沿用同名 bean 时显示真实注册名。</p>
     */
    private String defaultImplementationOf(OverridePoint point) {
        String fromRegistry = visibleClass(point.beanName());
        if (fromRegistry != null && !fromRegistry.startsWith("（")) {
            return "（同名 bean，见右）" + fromRegistry;
        }
        return point.defaultImplementation();
    }

    /** 从注册表里读某个 bean 定义的实际类型；读不到就明确写"（本示例未启用）"。 */
    public String visibleClass(String beanName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return "（本示例未启用）";
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        String className = definition.getBeanClassName();
        if (className == null && definition.getFactoryMethodName() != null) {
            // @Bean 方法定义的 bean：真实类型藏在工厂方法返回值上，注册表里只有方法名。
            // 退化打印"@Bean 方法名()"，比打印 null 有用。
            return "@Bean " + definition.getFactoryMethodName() + "()";
        }
        return shortName(className);
    }

    // ------------------------------------------------------------------
    // ② 一次真实调用
    // ------------------------------------------------------------------

    /**
     * 一次 {@code toolAssistant.ask("北京天气怎么样？")} 会同时穿过八个切面。
     * 这一节把每个切面留下的痕迹打出来 —— "可插拔"因此不是一张表，而是可观测的行为。
     */
    private void liveEvidence(Demo demo) {
        demo.section("一次真实调用留下的证据（同时穿过多个切面）");

        startStubServerIfPossible(demo);
        resetEvidence();

        String answer = toolAssistant.ask("北京天气怎么样？");
        demo.ok("回答：%s", oneLine(answer));

        demo.info("① HttpTransport（换过）：%s", transport.calls());
        demo.info("② ModelRegistry（换过）：%s", modelRegistry.describe());
        demo.info("③ ContextManager（换过）：prepare %d 次 / afterStep %d 次；%s",
                contextManager.prepares().size(), contextManager.afterSteps().size(),
                contextManager.prepares().isEmpty() ? "" : contextManager.prepares().get(0));
        demo.info("④ TokenEstimator（换过）：被调用 %d 次（%d token/字符）",
                tokenEstimator.invocations(), DoublingTokenEstimator.PER_CHAR);
        demo.info("⑤ MemoryStore（换过）：%s", memoryStore.operations());
        demo.info("⑥ SystemPromptProvider（换过）：被问到 %s → 模型看到的系统提示词 = %s",
                promptProvider.asked(), oneLine(scripted().lastRequest() == null
                        ? null : scripted().lastRequest().systemText()));
        demo.info("⑦ List<AgentInterceptor>（换过）：TraceInterceptor %s，OrderFollowerInterceptor %s",
                traceInterceptor.beforeModelCalls(), orderInterceptor.observedTraces());
        demo.info("⑧ AgentListener（换过）：%s", listener.events());
        demo.info("⑨ LlmAgentFactory（换过）：已装配 %s", agentFactory.builtAgents());
        demo.info("   工具来源 CommonTools 被调用 %d 次；本模块的 LoopRegistry：%s",
                commonTools.callCount(), loopRegistry.describe());
        demo.ok("ToolSandbox / ApprovalHandler 这次没被触发 —— 它们是「用得上才走到」的切面，"
                + "下面两段分别把它们的开关合上，验证它们真的拦得住。");

        demo.info("");
        demo.info("沙箱闸门合上之后（同一个 Agent，同一次问法）：");
        sandbox.setPermissive(false);
        try {
            AgentResult denied = agentRegistry.get("toolAssistant").call("北京天气怎么样？", "denied-session");
            demo.info("   工具调用记录：%s", denied.toolCalls().stream()
                    .map(tc -> tc.name() + "(error=" + tc.error() + ", " + oneLine(tc.result()) + ")")
                    .toList());
            demo.info("   沙箱检查流水：%s", sandbox.checks());
            demo.ok("换了 ToolSandbox，工具在真正执行之前就被短路了。");
        } finally {
            sandbox.setPermissive(true);
        }

        demo.info("");
        demo.info("审批 handler 置为拒绝之后（ApprovalPolicy.ALWAYS 时每个工具调用都要问）：");
        approvalHandler.setApproved(false);
        try {
            agentRegistry.get("toolAssistant").toBuilder()
                    .approvalPolicy(ApprovalPolicy.ALWAYS)
                    .build()
                    .call("北京天气怎么样？", "approval-session");
            demo.ok("换了 ApprovalHandler 并置为拒绝：审批被问了 %d 次 → %s",
                    approvalHandler.count(), approvalHandler.requests());
        } finally {
            approvalHandler.setApproved(true);
        }

        demo.info("");
        demo.info("ContextCompactor（换过）：本示例的 ContextManager 语义是「永不压缩」，"
                + "所以它一次都没被调用（%d 次）—— 这也是可观测的。", compactor.count());
        demo.info("换过的拦截器链共 %d 个：%s", interceptorList.size(), interceptorList);
    }

    private void startStubServerIfPossible(Demo demo) {
        try {
            stubServer = StubHttpServer.start();
            stubServer.onPrefix("/v1/chat/completions", request -> StubHttpServer.Reply.json(200,
                    "{\"id\":\"exp09-stub\",\"object\":\"chat.completion\",\"model\":\"stub-gpt\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"（桩服务）已收到请求\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
            injectEndpoint(stubServer.baseUrl());
            demo.banner("StubHttpServer 已启动：%s（离线、零 token）", stubServer.baseUrl());
        } catch (RuntimeException e) {
            demo.warn("桩服务启动失败，本次跳过 HTTP 证据：%s", e.toString());
        }
    }

    /** 把 {@code stub} 模型的端点指向桩服务 —— 只在演示进程里做（测试里由测试自己装）。 */
    private void injectEndpoint(String baseUrl) {
        LlmModel raw = stubModel.unwrap();
        if (raw == null) {
            return;
        }
        // 反射在这里是刻意的"最小侵入"：HttpBackedModel 是示例自己的实现细节，
        // 为了演示去扩大它的可见性不值得。
        // 两个细节：① 运行时类型是子类 StubDemoModel，方法在父类上，所以必须沿类层次找；
        //           ② 这一步同时把模型从"纯内存"切到"真的发 HTTP"，是演示里 HTTP 证据的来源。
        try {
            java.lang.reflect.Method setter = null;
            for (Class<?> type = raw.getClass(); type != null && setter == null; type = type.getSuperclass()) {
                try {
                    setter = type.getDeclaredMethod("setBaseUrl", String.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (setter == null) {
                return;
            }
            setter.setAccessible(true);
            setter.invoke(raw, baseUrl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法把 stub 模型指向桩服务", e);
        }
    }

    private void resetEvidence() {
        transport.reset();
        tokenEstimator.reset();
        contextManager.reset();
        memoryStore.reset();
        promptProvider.reset();
        listener.reset();
        traceInterceptor.reset();
        orderInterceptor.reset();
        approvalHandler.reset();
        sandbox.reset();
        compactor.reset();
        commonTools.reset();
        // 会话记忆开着：不传 sessionId 时都落在 "<agentName>-default" 上，
        // 每次演示前清一次，免得上一节的对话进入下一节。
        // 必须走 names() + find()：resolved() 看不到惰性条目（这正是下一节要讲的语义）。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    // ------------------------------------------------------------------
    // ③ AgentRegistry 的惰性语义
    // ------------------------------------------------------------------

    private void lazySemantics(Demo demo) {
        demo.section("AgentRegistry 的惰性语义 —— names() / resolved() / find() 的差别");
        demo.info("names()          = %s", agentRegistry.names());
        demo.info("resolved()       = %s", agentRegistry.resolved().keySet());
        demo.info("registerLazy 次数 = %d（= 被登记的 @LlmAgent 接口个数）", agentRegistry.lazyRegistrations());

        // 走到这里时，两个 Agent 已经被上面那次调用解析过了，所以 resolved() 里有两个条目。
        // 想看"全新上下文里 resolved() 是空的"这个对照，见 SpringOverrideTest 里的惰性用例：
        // 它在一个刚启动、还没人调用过的上下文里断言了这一点。
        demo.ok("口径：想遍历全部 Agent 请用 names() + find()；resolved() 只列出已经解析成真身的那些。");
        demo.info("%s", agentRegistry.describe());
    }

    // ------------------------------------------------------------------
    // ④ yml → LlmProperties 全字段对照
    // ------------------------------------------------------------------

    private void propertiesDump(Demo demo) {
        demo.section("yml → LlmProperties 的全字段对照（application.yml 把每个字段都写了一遍）");
        LlmPropertiesPrinter.print(demo, properties);
    }

    /** 与 {@link #propertiesDump(Demo)} 同一份文本，供测试断言 —— 见 {@link LlmPropertiesPrinter#render}。 */
    public String renderProperties() {
        return LlmPropertiesPrinter.render(properties);
    }

    // ------------------------------------------------------------------
    // ⑤ 程序化装配
    // ------------------------------------------------------------------

    private void programmaticAssembly(Demo demo) {
        demo.section("程序化装配 —— 不写注解、不写 yml，直接 Agent.builder()");
        Agent agent = Agent.builder()
                .name("exp09-programmatic")
                .model(properties.getDefaultModel())
                .modelRegistry(modelRegistry)
                .loop(properties.getAgent().getDefaultLoop())
                .loopRegistry(loopRegistry)
                .systemPrompt("你是 exp-09 的程序化装配演示。")
                .maxSteps(3)
                .hardMaxSteps(2)
                .memory(true)
                .temperature(0.5)
                .maxTokens(64)
                .metadata("scene", "exp-09")
                .build();
        demo.info("装配结果：%s", agent);
        demo.ok("maxSteps(3) + hardMaxSteps(2) → spec.maxSteps() = %d（防呆硬上限在装配层夹取）",
                agent.spec().maxSteps());
        demo.info("Agent.ask(...) 是纯模型调用，不经过 Loop，所以 memory / 工具都不参与：%s",
                oneLine(agent.ask("你好").text()));

        AgentSpec spec = AgentSpec.builder()
                .name("exp09-spec-only")
                .model("stub")
                .loop(properties.getAgent().getDefaultLoop())
                .maxSteps(5)
                .metadata("from", "AgentSpec.builder()")
                .build();
        Agent fromSpec = Agent.builder().spec(spec).modelRegistry(modelRegistry).loopRegistry(loopRegistry).build();
        demo.info("AgentSpec.builder()...build() → Agent.builder().spec(spec) → %s（loop=%s，model=%s，maxSteps=%d）",
                fromSpec.name(), fromSpec.loop().name(), fromSpec.model().name(), fromSpec.spec().maxSteps());

        Agent copy = agent.toBuilder().name("exp09-copy").maxSteps(1).build();
        demo.ok("toBuilder 是隔离的：副本 name=%s / 原名 name=%s / 原 maxSteps=%d（未被副本改动）",
                copy.name(), agent.name(), agent.spec().maxSteps());

        String thrown = null;
        try {
            AgentSpec.builder().maxSteps(0);
        } catch (IllegalArgumentException e) {
            thrown = e.getMessage();
        }
        demo.ok("AgentSpec.Builder.maxSteps(0) 直接报错（v1.1.0 的行为变更）：%s", oneLine(thrown));
        demo.info("程序化装配的 hardMaxSteps 默认不夹取（0）：%s",
                Agent.builder().maxSteps(99).hardMaxSteps() == 0 ? "Agent.builder().hardMaxSteps() == 0" : "（意外）");
    }

    // ------------------------------------------------------------------

    private com.benxin.exp.common.model.ScriptedLlmModel scripted() {
        // 包了两层装饰器，所以要走两步 unwrap：
        //   RecordingModel（记录请求） → StubDemoModel（HttpBackedModel，脚本决策 + 可选 HTTP）
        //                              → ScriptedLlmModel（真正的脚本决策源）
        // 只 unwrap 一次的话拿到的是 StubDemoModel，强转会抛 ClassCastException —— 而且
        // 这个异常发生在"演示"里，会被 Demo.guard 记成一次失败而不中断，容易看漏。
        com.benxin.llm.core.model.LlmModel inner = stubModel.unwrap();
        if (inner instanceof com.benxin.exp.common.model.ScriptedLlmModel direct) {
            return direct;
        }
        // 但 HttpBackedModel 自己没有 unwrap() —— 它是示例自己写的"决策 + 传输"缝合层，
        // 不是一个装饰器。所以这里用它的只读访问器 scripted() 取最里面那层。
        // （反射取私有字段也能做到，但那东西编译器不会帮你发现失效，能不用就不用。）
        if (inner instanceof com.benxin.exp.override.override.DefaultOverrideConfiguration.HttpBackedModel http) {
            return http.scripted();
        }
        throw new IllegalStateException("stub 模型的装饰链不是预期的形状：" + inner);
    }

    private void closeQuietly() {
        if (stubServer != null) {
            stubServer.close();
        }
    }

    private static String shortName(String className) {
        if (className == null) {
            return "(null)";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 140 ? flat : flat.substring(0, 140) + "...";
    }

    /** 只是为了让 {@code context} 的引用不被 IDE 当成未使用（诊断时它是最好用的入口）。 */
    ApplicationContext context() {
        return context;
    }
}
