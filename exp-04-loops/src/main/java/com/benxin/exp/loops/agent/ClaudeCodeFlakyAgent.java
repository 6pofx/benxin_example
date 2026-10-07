package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code claude-code} 的**连续工具错误熔断**专用 Agent。
 *
 * <p>{@code maxSteps = 4} 是刻意的：让"两次失败 → 第三次调用前注入提醒 → 预算用尽收尾"
 * 这套时序能在一屏输出里看完，不必等它自然收敛。</p>
 *
 * <p>它绑的是 {@code FlakyTools}（一个必然抛异常的工具），
 * 因为熔断的判据是工具结果上的 {@code error} 标志 —— {@code CommonTools} 里的工具
 * 即使返回"错误：xxx"这种文本，{@code error} 也是 false，永远触发不了熔断。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.GENERIC, loop = "claude-code", memory = false, maxSteps = 4,
        toolNames = {"flaky_query"},
        systemPrompt = "你是一个会调用不稳定工具的助手，失败时要先读错误再决定下一步。")
public interface ClaudeCodeFlakyAgent {

    AgentResult ask(@User String question);
}
