package com.benxin.exp.override;

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
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.LoggingListener;
import com.benxin.llm.core.loop.DshMinimalLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmInterceptorChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 14 个切面逐个覆盖 —— 每个切面一个测试方法，方法名点明它测的是哪一条。
 *
 * <p>断言的口径统一成三条：</p>
 * <ol>
 *   <li><b>类型</b>：容器里那个 bean 的类型是我的自定义实现（不是默认实现）；</li>
 *   <li><b>唯一</b>：那一类 bean 在整个容器里只有一个 —— 也就是"顶掉"，不是"并存"；</li>
 *   <li><b>生效</b>：跑一次真实调用，我的实现留下了可观测的痕迹。</li>
 * </ol>
 *
 * <p>第 3 条是重点。前两条只能证明"我的 bean 进了容器"，
 * 只有第 3 条能证明"插拔真的兑现到了调用方"。</p>
 *
 * <p>全部离线、零 token：默认模型是 {@code exp-common} 的脚本化模型
 * （本示例把它套在 {@code HttpBackedModel} 里，好让自定义传输层有东西可发），
 * 需要真 HTTP 时用 {@link StubHttpServer} 起一个回环地址上的桩服务。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@DisplayName("示例 09 · 装配与覆盖（14 个切面逐个顶掉）")
class SpringOverrideTest {

    @Autowired
    private StubTransport transport;

    @Autowired
    private StubTransport stubTransport;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private DemoModelRegistry demoModelRegistry;

    @Autowired
    private LoopRegistry loopRegistry;

    @Autowired
    private DemoLoopRegistry demoLoopRegistry;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private DoublingTokenEstimator doublingTokenEstimator;

    @Autowired
    private ContextCompactor compactor;

    @Autowired
    private RecordingNoopCompactor recordingCompactor;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private RecordingContextManager recordingContextManager;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private RecordingMemoryStore recordingMemoryStore;

    @Autowired
    private SystemPromptProvider systemPromptProvider;

    @Autowired
    private PlatformSystemPromptProvider platformSystemPromptProvider;

    @Autowired
    private ApprovalHandler approvalHandler;

    @Autowired
    private ToggleApprovalHandler toggleApprovalHandler;

    @Autowired
    private ToolSandbox sandbox;

    @Autowired
    private ToggleToolSandbox toggleToolSandbox;

    @Autowired
    private AgentListener listener;

    @Autowired
    private RecordingAgentListener recordingAgentListener;

    @Autowired
    private List<AgentInterceptor> interceptors;

    @Autowired
    private TraceInterceptor traceInterceptor;

    @Autowired
    private OrderFollowerInterceptor orderFollowerInterceptor;

    @Autowired
    private LlmInterceptorChain interceptorChain;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private DemoAgentRegistry demoAgentRegistry;

    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private DemoLlmAgentFactory demoAgentFactory;

    @Autowired
    private RecordingModel stubModel;

    @Autowired
    private CommonTools commonTools;

    @Autowired
    private SupportAssistant support;

    @Autowired
    private ToolAssistant toolAssistant;

