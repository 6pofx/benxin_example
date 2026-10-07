package com.benxin.exp.loops.config;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 父侧审计监听器：把每一次子代理派生记下来。
 *
 * <p>它演示的是 {@link AgentListener} 上那两个**只有派生才会触发**的回调：
 * {@code onSubAgentStart(parentAgent, childAgent, prompt)} 与
 * {@code onSubAgentEnd(parentAgent, childAgent, result)}。触发点是
 * {@code DefaultAgent.spawn} → {@code parentListener.onSubAgentStart/End}，
 * 与 {@code SubAgentObserver} 是同一处派生路径上的两个不同观察点：
 * 前者是"全局监听器"，后者是"当前 Loop 自己"。</p>
 *
 * <p><b>一个容易踩的坑：</b>{@code LlmAutoConfiguration.llmLoggingListener} 带
 * {@code @ConditionalOnMissingBean(AgentListener.class)} —— 容器里只要出现任意一个
 * {@code AgentListener} bean，内置的日志监听器就让位。本模块的 {@code application.yml}
 * 因此显式写了 {@code llm.agent.logging-listener: false}，把这件事写明而不是依赖巧合。</p>
 */
public class SubAgentAuditListener implements AgentListener {

    /** 每次派生的记账：{@code parent → child}，外加子代理跑了多少步、烧了多少 token。 */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @Override
    public void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
        synchronized (events) {
            events.add(Map.of(
                    "phase", "start",
                    "parent", parentAgent == null ? "" : parentAgent,
                    "child", childAgent == null ? "" : childAgent,
                    "promptChars", prompt == null ? 0 : prompt.length()));
        }
    }

    @Override
    public void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
        synchronized (events) {
            events.add(Map.of(
                    "phase", "end",
                    "parent", parentAgent == null ? "" : parentAgent,
                    "child", childAgent == null ? "" : childAgent,
                    "steps", result == null ? -1 : result.steps(),
                    "loopName", result == null ? "" : String.valueOf(result.loopName()),
                    "inputTokens", result == null ? 0 : result.usage().inputTokens(),
                    "outputTokens", result == null ? 0 : result.usage().outputTokens()));
        }
    }

    public List<Map<String, Object>> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public void reset() {
        synchronized (events) {
            events.clear();
        }
    }
}
