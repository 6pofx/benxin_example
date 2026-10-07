package com.benxin.exp.local.config;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地调用的用量与事件统计。
 *
 * <p>本地推理不花钱，但**用量仍然值得统计**：它是判断"这个模型能不能胜任"的第一手数据
 * （一次问答吃掉多少上下文、工具闭环多花几倍），也是和 exp-11 的真实云端用量做对照的基准。</p>
 *
 * <p>顺带演示一条容易忽略的装配规则：容器里只要出现任意一个 {@code AgentListener} bean，
 * 内置的 {@code llmLoggingListener} 就会**让位**（按类型判断的 {@code @ConditionalOnMissingBean}）。
 * 想两者都要，就用 {@code CompositeListener.of(...)} 自己拼。</p>
 */
@Component
public class LocalCallMetrics implements AgentListener {

    private final List<String> runs = new ArrayList<>();
    private int toolCalls;
    private int toolErrors;
    private int inputTokens;
    private int outputTokens;
    private int steps;

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        toolCalls++;
        if (result.error()) {
            toolErrors++;
        }
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        steps += result.steps();
        inputTokens += result.usage().inputTokens();
        outputTokens += result.usage().outputTokens();
        synchronized (runs) {
            runs.add(String.format("%-18s %s | 步 %d | 输入 %d + 输出 %d | %d ms",
                    result.agentName(), result.modelName(), result.steps(),
                    result.usage().inputTokens(), result.usage().outputTokens(),
                    result.durationMillis()));
        }
    }

    public List<String> runs() {
        synchronized (runs) {
            return List.copyOf(runs);
        }
    }

    public int runCount() {
        synchronized (runs) {
            return runs.size();
        }
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int toolErrors() {
        return toolErrors;
    }

    public int steps() {
        return steps;
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public void reset() {
        synchronized (runs) {
            runs.clear();
        }
        toolCalls = 0;
        toolErrors = 0;
        inputTokens = 0;
        outputTokens = 0;
        steps = 0;
    }
}
