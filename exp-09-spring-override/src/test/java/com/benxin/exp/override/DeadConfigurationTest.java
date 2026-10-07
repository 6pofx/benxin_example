package com.benxin.exp.override;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.override.agent.SupportAssistant;
import com.benxin.exp.overridefixture.ctx.NoAnnotationApplication;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.annotation.LlmRetry;
import com.benxin.llm.core.loop.DshMinimalLoop;
import com.benxin.llm.core.protocol.openai.OpenAiCodec;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmAutoConfiguration;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三处「文档说它会生效、代码里却没人读」的配置 —— 断言它们的<b>真实</b>行为。
 *
 * <p>为什么值得单独一个测试类：这几条都属于"写了不报错、也看不出没生效"的类型。
 * 使用者按文档写下去会以为自己配上了；排查时又会被"注解明明在、配置明明在"带偏。
 * 把它们钉成可回归的断言，比在 README 里写一句"注意 XXX 不生效"可靠得多 ——
 * 后者会随版本腐烂，前者会在插件真的去消费它们的那一天直接变红，
 * 提醒你回来把这些注释改成"现在生效了"。</p>
 *
 * <p>被钉住的三条：</p>
 * <ol>
 *   <li>{@code AgentSpec.stream()}（以及 {@code @LlmAgent(stream = true)} / {@code llm.agent.stream}）：
 *       只被<b>写入</b>，插件里没有任何消费点；真正的流式开关是
 *       {@code ModelCapabilities.streaming()}；</li>
 *   <li>{@code @LlmRetry}：整个插件里除了它自己的定义与 README 里那行表格，没有任何一处引用它；</li>
 *   <li>{@code AgentRegistry.resolved()} 与 {@code names()} 的差别（顺带钉一条容易猜错的）。</li>
 * </ol>
 *
 * <p>{@code @LlmLoop(defaultLoop = true)} 那一处性质略有不同 —— 它有消费点，
 * 只是几乎永远轮不到 —— 所以留在 {@code AnnotationRegistrationTest} 里断言。</p>
 */
