package com.benxin.exp.observability;

import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.agent.GuardedAssistant;
import com.benxin.exp.observability.agent.RewriteAssistant;
import com.benxin.exp.observability.agent.ToolErrorProbe;
import com.benxin.exp.observability.agent.ToolGuardAssistant;
import com.benxin.exp.observability.interceptor.GuardInterceptor;
import com.benxin.exp.observability.interceptor.OrderProbeInterceptor;
import com.benxin.exp.observability.interceptor.RewriteInterceptor;
import com.benxin.exp.observability.interceptor.ToolAuditInterceptor;
import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.DefaultAgent;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.SelectiveInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 把 {@code AgentInterceptor} 的 <b>9 个钩子</b>逐个钉住，外加三条"按直觉猜会猜错"的口径：
 * {@code afterAgent} 的执行方向、{@code beforeTool} 的短路、以及
 * {@code @LlmGuard} 的范围为什么必须经由 {@code LlmInterceptorChain} 才有效。
 */
@DisplayName("示例 08 · 拦截器 9 个钩子")
class ObservingInterceptorTest extends ObservabilityTestSupport {

    @Autowired
    private ChatAssistant chat;

    @Autowired
    private GuardedAssistant guarded;

    @Autowired
    private ToolGuardAssistant toolGuard;

    @Autowired
    private ToolErrorProbe toolErrorProbe;

    @Autowired
    private RewriteAssistant rewrite;

    @Autowired
    private LlmInterceptorChain interceptorChain;

    @Autowired
    private ObjectProvider<AgentInterceptor> rawInterceptors;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ModelRegistry modelRegistry;

    // ------------------------------------------------------------------
    // ① order()：升序执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order() 升序执行；afterAgent 也是升序（不是洋葱式逆序）")
    void orderIsAscendingForBothBeforeAndAfter() {
        guarded.ask("自我介绍");

        // 三个探针的 order 分别是 -30 / -20 / -10。
        // SessionTrackingInterceptor(-100)、ShortCircuitInterceptor(0)、
        // RewriteInterceptor(30) 等不写轨迹，因此这里能拿到干净的相对顺序。
        assertThat(OrderProbeInterceptor.trace()).containsExactly(
                "A.beforeAgent", "B.beforeAgent", "C.beforeAgent",
                "A.afterAgent", "B.afterAgent", "C.afterAgent");
    }

    @Test
    @DisplayName("LlmInterceptorChain 暴露的清单已按 order 升序排好")
    void chainIsSortedByOrder() {
        List<Integer> orders = interceptorChain.interceptors().stream().map(AgentInterceptor::order).toList();

        assertThat(orders).isSorted();
        assertThat(orders).contains(-100, -30, -20, -10);   // 会话登记 + 三个探针
    }

    @Test
    @DisplayName("order() 相同不会报错，装配照常完成")
    void equalOrdersDoNotThrow() {
        AgentInterceptor first = new FixedOrderInterceptor("first");
        AgentInterceptor second = new FixedOrderInterceptor("second");

        AgentResult result = agentRegistry.get("chatAssistant").toBuilder()
                .interceptor(first)
                .interceptor(second)
                .build()
                .call("自我介绍", "equal-order-session");

        assertThat(result.text()).isNotBlank();
    }

    private static final class FixedOrderInterceptor implements AgentInterceptor {
        private final String id;

        private FixedOrderInterceptor(String id) {
            this.id = id;
        }

        @Override
        public int order() {
            return 7;
        }

        @Override
        public String toString() {
            return "FixedOrder(" + id + ")";
        }
    }

