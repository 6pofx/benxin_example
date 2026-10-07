package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * {@code @LlmGuard} 的"生效目标"：{@code GuardInterceptor} 的注解里点名了这个 Agent。
 *
 * <p>把它与 {@link ChatAssistant} 对照着调用，就能证明范围声明真的起作用：
 * 前者会收到全部钩子，后者一个都不会收到。</p>
 */
@LlmAgent(
        name = "guardedAssistant",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是被 @LlmGuard 盯上的 Agent。",
        maxSteps = 4)
public interface GuardedAssistant {

    String ask(String question);
}
