package com.benxin.exp.loops;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.DshMinimalLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.spring.LlmAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @LlmLoop(defaultLoop = true, order = ...)} 的独立上下文验证。
 *
 * <p>为什么不放在主测试上下文里：本模块的 {@code application.yml} 写了
 * {@code llm.agent.default-loop = exp-decorate}，而它的优先级**高于**注解 ——
 * 在主上下文里根本验证不到注解这条路。用一个独立的 {@link ApplicationContextRunner}
 * 只装 {@link LlmAutoConfiguration} + 探针 Loop，才能干净地看到默认 Loop 是怎么选出来的。</p>
 *
 * <p><b>本类钉住的两条实测行为（都与插件文档不符，已记入 README「插件问题」）：</b></p>
 *
 * <ol>
 *   <li><b>{@code @LlmLoop(defaultLoop = true)} 这条分支实际上不可达。</b>
 *       {@code LlmAutoConfiguration.llmLoopRegistry} 的选举顺序是
 *       「配置了 {@code llm.agent.default-loop} 且注册表里有 → 用它；否则用第一个
 *       {@code defaultLoop = true} 的注解 bean；否则 {@code "dsh-minimal"}」，
 *       看起来注解有机会。但 {@code LlmProperties.AgentProperties.defaultLoop}
 *       的<b>字段初值就是 {@code "dsh-minimal"}</b>（不是 null），而 {@code dsh-minimal}
 *       又是内置 Loop —— 于是第一个条件<b>永远成立</b>，第二、三个分支一次都走不到。
 *       本类因此不断言"注解能成为默认"，而是断言"注解被无条件覆盖"。</li>
 *   <li><b>{@code @LlmLoop.order()} 完全没有被消费。</b>
 *       注解文档写着"同时存在多个时按 order 取最小者"，但实现里
 *       {@code if (annotation != null && annotation.defaultLoop() && annotatedDefault == null)}
 *       取的是 {@code loopBeans.orderedStream()} 里第一个带 {@code defaultLoop = true} 的 bean，
 *       {@code order()} 一个字符都没读。全仓检索 {@code .order()} 只命中
 *       {@code SelectiveInterceptor}（那是 {@code AgentInterceptor} 的 order，与 Loop 无关）。</li>
 * </ol>
 *
 * <p>要把注解那条路真正跑起来，只能显式把属性写成注解声明的名字
 * （{@code llm.agent.default-loop=…}）—— 那已经是"配置优先"，不是"注解生效"了。
 * 见 {@link #configuredDefaultWinsOverAnnotation}。</p>
 */
@DisplayName("示例 04 · @LlmLoop(defaultLoop) 与默认 Loop 优先级")
class DefaultLoopAnnotationTest {

    /** 注意：这里**不包含** {@code application.yml}。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class));

    @Test
    @DisplayName("探针 Loop 会被注册进注册表，但默认 Loop 仍是字段初值 dsh-minimal")
    void annotatedLoopIsRegisteredButNeverBecomesDefault() {
        runner.withUserConfiguration(SingleProbeConfiguration.class).run(context -> {
            LoopRegistry registry = context.getBean(LoopRegistry.class);

            assertThat(context).hasNotFailed();
            // 注册这一步是好的：@LlmLoop.value() 就是它在注册表里的名字
            assertThat(registry.names()).contains("dsh-minimal", "exp-annotated-default");

            // 实测：@LlmLoop(defaultLoop = true) **没有**成为默认。
            // 原因不是注解没被读到（下面那条断言证明它读到了），而是
            // LlmProperties.AgentProperties.defaultLoop 的字段初值就是 "dsh-minimal"，
            // 于是"配置里写了 default-loop"这个条件永远为真，注解分支不可达。
            assertThat(registry.defaultName())
                    .as("实测：注解的 defaultLoop = true 被字段初值 dsh-minimal 无条件覆盖")
                    .isEqualTo(BuiltinLoops.DEFAULT_LOOP);
            assertThat(registry.defaultLoop()).isInstanceOf(DshMinimalLoop.class);
        });
    }

    @Test
    @DisplayName("两个 defaultLoop 探针同时存在：先注册者胜出，order() 不参与（实测行为）")
    void firstRegisteredDefaultWinsNotLowestOrder() {
        runner.withUserConfiguration(TwoProbeConfiguration.class).run(context -> {
            LoopRegistry registry = context.getBean(LoopRegistry.class);

            assertThat(context).hasNotFailed();
            assertThat(registry.names()).contains("exp-annotated-default", "exp-annotated-default-low");

            // 两个探针都写了 defaultLoop = true，注解上的 order 分别是 10 与 0，
            // 但默认 Loop 两者都不是 —— 见上一个用例。
            // 这里把"两个探针都在、顺序不影响结果"这件事钉住：无论谁先注册，
            // 默认都由字段初值决定，order() 读都没被读。
            assertThat(registry.defaultName()).isEqualTo(BuiltinLoops.DEFAULT_LOOP);
        });
    }

    @Test
    @DisplayName("@LlmLoop(order) 读都没被读：注解上的值可以取到，但选举路径上没有任何消费者")
    void orderIsNeverConsumed() {
        runner.withUserConfiguration(TwoProbeConfiguration.class).run(context -> {
            // 注解值与它在注册表里的名字都能读到 —— 说明"读注解"这件事本身是通的
            assertThat(AnnotatedDefaultLoop.class.getAnnotation(LlmLoop.class).order()).isEqualTo(10);
            assertThat(LowOrderDefaultLoop.class.getAnnotation(LlmLoop.class).order()).isEqualTo(0);

            // 但 order 更小的那个并没有因此胜出：无论怎么组合，默认都是字段初值。
            // 若 order() 参与选举，order = 0 的 exp-annotated-default-low 必然胜出。
            assertThat(context.getBean(LoopRegistry.class).defaultName())
                    .isNotEqualTo("exp-annotated-default-low")
                    .isEqualTo(BuiltinLoops.DEFAULT_LOOP);
        });
    }

    @Test
    @DisplayName("把属性显式写成注解声明的名字时它能当上默认 —— 走的是配置那条路，不是注解")
    void configuredDefaultWinsOverAnnotation() {
        runner.withUserConfiguration(TwoProbeConfiguration.class)
                .withPropertyValues("llm.agent.default-loop=exp-annotated-default-low")
                .run(context -> {
                    LoopRegistry registry = context.getBean(LoopRegistry.class);

                    assertThat(context).hasNotFailed();
                    assertThat(registry.defaultName()).isEqualTo("exp-annotated-default-low");
                    assertThat(registry.defaultLoop()).isInstanceOf(LowOrderDefaultLoop.class);
                });
    }

    @Test
    @DisplayName("llm.agent.default-loop 配了名字时，它优先于 @LlmLoop(defaultLoop = true)")
    void configuredDefaultWins() {
        runner.withUserConfiguration(SingleProbeConfiguration.class)
                .withPropertyValues("llm.agent.default-loop=react")
                .run(context -> {
                    LoopRegistry registry = context.getBean(LoopRegistry.class);

                    assertThat(context).hasNotFailed();
                    assertThat(registry.defaultName()).isEqualTo("react");
                    assertThat(registry.defaultLoop().name()).isEqualTo("react");
                });
    }

    @Test
    @DisplayName("既没有配置也没有 defaultLoop 注解时，回落到写死的 dsh-minimal")
    void fallsBackToDshMinimal() {
        runner.run(context -> {
            LoopRegistry registry = context.getBean(LoopRegistry.class);

            assertThat(context).hasNotFailed();
            assertThat(registry.defaultName()).isEqualTo("dsh-minimal");
        });
    }

    @Test
    @DisplayName("自定义 Loop 无论是否默认，都会以 @LlmLoop.value() 的名字进注册表")
    void customLoopsAreRegisteredByName() {
        runner.withUserConfiguration(TwoProbeConfiguration.class).run(context -> {
            LoopRegistry registry = context.getBean(LoopRegistry.class);

            assertThat(registry.find("exp-annotated-default")).isPresent();
            assertThat(registry.find("exp-annotated-default-low")).isPresent();
            assertThat(registry.get("exp-annotated-default")).isInstanceOf(AnnotatedDefaultLoop.class);
        });
    }

    // ------------------------------------------------------------------
    // 探针 Loop：测试专用，刻意放在主包之外 —— 它们不会被主应用的组件扫描发现。
    // ------------------------------------------------------------------

    /** {@code defaultLoop = true, order = 10}。 */
    @LlmLoop(value = "exp-annotated-default", defaultLoop = true, order = 10)
    static class AnnotatedDefaultLoop extends AbstractAgentLoop {

        @Override
        public String name() {
            return "exp-annotated-default";
        }

        @Override
        public String description() {
            return "测试探针：@LlmLoop(defaultLoop = true, order = 10)";
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return runToolCallingLoop(ctx);
        }
    }

    /** {@code defaultLoop = true, order = 0}：order 更小，应当胜出。 */
    @LlmLoop(value = "exp-annotated-default-low", defaultLoop = true, order = 0)
    static class LowOrderDefaultLoop extends AbstractAgentLoop {

        @Override
        public String name() {
            return "exp-annotated-default-low";
        }

        @Override
        public String description() {
            return "测试探针：@LlmLoop(defaultLoop = true, order = 0)";
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return runToolCallingLoop(ctx);
        }
    }

    @Configuration
    static class SingleProbeConfiguration {

        @Bean
        AgentLoop annotatedDefaultLoopBean() {
            return new AnnotatedDefaultLoop();
        }
    }

    @Configuration
    static class TwoProbeConfiguration {

        @Bean
        AgentLoop annotatedDefaultLoopBean() {
            return new AnnotatedDefaultLoop();
        }

        @Bean
        AgentLoop lowOrderDefaultLoopBean() {
            return new LowOrderDefaultLoop();
        }
    }
}