    // ------------------------------------------------------------------
    // ② @LlmGuard 的作用范围
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@LlmGuard(agents={...}) 只对名单内的 Agent 生效")
    void guardOnlyAffectsNamedAgents() {
        chat.ask("自我介绍");
        assertThat(GuardInterceptor.hooks()).isEmpty();
        // 实测口径：名单不匹配时【连 supports() 都不会被问】。
        // SelectiveInterceptor.supports() 是 (agents.isEmpty() || agents.contains(name)) && delegate.supports(name)，
        // 前一项为 false 就短路了 —— 所以这里不是"问了但返回 false"，而是"根本没问到被包装者头上"。
        assertThat(GuardInterceptor.supportsAsked()).isZero();

        GuardInterceptor.resetHooks();
        guarded.ask("自我介绍");
        assertThat(GuardInterceptor.hooks()).isNotEmpty();
        assertThat(GuardInterceptor.names()).contains("beforeAgent", "afterAgent");
        // 名单命中时才会走到 delegate.supports(...)（它恒返回 true，真正的过滤在前一项）
        assertThat(GuardInterceptor.supportsAsked()).isPositive();
    }

    @Test
    @DisplayName("LlmInterceptorChain 里装的是包装后的 SelectiveInterceptor；"
            + "ObjectProvider<AgentInterceptor> 里是未包装的原始 bean")
    void guardRangeLivesInTheChainNotInTheRawProvider() {
        boolean chainHasWrapper = interceptorChain.interceptors().stream()
                .anyMatch(i -> i instanceof SelectiveInterceptor);
        assertThat(chainHasWrapper)
                .as("LlmAutoConfiguration.resolveInterceptors(...) 会把带 @LlmGuard 的拦截器包一层")
                .isTrue();

        // 这是"消费端为什么必须依赖 LlmInterceptorChain"的直接证据：
        // ObjectProvider 按"类型为 AgentInterceptor 的 bean"解析，而
        // llmInterceptors() 返回的那个 List<AgentInterceptor> 不是这个类型，
        // 于是从这里拿到的是容器里未被包装的原始 bean。
        List<AgentInterceptor> raw = rawInterceptors.orderedStream().toList();
        assertThat(raw).anyMatch(i -> i instanceof GuardInterceptor);
        assertThat(raw).noneMatch(i -> i instanceof SelectiveInterceptor);
    }

    @Test
    @DisplayName("范围失效的对照：未包装的原始 bean 对任何 Agent 都返回 supports=true")
    void unwrappedInterceptorSupportsEverything() {
        GuardInterceptor raw = rawInterceptors.orderedStream()
                .filter(GuardInterceptor.class::isInstance)
                .map(GuardInterceptor.class::cast)
                .findFirst()
                .orElseThrow();

        // 原始 bean 自己并不知道 @LlmGuard 写了什么 —— 范围完全靠包装层兜住。
        assertThat(raw.supports("chatAssistant")).isTrue();
        assertThat(raw.supports("guardedAssistant")).isTrue();
    }

    @Test
    @DisplayName("@LlmGuard 标注的类不需要 @Component：LlmComponentScanner 会自动注册")
    void guardAnnotationRegistersBeanWithoutComponent() {
        assertThat(context.containsBean("guardInterceptor")).isTrue();
        assertThat(GuardInterceptor.class.getAnnotation(
                org.springframework.stereotype.Component.class)).isNull();
    }

    // ------------------------------------------------------------------
    // ③ beforeTool 短路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("beforeTool 返回非 null 即短路：工具不再执行，模型收到拦截器给出的结果")
    void beforeToolShortCircuitsToolExecution() {
        int before = commonTools.callCount();

        AgentResult result = toolGuard.ask("北京天气怎么样？");

        // 工具一次都没真的跑
        assertThat(commonTools.callCount()).isEqualTo(before);
        assertThat(shortCircuitInterceptor.shortCircuits()).isEqualTo(1);
        assertThat(shortCircuitInterceptor.passThroughs()).isZero();

        // 模型收到的是拦截器给的文本，并据此收尾
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(result.toolCalls().get(0).result()).contains("【拦截器短路】");
        assertThat(result.text()).contains("【拦截器短路】");
        assertThat(result.steps()).isEqualTo(2);
    }

