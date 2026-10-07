package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code react} 的**文本协议降级路径**专用 Agent。
 *
 * <p>{@code toolNames = {"get_current_time"}} 只给一个工具，让演示输出干净；
 * 关键在于它绑定的模型声明了 {@code toolCalling = false} —— 于是 {@code ReActLoop}
 * 不会走结构化工具调用，而是把格式说明塞进一条 user 消息，靠文本解析推进。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.REACT_TEXT, loop = "react", memory = false,
        toolNames = {"get_current_time"},
        systemPrompt = "你是一个只会写 Action / Action Input / Final Answer 的模型。")
public interface ReactTextAgent {

    AgentResult ask(@User String question);
}
