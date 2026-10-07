package com.benxin.exp.real.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * Loop 对照组的 B 面：{@code react}（Thought → Action → Observation）。
 *
 * <p>值得先记住的一条口径：{@code react} 有两条路 —— 模型支持原生 function calling 时走
 * <b>结构化路径</b>（与 {@code dsh-minimal} 同一条骨架），不支持时才降级成
 * {@code Action / Action Input / Final Answer} 文本协议。DeepSeek 支持原生工具调用，
 * 所以本对照组两边都会走结构化路径，"差异"恰恰在于<b>没有差异</b>：
 * 这本身就是一条有用的结论（见 README 的实测记录）。</p>
 */
@LlmAgent(
        name = "reactLoopAssistant",
        loop = "react",
        toolNames = {"now"},
        memory = false,
        maxSteps = 2,
        temperature = 0,
        maxTokens = 48)
public interface ReactLoopAssistant {

    AgentResult run(@User String task);
}
