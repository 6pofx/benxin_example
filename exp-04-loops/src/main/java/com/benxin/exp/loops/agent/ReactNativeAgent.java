package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code react} 的**原生 function calling 路径** Agent。
 *
 * <p>它用的模型（{@code mock}）在能力声明里写着 {@code toolCalling = true}，
 * 于是 {@code ReActLoop} 判定"这个模型会原生工具调用"，直接复用标准骨架 ——
 * 与 {@code dsh-minimal} 走的是同一条主线。</p>
 *
 * <p>与 {@link ReactTextAgent} 摆在一起看才有意义：同一个 Loop、同一批工具，
 * 只因为模型能力声明不同，就走上了两条完全不同的路径。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.GENERIC, loop = "react", memory = false,
        toolNames = {"get_weather", "get_current_time"},
        systemPrompt = "你是一个 ReAct Agent；模型支持原生工具调用时直接用工具字段表达行动。")
public interface ReactNativeAgent {

    AgentResult ask(@User String question);
}
