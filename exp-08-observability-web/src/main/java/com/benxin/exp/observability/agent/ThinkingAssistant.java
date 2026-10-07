package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 思考型 Agent：模型 {@code capabilities().thinking == true}，因此每一步都会产生思维链增量。
 *
 * <p>{@code onThinkingDelta} 是 {@code AgentListener} 里最容易被忽略的一个回调 ——
 * 它来自模型输出的 {@code ThinkingPart}，与 {@code onTextDelta} 走的是同一条流式通道。
 * 离线脚本化模型的 {@code thinking(true)} 会产出一段固定的"思考占位"，
 * 让这条路径在零 token 的前提下也能被断言。</p>
 */
@LlmAgent(
        name = "thinkingAssistant",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是会先思考再回答的助手。",
        maxSteps = 4)
public interface ThinkingAssistant {

    String ask(@User String question);
}
