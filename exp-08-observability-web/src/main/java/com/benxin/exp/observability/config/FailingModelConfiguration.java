package com.benxin.exp.observability.config;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 一个"永远失败的模型"，专门用来演示失败路径的可观测性。
 *
 * <p>失败路径很难用脚本化模型触发：脚本模型总能给出一段兜底回答，于是
 * {@code onError} 与"失败时不产生 {@code agent_run}"这两条口径就永远验证不到。
 * 这里直接给一个只会抛 {@link ModelException} 的 {@link LlmModel}，把它装配给
 * {@code failAssistant} 这一个 Agent，其余 Agent 完全不受影响 ——
 * 这也顺带示范了"模型按名字装配"的可插拔方式。</p>
 *
 * <p>{@code stream()} 显式覆写为"抛异常"而不是依赖默认实现：默认实现会先回调
 * {@code onStart} 再转调 {@code chat()}，虽然同样能抛出，但事件序列里会多一个
 * 语义上并不成立的 {@code onStart}。真实模型连接失败时也不会先告诉你"开始流式了"。</p>
 */
@Configuration
public class FailingModelConfiguration {

    /** 注册名（= {@code name()}）为 {@code failing}，供 {@code @LlmAgent(model = "failing")} 引用。 */
    @Bean
    public LlmModel failingLlmModel() {
        return new FailingModel();
    }

    /** 模型实现：任何调用都抛 {@code ModelException}。 */
    public static class FailingModel implements LlmModel {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.DEFAULT;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new ModelException("演示用的模型故障：上游返回 503（这条异常是刻意制造的）");
        }

        @Override
        public void stream(ChatRequest request, LlmStreamHandler handler) {
            throw new ModelException("演示用的模型故障：上游返回 503（这条异常是刻意制造的）");
        }

        @Override
        public String toString() {
            return "FailingModel(failing)";
        }
    }
}
