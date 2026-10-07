package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 步数预算演示 Agent：{@code maxSteps = 100}，会被 {@code llm.agent.hard-max-steps} 夹回去。
 *
 * <p>夹取发生在 {@code AgentBuilder.build()} 这唯一一处（{@code spec.maxSteps > hardMaxSteps}
 * 就改写成 {@code hardMaxSteps}），两条装配路径都要过它，所以"注解写 100"是拦不住夹取的。</p>
 *
 * <p>它绑的脚本模型（{@code mock-budget}）无论问什么都请求同一个工具，因此预算一定会被用完 ——
 * 这正是"步数预算"这道闸门存在的场景：模型不肯收尾时，框架替你收。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.BUDGET, loop = "dsh-minimal", memory = false,
        maxSteps = 100,
        toolNames = {"get_weather"},
        systemPrompt = "你是一个只会反复查询天气的 Agent（用于演示步数预算被耗尽）。")
public interface BudgetProbeAgent {

    AgentResult ask(@User String question);
}
