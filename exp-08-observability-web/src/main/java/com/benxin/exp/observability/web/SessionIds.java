package com.benxin.exp.observability.web;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话登记簿：记住"本次进程里出现过哪些 sessionId"，让测试与演示能确定性清空历史。
 *
 * <p><b>它解决什么真实问题：</b>本心默认开启记忆，而 {@code DefaultAgent} 不提供
 * "列出所有会话"的能力（{@code MemoryStore} 只有 load/save/clear 三个动作）。
 * 偏偏 HTTP 端点的契约之一是"不传 {@code sessionId} 就每次生成一个新的
 * {@code http-<uuid>}" —— 这类会话的 id 是随机的，测试里根本猜不到，
 * 于是"清空历史"这件事就落不了地，跨用例的记忆污染会让断言随机失败。</p>
 *
 * <p>这里用一条极窄的路子把它接上：实现一个只有 {@code onAgentStart} 的拦截器，
 * 在每次运行开始时把 sessionId 记下来。它同时也是一个"拦截器能做监听器做不了的事"的
 * 小示范 —— 只有拦截器拿得到 {@code AgentInvocation}，而 {@code AgentListener.onAgentStart}
 * 其实也拿得到；区别在于拦截器可以拿它来<b>改</b>东西（这里也可以），
 * 监听器只应该观察。</p>
 *
 * <p>测试里的用法：</p>
 * <pre>{@code
 * sessionIds.clearAll(agentRegistry);   // 清掉所有已知会话的历史
 * }</pre>
 */
@Component
public class SessionIds {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** 记录一次看到的 sessionId（由 {@link SessionTrackingInterceptor} 调用）。 */
    public void remember(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            seen.add(sessionId);
        }
    }

    /** 清掉所有已知会话的历史，并遗忘登记（下一次调用会重新登记）。 */
    public void clearAll(AgentRegistry registry) {
        for (String name : registry.names()) {
            registry.find(name).ifPresent(agent -> clear(agent, seen));
        }
        seen.clear();
    }

    /** 清掉所有"以给定前缀开头"的会话历史 —— 用于只清理 HTTP 端点造出来的 {@code http-<uuid>}。 */
    public void clearPrefix(AgentRegistry registry, String prefix) {
        List<String> targets = seen.stream().filter(id -> id.startsWith(prefix)).toList();
        for (String name : registry.names()) {
            registry.find(name).ifPresent(agent -> {
                for (String id : targets) {
                    agent.clearHistory(id);
                }
                // 前缀会话也有可能落在 "<agentName>-default" 之外的固定名字上，这里一并兜住
                agent.clearHistory(agent.name() + "-default");
            });
        }
        targets.forEach(seen::remove);
    }

    public Set<String> seen() {
        return Set.copyOf(seen);
    }

    private static void clear(Agent agent, Set<String> ids) {
        for (String id : ids) {
            agent.clearHistory(id);
        }
        agent.clearHistory(agent.name() + "-default");
    }
}
