package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.tool.CodexPatchTools;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code codex} + {@code apply_patch} 专用格式演示 Agent。
 *
 * <p>只给 {@link CodexPatchTools} 一个工具，于是"模型吐补丁 → 工具落盘 → 结果回灌"
 * 这条链路在输出里是唯一的剧情，不会被别的工具干扰。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.CODEX_PATCH, loop = "codex", memory = false, maxSteps = 8,
        tools = {CodexPatchTools.class},
        systemPrompt = "你现在切换到补丁演示模式：所有改动都必须以 *** Begin Patch 补丁的形式给出。")
public interface CodexPatchAgent {

    AgentResult ask(@User String question);
}
