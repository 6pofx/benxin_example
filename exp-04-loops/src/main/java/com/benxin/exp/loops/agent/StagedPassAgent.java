package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code staged} 一次通过：规划 → 执行 → 验收（通过）→ 汇总。
 *
 * <p>五个阶段里 {@code REPAIR} 不该出现，因此 {@code AgentResult.attributes()} 里
 * 不会出现 {@code benxin.staged.repair-rounds} —— 这条"键不存在即没修过"的约定
 * 比写一个 {@code repairRounds = 0} 更适合外部按"是否存在"判断。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.STAGED_PASS, loop = "staged", memory = false, maxSteps = 12,
        systemPrompt = "你是一个分阶段工作的 Agent：规划、执行、然后接受独立验收。")
public interface StagedPassAgent {

    AgentResult ask(@User String question);
}