    @Test
    @DisplayName("afterTool 对短路结果同样会执行（短路也要进审计）")
    void afterToolStillRunsOnShortCircuit() {
        toolGuard.ask("北京天气怎么样？");

        assertThat(toolAuditInterceptor.entries()).hasSize(1);
        ToolAuditInterceptor.Entry entry = toolAuditInterceptor.entries().get(0);
        assertThat(entry.toolName()).isEqualTo("get_weather");
        assertThat(entry.step()).isEqualTo(1);
        assertThat(entry.error()).isFalse();
        assertThat(entry.contentLength()).isPositive();
    }

    @Test
    @DisplayName("不在 @LlmGuard 名单里的 Agent：同一个工具照常真实执行")
    void otherAgentsAreNotShortCircuited() {
        int before = commonTools.callCount();

        AgentResult result = chat.run("北京天气怎么样？");

        assertThat(commonTools.callCount()).isEqualTo(before + 1);
        assertThat(result.toolCalls().get(0).result()).doesNotContain("【拦截器短路】");
    }

    @Test
    @DisplayName("beforeTool 返回 null = 放行，同一个拦截器里按工具名分支")
    void passThroughReturnsNull() {
        commonTools.reset();
        AgentResult result = toolGuard.ask("#tool:calculate {\"expression\":\"(12+5)*3\"}");

        // `#tool:<名字>` 在脚本模型里每一步都会重新命中（见 MonitorListenerTest.forcedToolRepeatsEveryStep），
        // 而 toolGuard 的 maxSteps=2 —— 因此一次运行会走两遍 beforeTool，两遍都是"放行"（返回 null）。
        assertThat(shortCircuitInterceptor.passThroughs()).isEqualTo(2);
        assertThat(shortCircuitInterceptor.shortCircuits()).isZero();

        // "放行"的证据：工具真的执行了两次，而且算出了正确结果（不是拦截器给的文本）
        assertThat(commonTools.callCount()).isEqualTo(2);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).result()).contains("(12+5)*3 = 51.0");
        assertThat(result.toolCalls().get(0).result()).doesNotContain("【拦截器短路】");
    }

    @Test
    @DisplayName("工具失败时 afterTool 的记账里 error=true")
    void afterToolRecordsErrors() {
        // 用 probe_fail（真的会抛异常的工具）+ 关键字触发：一次运行恰好一次工具调用。
        // 这里刻意不用 `#tool:...`（每一步都会重新命中，会记 2~4 条）也不用 CommonTools.calculate
        // （它的失败只是内容里的错误文本，error 标志为 false，见 MonitorListenerTest）。
        toolErrorProbe.ask(FailingProbeTool.TRIGGER);

        assertThat(toolAuditInterceptor.entries()).hasSize(1);
        ToolAuditInterceptor.Entry entry = toolAuditInterceptor.entries().get(0);
        assertThat(entry.toolName()).isEqualTo(FailingProbeTool.NAME);
        assertThat(entry.step()).isEqualTo(1);
        assertThat(entry.error()).isTrue();
        assertThat(entry.contentLength()).isPositive();
        assertThat(toolAuditInterceptor.errorCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ④ beforeModel / afterModel 改写
    // ------------------------------------------------------------------

    @Test
    @DisplayName("beforeModel 改写请求：extra 里的 trace_id 真的下发给了模型")
    void beforeModelRewritesRequest() {
        rewrite.ask("自我介绍");

        ChatRequest captured = mockLlmModel.lastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.extra()).containsKey(RewriteInterceptor.TRACE_KEY);
        assertThat(String.valueOf(captured.extra().get(RewriteInterceptor.TRACE_KEY)))
                .startsWith("rewrite:");
    }

    @Test
    @DisplayName("afterModel 改写响应：追加的标记出现在最终结果里")
    void afterModelRewritesResponse() {
        String text = rewrite.ask("自我介绍");

        assertThat(text).endsWith(RewriteInterceptor.SUFFIX_MARKER);
    }

    @Test
    @DisplayName("@LlmAgent(interceptors={...}) 优先取容器 bean、取不到就 new：RewriteInterceptor 不在容器里")
    void annotationInterceptorIsInstantiatedWhenNotABean() {
        assertThat(context.getBeansOfType(RewriteInterceptor.class)).isEmpty();
        assertThat(context.containsBean("rewriteInterceptor")).isFalse();

        // 但注解级拦截器确实生效了（否则上面两个用例会失败）
        assertThat(rewrite.ask("自我介绍")).contains(RewriteInterceptor.SUFFIX_MARKER);
    }

    @Test
    @DisplayName("注解级拦截器只作用于声明它的 Agent：chatAssistant 的请求里没有 trace_id")
    void annotationInterceptorIsScopedToItsAgent() {
        mockLlmModel.reset();
        chat.ask("自我介绍");

        assertThat(mockLlmModel.lastRequest().extra()).doesNotContainKey(RewriteInterceptor.TRACE_KEY);
    }

    // ------------------------------------------------------------------
    // ⑤ onError
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Agent 抛异常时拦截器的 onError 被调用，且 afterAgent 不再被调用")
    void interceptorOnErrorIsCalled() {
        // 用"容器内 Agent 的程序化副本 + 换成会失败的模型"来构造这个组合：
        // 这样 @LlmGuard 的原始拦截器与真实的失败模型都在场。
        GuardInterceptor.resetHooks();
        LlmModel failing = modelRegistry.get("failing");
        Agent failingAgent = agentRegistry.get("guardedAssistant").toBuilder()
                .model(failing)
                .build();

        assertThatThrownBy(() -> failingAgent.call("随便问点什么", "interceptor-error-session"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("演示用的模型故障");

        assertThat(GuardInterceptor.names()).contains("beforeAgent", "beforeModel", "onError");
        assertThat(GuardInterceptor.names()).doesNotContain("afterAgent");
    }

    @Test
    @DisplayName("成功路径上拦截器的 onError 不会被调用")
    void interceptorOnErrorIsNotCalledOnSuccess() {
        GuardInterceptor.resetHooks();
        guarded.ask("自我介绍");

        assertThat(GuardInterceptor.names()).doesNotContain("onError");
    }

    @Test
    @DisplayName("拦截器的 onError 自己抛异常不会掩盖原始异常（DefaultAgent 做了保护）")
    void interceptorErrorCallbackFailureIsSwallowed() {
        AgentInterceptor faulty = new AgentInterceptor() {
            @Override
            public void onError(com.benxin.llm.core.agent.AgentInvocation invocation, Throwable error) {
                throw new IllegalStateException("拦截器的 onError 自己炸了");
            }
        };
        Agent failingAgent = agentRegistry.get("guardedAssistant").toBuilder()
                .model(modelRegistry.get("failing"))
                .interceptor(faulty)
                .build();

        // 原始异常必须原样抛出，而不是被 onError 里的 IllegalStateException 顶掉
        assertThatThrownBy(() -> failingAgent.call("失败一次", "swallow-error-session"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("演示用的模型故障");
    }

    // ------------------------------------------------------------------
    // ⑥ supports() 过滤
    // ------------------------------------------------------------------

    @Test
    @DisplayName("supports(agentName) 返回 false 的拦截器完全不参与该 Agent 的运行")
    void supportsFiltersOutInterceptor() {
        AtomicInteger hits = new AtomicInteger();
        AgentInterceptor never = new AgentInterceptor() {
            @Override
            public boolean supports(String agentName) {
                return false;
            }

            @Override
            public void beforeAgent(com.benxin.llm.core.agent.AgentInvocation invocation) {
                hits.incrementAndGet();
            }
        };

        agentRegistry.get("chatAssistant").toBuilder()
                .interceptor(never)
                .build()
                .call("自我介绍", "supports-filter-session");

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("DefaultAgent.activeInterceptors() 就是 supports() 过滤后的清单（元素是 @LlmGuard 的包装层）")
    void activeInterceptorsArePreFiltered() {
        DefaultAgent guardedAgent = (DefaultAgent) agentRegistry.get("guardedAssistant");
        DefaultAgent chatAgent = (DefaultAgent) agentRegistry.get("chatAssistant");

        List<AgentInterceptor> guardedActive = guardedAgent.activeInterceptors();
        List<AgentInterceptor> chatActive = chatAgent.activeInterceptors();

        // ① "预过滤"这条承诺成立：交给 Loop 的每一个都支持当前 Agent
        assertThat(guardedActive).isNotEmpty().allMatch(i -> i.supports("guardedAssistant"));
        assertThat(chatActive).allMatch(i -> i.supports("chatAssistant"));

        // ② 实测口径：清单里的元素是 SelectiveInterceptor 包装层，而不是裸的 GuardInterceptor。
        //    @LlmGuard 的包装发生在 LlmAutoConfiguration.resolveInterceptors(...)，
        //    之后这份清单被 LlmInterceptorChain → LlmAgentFactory → AgentBuilder 一路带进来，
        //    activeInterceptors() 只做 supports() 过滤，不会把包装拆掉。
        //    所以按 GuardInterceptor.class::isInstance 去找是找不到的（会得到 0）。
        assertThat(guardedActive).noneMatch(GuardInterceptor.class::isInstance);
        assertThat(guardedActive.stream().filter(SelectiveInterceptor.class::isInstance).count())
                .as("guardedAssistant 只命中一个带 @LlmGuard 的拦截器")
                .isEqualTo(1);
        assertThat(guardedActive.stream()
                .filter(SelectiveInterceptor.class::isInstance)
                .map(Object::toString))
                .anyMatch(text -> text.contains("GuardInterceptor"));

        // ③ 范围之外：chatAssistant 名单不匹配 → 包装层在过滤时就被丢掉了，一个都不剩
        assertThat(chatActive).noneMatch(SelectiveInterceptor.class::isInstance);
    }

    @Test
    @DisplayName("order() 是装配期钩子：运行期只问 supports()，重新装配才会再问 order()")
    void orderIsAskedAtAssemblyTimeOnly() {
        GuardInterceptor.resetHooks();
        guarded.ask("自我介绍");

        // 运行期：每次调用前问 supports()（DefaultAgent.activeInterceptors()）
        assertThat(GuardInterceptor.supportsAsked()).isPositive();
        // 但不会再问 order()：它只在 LlmAutoConfiguration.resolveInterceptors(...) 与
        // AgentBuilder.build() 的排序里被问，而容器的装配早就完成了
        // （@BeforeEach 的 resetHooks() 把启动期那几次计数清掉了）。
        assertThat(GuardInterceptor.ordersAsked()).isZero();

        // 现场重新装配一个副本 → 排序再次发生 → order() 被问
        GuardInterceptor.resetHooks();
        agentRegistry.get("guardedAssistant").toBuilder().build();
        assertThat(GuardInterceptor.ordersAsked()).isPositive();
    }

    @Test
    @DisplayName("九个钩子的完整清单在 @LlmGuard 范围内全部可达")
    void allNineHooksRepresented() {
        GuardInterceptor.resetHooks();
        // guardedAssistant 不声明 tools，继承全局工具表；问天气即可同时走到工具钩子
        guarded.ask("北京天气怎么样？");

        List<String> hooks = GuardInterceptor.names().stream().distinct().toList();
        assertThat(hooks).contains(
                "beforeAgent",     // 钩子 3
                "afterAgent",      // 钩子 4
                "beforeModel",     // 钩子 6
                "afterModel",      // 钩子 7
                "beforeTool",      // 钩子 8
                "afterTool");      // 钩子 9
        // order()（钩子 1）是装配期钩子、supports()（钩子 2）是运行期钩子：
        // 后者在这里用计数证明被问过，前者见 orderIsAskedAtAssemblyTimeOnly 与 chainIsSortedByOrder
        assertThat(GuardInterceptor.supportsAsked()).isPositive();
        // onError（钩子 5）只在失败路径出现，由另一个用例覆盖
        assertThat(hooks).doesNotContain("onError");
    }
}
