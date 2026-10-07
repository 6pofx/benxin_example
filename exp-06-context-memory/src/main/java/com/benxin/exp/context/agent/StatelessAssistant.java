package com.benxin.exp.context.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;

/**
 * 无记忆 Agent：{@code @LlmAgent(memory = false)} 的形态。
 *
 * <p>与"换掉 {@code MemoryStore} bean"和"用 {@code AgentBuilder.memory(false)} 派生"相比，
 * 注解级关闭影响的是<b>这个 Agent 的所有调用</b>：即使调用方老老实实传了 sessionId，
 * 历史也不会被装载、更不会被写回（{@code DefaultAgent} 里 {@code persist} 为 false 时
 * 连 {@code save} 都不调用）。</p>
 *
 * <p>注意 {@code memory = annotation.memory() && defaults.isMemory()}：
 * 注解只能往"更严格"的方向写 —— 如果 {@code llm.agent.memory=false}，
 * 这里写 {@code true} 也开不起来。</p>
 */
@LlmAgent(
        name = "statelessAssistant",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是没有会话记忆的助手，每次都从零开始。",
        maxSteps = 4,
        memory = false)
public interface StatelessAssistant {

    /** 传了 sessionId 也不会续接：这正是"注解级关闭"与"某次调用不传 sessionId"的区别。 */
    String ask(@Memory String sessionId, @User String question);
}
