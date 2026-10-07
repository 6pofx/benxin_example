package com.benxin.exp.context.model;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;

/**
 * 能力装饰器：只改写 {@link ModelCapabilities}，其余全部委托。
 *
 * <p>为什么需要它：{@code exp-common} 的 {@code ScriptedLlmModel} 把 {@code vision}
 * 写死成 {@code false} 且没有暴露开关，而"模型声明支不支持视觉"恰好是本示例要演示的
 * 一条口径。装饰器是插件推荐的扩展方式（{@code RetryingLlmModel} 同款写法）：
 * 不继承、不改造被装饰的模型，只补一个能力位。</p>
 *
 * <p>注意 {@link #capabilities()} 只影响"框架与 Loop 怎么想"，
 * <b>不会让不支持视觉的模型突然能看图</b>：本心不做自动降级，图片该不该发由使用者决定。</p>
 */
public class VisionCapableModel implements LlmModel {

    private final LlmModel delegate;
    private final boolean vision;

    public VisionCapableModel(LlmModel delegate, boolean vision) {
        this.delegate = delegate;
        this.vision = vision;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ModelCapabilities capabilities() {
        ModelCapabilities c = delegate.capabilities();
        return ModelCapabilities.builder()
                .streaming(c.streaming())
                .toolCalling(c.toolCalling())
                .parallelToolCalls(c.parallelToolCalls())
                .vision(vision)
                .thinking(c.thinking())
                .maxContextTokens(c.maxContextTokens())
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(request);
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        delegate.stream(request, handler);
    }

    @Override
    public LlmModel unwrap() {
        return delegate;
    }

    public boolean vision() {
        return vision;
    }

    @Override
    public String toString() {
        return "VisionCapableModel(" + delegate + ", vision=" + vision + ")";
    }
}
