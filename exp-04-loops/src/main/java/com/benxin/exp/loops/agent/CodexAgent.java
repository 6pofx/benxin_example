package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code codex} 的 turn 制 Agent（计划 → 执行 → 验证）。
 *
 * <p>不打 {@code tools} / {@code toolNames}：codex 的"验证阶段"只按工具名判断
 * 有没有命令类工具（名字含 bash/shell/exec…）。本模块按需求**没有**打开 {@code bash}，
 * 所以验证干预不会被触发 —— 这正好用来演示"没有命令工具时，它不会硬要求验证，
 * 而是在 {@code benxin.codex.verificationNote} 里如实写清原因"。</p>
 *
 * <p>输入超过 80 字符才会进入计划阶段（{@code PLAN_THRESHOLD_CHARS}），
 * 因此演示里给的是一句长任务描述。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.GENERIC, loop = "codex", memory = false, maxSteps = 12,
        systemPrompt = "你按 Codex 的 turn 制工作：先出计划，再做最小改动，最后必须验证。")
public interface CodexAgent {

    AgentResult ask(@User String question);
}
