package com.benxin.exp.real.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * Loop 对照组的 A 面：{@code dsh-minimal}（本心的默认 Loop，最小内核）。
 *
 * <p>两个对照组接口除了 {@code loop} 与名字之外完全一样 —— 工具表、步数预算、
 * 采样参数都相同，这样"steps / usage / 回答差异"才归因得到 Loop 本身。</p>
 */
@LlmAgent(
        name = "minimalLoopAssistant",
        loop = "dsh-minimal",
        toolNames = {"now"},
        memory = false,
        maxSteps = 2,
        temperature = 0,
        maxTokens = 48)
public interface MinimalLoopAssistant {

    AgentResult run(@User String task);
}
