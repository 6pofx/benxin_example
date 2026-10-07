package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * {@code @LlmAgent(stream = true)} 的样本：用来验证这个开关**真的被消费**。
 *
 * <p>1.1.0 时它与 {@code llm.agent.stream} 都是死配置 —— 写与不写行为完全一致
 * （本工程上报为 FINDINGS 第 2 条）。1.1.1 起 {@code LlmAgentController.chat} 会读
 * {@code agent.spec().stream()}：为真时 {@code POST /llm/agents/{name}/chat} 返回 <b>SSE</b>，
 * 为假时返回 JSON。</p>
 *
 * <p>所以这个 Agent 与 {@link ChatAssistant} 是天然的一组对照：
 * 两者都只声明了 {@code String ask(String)}，唯一的差别就是本接口多写了 {@code stream = true}，
 * 而 {@code LlmWebEndpointTest} 用同一个端点、同一句话去打它们，比较响应的 Content-Type。</p>
 *
 * <p>注意它管的是 <b>HTTP 通道</b>，不是"模型怎么被调用"：
 * 声明式路径对支持流式的模型本来就恒走 {@code stream()}（见 {@code DefaultLoopContext.callModel}）。</p>
 */
@LlmAgent(
        name = "streamingAssistant",
        model = "mock",
        loop = "dsh-minimal",
        stream = true,
        systemPrompt = "你是本心示例 08 的助手：回答简短、直接，不要客套。",
        maxSteps = 4)
public interface StreamingAssistant {

    String ask(String question);
}
