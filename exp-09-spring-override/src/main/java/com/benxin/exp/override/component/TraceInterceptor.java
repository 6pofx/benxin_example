package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 切面 13 的自定义实现：{@code List<AgentInterceptor>} 链上的第一个拦截器。
 *
 * <p>为什么这个切面是 {@code List<AgentInterceptor>} 而不是单个拦截器：
 * 拦截器是"多个并存"的，自动配置把它们收成<b>一个名为 {@code llmInterceptors} 的 bean</b>，
 * 并在收的过程中做了一件重要的事 —— 给带 {@link LlmGuard} 的拦截器套上
 * {@code SelectiveInterceptor}，让 {@code agents = {...}} 的范围声明真正生效。</p>
 *
 * <p>本类演示 {@link #beforeModel} 这条最常用的钩子：它<b>改写请求</b>
 * （往 {@code extra} 里塞一个 trace 标记），改写结果会一路下发到模型。
 * 想看 {@code @LlmGuard(agents)} 的效果，就换一个<b>不在</b>名单里的 Agent 再调一次 ——
 * 你会发现本类一次都没被触发。</p>
 */
@LlmGuard(agents = {"toolAssistant"})
public class TraceInterceptor implements AgentInterceptor {

    private final List<String> beforeModelCalls = new CopyOnWriteArrayList<>();
    private final List<String> beforeAgentCalls = new CopyOnWriteArrayList<>();
    private final List<String> afterToolCalls = new CopyOnWriteArrayList<>();

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        beforeAgentCalls.add(invocation.agentName());
    }

    @Override
    public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        beforeModelCalls.add(invocation.agentName());
        // 改写请求：extra 里的键会原样透传到厂商请求体顶层（见 HttpLlmModel#merge）。
        // 对一个脚本模型来说它只是"看得见的证据"，对真实网关来说它就是链路追踪头。
        return request.toBuilder()
                .extra("exp09_trace_agent", invocation.agentName())
                .build();
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        // 返回 null = 放行；返回结果则短路（工具不会被执行）。这里刻意放行。
        return null;
    }

    @Override
    public void afterTool(ToolInvocation invocation, ToolResult result) {
        afterToolCalls.add(invocation.toolName());
    }

    public List<String> beforeModelCalls() {
        return List.copyOf(beforeModelCalls);
    }

    public List<String> beforeAgentCalls() {
        return List.copyOf(beforeAgentCalls);
    }

    public List<String> afterToolCalls() {
        return List.copyOf(afterToolCalls);
    }

    public void reset() {
        beforeModelCalls.clear();
        beforeAgentCalls.clear();
        afterToolCalls.clear();
    }

    @Override
    public String toString() {
        return "TraceInterceptor(order=10, @LlmGuard(agents=toolAssistant))";
    }
}
