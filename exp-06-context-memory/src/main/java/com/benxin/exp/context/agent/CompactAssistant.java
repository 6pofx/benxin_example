package com.benxin.exp.context.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;

/**
 * 压缩演示 Agent：小窗口模型 + 普通 Loop。
 *
 * <p>它的全部意义在于 {@code model = "tiny"}：{@code ScriptedLlmModel} 的窗口被设成 6000，
 * 配合本示例的 {@code (threshold = 0.8, reservedOutputTokens = 4096)}，
 * 触发线只有 1523 token —— 几十条离线消息就能越过去，于是
 * {@code SummarizingCompactor} 的"额外一次模型调用"在离线环境里也能被稳定复现。</p>
 *
 * <p>如果换成默认的窗口 64000，触发线是 47923 token，离线示例永远到不了，
 * 压缩相关的代码路径就变成了"写了但从没跑过"。</p>
 */
@LlmAgent(
        name = "compactAssistant",
        model = "tiny",
        loop = "dsh-minimal",
        systemPrompt = "你是上下文压缩示例助手，回答保持一句话。",
        maxSteps = 4,
        memory = true)
public interface CompactAssistant {

    String chat(@Memory String sessionId, @User String question);
}
