package com.benxin.exp.override;

import com.benxin.exp.overridefixture.AnnotatedDefaultLoop;
import com.benxin.exp.overridefixture.ModelDefFixtures;
import com.benxin.exp.overridefixture.UnnamedLoop;
import com.benxin.exp.override.component.DemoAgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @LlmLoop} / {@code @LlmModelDef} 的注册细节。
 *
 * <p>两个注解各有一条容易记错的规则，而这个类把它们逐条钉住：</p>
 *
 * <p><b>{@code @LlmLoop(value, defaultLoop, order)}</b>：</p>
 * <ul>
 *   <li>注册名：{@code annotation.value() 非空 ? annotation.value() : loop.name()}；</li>
 *   <li>默认 Loop 的优先级：{@code llm.agent.default-loop}（且必须在表里）&gt;
 *       {@code @LlmLoop(defaultLoop = true)} &gt; 字面量 {@code "dsh-minimal"}；</li>
 *   <li>{@code order} 的语义是"同时有多个 defaultLoop 时取最小的那个"，但实现里其实是
 *       "<b>取第一个</b>"（{@code annotatedDefault == null} 时才赋值）—— 这条与注解文档不一致，
 *       见 README 的「插件问题」一节。</li>
 * </ul>
 *
 * <p><b>{@code @LlmModelDef(value, defaultModel, order)}</b>：</p>
 * <ul>
 *   <li>注册名：{@code annotation.value() 非空 ? annotation.value() : model.name()}；</li>
 *   <li>默认模型：{@code llm.default-model} &gt; {@code primary: true} 的配置项 &gt;
 *       {@code @LlmModelDef(defaultModel = true)} 的 bean &gt; 第一个 bean；</li>
 *   <li>同样地，{@code order} 只在"没有更权威的默认值"时才谈得上，而实现走的也不是排序。</li>
 * </ul>
 *
 * <p>本类全程用 {@link ApplicationContextRunner}：这些规则都要在"干净容器"里看才准，
 * 主示例上下文里的 {@code DefaultOverrideConfiguration} 会把两个注册表整个换掉。</p>
 */
