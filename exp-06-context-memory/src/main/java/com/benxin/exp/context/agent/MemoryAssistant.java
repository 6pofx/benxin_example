package com.benxin.exp.context.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.message.ChatMessage;

import java.util.List;

/**
 * 会话记忆示例 Agent：多轮续接、按 sessionId 隔离、历史查询。
 *
 * <p>它用默认模型 {@code mock}（窗口 64000），因此上下文切面几乎不会插手 ——
 * 观察到的现象全部来自"记忆"本身：同一个 sessionId 的历史线性增长，
 * 不同 sessionId 互不干扰。</p>
 */
@LlmAgent(
        name = "memoryAssistant",
        model = "mock",
        loop = "dsh-minimal",
        // 系统提示词里的 {agentName} / {loop} / {tools} 由 SystemPromptProvider 渲染，
        // 这几个变量必须由 AgentSpec 提供，因此它们只在"provider 路径"上有值。
        systemPrompt = "你是本心「会话记忆」示例里的助手（{agentName} 跑在 {loop} 上，工具：{tools}）。回答保持一句话。",
        maxSteps = 6,
        temperature = 0.2,
        maxTokens = 512,
        memory = true)
public interface MemoryAssistant {

    /** 不传 sessionId：所有调用都落在 {@code <agentName>-default} 这个默认会话上。 */
    String ask(@User String question);

    /** {@code @Memory} 参数的值就是 sessionId：框架自动装载 / 写回该会话的历史。 */
    String chat(@Memory String sessionId, @User String question);

    /** 需要看本次运行的完整产物（步数 / 用量 / 消息轨迹）时声明成 AgentResult。 */
    AgentResult result(@Memory String sessionId, @User String question);

    /** 声明成 {@code List<ChatMessage>} 时拿到的是本次运行的消息轨迹。 */
    List<ChatMessage> transcript(@Memory String sessionId, @User String question);
}
