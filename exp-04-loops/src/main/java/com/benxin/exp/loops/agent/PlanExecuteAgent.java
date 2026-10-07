package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code plan-execute} Agent：先规划、再逐步推进、最后追加一轮汇总。
 *
 * <p>{@code maxSteps = 12} 是算出来的，不是随手写的：规划 1 步 + 每个计划步骤
 * 至少一次模型往返 + 收尾汇总 1 步。预算给窄了会以 {@code maxStepsReached = true}
 * 收尾，"计划走完再汇总"这条特征就演示不到了。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.PLAN_EXECUTE, loop = "plan-execute", memory = false,
        maxSteps = 12,
        systemPrompt = "你是一个先规划后执行的 Agent。")
public interface PlanExecuteAgent {

    AgentResult ask(@User String question);
}
