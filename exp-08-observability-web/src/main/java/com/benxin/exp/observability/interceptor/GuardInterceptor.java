package com.benxin.exp.observability.interceptor;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 {@code @LlmGuard(agents = {...})} 声明作用范围的拦截器，同时把<b>全部 9 个钩子</b>都覆写一遍。
 *
 * <p>两件事一起演示：</p>
 * <ol>
 *   <li><b>范围</b>：{@code agents = {"guardedAssistant"}} 只对这一个 Agent 生效。
 *       实现方式不是拦截器自己判断，而是 {@code LlmAutoConfiguration.resolveInterceptors(...)}
 *       把它包成 {@code SelectiveInterceptor}，由后者的 {@code supports()} 兜住。
 *       因此消费端必须依赖 {@code LlmInterceptorChain} 才拿得到包装后的那份清单 ——
 *       直接用 {@code ObjectProvider<AgentInterceptor>} 会拿到未包装的原始 bean，
 *       范围声明就完全失效。</li>
 *   <li><b>九个钩子</b>：{@code order()} / {@code supports()} / {@code beforeAgent} /
 *       {@code afterAgent} / {@code onError} / {@code beforeModel} / {@code afterModel} /
 *       {@code beforeTool} / {@code afterTool}。全部留档，测试逐个断言。</li>
 * </ol>
 *
 * <p>注意本类没有 {@code @Component}：{@code @LlmGuard} 由
 * {@code LlmComponentScanner} 自动注册。两者等价（扫描器发现同名 bean 已存在就跳过），
 * 但<b>同时写</b>会让人分不清到底是哪条路径注册的。</p>
 */
@LlmGuard(agents = {"guardedAssistant"})
public class GuardInterceptor implements AgentInterceptor {

    private static final List<String> HOOKS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger ORDERS_ASKED = new AtomicInteger();
    private static final AtomicInteger SUPPORTS_ASKED = new AtomicInteger();

    /** 覆写 {@code supports()} 以证明"注解范围"与"代码范围"是<b>与</b>的关系。 */
    @Override
    public int order() {
        ORDERS_ASKED.incrementAndGet();
        return 40;
    }

    @Override
    public boolean supports(String agentName) {
        SUPPORTS_ASKED.incrementAndGet();
        // SelectiveInterceptor 会先判注解里的 agents，再判这里 —— 两个条件都满足才生效。
        return true;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        HOOKS.add("beforeAgent:" + invocation.agentName());
    }

    @Override
    public void afterAgent(AgentInvocation invocation, AgentResult result) {
        HOOKS.add("afterAgent:" + result.agentName() + ":" + result.steps());
    }

    @Override
    public void onError(AgentInvocation invocation, Throwable error) {
        HOOKS.add("onError:" + invocation.agentName() + ":" + error.getClass().getSimpleName());
    }

    @Override
    public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        HOOKS.add("beforeModel:" + invocation.agentName() + ":msgs=" + request.messages().size());
        return request;
    }

    @Override
    public ChatResponse afterModel(AgentInvocation invocation, ChatResponse response) {
        HOOKS.add("afterModel:" + invocation.agentName() + ":" + response.finishReason());
        return response;
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        HOOKS.add("beforeTool:" + invocation.toolName());
        return null;   // 放行
    }

    @Override
    public void afterTool(ToolInvocation invocation, ToolResult result) {
        HOOKS.add("afterTool:" + invocation.toolName() + ":error=" + result.error());
    }

    // ------------------------------------------------------------------
    // 观测（与 OrderProbeInterceptor 同理：夹具用静态，生产代码应当交给指标组件）
    // ------------------------------------------------------------------

    public static void resetHooks() {
        HOOKS.clear();
        ORDERS_ASKED.set(0);
        SUPPORTS_ASKED.set(0);
    }

    public static List<String> hooks() {
        return List.copyOf(HOOKS);
    }

    public static List<String> names() {
        return HOOKS.stream().map(h -> h.substring(0, h.indexOf(':'))).toList();
    }

    public static int ordersAsked() {
        return ORDERS_ASKED.get();
    }

    public static int supportsAsked() {
        return SUPPORTS_ASKED.get();
    }

    @Override
    public String toString() {
        return "GuardInterceptor[hooks=" + HOOKS.size() + "]";
    }
}
