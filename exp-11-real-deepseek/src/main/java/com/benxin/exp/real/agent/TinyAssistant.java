package com.benxin.exp.real.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.model.LlmStreamHandler;

/**
 * 最小的真实对话 Agent：一问一答、流式、多轮三条路径共用一个接口。
 *
 * <p>三处刻意的设计，都是为了"少花 token"：</p>
 * <ol>
 *   <li><b>没有写 {@code model} 属性</b>：模型来自 {@code llm.default-model}。
 *       于是默认 profile 下它是离线脚本模型（零 token），real profile 下它才变成
 *       {@code deepseek} —— "换模型不改业务代码"这句话在这里是可执行的，不是口号。</li>
 *   <li><b>没有 Agent 级系统提示词</b>：系统提示词会在每一次请求里重复计费，
 *       本演示不需要人格设定，所以一个字都不写（{@code systemPrompt} 为空 = 不发 SYSTEM 消息）。</li>
 *   <li><b>每个方法都显式带 {@code @Memory} 的 sessionId</b>：不写时所有调用都落在
 *       {@code tinyAssistant-default} 这个共享会话上，上一节的问答会成为下一节的上下文，
 *       既浪费 token 又让"这一轮到底发了什么"变得不可读。</li>
 * </ol>
 */
@LlmAgent(
        name = "tinyAssistant",
        // 一次运行最多 3 步（一问一答只需 1 步），够用且不可能演变成多轮反复试
        maxSteps = 3,
        // 温度为 0：同一个问题在 Chat Completions 与 Responses 两种协议下的对照才有意义
        temperature = 0,
        // 输出上限：抠 token 的第一道闸门
        maxTokens = 48)
public interface TinyAssistant {

    /** 完整结果：text / steps / usage / durationMillis / finishReason 全都能看到。 */
    AgentResult result(@Memory String sessionId, @User String question);

    /** 流式：多一个 {@link LlmStreamHandler} 参数即可，不需要另一套 API。 */
    String stream(@Memory String sessionId, @User String question, LlmStreamHandler handler);

    /** 多轮：同一个 sessionId 连续调用即可续接历史（第二问依赖第一问时最能看出效果）。 */
    String chatWithMemory(@Memory String sessionId, @User String question);
}
