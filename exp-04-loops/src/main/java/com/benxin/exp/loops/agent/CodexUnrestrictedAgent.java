package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 对照组：{@code codex} + **没有** {@code systemPrompt} + 权限全开的变体 Loop。
 *
 * <p>两处刻意：</p>
 * <ol>
 *   <li><b>不写 systemPrompt</b>：codex 的兜底提示词只在 {@code ctx.systemPrompt()} 为空时注入，
 *       配了提示词它就完全不动（"不抢用户的系统提示词"）。所以只有这个 Agent
 *       才能把"权限边界如实告知"那段话打出来；</li>
 *   <li><b>用 {@code exp-codex-unrestricted}</b>：写权限与执行权限都打开，
 *       于是兜底提示词里的权限描述会与内置 {@code codex}（两者皆关）形成对照。</li>
 * </ol>
 */
@LlmAgent(model = OfflineModelConfiguration.GENERIC, loop = LoopNames.CODEX_UNRESTRICTED,
        memory = false, maxSteps = 6)
public interface CodexUnrestrictedAgent {

    AgentResult ask(@User String question);
}
