package com.benxin.exp.real.config;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.hook.AgentListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实调用的记账本：统计"调了几次模型、每次用了多少 token"。
 *
 * <p>实现方式是本心的 {@link AgentListener}：{@code onStepEnd} 每一步（= 每一次模型调用）
 * 都会带着 {@link ChatResponse} 回调一次，用量就在响应里，因此不需要解析任何日志文本。</p>
 *
 * <p>两个容易踩的点，顺手当作示例的一部分：</p>
 * <ol>
 *   <li>本 bean <b>只在 real 模式存在</b>（{@code @ConditionalOnProperty}）：离线模式下
 *       脚本化模型也会报出"估算用量"，把它算进真实消耗会让人误以为花了 token。</li>
 *   <li>一旦容器里有了 {@code AgentListener} bean，本心内置的 {@code LoggingListener} 就
 *       不再装配（{@code @ConditionalOnMissingBean(AgentListener.class)} 是<b>按类型</b>判的）。
 *       也就是说"监听器位置只有一个"，这也是 application.yml 里把
 *       {@code llm.agent.logging-listener} 显式写成 false 的原因 —— 免得读者以为两套在同时工作。</li>
 * </ol>
 *
 * <p>另外，演示里有两处是<b>绕过 Agent 直接调模型</b>的（Responses 协议对照、401 错误路径），
 * 那两次不会触发监听器，所以额外提供 {@link #record} 手工记账，保证汇总不漏账。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.real", name = "enabled", havingValue = "true")
public class RealCallMetrics implements AgentListener {

    /** 一次模型调用的记账条目。 */
    public record Call(String label, String agent, int step, String model,
                       int inputTokens, int outputTokens, String finishReason) {

        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private String label = "(未分组)";

    /** 给接下来的调用打一个分组标签（演示按小节标记，出账时一眼能对上）。 */
    public synchronized void mark(String label) {
        this.label = label == null ? "(未分组)" : label;
    }

    /** 当前分组标签。 */
    public synchronized String label() {
        return label;
    }

    @Override
    public void onStepEnd(String agentName, int step, ChatResponse response) {
        Usage usage = response == null ? Usage.ZERO : response.usage();
        String model = response == null ? "?" : response.model();
        String finish = response == null || response.finishReason() == null
                ? "?" : response.finishReason().name();
        add(new Call(label(), agentName, step, model,
                usage.inputTokens(), usage.outputTokens(), finish));
    }

    /** 手工记账（用于绕过 Agent 的直接模型调用）。 */
    public void record(String agentName, String model, Usage usage, String finishReason) {
        Usage effective = usage == null ? Usage.ZERO : usage;
        add(new Call(label(), agentName, 1, model,
                effective.inputTokens(), effective.outputTokens(),
                finishReason == null ? "?" : finishReason));
    }

    private synchronized void add(Call call) {
        calls.add(call);
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** 记账条目数（≈ 模型调用次数）。 */
    public synchronized int modelCalls() {
        return calls.size();
    }

    /** 累计用量。 */
    public synchronized Usage total() {
        Usage total = Usage.ZERO;
        for (Call call : calls) {
            total = total.plus(new Usage(call.inputTokens(), call.outputTokens()));
        }
        return total;
    }

    public synchronized void reset() {
        calls.clear();
        label = "(未分组)";
    }
}
