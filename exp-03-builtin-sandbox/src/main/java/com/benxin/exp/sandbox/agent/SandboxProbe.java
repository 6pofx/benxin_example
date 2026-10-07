package com.benxin.exp.sandbox.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;

/**
 * 沙箱演示的"执行体"：所有 {@code #tool:xxx {...}} 指令都由它来落地。
 *
 * <p>它刻意<b>不</b>写 {@code tools} / {@code toolNames} —— 按 exp-01 测出来的口径，
 * "什么都不写"等于继承容器的全局工具表，于是内置的 8 个工具（真实生效的那几个）与自写工具
 * 都会出现在它的工具表里。沙箱演示要看的正是"全局工具表里有什么、每个工具落地时被谁拦住"。</p>
 *
 * <p>{@code subAgents = {"researchAssistant"}} 是 {@code task} 工具能跑起来的<b>前置条件</b>：
 * 没有它，{@code ToolContext.canSpawnSubAgent()} 是 false，{@code task} 会直接返回
 * "当前 Agent 未配置子代理能力"。</p>
 */
@LlmAgent(
        name = "sandboxProbe",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是沙箱演示用的执行体：严格按指令调用工具，不要编造工具结果。",
        maxSteps = 4,
        subAgents = {"researchAssistant"})
public interface SandboxProbe {

    /** 只关心"模型最后说了什么"时用这个。 */
    String run(@User String instruction);

    /** 关心 {@code toolCalls()} / {@code attributes()} / 步数时用这个。 */
    AgentResult runForResult(@User String instruction);

    /** 审批演示需要指定 sessionId（自定义 ApprovalHandler 会按会话决定批不批）。 */
    AgentResult runInSession(@Memory String sessionId, @User String instruction);
}
