package com.benxin.exp.tools.support;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 监听器：把"工具里发生的事"记下来，供示例与测试断言。
 *
 * <p>它同时演示了一件容易被忽略的事：<b>{@code ToolResult.meta()} 只在监听器/拦截器里可见</b>。
 * 进模型上下文的是 {@code content()}，而 {@code AgentResult.toolCalls()} 里的
 * {@code ToolCallRecord} 也<b>没有</b> meta 字段（见 {@code ToolCallRecord.of}）——
 * 想把耗时、退出码这类信息传给"外面"，监听器是唯一的出口。</p>
 *
 * <p>本 bean 的存在还会顶掉 starter 内置的 {@code LoggingListener}：
 * {@code LlmAutoConfiguration.llmLoggingListener} 上的条件是
 * {@code @ConditionalOnMissingBean(AgentListener.class)}，按<b>类型</b>让位。
 * 这正是"替换任意一个切面"的统一机制。</p>
 */
@Component
public class ToolEventCollector implements AgentListener {

    private final List<CustomEvent> customEvents = new CopyOnWriteArrayList<>();
    private final List<ToolInvocation> toolCalls = new CopyOnWriteArrayList<>();
    private final List<ToolResult> toolResults = new CopyOnWriteArrayList<>();
    private final List<String> metaSnapshots = new CopyOnWriteArrayList<>();

    @Override
    public void onToolCall(ToolInvocation invocation) {
        toolCalls.add(invocation);
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        toolResults.add(result);
        // meta 不进模型、也不进 AgentResult，只能在这里取。
        metaSnapshots.add(result.meta().toString());
    }

    @Override
    public void onCustomEvent(String type, Object payload) {
        customEvents.add(new CustomEvent(type, payload));
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        // 示例不需要，但这里保留一个空实现会让"监听器默认什么都不做"更直观：
        // AgentListener 的每个回调都是 default 方法，想只关心一件事就只覆写那一件。
    }

    public void reset() {
        customEvents.clear();
        toolCalls.clear();
        toolResults.clear();
        metaSnapshots.clear();
    }

    public List<CustomEvent> customEvents() {
        return List.copyOf(customEvents);
    }

    public List<ToolInvocation> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<ToolResult> toolResults() {
        return List.copyOf(toolResults);
    }

    public List<String> metaSnapshots() {
        return List.copyOf(metaSnapshots);
    }

    /** 一次自定义事件：{@code ToolContext#emit} 把它送到这里。 */
    public record CustomEvent(String type, Object payload) {
    }
}
