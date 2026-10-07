package com.benxin.exp.observability.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 工具被拦截器短路 / 被记账的 Agent。
 *
 * <p>它<b>没有</b>声明 {@code tools} / {@code toolNames}，因此继承容器的全局工具表 ——
 * "什么都不写"等于"拿到所有工具"，这是最容易猜反的一条口径，本模块在 HTTP 端点测试里
 * 也把它作为契约断言了一遍。</p>
 */
@LlmAgent(
        name = "toolGuard",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是工具拦截器演示对象：需要外部信息时先调工具。",
        maxSteps = 2)
public interface ToolGuardAssistant {

    /** 声明成 {@link AgentResult} 才能让调用方看到工具调用记录（断言短路用）。 */
    AgentResult ask(String question);
}
