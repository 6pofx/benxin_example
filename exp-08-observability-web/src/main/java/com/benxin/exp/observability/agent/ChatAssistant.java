package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 普通对话 Agent：HTTP 端点演示的主对象（同步 chat 与 SSE stream 都用它）。
 *
 * <p>刻意把 {@code maxSteps} 设为 4：一次"问天气"的运行是 2 步（请求工具 + 收尾），
 * 4 步足够，同时让 {@code AgentResult.maxStepsReached} 的取值是有意义的（正常为 false）。</p>
 */
@LlmAgent(
        name = "chatAssistant",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是本心示例 08 的助手：回答简短、直接，不要客套。",
        maxSteps = 4)
public interface ChatAssistant {

    String ask(String question);

    /** 声明成 {@link com.benxin.llm.core.agent.AgentResult} 时能拿到完整运行结果（含 toolCalls）。 */
    com.benxin.llm.core.agent.AgentResult run(String question);

    /**
     * 方法级 {@code @SystemPrompt}：<b>只在 Java 方法调用时生效</b>。
     *
     * <p>HTTP 端点 {@code POST /llm/agents/{name}/chat} 是按 Agent 名调用的，
     * 不经过方法绑定，所以调用这个端点时方法级提示词<b>完全不会</b>进入系统提示词 ——
     * 这条"按直觉猜会猜错"的口径由 {@code LlmWebEndpointTest} 用 {@code RecordingModel}
     * 抓真实请求来断言。</p>
     */
    @com.benxin.llm.core.annotation.SystemPrompt("【方法级提示词】请用一句话回答，并在结尾加一个句号。")
    String askWithMethodPrompt(@User String question);

    /**
     * 带 {@code LlmStreamHandler} 参数的重载：<b>只有把它传进来，调用方才会收到增量</b>。
     *
     * <p>与 {@link #ask(String)} 对照使用即可证明本模块第七条口径：
     * 模型支持流式 → {@code AgentListener.onTextDelta} 一定会被调用；
     * 但 {@code LlmStreamHandler} 只在你作为参数传进去时才收到增量。</p>
     */
    String askWithHandler(@User String question, com.benxin.llm.core.model.LlmStreamHandler handler);
}
