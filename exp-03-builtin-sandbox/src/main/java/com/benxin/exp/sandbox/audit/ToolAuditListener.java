package com.benxin.exp.sandbox.audit;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用留痕：把每次工具调用的<b>参数、结果文本、error 标志与 meta</b> 都记下来。
 *
 * <p>为什么示例需要它：{@code AgentResult.toolCalls()} 里存的是
 * {@code ToolCallRecord}，它<b>不含 meta</b>（meta 明确被设计成"不进模型上下文"的旁路数据）。
 * 而"工具返回了什么 meta"恰恰是沙箱演示要看的重点（{@code exitCode}、{@code matches}、
 * {@code bytes}、{@code subAgent}…），所以这里用 {@link AgentListener#onToolResult} —— 它是
 * 唯一能拿到完整 {@link ToolResult} 的扩展点。</p>
 *
 * <p><b>注意一个真实的副作用</b>：{@code LlmAutoConfiguration} 里的内置日志监听器带
 * {@code @ConditionalOnMissingBean(AgentListener.class)}，所以一旦本类成为 bean，
 * 内置的 {@code LoggingListener} 就会<b>让位</b>（{@code llm.agent.logging-listener=true}
 * 不再有任何效果）。这正是本心 13 个可插拔切面统一的"同类型 bean 即接管"机制，
 * 不是缺陷；想两者都要，请自己组合一个 {@code CompositeListener}。</p>
 */
@Component
public class ToolAuditListener implements AgentListener {

    /** 一次工具调用的完整留痕。 */
    public record ToolObservation(String agentName, String sessionId, int step, String toolName,
                                  Map<String, Object> arguments, String content, boolean error,
                                  Map<String, Object> meta) {

        public String summary() {
            return toolName + " " + arguments;
        }
    }

    /** 一次子代理派生。 */
    public record SubAgentObservation(String parent, String child, String prompt, String conclusion,
                                      int steps) {
    }

    /** 一条自定义事件（例如 {@code todo_write} 发出的 {@code todos.updated}）。 */
    public record CustomEventObservation(String type, Object payload) {
    }

    private final List<ToolObservation> tools = new ArrayList<>();
    private final List<SubAgentObservation> subAgents = new ArrayList<>();
    private final List<CustomEventObservation> events = new ArrayList<>();

    @Override
    public void onCustomEvent(String type, Object payload) {
        synchronized (events) {
            events.add(new CustomEventObservation(type, payload));
        }
    }

    /** 按顺序的全部自定义事件。 */
    public List<CustomEventObservation> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** 取某类事件的最后一条负载。 */
    public Object lastEventPayload(String type) {
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).type().equals(type)) {
                    return events.get(i).payload();
                }
            }
            return null;
        }
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        if (invocation == null || result == null) {
            return;
        }
        synchronized (tools) {
            tools.add(new ToolObservation(invocation.agentName(), invocation.sessionId(),
                    invocation.step(), invocation.toolName(),
                    new LinkedHashMap<>(invocation.arguments()),
                    result.content(), result.error(), new LinkedHashMap<>(result.meta())));
        }
    }

    @Override
    public void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
        synchronized (subAgents) {
            subAgents.add(new SubAgentObservation(parentAgent, childAgent, prompt, null, 0));
        }
    }

    @Override
    public void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
        synchronized (subAgents) {
            for (int i = subAgents.size() - 1; i >= 0; i--) {
                SubAgentObservation existing = subAgents.get(i);
                if (existing.child().equals(childAgent) && existing.conclusion() == null) {
                    subAgents.set(i, new SubAgentObservation(existing.parent(), existing.child(),
                            existing.prompt(), result == null ? "" : result.text(),
                            result == null ? 0 : result.steps()));
                    return;
                }
            }
            subAgents.add(new SubAgentObservation(parentAgent, childAgent, null,
                    result == null ? "" : result.text(), result == null ? 0 : result.steps()));
        }
    }

    /** 按顺序的全部工具留痕。 */
    public List<ToolObservation> observations() {
        synchronized (tools) {
            return List.copyOf(tools);
        }
    }

    /** 最近一次工具留痕（演示里每组演示只看自己那一次）。 */
    public ToolObservation latest() {
        synchronized (tools) {
            return tools.isEmpty() ? null : tools.get(tools.size() - 1);
        }
    }

    /** 按工具名取最近一次留痕。 */
    public ToolObservation latestOf(String toolName) {
        synchronized (tools) {
            for (int i = tools.size() - 1; i >= 0; i--) {
                if (tools.get(i).toolName().equals(toolName)) {
                    return tools.get(i);
                }
            }
            return null;
        }
    }

    public List<SubAgentObservation> subAgentObservations() {
        synchronized (subAgents) {
            return List.copyOf(subAgents);
        }
    }

    public void reset() {
        synchronized (tools) {
            tools.clear();
        }
        synchronized (subAgents) {
            subAgents.clear();
        }
        synchronized (events) {
            events.clear();
        }
    }
}
