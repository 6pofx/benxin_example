package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 对照组：**同样有 {@code delegate_task} 工具，但没有声明任何子代理**。
 *
 * <p>它用来把"工具在不在"和"能力开没开"这两件事分开：
 * 工具照样暴露给模型、照样被调用，但 {@code ToolContext.canSpawnSubAgent()} 为 false
 * （{@code DefaultLoopContext.supportsSubAgents()} 只看 {@code spec.subAgents()} 是否为空），
 * 于是工具返回一条明确的错误结果，而不是抛异常炸掉整条链路。</p>
 *
 * <p>这也是 {@code SubAgentProbeTools} 里那段 {@code canSpawnSubAgent()} 判断存在的意义：
 * 工具作者应当先问能力、再动手，把"没这个能力"变成一次可读的工具错误。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.SUB_AGENT, loop = "exp-decorate", memory = false, maxSteps = 6,
        tools = {SubAgentProbeTools.class},
        systemPrompt = "你是一个没有子代理能力的 Agent（" + OfflineModelConfiguration.PARENT_MARKER + "）。")
public interface ParentNoSubAgent {

    AgentResult ask(@User String question);
}
