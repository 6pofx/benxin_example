package com.benxin.exp.override;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SummarizingCompactor;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.LoggingListener;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmAgentFactoryBean;
import com.benxin.llm.spring.LlmAgentRegistrar;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmAutoConfiguration;
import com.benxin.llm.spring.LlmBuiltinToolRegistrar;
import com.benxin.llm.spring.LlmComponentRegistrar;
import com.benxin.llm.spring.LlmToolCatalog;
import com.benxin.exp.overridefixture.cb.CustomBeans;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @ConditionalOnMissingBean} 的机制演示 —— 有 / 无自定义 bean 的对照实验。
 *
 * <p>为什么必须用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * "用户 bean 优先"这个结论需要<b>两个容器</b>才能证明 ——
 * 一个是干净的（默认实现生效），一个是加了自定义 bean 的（默认实现让位）。
 * 在同一个上下文里只能看到后一半，那就退化成"复述结论"了。</p>
 *
 * <p>对照实验的写法是同一份断言跑两遍（见 {@link Experiment}）：</p>
 * <ul>
 *   <li><b>无自定义 bean</b>：容器里是 {@code LlmAutoConfiguration} 的默认实现；</li>
 *   <li><b>有自定义 bean</b>：同一个类型位置换成我的实现，而且<b>没有</b>任何开关、
 *       没有任何 {@code exclude}、没有任何 {@code @Primary}。</li>
 * </ul>
 *
 * <p>还顺带把"基础设施 bean"这条例外钉住：{@code LlmToolCatalog} 与
 * {@code LlmComponentRegistrar} 是 static 定义的、必须早于普通 bean 就绪，
 * 因此它们<b>不在</b>可替换清单里；{@code LlmBuiltinToolRegistrar} 的开关是配置。</p>
 */
