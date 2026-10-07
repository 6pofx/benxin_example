package com.benxin.exp.real.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 真实工具调用闭环：让模型自己决定调用 {@code now}，工具结果回灌后再收尾。
 *
 * <p>{@code toolNames = {"now"}} 是<b>按名字点名</b>——注意它和"什么都不写"的差别：
 * 什么都不写等于继承容器全局工具表，将来别人往容器里加一个工具，
 * 这个 Agent 的每一次请求都会跟着变贵。示例里显式点名，既省 token 也把意图写清楚。</p>
 *
 * <p>{@code memory = false}：工具闭环演示只需要一次运行，不需要跨调用的历史。
 * 关掉记忆后，每次调用的请求体里只有"这一问 + 工具结果"，token 花在刀刃上。</p>
 */
@LlmAgent(
        name = "timeAssistant",
        toolNames = {"now"},
        memory = false,
        maxSteps = 3,
        temperature = 0,
        maxTokens = 48)
public interface TimeAssistant {

    /**
     * 系统提示词里明确要求"先调工具"。
     *
     * <p>这一步不能省：模型完全有能力直接编一个时间回答你，那样演示就变成
     * "模型看起来会调工具"而不是"工具真的被调用了"。断言 {@code steps() == 2}
     * 与 {@code toolCalls()} 非空，才是对"tools 声明 → tool_calls 解析 → 结果回灌"
     * 这条链路的真实验证。</p>
     */
    @SystemPrompt("必须先调用 now 工具取得服务器时间，再根据工具结果回答；不要凭猜测回答。")
    AgentResult ask(@User String question);
}