@DisplayName("示例 09 · 几处「写了不生效」的配置（断言真实行为）")
class DeadConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class));

    // ==================================================================
    // ① AgentSpec.stream() 是死配置
    // ==================================================================

    @Test
    @DisplayName("AgentSpec.stream()：1.1.1 起**有人读它了**（HTTP /chat 按它选通道）")
    void streamFlagIsWrittenAndConsumed() {
        // ① 写得进去：注解 @LlmAgent(stream = true) 与 llm.agent.stream=true 两条来源都会
        //    走到 LlmAgentFactory 里那一行 .stream(annotation.stream() || defaults.isStream())。
        runner.withPropertyValues("llm.agent.stream=true")
                .withUserConfiguration(NoAnnotationApplication.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LlmAgentFactory factory = context.getBean(LlmAgentFactory.class);
                    assertThat(factory.specOf(SupportAssistant.class).stream())
                            .as("llm.agent.stream=true 确实写进了 AgentSpec.stream()")
                            .isTrue();
                });

        // ② 1.1.1 起有了消费点：LlmAgentController.chat 会读 agent.spec().stream()，
        //    为真时改返回 SSE（否则返回 JSON）——
        //        if (agent.spec().stream()) { response.setContentType(SSE_CONTENT_TYPE); return streamResponse(...); }
        //    这正好兑现了使用者对这两个开关的直觉；1.1.0 时它们是死配置（本工程上报为 FINDINGS 第 2 条）。
        //    端到端断言（同一个 Agent 用两种设置打 /chat，比较 Content-Type）放在 exp-08 ——
        //    本模块刻意不依赖 Web，所以只在这里记录"消费点在哪里"。
        //
        //    注意：`AgentLoopContext.callModel` 是否走 stream() 仍然由**模型能力**决定
        //    （DefaultLoopContext: if (model().capabilities().streaming())），
        //    与这个开关无关 —— 开关管的是"HTTP 通道"，不是"模型调用方式"。
    }

    // ==================================================================
    // ② @LlmRetry 没有消费点
    // ==================================================================

    /**
     * 一个"同时打了 {@code @LlmAgent(stream = true)} 与 {@code @LlmRetry}"的接口样本。
     *
     * <p>{@code @LlmAgent} 是必需的：{@code LlmAgentFactory.specOf()} 要求接口上必须有它
     * （没有就直接抛）。三样东西放一起，才谈得上"哪些注解属性被消费了、哪些没有"。</p>
     */
    @com.benxin.llm.core.annotation.LlmAgent(stream = true)
    @LlmRetry(maxAttempts = 5, backoffMillis = 1000, multiplier = 3.0, includeTools = true)
    interface RetriedAssistant {
        String ask(String question);
    }

    @Test
    @DisplayName("@LlmAgent(stream = true)：注解这条路照样写进 spec（1.1.1 起该值会被端点消费）")
    void annotationStreamFlagIsAlsoWritten() {
        runner.withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            AgentSpec spec = context.getBean(LlmAgentFactory.class).specOf(RetriedAssistant.class);
            assertThat(spec.stream())
                    .as("@LlmAgent(stream = true) 确实写进了 AgentSpec.stream()")
                    .isTrue();
            assertThat(RetriedAssistant.class.getAnnotation(LlmRetry.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("@LlmRetry：1.1.1 起真的被消费 —— 工具会被 RetryingToolCallback 包一层")
    void llmRetryAnnotationIsConsumed() {
        // 先钉住"注解确实在类上、属性确实写得进去"
        LlmRetry annotation = RetriedAssistant.class.getAnnotation(LlmRetry.class);
        assertThat(annotation).as("@LlmRetry 应当标在这个接口上").isNotNull();
        assertThat(annotation.maxAttempts()).isEqualTo(5);
        assertThat(annotation.backoffMillis()).isEqualTo(1000);
        assertThat(annotation.multiplier()).isEqualTo(3.0);
        assertThat(annotation.includeTools()).isTrue();

        // 1.1.0 时整个插件里没有任何 `getAnnotation(LlmRetry.class)`
        // —— 打了不重试、不报错、也不警告（本工程上报为 FINDINGS 第 1 条）。
        // 1.1.1 新增了 LlmAgentFactory#resolveRetryPolicy（接口 + 方法级取 maxAttempts 最大者、
        // includeTools 取或）；落点是：模型调用包一层 RetryingLlmModel，
        // includeTools = true 时再给该 Agent 的每个工具包一层 RetryingToolCallback。
        // 这里用"模型确实被包了"做可观测断言：断言链路不需要任何工具、
        // 只要上下文里有一个模型 bean 就能把 Agent 装起来。
        runner.withUserConfiguration(NoAnnotationApplication.class)
                .withBean(com.benxin.llm.core.model.LlmModel.class,
                        () -> com.benxin.exp.common.model.ScriptedLlmModel.of("retry-stub"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LlmAgentFactory factory = context.getBean(LlmAgentFactory.class);

                    AgentSpec spec = factory.specOf(RetriedAssistant.class);
                    assertThat(spec.name()).isEqualTo("retriedAssistant");
                    // AgentSpec 里**仍然**没有重试字段 —— 重试不是 spec 的属性，而是装配时套的装饰器
                    assertThat(specFields())
                            .as("重试不落在 AgentSpec 上，而是包在模型/工具外面")
                            .doesNotContain("retries", "maxattempts", "attempts", "backoff",
                                    "backoffmillis", "multiplier");

                    var agent = factory.createFromInterface(RetriedAssistant.class);
                    assertThat(agent.model().getClass().getSimpleName())
                            .as("1.1.1：@LlmRetry 生效，模型被 RetryingLlmModel 包住")
                            .isEqualTo("RetryingLlmModel");
                });

        // 对照：真正能配出重试的地方是 ModelConfig.maxRetries（yml 的 llm.models.*.max-retries），
        // 由 ModelFactory.create 自动包一层 RetryingLlmModel —— 与 @LlmRetry 完全无关。
        assertThat(LlmProperties.ModelProperties.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .contains("getMaxRetries", "setMaxRetries");
    }

    /** 反射列出 {@link AgentSpec} 的全部实例字段名（小写），用来断言"没有承载重试的字段"。 */
    private static List<String> specFields() {
        List<String> names = new ArrayList<>();
        for (Field field : AgentSpec.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                names.add(field.getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // ==================================================================
    // ③ 真正的流式开关在哪
    // ==================================================================

    @Test
    @DisplayName("真正的流式开关是模型能力 capabilities().streaming() —— 不是 AgentSpec.stream()")
    void realStreamingSwitchIsModelCapability() {
        // 内置 codec 都把 streaming 声明成 true（OpenAiCodec.RECOMMENDED_CAPABILITIES 等），
        // 所以走 HTTP 的模型在声明式路径上一律走 stream(...)。
        // 想让某条链路走同步通道，只能换一个 streaming=false 的模型/编解码器。
        assertThat(new OpenAiCodec().capabilities().streaming()).isTrue();
        assertThat(OpenAiCodec.RECOMMENDED_CAPABILITIES.streaming()).isTrue();
        assertThat(OpenAiCodec.RECOMMENDED_CAPABILITIES.toolCalling()).isTrue();
    }

    // ==================================================================
    // ④ 惰性注册表的口径
    // ==================================================================

    @Test
    @DisplayName("AgentRegistry：resolved() 只看已解析的，names() 含全部惰性条目 —— 遍历要用 names() + find()")
    void registryNamesVersusResolved() {
        runner.withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            AgentRegistry registry = context.getBean(AgentRegistry.class);

            // 这个上下文里没有任何 @LlmAgent 接口，两者都是空的 ——
            // 但它们是两套口径，不是"同一个东西的两个名字"。
            assertThat(registry.names()).isEmpty();
            assertThat(registry.resolved()).isEmpty();
            assertThat(registry.isEmpty()).isTrue();

            // 手工登记一个惰性条目：它立刻出现在 names() 里，但不在 resolved() 里。
            registry.registerLazy("lazy-one", () -> {
                throw new IllegalStateException("本用例不该真的构建它");
            });
            assertThat(registry.names()).containsExactly("lazy-one");
            assertThat(registry.resolved()).doesNotContainKey("lazy-one");
            assertThat(registry.isEmpty()).isFalse();

            // 而 find() 才会把它解析成真身 —— 所以"遍历全部 Agent"要写 names() + find()。
            assertThat(registry.find("找不到的名字")).isEmpty();
        });
    }
}
