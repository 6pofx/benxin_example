package com.benxin.exp.declarative.agent;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code tools = {...}} 的最小示范：只把指定类里的工具交给这个 Agent。
 *
 * <p>"最小权限"在声明式路径上的落地方式就是它 —— 不写 {@code tools} / {@code toolNames} 时，
 * Agent 会<b>继承容器的全局工具表</b>（"什么都不写"等于"拿到所有工具"，
 * 这是很多人会猜反的一条口径）。</p>
 */
@LlmAgent(
        name = "weatherAssistant",
        model = "mock",
        loop = "dsh-minimal",
        tools = {CommonTools.class},
        systemPrompt = "你是天气助手：需要外部信息时先调工具，拿到结果后用一两句话回答。",
        maxSteps = 5)
public interface WeatherAssistant {

    String ask(String question);
}
