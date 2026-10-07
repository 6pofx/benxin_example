package com.benxin.exp.overridefixture;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.annotation.LlmModelDef;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;

/**
 * {@code @LlmModelDef} 的注册细节实验，一个类演示三条规则。
 *
 * <p>{@code LlmAutoConfiguration.llmModelRegistry()} 里那一行：</p>
 * <pre>
 *   LlmModelDef annotation = targetClass.getAnnotation(LlmModelDef.class);
 *   String name = annotation != null &amp;&amp; !annotation.value().isBlank() ? annotation.value() : model.name();
 * </pre>
 *
 * <ul>
 *   <li>{@link Annotated}：注解写了名字 → <b>注册名取注解</b>（与 {@code name()} 不同）；</li>
 *   <li>{@link Unannotated}：没标注解 → 注册名取 {@code name()}；</li>
 *   <li>{@link DefaultOne}：{@code defaultModel = true} → 在
 *       {@code llm.default-model} 没写、也没有 {@code primary: true} 的配置项时成为默认模型。</li>
 * </ul>
 *
 * <p>三个类都是 {@code LlmModel} 的最小实现（直接委派给一个脚本模型），
 * 因为它们要演示的是<b>注册规则</b>，不是模型能力。</p>
 */
public final class ModelDefFixtures {

    private ModelDefFixtures() {
    }

    /** 注解写了值：注册名 = 注解值，而不是 {@code name()}。 */
    @LlmModelDef("annotated-name")
    public static class Annotated extends Delegating {
        public Annotated() {
            super("method-name-should-be-ignored");
        }
    }

    /** 没标注解：注册名 = {@code name()}。 */
    public static class Unannotated extends Delegating {
        public Unannotated() {
            super("plain-name");
        }
    }

    /** 标了 {@code defaultModel = true}：没有更权威的默认值时它就是默认模型。 */
    @LlmModelDef(value = "annotated-default", defaultModel = true, order = 7)
    public static class DefaultOne extends Delegating {
        public DefaultOne() {
            super("default-method-name");
        }
    }

    /** 三个 fixture 共用的实现：把所有调用委派给一个离线脚本模型。 */
    public static class Delegating implements LlmModel {

        private final ScriptedLlmModel delegate;

        Delegating(String name) {
            this.delegate = ScriptedLlmModel.builder(name).fallback("（fixture 模型）{input}").build();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public ModelCapabilities capabilities() {
            return delegate.capabilities();
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
        public String toString() {
            return getClass().getSimpleName() + "(" + delegate.name() + ")";
        }
    }
}
