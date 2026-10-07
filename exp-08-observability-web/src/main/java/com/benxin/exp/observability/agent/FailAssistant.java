package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 触发模型异常的 Agent：绑定到 {@code FailingModelConfiguration} 里那个只抛异常的模型。
 *
 * <p>它用来验证失败路径的两条口径：{@code AgentListener.onError} 会被调用、
 * 而 {@code onAgentEnd}（以及服务端可能写入的 {@code agent_run}）不会出现。</p>
 */
@LlmAgent(
        name = "failAssistant",
        model = "failing",
        loop = "dsh-minimal",
        systemPrompt = "这个 Agent 永远会失败，用来演示 onError。",
        maxSteps = 2)
public interface FailAssistant {

    String ask(@User String question);
}
