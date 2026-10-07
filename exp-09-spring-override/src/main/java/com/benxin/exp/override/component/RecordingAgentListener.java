package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 切面 12 的自定义实现：{@code AgentListener}。
 *
 * <p>自动配置里的写法值得单独看一眼：{@code @ConditionalOnMissingBean(AgentListener.class)}
 * —— 按<b>类型</b>判断。以前写的是 {@code @ConditionalOnMissingBean(name = "llmLoggingListener")}，
 * 按 bean 名判，于是自定义监听器会跟内置那个"并存"而不是"顶掉"，
 * 与 README 承诺的统一机制不一致。所以这里只要定义<b>一个</b> {@code AgentListener} bean，
 * {@code LoggingListener} 就会整个让位。</p>
 *
 * <p>本类把事件按顺序记下来，示例与测试都用它当"运行时发生了什么"的观测窗口。</p>
 */
public class RecordingAgentListener implements AgentListener {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void onAgentStart(AgentInvocation invocation) {
        events.add("onAgentStart(" + invocation.agentName() + ", session=" + invocation.sessionId() + ")");
    }

    @Override
    public void onStepStart(String agentName, int step) {
        events.add("onStepStart(" + agentName + ", 第 " + step + " 步)");
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        events.add("onToolCall(" + invocation.toolName() + ")");
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        events.add("onToolResult(" + invocation.toolName() + ", error=" + result.error() + ")");
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        events.add("onAgentEnd(" + result.agentName() + ", " + result.steps() + " 步, "
                + result.usage().totalTokens() + " token)");
    }

    @Override
    public void onError(Throwable error) {
        events.add("onError(" + error.getClass().getSimpleName() + ")");
    }

    @Override
    public void onCustomEvent(String type, Object payload) {
        events.add("onCustomEvent(" + type + ")");
    }

    /** 依次记录全部事件。 */
    public List<String> events() {
        return List.copyOf(events);
    }

    public int count() {
        return events.size();
    }

    /** 是否存在包含给定片段的事件（测试里用得多）。 */
    public boolean sawEvent(String fragment) {
        return events.stream().anyMatch(e -> e.contains(fragment));
    }

    public void reset() {
        events.clear();
    }

    @Override
    public String toString() {
        return "RecordingAgentListener(已记录 " + events.size() + " 个事件)";
    }
}
