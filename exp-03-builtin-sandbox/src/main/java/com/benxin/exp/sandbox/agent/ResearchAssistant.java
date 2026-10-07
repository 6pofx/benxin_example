package com.benxin.exp.sandbox.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 子代理：被内置 {@code task} 工具派生出来的那个"隔离开的上下文"。
 *
 * <p>它只做一件事：独立完成一段任务并回一段结论。父 Agent 拿到的永远是这段结论，
 * 看不到子代理内部的消息轨迹 —— 这正是 {@code task} 工具"只回摘要"的设计要点。</p>
 *
 * <p>{@code memory = false}：子代理是"用完即走"的执行体，把它的中间过程写回会话记忆
 * 只会污染父会话的上下文预算。</p>
 */
@LlmAgent(
        name = "researchAssistant",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是本心示例里的子代理 researchAssistant：独立完成任务，只回一段结论，不要展开过程。",
        maxSteps = 2,
        memory = false)
public interface ResearchAssistant {

    String research(@User String task);

    /** 便于示例打印子代理自己的运行事实（步数、用量）。 */
    AgentResult researchForResult(@User String task);
}
