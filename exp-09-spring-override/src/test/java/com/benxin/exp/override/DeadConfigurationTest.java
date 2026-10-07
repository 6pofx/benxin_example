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
    @DisplayName("AgentSpec.stream()：注解与 yml 都写得进去，但插件里没有任何一处读它")
    void streamFlagIsWrittenButNeverRead() {
        // 第一步：确认它真的被"写"进去了 —— 两个来源都生效。
        // 注解 @LlmAgent(stream = true) 或 llm.agent.stream=true，都会走到 LlmAgentFactory 里
        // 那一行 .stream(annotation.stream() || defaults.isStream())（LlmAgentFactory.java:120）。
        runner.withPropertyValues("llm.agent.stream=true")
                .withUserConfiguration(NoAnnotationApplication.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LlmAgentFactory factory = context.getBean(LlmAgentFactory.class);
                    assertThat(factory.specOf(SupportAssistant.class).stream())
                            .as("llm.agent.stream=true 确实写进了 AgentSpec.stream()")
                            .isTrue();
                });

        // 第二步：确认"没人读它"。
        // 流式与否由 DefaultLoopContext.callModel 现场判断：
        //     if (model().capabilities().streaming()) { model().stream(...) }
        //     else { ...同步通道... }                        （DefaultLoopContext.java:262-268）
        // 它看的是<b>模型能力</b>，与 AgentSpec 无关。
        // 所以同一次调用，stream=true 与 stream=false 走的是同一条代码路径、
        // 产生同样的步数与同样的回答。
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("dead-stream")
                .fallback("（离线模型）{input}")
                .build();
        RecordingModel model = new RecordingModel(scripted);

        List<String> answers = new ArrayList<>();
        for (boolean specStream : new boolean[]{false, true}) {
            scripted.reset();
            var agent = com.benxin.llm.core.agent.Agent.builder("stream-" + specStream)
                    .model(model)
                    .loop(new DshMinimalLoop())
                    .stream(specStream)                 // ← 唯一变量：这个没人读的布尔值
                    .build();
            assertThat(agent.spec().stream()).isEqualTo(specStream);

            var result = agent.call("你好");

            assertThat(result.text()).isNotBlank();
            assertThat(result.steps()).isEqualTo(1);
            assertThat(scripted.calls())
                    .as("stream=%s 时模型被调用了几次", specStream)
                    .isEqualTo(1);
            answers.add(result.text());
        }
        // 两次调用的可观测结果完全一致 —— 差别只有 spec 里那个没人读的布尔值。
        assertThat(answers.get(0)).isEqualTo(answers.get(1));
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
    @DisplayName("@LlmAgent(stream = true)：注解这条路也照样写进 spec（同样没人读）")
    void annotationStreamFlagIsAlsoWritten() {
        runner.withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            AgentSpec spec = context.getBean(LlmAgentFactory.class).specOf(RetriedAssistant.class);
            assertThat(spec.stream())
                    .as("@LlmAgent(stream = true) 确实写进了 AgentSpec.stream()")
                    .isTrue();
            // 两个"死配置"写在同一个接口上，写进去了，但都不改变行为。
            assertThat(RetriedAssistant.class.getAnnotation(LlmRetry.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("@LlmRetry：打上它不改变任何行为 —— 插件里没有任何消费点")
    void llmRetryAnnotationHasNoConsumer() {
        // 先把"注解确实在类上、属性确实写得进去"这件事钉住 ——
        // 免得将来注解被删掉之后，这条测试退化成一个空断言。
        LlmRetry annotation = RetriedAssistant.class.getAnnotation(LlmRetry.class);
        assertThat(annotation).as("@LlmRetry 应当标在这个接口上").isNotNull();
        assertThat(annotation.maxAttempts()).isEqualTo(5);
        assertThat(annotation.backoffMillis()).isEqualTo(1000);
        assertThat(annotation.multiplier()).isEqualTo(3.0);
        assertThat(annotation.includeTools()).isTrue();

        // 再钉住"没人读它"：LlmAgentFactory.specOf() 是"注解 → AgentSpec"的唯一翻译点，
        // 它把需要的注解属性逐个搬进 AgentSpec —— 而 @LlmRetry 的四个属性一个都没进去。
        runner.withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            LlmAgentFactory factory = context.getBean(LlmAgentFactory.class);

            AgentSpec spec = factory.specOf(RetriedAssistant.class);
            assertThat(spec.name()).isEqualTo("retriedAssistant");
            assertThat(specFields())
                    .as("AgentSpec 里没有任何承载 @LlmRetry 的字段")
                    .doesNotContain("retries", "maxattempts", "attempts", "backoff", "backoffmillis", "multiplier");
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
