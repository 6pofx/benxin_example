package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 子代理装配方式③：在自己的**工具**里调 {@code ToolContext.spawn(...)}。
 *
 * <p>{@code task} 工具是框架给的那一个，而 {@code delegate_task} 是本模块自己写的
 * （见 {@link SubAgentProbeTools}）—— 它证明"工具派生子代理"是一条**公开路径**，
 * 不需要非得用内置 {@code task}。这条链路是：</p>
 *
 * <pre>
 *   delegate_task 工具 → ToolContext.spawn → DefaultLoopContext.spawn
 *     → DefaultAgent.spawn → 子代理自己的 Agent 运行
 * </pre>
 *
 * <p>同时它绑的是本模块的自定义 Loop {@code exp-decorate}（实现了 {@code SubAgentObserver}），
 * 因此这次派生会被 Loop 侧记进 {@code exp.decorate.subAgentEvents} —— 一句话看穿
 * "真实派生路径会回调观察者"这件事。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.SUB_AGENT, loop = "exp-decorate", memory = false, maxSteps = 8,
        subAgents = {AgentNames.CHILD},
        tools = {SubAgentProbeTools.class},
        systemPrompt = "你是父代理（" + OfflineModelConfiguration.PARENT_MARKER
                + "）：用 delegate_task 工具把子任务派给 [" + AgentNames.CHILD + "]。")
public interface ParentDelegateAgent {

    AgentResult ask(@User String question);
}