@DisplayName("示例 09 · @LlmLoop / @LlmModelDef 的注册细节")
class AnnotationRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class));

    // ==================================================================
    // @LlmLoop
    // ==================================================================

    @Test
    @DisplayName("@LlmLoop(value = \"...\")：注册名取注解值，而不是 loop.name()")
    void loopNameComesFromAnnotation() {
        runner.withBean("annotatedDefault", AnnotatedDefaultLoop.class, AnnotatedDefaultLoop::new).run(context -> {
            assertThat(context).hasNotFailed();
            LoopRegistry loops = context.getBean(LoopRegistry.class);

            assertThat(loops.contains("fixture-default-loop")).isTrue();
            assertThat(loops.get("fixture-default-loop")).isInstanceOf(AnnotatedDefaultLoop.class);
            // name() 返回的那个值反而<b>不</b>在表里 —— 这就是"注解优先"的直接证据。
            assertThat(loops.contains("fixture-default-loop-from-method")).isFalse();
        });
    }

    @Test
    @DisplayName("@LlmLoop 不写 value：注册名取 loop.name()")
    void loopNameFallsBackToMethodName() {
        runner.withBean("unnamed", UnnamedLoop.class, UnnamedLoop::new).run(context -> {
            assertThat(context).hasNotFailed();
            LoopRegistry loops = context.getBean(LoopRegistry.class);

            assertThat(loops.contains(UnnamedLoop.METHOD_NAME)).isTrue();
            assertThat(loops.get(UnnamedLoop.METHOD_NAME)).isInstanceOf(UnnamedLoop.class);
        });
    }

    @Test
    @DisplayName("@LlmLoop(defaultLoop = true)：<b>不会</b>成为默认 Loop —— 总有一个配置值先赢（见下方注释）")
    void annotatedDefaultBecomesDefaultLoop() {
        runner.withBean("annotatedDefault", AnnotatedDefaultLoop.class, AnnotatedDefaultLoop::new).run(context -> {
            assertThat(context).hasNotFailed();

            // ⚠️ 实测出来的口径（与 @LlmLoop 的注解文档不一致，见 README 的「插件问题」）：
            // 注解上的 defaultLoop = true 在<b>真实插件里几乎不可能生效</b>。
            //
            // LlmAutoConfiguration#llmLoopRegistry 的选举顺序是：
            //   ① properties.getAgent().getDefaultLoop() 非空且 registry.contains(它) → 用它
            //   ② 否则用第一个 @LlmLoop(defaultLoop = true) 的名字
            //   ③ 否则字面量 "dsh-minimal"
            // 而 AgentProperties.defaultLoop 的字段默认值<b>就是 "dsh-minimal"</b>
            // （LlmProperties.java:295），它又在内置 Loop 表里必然存在 ——
            // 于是①永远成立，②是死代码。
            //
            // 换句话说：只有当显式把 llm.agent.default-loop 写成一个<b>没注册</b>的名字时，
            // 注解才轮得到（见下一个用例）。这条断言钉住的就是"真实行为"。
            assertThat(context.getBean(LoopRegistry.class).defaultName()).isEqualTo("dsh-minimal");
            // 注解的注册名照样生效 —— 失效的只是 defaultLoop 这个选举标记。
            assertThat(context.getBean(LoopRegistry.class).contains("fixture-default-loop")).isTrue();
        });
    }

    @Test
    @DisplayName("优先级：llm.agent.default-loop 指向已注册的 Loop 时，注解的 defaultLoop 让位")
    void configuredDefaultLoopWins() {
        runner.withBean("annotatedDefault", AnnotatedDefaultLoop.class, AnnotatedDefaultLoop::new)
                .withPropertyValues("llm.agent.default-loop=react")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LoopRegistry.class).defaultName()).isEqualTo("react");
                });
    }

    @Test
    @DisplayName("优先级：llm.agent.default-loop 指向一个不存在的 Loop 时，回退到注解的 defaultLoop")
    void unknownConfiguredDefaultFallsBackToAnnotated() {
        runner.withBean("annotatedDefault", AnnotatedDefaultLoop.class, AnnotatedDefaultLoop::new)
                .withPropertyValues("llm.agent.default-loop=这个 Loop 不存在")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 配置里的名字必须先"在表里"才算数 —— 这是判断，不是回退。
                    assertThat(context.getBean(LoopRegistry.class).defaultName())
                            .isEqualTo("fixture-default-loop");
                });
    }

    @Test
    @DisplayName("两者都没有时：默认 Loop 是字面量 dsh-minimal（内置那几个的总入口）")
    void noAnnotationFallsBackToBuiltinDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LoopRegistry loops = context.getBean(LoopRegistry.class);
            assertThat(loops.defaultName()).isEqualTo("dsh-minimal");
            assertThat(loops.names()).contains("dsh-minimal", "react", "claude-code", "codex",
                    "plan-execute", "reflexion", "workflow", "staged");
        });
    }

    // ==================================================================
    // @LlmModelDef
    // ==================================================================

    @Test
    @DisplayName("@LlmModelDef(\"...\")：注册名取注解值，而不是 model.name()")
    void modelNameComesFromAnnotation() {
        runner.withBean("annotated", ModelDefFixtures.Annotated.class, ModelDefFixtures.Annotated::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelRegistry models = context.getBean(ModelRegistry.class);

                    assertThat(models.names()).contains("annotated-name");
                    assertThat(models.get("annotated-name")).isInstanceOf(ModelDefFixtures.Annotated.class);
                    // name() 返回的那个值不在表里。
                    assertThat(models.names()).doesNotContain("method-name-should-be-ignored");
                });
    }

    @Test
    @DisplayName("不标 @LlmModelDef：注册名取 model.name()")
    void modelNameFallsBackToMethodName() {
        runner.withBean("plain", ModelDefFixtures.Unannotated.class, ModelDefFixtures.Unannotated::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelRegistry.class).names()).containsExactly("plain-name");
                });
    }

    @Test
    @DisplayName("@LlmModelDef(defaultModel = true)：没有 llm.default-model 时它成为默认模型")
    void annotatedDefaultBecomesDefaultModel() {
        runner.withBean("defaultOne", ModelDefFixtures.DefaultOne.class, ModelDefFixtures.DefaultOne::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelRegistry models = context.getBean(ModelRegistry.class);
                    assertThat(models.defaultName()).isEqualTo("annotated-default");
                    assertThat(models.defaultModel()).isInstanceOf(ModelDefFixtures.DefaultOne.class);
                });
    }

    @Test
    @DisplayName("优先级：llm.default-model 一写，@LlmModelDef(defaultModel = true) 就让位")
    void configuredDefaultModelWins() {
        runner.withBean("defaultOne", ModelDefFixtures.DefaultOne.class, ModelDefFixtures.DefaultOne::new)
                .withPropertyValues("llm.default-model=other",
                        "llm.models.other.protocol=openai",
                        "llm.models.other.base-url=https://api.example.invalid/v1",
                        "llm.models.other.model=other-chat")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelRegistry models = context.getBean(ModelRegistry.class);
                    // 两个模型都在表里；默认由配置决定，注解只是"没有配置时的兜底"。
                    assertThat(models.names()).contains("other", "annotated-default");
                    assertThat(models.defaultName()).isEqualTo("other");
                });
    }

    @Test
    @DisplayName("primary 与 order 只在 yml 里有意义：primary 的配置项在没有 llm.default-model 时成为默认")
    void primaryTakesPrecedenceInProperties() {
        runner.withBean("defaultOne", ModelDefFixtures.DefaultOne.class, ModelDefFixtures.DefaultOne::new)
                .withPropertyValues("llm.models.second.protocol=openai",
                        "llm.models.second.base-url=https://api.example.invalid/v1",
                        "llm.models.second.model=second-chat",
                        "llm.models.second.primary=true",
                        "llm.models.second.order=9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 注意这条：yml 里的 primary 优先于 bean 上的 @LlmModelDef(defaultModel)。
                    // LlmAutoConfiguration 把"配置里的 primary"当作 configuredDefault，
                    // 而 @LlmModelDef(defaultModel) 只在 configuredDefault 为 null 时才被考虑。
                    assertThat(context.getBean(ModelRegistry.class).defaultName()).isEqualTo("second");
                });
    }

    // ==================================================================
    // @LlmLoop 的"自动注册"那一步：谁把它变成 bean 的
    // ==================================================================

    @Test
    @DisplayName("自动注册：LlmComponentRegistrar 扫主应用包，把 @LlmLoop 标注的类补成 bean（不用 @Component）")
    void componentRegistrarRegistersAnnotatedLoops() {
        // 本模块的 DemoAgentLoop 标着 @LlmLoop("exp-demo-step") 但<b>没有</b> @Component。
        // 它之所以能进 LoopRegistry，全靠 LlmComponentRegistrar 这个
        // BeanDefinitionRegistryPostProcessor —— 它必须在 llmLoopRegistry 之前跑完，
        // 否则 ObjectProvider<AgentLoop> 会因为"扫描还没发生"而看不到它。
        //
        // 这条断言必须放在<b>主上下文</b>里做：扫描范围取自 AutoConfigurationPackages
        // （也就是 @SpringBootApplication 所在的 com.benxin.exp.override），
        // 而 ApplicationContextRunner 里没有主应用类，那一步会拿到空集合。
        mainContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            // ① 注解组件被补成了 bean，bean 名是类简单名首字母小写。
            assertThat(context.getBeanNamesForType(com.benxin.llm.core.loop.AgentLoop.class))
                    .contains("demoAgentLoop");
            assertThat(context.getBean("demoAgentLoop")).isInstanceOf(DemoAgentLoop.class);
            // ② 而且它真的进了 LoopRegistry，注册名取的是注解值。
            LoopRegistry loops = context.getBean(LoopRegistry.class);
            assertThat(loops.contains(DemoAgentLoop.REGISTERED_NAME)).isTrue();
            assertThat(loops.contains(DemoAgentLoop.METHOD_NAME)).isFalse();
            // ③ LlmComponentRegistrar 就是那个"提前就绪"的机器。
            assertThat(context.getBean(com.benxin.llm.spring.LlmComponentRegistrar.class))
                    .isInstanceOf(org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor.class);
        });
    }

    private ApplicationContextRunner mainContextRunner() {
        // 只把"主应用类"交给 runner：扫描范围就落在 com.benxin.exp.override。
        // 这份上下文里的两个注册表都是自动配置给的默认实现
        // （DefaultOverrideConfiguration 是用户 @Configuration，不在自动配置清单里，跟不进来），
        // 正好适合观察"注解组件怎么进容器"。
        //
        // ⚠️ 两点注意：
        //   ① 不要给它叠 AutoConfigurations.of(...)：SpringOverrideApplication 带着
        //      @EnableAutoConfiguration，starter 的 AutoConfiguration.imports 已经会装一遍；
        //      叠了就会处理两次，@EnableLlmAgents 的 ImportBeanDefinitionRegistrar 跟着跑两遍，
        //      @LlmAgent 接口被注册成 xxxAssistant 与 xxxAssistant#1 两个 bean，
        //      context.getBean(...) 直接抛 NoUniqueBeanDefinitionException。
        //   ② 本心的组件扫描（LlmComponentRegistrar）按 @SpringBootApplication 所在包做，
        //      而 ClassPathScanningCandidateComponentProvider 连 target/test-classes 一起扫 ——
        //      所以测试夹具必须放在 com.benxin.exp.override <b>之外</b>
        //      （本模块把夹具统一放在 com.benxin.exp.overridefixture），
        //      否则 @LlmLoop / @LlmGuard 夹具会溜进主上下文，
        //      让这里与 SpringOverrideTest 的"链上有几个拦截器"这类断言凭空多出东西。
        // exp.demo.enabled=false 也一起给上：SpringOverrideDemo 是个 @Component（启动即跑的演示），
        // 它注入了一堆本模块的自定义切面 bean。对照实验只关心"注解组件怎么进容器"，
        // 把演示关掉可以让上下文更干净、失败原因更聚焦。
        return new ApplicationContextRunner()
                .withPropertyValues("exp.demo.enabled=false")
                .withUserConfiguration(SpringOverrideApplication.class);
    }
}
