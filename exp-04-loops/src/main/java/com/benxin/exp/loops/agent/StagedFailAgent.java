package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code staged} 验收不通过：规划 → 执行 → 验收（不通过）→ 修复 → 再验收（通过）→ 汇总。
 *
 * <p>这条路径才是 {@code staged} 相对 {@code plan-execute} 的全部价值所在：
 * "步骤都跑完了"与"任务真的完成了"不是一回事，闸门就是用来分开这两件事的。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.STAGED_FAIL, loop = "staged", memory = false, maxSteps = 12,
        systemPrompt = "你是一个分阶段工作的 Agent：规划、执行、然后接受独立验收。")
public interface StagedFailAgent {

    AgentResult ask(@User String question);
}
