package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 子代理①：三种装配方式共用的**子侧** Agent。
 *
 * <p>它的提示词里带着 {@link OfflineModelConfiguration#CHILD_MARKER}，
 * 于是脚本模型的 {@code systemRule} 会直接给一句话结论 —— 子代理因此是一次
 * "单步、无工具"的运行，方便在演示里一眼看出"子代理有自己的上下文"。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.SUB_AGENT, name = "childWeatherAgent", memory = false,
        systemPrompt = "你是子代理（" + OfflineModelConfiguration.CHILD_MARKER
                + "）：只回答被问到的天气问题，一句话，不要调用任何工具。")
public interface ChildWeatherAgent {

    AgentResult ask(@User String question);
}