    @BeforeEach
    void reset() {
        stubModel.reset();
        commonTools.reset();
        stubTransport.reset();
        doublingTokenEstimator.reset();
        recordingCompactor.reset();
        recordingContextManager.reset();
        recordingMemoryStore.reset();
        platformSystemPromptProvider.reset();
        toggleApprovalHandler.reset();
        toggleApprovalHandler.setApproved(true);
        toggleToolSandbox.reset();
        toggleToolSandbox.setPermissive(true);
        recordingAgentListener.reset();
        traceInterceptor.reset();
        orderFollowerInterceptor.reset();

        // 把 stub 模型退回"纯内存"：上面的 httpTransportFacetActuallyCarriesTraffic 会把
        // 它指向一个临时桩服务，那个服务在 try-with-resources 里已经被关掉了。
        // 桩模型是单例 bean，端点是它身上的一个字段 —— 不清掉的话，后面的用例
        // 会一直去连一个已经关闭的端口，报"流式请求 http://127.0.0.1:xxxx 失败"。
        pointStubModelAt(null);

        // 声明式 Agent 默认开着记忆，不传 sessionId 时都落在 "<agentName>-default" 上。
        // 测试之间共享同一个 ApplicationContext，所以必须显式清空历史。
        // 必须走 names() + find()：resolved() 看不到惰性条目，别用它。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    // ==================================================================
    // 1 / 14 · HttpTransport
    // ==================================================================

    @Test
    @DisplayName("切面 1：HttpTransport —— 容器里生效的是我的 StubTransport，而不是 JdkHttpTransport")
    void httpTransportFacetIsOverridden() {
        assertThat(transport).isInstanceOf(StubTransport.class);
        assertThat(transport).isSameAs(stubTransport);
        assertThat((Object) transport).isNotInstanceOf(JdkHttpTransport.class);
    }

    @Test
    @DisplayName("切面 1（生效）：所有 HTTP 模型都改走我的传输层 —— 请求真的发到了 StubHttpServer")
    void httpTransportFacetActuallyCarriesTraffic() {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.onPrefix("/v1/chat/completions", request -> StubHttpServer.Reply.json(200,
                    "{\"id\":\"exp09\",\"object\":\"chat.completion\",\"model\":\"stub-gpt\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"（桩服务）收到\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
            pointStubModelAt(server.baseUrl());

            String answer = toolAssistant.ask("你好");

            assertThat(answer).isNotBlank();
            assertThat(stubTransport.count()).isEqualTo(1);
            // ⚠️ 前缀是 POST(stream) 而不是 POST：声明式路径恒为流式
            // （DefaultLoopContext 看的是 capabilities().streaming()，不是 AgentSpec.stream()），
            // 所以自定义传输层被调用的是 postStreaming —— StubTransport 会给它加上这个标记。
            // 这一条也顺带证明"自定义 HttpTransport 的流式方法真的被用上了"。
            assertThat(stubTransport.lastCall())
                    .isEqualTo("POST(stream) " + server.baseUrl() + "/v1/chat/completions");
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.lastRequest().orElseThrow().header("Authorization"))
                    .isEqualTo("Bearer exp-09-demo");
        }
    }

    // ==================================================================
    // 3 / 14 · ModelRegistry
    // ==================================================================

    @Test
    @DisplayName("切面 3：ModelRegistry —— 生效的是我的 DemoModelRegistry，yml 与 bean 两条路径都进了它")
    void modelRegistryFacetIsOverridden() {
        assertThat(modelRegistry).isInstanceOf(DemoModelRegistry.class);
        assertThat(modelRegistry).isSameAs(demoModelRegistry);
        assertThat(demoModelRegistry.marker()).isEqualTo("DemoModelRegistry");

        // yml 里的 llm.models.mock 进来了（MockModelRegistry.seed 把配置翻译成模型）。
        assertThat(modelRegistry.names()).contains("mock", "stub");
        // 默认模型取配置里的 stub（不是 mock）：mock 的 base-url 是一个刻意不可解析的
        // 假域名，而声明式路径恒为流式，让它当默认会让每一次调用都先等一次连接超时。
        assertThat(modelRegistry.defaultName()).isEqualTo("stub");
        // 默认模型最终被 LlmAgentFactory 用上：supportAssistant 的模型就是 stub。
        assertThat(agentRegistry.get("supportAssistant").model().name()).isEqualTo("stub");
        // 而 ToolAssistant 是显式写 model = "stub" 的那个 —— 同一份实现，两条解析路径。
        assertThat(agentRegistry.get("toolAssistant").model().name()).isEqualTo("stub");
    }

    // ==================================================================
    // 4 / 14 · LoopRegistry
    // ==================================================================

    @Test
    @DisplayName("切面 4：LoopRegistry —— 生效的是我的 DemoLoopRegistry，内置 Loop 与 llm.workflows.* 都在里面")
    void loopRegistryFacetIsOverridden() {
        assertThat(loopRegistry).isInstanceOf(DemoLoopRegistry.class);
        assertThat(loopRegistry).isSameAs(demoLoopRegistry);

        assertThat(loopRegistry.names())
                .contains("dsh-minimal", "react", "claude-code", "codex", "plan-execute", "reflexion",
                        "workflow", "staged")
                .contains("exp-demo-step", "workflow:quick-fix", "workflow:code-review");
        assertThat(loopRegistry.defaultName()).isEqualTo("dsh-minimal");
        assertThat(loopRegistry.get("exp-demo-step")).isInstanceOf(com.benxin.exp.override.component.DemoAgentLoop.class);
        assertThat(agentRegistry.get("supportAssistant").loop()).isInstanceOf(DshMinimalLoop.class);
    }

    // ==================================================================
    // 5 / 14 · TokenEstimator
    // ==================================================================

    @Test
    @DisplayName("切面 5：TokenEstimator —— 生效的是我的 DoublingTokenEstimator，上下文管理用它来估算")
    void tokenEstimatorFacetIsOverridden() {
        assertThat(tokenEstimator).isInstanceOf(DoublingTokenEstimator.class);
        assertThat(tokenEstimator).isSameAs(doublingTokenEstimator);
        assertThat(doublingTokenEstimator.estimate("abcd")).isEqualTo(4 * DoublingTokenEstimator.PER_CHAR);

        // 真正的证据在下面：ContextManager（也是我换的）在 prepare 里调的就是它。
        assertThat(recordingContextManager.estimator()).isSameAs(doublingTokenEstimator);
    }

    // ==================================================================
    // 6 / 14 · ContextCompactor
    // ==================================================================

    @Test
    @DisplayName("切面 6：ContextCompactor —— 生效的是我的 RecordingNoopCompactor，而不是 SummarizingCompactor")
    void contextCompactorFacetIsOverridden() {
        assertThat(compactor).isInstanceOf(RecordingNoopCompactor.class);
        assertThat(compactor).isSameAs(recordingCompactor);
        // 切面之间是靠构造器注入连起来的：我的 ContextManager 拿到的就是我这份压缩器。
        assertThat(recordingContextManager.compactor()).isSameAs(recordingCompactor);
    }

    // ==================================================================
    // 7 / 14 · ContextManager
    // ==================================================================

    @Test
    @DisplayName("切面 7：ContextManager —— 生效的是我的 RecordingContextManager，每一轮都从它过")
    void contextManagerFacetIsOverridden() {
        assertThat(contextManager).isInstanceOf(RecordingContextManager.class);
        assertThat(contextManager).isSameAs(recordingContextManager);
        assertThat(contextManager).isNotInstanceOf(DefaultContextManager.class);

        support.ask("你好");

        assertThat(recordingContextManager.prepares()).isNotEmpty();
        assertThat(recordingContextManager.afterSteps()).isNotEmpty();
        assertThat(recordingContextManager.prepares().get(0)).contains("prepare(历史 1 条");
    }

    // ==================================================================
    // 8 / 14 · MemoryStore
    // ==================================================================

    @Test
    @DisplayName("切面 8：MemoryStore —— 生效的是我的 RecordingMemoryStore，会话历史往它里面写")
    void memoryStoreFacetIsOverridden() {
        assertThat(memoryStore).isInstanceOf(RecordingMemoryStore.class);
        assertThat(memoryStore).isSameAs(recordingMemoryStore);
        assertThat(memoryStore).isNotInstanceOf(InMemoryMemoryStore.class);

        support.ask("你好");

        assertThat(recordingMemoryStore.count()).isPositive();
        // ⚠️ 口径：RecordingMemoryStore 记的是 "load(<sessionId>) → N 条"，
        // 不是裸的 "load(<sessionId>)" —— 而且第一个元素未必是 supportAssistant 的那次
        // （@BeforeEach 会为每个 Agent 调一次 clearHistory，之后还可能有别的调用）。
        // 所以这里断言"列表里存在这次 load/save"，而不是断言它在下标 0。
        assertThat(recordingMemoryStore.operations())
                .anySatisfy(op -> assertThat(op).startsWith("load(supportAssistant-default)"));
        assertThat(recordingMemoryStore.savesOf("supportAssistant-default")).isEqualTo(1);
        assertThat(agentRegistry.get("supportAssistant").history("supportAssistant-default")).hasSize(2);
    }

    // ==================================================================
    // 9 / 14 · SystemPromptProvider
    // ==================================================================

    @Test
    @DisplayName("切面 9：SystemPromptProvider —— 生效的是我的 PlatformSystemPromptProvider，提示词由它给")
    void systemPromptProviderFacetIsOverridden() {
        assertThat(systemPromptProvider).isInstanceOf(PlatformSystemPromptProvider.class);
        assertThat(systemPromptProvider).isSameAs(platformSystemPromptProvider);

        support.ask("你好");

        assertThat(platformSystemPromptProvider.asked()).isNotEmpty();
        assertThat(platformSystemPromptProvider.asked().get(0)).contains("supportAssistant");
        assertThat(stubModel.lastRequest().systemText()).contains("平台提示词");
    }

    // ==================================================================
    // 10 / 14 · ApprovalHandler
    // ==================================================================

    @Test
    @DisplayName("切面 10：ApprovalHandler —— 生效的是我的 ToggleApprovalHandler，置为拒绝就真的拒绝")
    void approvalHandlerFacetIsOverridden() {
        assertThat(approvalHandler).isInstanceOf(ToggleApprovalHandler.class);
        assertThat(approvalHandler).isSameAs(toggleApprovalHandler);
        // 默认实现是 autoApprove() 那个 lambda，不是可注入的类型 —— 只要类型是我的就说明顶掉了。
        assertThat(toggleApprovalHandler.isApproved()).isTrue();

        toggleApprovalHandler.setApproved(false);
        Agent always = agentRegistry.get("toolAssistant").toBuilder()
                .approvalPolicy(ApprovalPolicy.ALWAYS)
                .build();
        always.call("北京天气怎么样？", "approval-facet-session");

        // 审批被问了，而且结果按"拒绝"处理 —— 说明决定权确实在我这个 handler 手上。
        assertThat(toggleApprovalHandler.count()).isPositive();
        assertThat(toggleApprovalHandler.requests().get(0)).contains("get_weather");
    }

    // ==================================================================
    // 11 / 14 · ToolSandbox
    // ==================================================================

    @Test
    @DisplayName("切面 11：ToolSandbox —— 生效的是我的 ToggleToolSandbox，合上闸门工具就真的跑不动")
    void toolSandboxFacetIsOverridden() {
        assertThat(sandbox).isInstanceOf(ToggleToolSandbox.class);
        assertThat(sandbox).isSameAs(toggleToolSandbox);
        assertThat(sandbox).isNotSameAs(ToolSandbox.permissive());

        toggleToolSandbox.setPermissive(false);
        AgentResult denied = agentRegistry.get("toolAssistant").call("北京天气怎么样？", "sandbox-facet-session");

        assertThat(toggleToolSandbox.checks()).isNotEmpty();
        assertThat(toggleToolSandbox.checks().get(0)).contains("get_weather").contains("deny");
        assertThat(denied.toolCalls()).isNotEmpty();
        assertThat(denied.toolCalls().get(0).error()).isTrue();
        assertThat(denied.toolCalls().get(0).result()).contains("沙箱闸门");
        // 工具方法根本没被执行 —— 这就是"沙箱是执行前的唯一闸门"的意思。
        assertThat(commonTools.callCount()).isZero();
    }

    // ==================================================================
    // 12 / 14 · AgentListener
    // ==================================================================

    @Test
    @DisplayName("切面 12：AgentListener —— 生效的是我的 RecordingAgentListener，内置 LoggingListener 整个让位")
    void agentListenerFacetIsOverridden() {
        assertThat(listener).isInstanceOf(RecordingAgentListener.class);
        assertThat(listener).isSameAs(recordingAgentListener);
        assertThat(listener).isNotInstanceOf(LoggingListener.class);

        toolAssistant.ask("北京天气怎么样？");

        assertThat(recordingAgentListener.sawEvent("onAgentStart(toolAssistant")).isTrue();
        assertThat(recordingAgentListener.sawEvent("onToolCall(get_weather)")).isTrue();
        assertThat(recordingAgentListener.sawEvent("onAgentEnd(toolAssistant")).isTrue();
    }

    // ==================================================================
    // 13 / 14 · List<AgentInterceptor>
    // ==================================================================

    @Test
    @DisplayName("切面 13：List<AgentInterceptor> —— 我接管了这条链，且 @LlmGuard 的范围过滤与 order 排序都生效")
    void interceptorListFacetIsOverridden() {
        // 直接注入的 List<AgentInterceptor> 是"容器里所有 AgentInterceptor bean"，
        // 也就是<b>未包装</b>的原始实例 —— 这一点与 LlmInterceptorChain 的差别见下面。
        assertThat(interceptors).hasSize(2);
        // order 升序：10 的 TraceInterceptor 在前，20 的 OrderFollowerInterceptor 在后。
        assertThat(interceptors).extracting(AgentInterceptor::order).containsExactly(10, 20);
        assertThat(interceptors.get(0)).isSameAs(traceInterceptor);
        assertThat(interceptors.get(1)).isSameAs(orderFollowerInterceptor);

        // 声明式装配消费的是 LlmInterceptorChain（"已被 @LlmGuard 包好的那份清单"）。
        // DefaultOverrideConfiguration.llmInterceptors() 手写了一遍包装：
        // 先给 TraceInterceptor 套上 SelectiveInterceptor，再按 order 排序。
        assertThat(interceptorChain.interceptors()).hasSize(2);
        assertThat(interceptorChain.interceptors().get(0))
                .isInstanceOf(com.benxin.llm.spring.SelectiveInterceptor.class);
        assertThat(interceptorChain.interceptors().get(1)).isSameAs(orderFollowerInterceptor);

        support.ask("你好");
        // @LlmGuard(agents = {"toolAssistant"})：对 supportAssistant 不该生效。
        assertThat(traceInterceptor.beforeModelCalls()).isEmpty();
        assertThat(orderFollowerInterceptor.observedTraces()).isNotEmpty();
        assertThat(orderFollowerInterceptor.observedTraces().get(0)).contains("exp09_trace_agent=null");

        toolAssistant.ask("北京天气怎么样？");
        assertThat(traceInterceptor.beforeModelCalls()).isNotEmpty();
        assertThat(orderFollowerInterceptor.observedTraces().get(1))
                .contains("exp09_trace_agent=toolAssistant");
    }

    // ==================================================================
    // 14 / 14 · AgentRegistry
    // ==================================================================

    @Test
    @DisplayName("切面 14：AgentRegistry —— 生效的是我的 DemoAgentRegistry，惰性登记也被它记下来了")
    void agentRegistryFacetIsOverridden() {
        assertThat(agentRegistry).isInstanceOf(DemoAgentRegistry.class);
        assertThat(agentRegistry).isSameAs(demoAgentRegistry);
        assertThat(demoAgentRegistry.marker()).isEqualTo("DemoAgentRegistry");
        assertThat(demoAgentRegistry.names()).contains("supportAssistant", "toolAssistant");
        assertThat(demoAgentRegistry.lazyRegistrations()).isGreaterThanOrEqualTo(2);
    }

    // ==================================================================
    // 15 / 14 · LlmAgentFactory
    // ==================================================================

    @Test
    @DisplayName("切面 15：LlmAgentFactory —— 生效的是我的 DemoLlmAgentFactory，注解 → AgentSpec 仍由它翻译")
    void llmAgentFactoryFacetIsOverridden() {
        assertThat(agentFactory).isInstanceOf(DemoLlmAgentFactory.class);
        assertThat(agentFactory).isSameAs(demoAgentFactory);
        assertThat(demoAgentFactory.marker()).isEqualTo("DemoLlmAgentFactory");

        var spec = agentFactory.specOf(SupportAssistant.class);
        assertThat(spec.name()).isEqualTo("supportAssistant");
        assertThat(spec.model()).isNull();          // 注解没写 model → 用全局默认
        // 装配过的 Agent 都被它记下来了（LlmAgentFactoryBean 是 eager-init 的 SmartFactoryBean，
        // 所以启动期就会走一遍 create —— 冷启动即可见）。
        assertThat(demoAgentFactory.builtAgents()).contains("supportAssistant", "toolAssistant");
    }

    // ==================================================================

    /**
     * 把 stub 模型指向桩服务；演示进程里由 {@code SpringOverrideDemo} 做同一件事。
     *
     * <p>这里必须<b>沿类层次往上找</b> {@code setBaseUrl}：运行时类型是
     * {@code StubDemoModel}，而那个方法定义在它的父类 {@code HttpBackedModel} 上，
     * {@code getDeclaredMethod} 只看当前类，会漏掉继承来的方法。</p>
     */
    private void pointStubModelAt(String baseUrl) {
        var raw = stubModel.unwrap();
        assertThat(raw).isNotNull();
        try {
            java.lang.reflect.Method setter = null;
            for (Class<?> type = raw.getClass(); type != null && setter == null; type = type.getSuperclass()) {
                try {
                    setter = type.getDeclaredMethod("setBaseUrl", String.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            assertThat(setter).as("HttpBackedModel#setBaseUrl 应该存在").isNotNull();
            setter.setAccessible(true);
            setter.invoke(raw, baseUrl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法把 stub 模型指向桩服务", e);
        }
    }
}
