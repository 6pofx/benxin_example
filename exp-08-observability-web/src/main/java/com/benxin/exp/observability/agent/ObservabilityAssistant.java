package com.benxin.exp.observability.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 自定义事件与子代理事件的演示对象：绑定到 {@code EmittingLoop}（{@code @LlmLoop("exp-observe")}）。
 *
 * <p>{@code subAgents = {"task"}} 把 {@link TaskAssistant} 挂成本 Agent 的可派生对象。
 * 注意它只对"支持子代理的 Loop"有意义：{@code AbstractAgentLoop} 里的标准
 * tool-calling 骨架不认这个配置，必须由 Loop 自己调用 {@code ctx.spawnSubAgent(...)} ——
 * 本示例的 {@code EmittingLoop} 正是这么做的。</p>
 */
@LlmAgent(
        name = "observability",
        model = "mock",
        loop = "exp-observe",
        systemPrompt = "你是可观测性演示对象：需要外部信息时先调工具。",
        maxSteps = 4,
        subAgents = {"task"})
public interface ObservabilityAssistant {

    String ask(String question);

    /** 声明成 {@link AgentResult} 就能把运行结果整个拿出来 —— 用于逐字段断言。 */
    AgentResult run(String question);
}