@DisplayName("示例 09 · @ConditionalOnMissingBean 的有/无对照实验")
class ConditionalOnMissingBeanMechanismTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class));

    /** 一个自定义实现的样板：只要类型对得上，内容是什么并不重要。 */
    @Nested
    @DisplayName("有 / 无 自定义 bean 的对照")
    class Experiment {

        @Test
        @DisplayName("切面 1 HttpTransport：无自定义 bean → JdkHttpTransport；有 → 我的（无需开关、无需 exclude）")
        void httpTransport() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(HttpTransport.class);
                assertThat(context.getBean(HttpTransport.class)).isInstanceOf(JdkHttpTransport.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomTransport.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(HttpTransport.class);
                assertThat(context.getBean(HttpTransport.class)).isInstanceOf(CustomBeans.MyTransport.class);
                // 关键：默认实现连 bean 定义都没有被实例化 —— 不是"并存"，是"让位"。
                assertThat(context.getBeanNamesForType(HttpTransport.class)).hasSize(1);
            });
        }

        @Test
        @DisplayName("切面 8 MemoryStore：无自定义 bean → InMemoryMemoryStore；有 → 我的")
        void memoryStore() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MemoryStore.class)).isInstanceOf(InMemoryMemoryStore.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomMemoryStore.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(MemoryStore.class);
                assertThat(context.getBean(MemoryStore.class)).isInstanceOf(CustomBeans.MyMemoryStore.class);
            });
        }

        @Test
        @DisplayName("切面 9 SystemPromptProvider：无自定义 bean → TemplateSystemPromptProvider；有 → 我的")
        void systemPromptProvider() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(SystemPromptProvider.class))
                        .isInstanceOf(TemplateSystemPromptProvider.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomPromptProvider.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SystemPromptProvider.class);
                assertThat(context.getBean(SystemPromptProvider.class)).isInstanceOf(CustomBeans.MyPromptProvider.class);
            });
        }

        @Test
        @DisplayName("切面 6 ContextCompactor：无自定义 bean → SummarizingCompactor；有 → 我的")
        void contextCompactor() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ContextCompactor.class)).isInstanceOf(SummarizingCompactor.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomCompactor.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ContextCompactor.class);
                assertThat(context.getBean(ContextCompactor.class)).isInstanceOf(CustomBeans.MyCompactor.class);
            });
        }

        @Test
        @DisplayName("切面 7 ContextManager：换掉压缩器之后，默认 ContextManager 拿到的也是我的压缩器")
        void contextManagerPicksUpTheCustomCompactor() {
            runner.withUserConfiguration(CustomBeans.CustomCompactor.class).run(context -> {
                assertThat(context).hasNotFailed();
                ContextManager manager = context.getBean(ContextManager.class);
                assertThat(manager).isInstanceOf(DefaultContextManager.class);
                // 这就是"切面之间靠构造器注入连成一张网"的证据：
                // 我没换 ContextManager，但它已经用上了我换的压缩器。
                assertThat(customCompactorSeenBy(context)).isInstanceOf(CustomBeans.MyCompactor.class);
            });
        }

        @Test
        @DisplayName("切面 12 AgentListener：条件按类型判 —— 自定义监听器顶掉内置 LoggingListener（而不是并存）")
        void agentListener() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AgentListener.class)).isInstanceOf(LoggingListener.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomListener.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AgentListener.class);
                assertThat(context.getBean(AgentListener.class)).isInstanceOf(CustomBeans.MyListener.class);
            });
        }

        @Test
        @DisplayName("切面 14/15 AgentRegistry 与 LlmAgentFactory：定义同类型 bean 就整体接管")
        void agentRegistryAndFactory() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AgentRegistry.class)).isExactlyInstanceOf(AgentRegistry.class);
                assertThat(context.getBean(LlmAgentFactory.class)).isExactlyInstanceOf(LlmAgentFactory.class);
            });

            runner.withUserConfiguration(CustomBeans.CustomRegistryAndFactory.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AgentRegistry.class)).isInstanceOf(CustomBeans.MyAgentRegistry.class);
                assertThat(context.getBean(LlmAgentFactory.class)).isInstanceOf(CustomBeans.MyAgentFactory.class);
            });
        }

        private Object customCompactorSeenBy(org.springframework.context.ApplicationContext context) {
            // DefaultContextManager 把 compactor 收在私有字段里，没有 getter。
            // 用反射读它，只为把"依赖真的被替换了"这一点断言出来 ——
            // 比"断言两个 bean 都存在"强得多。
            try {
                java.lang.reflect.Field field = DefaultContextManager.class.getDeclaredField("compactor");
                field.setAccessible(true);
                return field.get(context.getBean(ContextManager.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法读取 DefaultContextManager.compactor", e);
            }
        }
    }

    // ==================================================================
    // 基础设施 bean：刻意的例外
    // ==================================================================

    @Test
    @DisplayName("基础设施 bean 一：LlmToolCatalog 是 BeanPostProcessor，注册表里的定义必须是 static 的 @Bean")
    void toolCatalogIsStaticInfrastructure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BeanDefinition definition = beanFactoryOf(context).getBeanDefinition("llmToolCatalog");
            assertThat(definition.getFactoryMethodName()).isEqualTo("llmToolCatalog");
            // 这个方法在配置类上带 static。对 static 的 @Bean 方法，Spring 走的是
            // "静态工厂方法"那条路（beanClass 就是返回类型，factoryBeanName 为 null）——
            // 也正因为如此，它才能不实例化配置类就拿到这个 BeanPostProcessor。
            // 反过来看更清楚：非 static 的 @Bean 方法定义的 bean，factoryBeanName 才是配置类名。
            assertThat(definition.getFactoryBeanName()).isNull();
            // 它就是 BeanPostProcessor：Spring 必须在普通 bean 实例化之前把它准备好。
            assertThat(context.getBean(LlmToolCatalog.class))
                    .isInstanceOf(org.springframework.beans.factory.config.BeanPostProcessor.class);
        });
    }

    @Test
    @DisplayName("基础设施 bean 二：LlmComponentRegistrar 是 BeanDefinitionRegistryPostProcessor，必须早于普通 bean 就绪")
    void componentRegistrarIsRegistryPostProcessor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmComponentRegistrar registrar = context.getBean(LlmComponentRegistrar.class);
            // BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor，
            // 而 BeanFactoryPostProcessor 在整个容器里是第一批被回调的 ——
            // 这正是"打个 @LlmLoop 就能被 ObjectProvider<AgentLoop> 看到"能成立的原因。
            assertThat(registrar).isInstanceOf(BeanDefinitionRegistryPostProcessor.class);
            assertThat(registrar).isInstanceOf(BeanFactoryPostProcessor.class);
        });
    }

    @Test
    @DisplayName("基础设施 bean 三：LlmBuiltinToolRegistrar 的开关是配置（@ConditionalOnProperty），不是类型")
    void builtinToolRegistrarIsConfigDriven() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LlmBuiltinToolRegistrar.class));

        runner.withPropertyValues("llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=.",
                        "llm.tools.allow-write=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmBuiltinToolRegistrar.class);
                });
    }

    @Test
    @DisplayName("对照组：普通 bean 是构造器参数 —— 换掉 ModelRegistry，LlmAgentFactory 拿到的就是新的")
    void facetsAreWiredByConstructor() {
        runner.withUserConfiguration(CustomBeans.CustomModelRegistry.class,
                        CustomBeans.WithOneModel.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelRegistry.class)).isInstanceOf(CustomBeans.MyModelRegistry.class);
                    // 工厂本身没被替换，但它内部的 modelRegistry 已经是我的了。
                    LlmAgentFactory factory = context.getBean(LlmAgentFactory.class);
                    assertThat(factory.modelRegistry()).isInstanceOf(CustomBeans.MyModelRegistry.class);
                });
    }

    /** 从 {@code ApplicationContext} 里安全地拿到 {@code ConfigurableListableBeanFactory}（读 bean 定义用）。 */
    private static ConfigurableListableBeanFactory beanFactoryOf(org.springframework.context.ApplicationContext context) {
        // 注意这里<b>不能</b>写成 context.getBean(ConfigurableListableBeanFactory.class)：
        // Spring 从不把 BeanFactory 本身登记成一个 bean（它只是内部通过
        // ApplicationContextAware / BeanFactoryAware 暴露出去），所以那句会抛
        // NoSuchBeanDefinitionException。
        // 正确做法是沿 ApplicationContext 的接口走下去：ConfigurableApplicationContext#getBeanFactory()。
        return ((org.springframework.context.ConfigurableApplicationContext) context).getBeanFactory();
    }

    // ==================================================================
    // 顺手核对一下本心到底"内建"了哪些 bean —— 这份清单就是可替换点的全集
    // ==================================================================

    @Test
    @DisplayName("最小配置下，本心装配出的 bean 清单（这份清单就是可替换点的全集）")
    void minimalBeanInventory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(TokenEstimator.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(MemoryStore.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(LoopRegistry.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ModelRegistry.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ContextManager.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ContextCompactor.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SystemPromptProvider.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(com.benxin.llm.core.sandbox.ApprovalHandler.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(com.benxin.llm.core.sandbox.ToolSandbox.class)).hasSize(1);
            LoopRegistry loops = context.getBean(LoopRegistry.class);
            assertThat(loops.names()).containsExactlyInAnyOrderElementsOf(BuiltinLoops.NAMES);
            assertThat(loops.defaultName()).isEqualTo(BuiltinLoops.DEFAULT_LOOP);
        });
    }

    @Test
    @DisplayName("干扰项排除：这些名字听上去像「切面」，其实不是 bean（别去覆盖它们）")
    void nonBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // LlmAgentRegistrar 是 @EnableLlmAgents 的 ImportBeanDefinitionRegistrar，不是 bean。
            assertThat(context).doesNotHaveBean(LlmAgentRegistrar.class);
            // 没写 @EnableLlmAgents 时，本心的自动配置<b>不会</b>替你扫描 @LlmAgent 接口，
            // 所以连一个代理 bean 都没有 —— 这条口径见 EnableLlmAgentsScanTest。
            assertThat(context).doesNotHaveBean(LlmAgentFactoryBean.class);
            assertThat(context).doesNotHaveBean(AgentInterceptor.class);
        });
    }

    @Test
    @DisplayName("基础设施 bean 定义必须是 static 的：拿它的配置类元数据直接看")
    void staticBeanMethods() throws Exception {
        // @Bean 方法上的 static 修饰符不是风格问题：Spring 只有在工厂方法为 static 时
        // 才能"不实例化配置类"就调用它 —— 这正是 BeanPostProcessor 类 bean 提前就绪的前提。
        java.lang.reflect.Method toolCatalog = LlmAutoConfiguration.class.getMethod("llmToolCatalog");
        java.lang.reflect.Method registrar = LlmAutoConfiguration.class.getMethod("llmComponentRegistrar");
        assertThat(java.lang.reflect.Modifier.isStatic(toolCatalog.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isStatic(registrar.getModifiers())).isTrue();

        // 对照：可替换的那些切面方法<b>都不是</b> static（它们要参与依赖注入）。
        java.lang.reflect.Method transport = LlmAutoConfiguration.class.getMethod("llmHttpTransport");
        assertThat(java.lang.reflect.Modifier.isStatic(transport.getModifiers())).isFalse();
    }
}
