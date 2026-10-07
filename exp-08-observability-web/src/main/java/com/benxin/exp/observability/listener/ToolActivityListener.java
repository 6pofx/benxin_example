package com.benxin.exp.observability.listener;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第二个自定义监听器：只关心"工具被调了几次、成功几次、失败几次"。
 *
 * <p>它存在的唯一目的是证明<b>多个 {@code AgentListener} bean 会全部生效</b>：
 * starter 把它们收进 {@code ObjectProvider<AgentListener>} 的 orderedStream，
 * 再由 {@code AgentBuilder.build()} 用 {@code CompositeListener.of(...)} 合成一条广播链 ——
 * 于是"内置 llmLoggingListener 让位"与"自定义监听器叠加"是两件同时成立的事，
 * 很容易被误读成"只能有一个监听器"。</p>
 *
 * <p>真实项目里这对应"一个监听器推前端、一个监听器写指标、一个监听器落审计"的分工，
 * 各写各的、互不干扰（{@code CompositeListener} 会吞掉单个监听器的异常，
 * 一个监听器故障不会拖垮其它监听器与主流程）。</p>
 */
public class ToolActivityListener implements AgentListener {

    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger toolErrors = new AtomicInteger();
    private final AtomicInteger agentEnds = new AtomicInteger();
    private final AtomicInteger textDeltas = new AtomicInteger();
    private final List<String> toolNames = new CopyOnWriteArrayList<>();

    @Override
    public void onTextDelta(String delta) {
        textDeltas.incrementAndGet();
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        toolCalls.incrementAndGet();
        toolNames.add(invocation.toolName());
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        if (result.error()) {
            toolErrors.incrementAndGet();
        }
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        agentEnds.incrementAndGet();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int toolErrors() {
        return toolErrors.get();
    }

    public int agentEnds() {
        return agentEnds.get();
    }

    public int textDeltas() {
        return textDeltas.get();
    }

    public List<String> toolNames() {
        return List.copyOf(toolNames);
    }

    public void reset() {
        toolCalls.set(0);
        toolErrors.set(0);
        agentEnds.set(0);
        textDeltas.set(0);
        toolNames.clear();
    }

    @Override
    public String toString() {
        return "ToolActivityListener[工具 " + toolCalls() + " 次 / 失败 " + toolErrors()
                + " 次 / Agent 结束 " + agentEnds() + " 次]";
    }
}
