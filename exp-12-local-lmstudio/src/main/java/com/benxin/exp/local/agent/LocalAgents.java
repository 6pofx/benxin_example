package com.benxin.exp.local.agent;

import com.benxin.exp.local.tool.LocalTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;

/**
 * 本模块的四个声明式 Agent。
 *
 * <p><b>注意它们都没有写 {@code model} 属性</b> —— 模型由 {@code llm.default-model} 决定
 * （默认是离线的 {@code mock}）。要切到本机 LM Studio，演示里用的是：</p>
 *
 * <pre>{@code
 * Agent local = agentRegistry.get("localAssistant").toBuilder()
 *         .model("local-lmstudio")     // 只换模型，其余（Loop/工具/记忆/沙箱/审批）原样继承
 *         .build();
 * }</pre>
 *
 * <p>这就是"换模型不改业务代码"在本地的落地方式：业务代码里一个字都没提模型。</p>
 */
public final class LocalAgents {

    private LocalAgents() {
    }

    /** 最小对话：一个接口 + 一个注解。 */
    @LlmAgent(
            name = "localAssistant",
            systemPrompt = "你是运行在本机的小模型。回答要短，不要客套，不要输出思考过程。",
            maxSteps = 2,
            memory = false,
            temperature = 0)
    public interface LocalAssistant {

        String ask(String question);
    }

    /** 带工具的对话：验证真实模型能不能按协议发起 function calling，并把结果回灌收尾。 */
    @LlmAgent(
            name = "toolAssistant",
            tools = {LocalTools.class},
            systemPrompt = "需要当前时间或做加法时，必须调用工具，不要自己编。",
            maxSteps = 6,
            memory = false,
            temperature = 0)
    public interface ToolAssistant {

        String ask(String question);
    }

    /**
     * 视觉：Qwen3-VL 这类多模态模型的能力入口。
     *
     * <p>声明式接口的参数只支持"文本 / 上下文变量"（见 {@code LlmAgentInvocationHandler}），
     * 所以多模态要走 Agent API 手工组装消息：</p>
     *
     * <pre>{@code
     * visionAgent.call(List.of(ChatMessage.user(
     *         new TextPart("这张图里有什么？"),
     *         ImagePart.ofBase64("image/png", base64))));
     * }</pre>
     */
    @LlmAgent(
            name = "visionAssistant",
            systemPrompt = "你是图像描述助手：只描述你真实看到的内容，一句话回答，不要猜测。",
            maxSteps = 2,
            memory = false,
            temperature = 0)
    public interface VisionAssistant {

        String ask(String question);
    }

    /** 多轮记忆：同一个 sessionId 连续提问，第二问依赖第一问的实体。 */
    @LlmAgent(
            name = "memoryAssistant",
            systemPrompt = "回答尽量短。",
            maxSteps = 2,
            memory = true,
            temperature = 0)
    public interface MemoryAssistant {

        String chat(@Memory String sessionId, @User String question);
    }
}
