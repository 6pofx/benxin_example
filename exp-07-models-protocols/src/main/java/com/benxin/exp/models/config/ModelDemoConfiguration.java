package com.benxin.exp.models.config;

import com.benxin.exp.models.custom.DemoProtocolCodec;
import com.benxin.exp.models.transport.RecordingHttpTransport;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Path;

/**
 * 模型装配的全部入口。
 *
 * <p>这个类刻意只做"接线"，把"接线之后会发生什么"留给 {@code ModelConfigurationDemo} 打印 ——
 * 因为本模块要演示的正是<b>这些线是怎么被插件接起来的</b>：</p>
 *
 * <ol>
 *   <li>{@link #llmHttpTransport()} 是一个 {@link HttpTransport} bean。starter 里的
 *       {@code llmHttpTransport()} 带 {@code @ConditionalOnMissingBean}，因此<b>我们这一份
 *       会接管全应用</b>：yml 里的模型、{@code LlmModel} bean、程序化装配的模型，
 *       全都走同一个传输实现。这就是"换网络栈"这条切面的落点。</li>
 *   <li>{@link #demoProtocolCodec()} 是 {@link ProtocolCodec} bean。starter 会把容器里
 *       所有 Codec 收进 {@code ProtocolRegistry}，于是 {@code protocol: demo} 立刻可用。</li>
 *   <li>{@link #decoratedOpenAiModel(ModelConfig, HttpTransport)} 是 {@link LlmModel} bean，
 *       名字取 {@code @LlmModelDef} 或 {@code name()}。它与 yml 里的同名配置<b>撞名</b> ——
 *       按 starter 的装配顺序，bean 后注册、因此 bean 胜出，这正是
 *       "配置不够用时用代码接管"的通道。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelSettings.class)
@EnableLlmAgents(basePackages = "com.benxin.exp.models.agent")
public class ModelDemoConfiguration {

    /**
     * 把 {@code ${exp.stub.base-url}} 变成"可被解析的真相"。
     *
     * <p>放在配置类里而不是工具类里，是为了让 {@link BaseUrlSupplier} 拿到真实的
     * {@code Environment}：这样"yml 里的占位符到底解析成了什么"是可以被断言的，
     * 而不是一句"应该指向本地桩服务"的口头约定。</p>
     */
    @Bean
    public BaseUrlSupplier baseUrlSupplier(ConfigurableEnvironment environment) {
        return new BaseUrlSupplier(environment);
    }

    /**
     * 录制型传输层，套在 JDK 实现外面。
     *
     * <p>两件事一起完成：<b>真的走网络</b>（连的是本地桩服务），
     * 以及把每一笔往来落盘到 {@code target/wire-log/}。
     * 后者是排障手段，也是"传输层可被装饰"的最小示范。</p>
     */
    @Bean
    public HttpTransport llmHttpTransport() {
        return new RecordingHttpTransport(new JdkHttpTransport(), Path.of("target", "wire-log"));
    }

    /** 自定义协议 Codec：注册成 bean 即可让 {@code protocol: demo} 生效。 */
    @Bean
    public ProtocolCodec demoProtocolCodec() {
        return new DemoProtocolCodec();
    }

    /**
     * 与 yml 里 {@code llm.models.openai-demo} <b>同名</b>的 {@link LlmModel} bean。
     *
     * <p>它演示"配置不够用时用代码接管"：starter 先按 yml 装配，再注册容器里的
     * {@code LlmModel} bean，后者覆盖前者 —— 因为有些东西（自定义能力声明、
     * 临时换传输层、包一层装饰器）在 yml 里根本表达不出来。</p>
     *
     * <p>注意 {@link OpenAiModel} 的三参构造器：它允许把能力声明换掉。
     * 这里把 {@code thinking} 打开（DeepSeek 的 {@code reasoning_content} 需要它），
     * 而内置 openai 预设默认是关闭的 —— 这正是"yml 表达不出来、必须用代码"的典型例子。</p>
     */
    @Bean
    public LlmModel openaiDemoModel(BaseUrlSupplier baseUrlSupplier, HttpTransport transport) {
        ModelConfig config = baseUrlSupplier.toModelConfig(new ModelSettings(
                "openai-demo",
                "openai",
                null,                       // base-url 缺失 → 回落到桩服务
                "sk-bean-override",
                "stub-gpt-bean",            // 与 yml 里的 stub-gpt 不同，可用来断言"谁赢了"
                java.util.Map.of("X-From", "bean"),
                java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(8),
                java.time.Duration.ofSeconds(20),
                0,                          // 由 ModelConfigurationDemo 手工套重试装饰器，这里不自动包
                0.4d,
                321,
                java.util.Map.of("bean_marker", true)));
        return new OpenAiModel(config, transport,
                com.benxin.llm.core.model.ModelCapabilities.builder()
                        .streaming(true)
                        .toolCalling(true)
                        .parallelToolCalls(true)
                        .vision(true)
                        .thinking(true)     // ← yml 配不出来的那一项
                        .maxContextTokens(64_000)
                        .build());
    }
}
