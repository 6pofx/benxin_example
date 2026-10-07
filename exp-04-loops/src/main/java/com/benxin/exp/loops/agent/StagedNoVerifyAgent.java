package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code staged} 关掉验收闸门（{@code new StagedLoop(false, 0)}）：
 * 退化成"带汇总的 plan-execute"。
 *
 * <p>它绑的 {@code exp-staged-no-verify} 是 {@code LoopConfiguration} 里用
 * {@code new StagedLoop(false, 0)} 显式构造的实例 —— 这也是"用一个现成 Loop 类做变体配置"
 * 的写法：不需要新写一个 Loop 类，只要换一个注册名。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.STAGED_NO_VERIFY, loop = LoopNames.STAGED_NO_VERIFY,
        memory = false, maxSteps = 12,
        systemPrompt = "你是一个分阶段工作的 Agent（本 Agent 关闭了验收闸门）。")
public interface StagedNoVerifyAgent {

    AgentResult ask(@User String question);
}
