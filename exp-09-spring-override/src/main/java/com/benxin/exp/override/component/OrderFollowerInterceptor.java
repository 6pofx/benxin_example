package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.hook.AgentInterceptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 切面 13 的第二个拦截器：专门用来证明"order 决定顺序"。
 *
 * <p>它没有 {@code @LlmGuard}，因此对<b>所有</b> Agent 生效；
 * {@code order = 20} 排在 {@link TraceInterceptor}（order = 10）之后，
 * 于是 {@link #beforeModel} 看到的请求里已经带着前者写进去的 {@code exp09_trace_agent}。</p>
 *
 * <p>这条口径在很多地方会踩坑：{@code LlmAutoConfiguration.llmInterceptors()} 里同时做了
 * "包 @LlmGuard"与"按 order 排序"两件事，所以顺序由注解/方法的 order 决定，
 * 而不是 bean 的定义顺序。</p>
 */
public class OrderFollowerInterceptor implements AgentInterceptor {

    private final List<String> observedTraces = new CopyOnWriteArrayList<>();

    @Override
    public int order() {
        return 20;
    }

    @Override
    public com.benxin.llm.core.chat.ChatRequest beforeModel(AgentInvocation invocation,
                                                             com.benxin.llm.core.chat.ChatRequest request) {
        Object trace = request.extra() == null ? null : request.extra().get("exp09_trace_agent");
        observedTraces.add("看到 exp09_trace_agent=" + trace + "（agent=" + invocation.agentName() + "）");
        return request;
    }

    /** 后一个拦截器看到的 trace 值 —— 空表示前一个没跑到（被 @LlmGuard 过滤了）。 */
    public List<String> observedTraces() {
        return List.copyOf(observedTraces);
    }

    public void reset() {
        observedTraces.clear();
    }

    @Override
    public String toString() {
        return "OrderFollowerInterceptor(order=20)";
    }
}
