package com.benxin.exp.override;

import com.benxin.exp.override.agent.SupportAssistant;
import com.benxin.exp.override.agent.ToolAssistant;
import com.benxin.exp.overridefixture.alpha.AlphaAssistant;
import com.benxin.exp.overridefixture.alpha.AlphaGuardInterceptor;
import com.benxin.exp.overridefixture.alpha.AlphaOnlyLoop;
import com.benxin.exp.overridefixture.alpha.AlphaPackageClassScanApplication;
import com.benxin.exp.overridefixture.alpha.AlphaScanApplication;
import com.benxin.exp.overridefixture.alpha.AlphaSecondAssistant;
import com.benxin.exp.overridefixture.zeta.ZetaAssistant;
import com.benxin.exp.overridefixture.ctx.NoAnnotationApplication;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmAgentFactoryBean;
import com.benxin.llm.spring.LlmAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @EnableLlmAgents} 的三种扫描范围 —— 以及"不写它时一个都不注册"。
 *
 * <p>四条口径，一一对应下面四个测试方法：</p>
 * <ol>
 *   <li><b>不写参数</b>：扫描范围从主应用包推断（{@code AutoConfigurationPackages}）；</li>
 *   <li><b>{@code basePackages}</b>：只扫指定包；范围外的接口一个都不注册；</li>
 *   <li><b>{@code basePackageClasses}</b>：按类所在包扫，效果同上但包名由编译器帮你对；</li>
 *   <li><b>完全不写 {@code @EnableLlmAgents}</b>：starter 里<b>没有任何</b>自动配置会替你扫描
 *       {@code @LlmAgent} 接口，所以一个接口都不会注册，注入直接抛
 *       {@code NoSuchBeanDefinitionException}。</li>
 * </ol>
 *
 * <p>每个实验都用一个自己的"主应用类"（{@code @SpringBootApplication}）：因为第 1 条要靠
 * {@code AutoConfigurationPackages} 才能成立，而那个值只有 {@code @AutoConfigurationPackage}
 * （{@code @SpringBootApplication} 带着它）才会登记。所以"主应用类写在哪"直接决定"不写参数时扫到谁"。</p>
 *
 * <p>顺便钉住一条容易漏的口径：扫描范围不只作用于 {@code @LlmAgent} ——
 * 同一批包里的 {@code @LlmLoop} / {@code @LlmGuard} 也会被一并注册成 bean
 * （见 {@code LlmAgentRegistrar} 末尾那句注释）。</p>
 */
@DisplayName("示例 09 · @EnableLlmAgents 的扫描范围")
class EnableLlmAgentsScanTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class));

    /**
     * 专供那几个 {@code @SpringBootApplication} 实验上下文的 runner。
     *
     * <p><b>为什么不能给它们也用 {@link #runner}</b>：{@code @SpringBootApplication} 上带着
     * {@code @EnableAutoConfiguration}，于是 starter 的
     * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
     * 已经会把 {@code LlmAutoConfiguration} / {@code LlmAgentAutoConfiguration} 装一遍。
     * 这时再叠一层 {@code AutoConfigurations.of(...)}，同一份自动配置会被处理<b>两次</b> ——
     * {@code @EnableLlmAgents} 的 {@code ImportBeanDefinitionRegistrar} 因此跑两遍，
     * 第二遍发现 {@code alphaAssistant} 这个名字已被占用，于是按
     * {@code LlmAgentRegistrar} 里的去重规则登记成 {@code alphaAssistant#1}。
     * 结果是容器里有两个同类型 bean，{@code context.getBean(AlphaAssistant.class)} 直接抛
     * {@code NoUniqueBeanDefinitionException}。</p>
     *
     * <p>换个角度看：那几个 {@code @SpringBootApplication} 本来就是"一个完整的应用"，
     * 它们自己负责开自动配置 —— 这才是"零配置可用"的真实形态。</p>
     */
    private static ApplicationContextRunner appRunner() {
        // exp.demo.enabled=false：SpringOverrideDemo 是个启动即跑的 @Component，
        // 它注入了一大堆本模块的自定义切面 bean。扫描实验只关心"哪个接口被注册成 bean"，
        // 把演示关掉可以让上下文更干净、失败原因更聚焦。
        return new ApplicationContextRunner().withPropertyValues("exp.demo.enabled=false");
    }

    // 注意：那个"不写 @EnableLlmAgents 的主应用类"（NoAnnotationApplication）刻意放在
    // fixture.ctx 包里，而不是像其它 fixture 一样嵌套在这里。
    // 原因是 @SpringBootApplication 也带着 @Configuration：嵌套在 com.benxin.exp.override 下，
    // 它会被主示例的组件扫描注册，同时被 @SpringBootTest 的 AnnotatedClassFinder 当成第二个
    // @SpringBootConfiguration，让 SpringOverrideTest 等类集体起不来。详见该类注释。

    // ==================================================================

    @Test
    @DisplayName("① 不写参数：扫描范围从主应用包推断 —— 主应用包里的接口注册，包外的注册不了")
    void infersBasePackageFromApplication() {
        appRunner().withUserConfiguration(SpringOverrideApplication.class).run(context -> {
            assertThat(context).hasNotFailed();

            // 主应用包（com.benxin.exp.override）里的两个接口被扫到了。
            // ⚠️ 查 FactoryBean 本身的名字要带 "&" 前缀（Spring 用它区分"工厂实例"与"它产出的对象"）。
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class))
                    .contains("&supportAssistant", "&toolAssistant");
            assertThat(context.getBean(SupportAssistant.class)).isNotNull();
            assertThat(context.getBean(ToolAssistant.class)).isNotNull();

            // 主应用包<b>之外</b>的接口一个都没进来 —— 推断出来的范围就是那一个包。
            // 这正是下面第 ② 条要接着对比的地方：显式给 basePackages 时范围会整个换掉。
            assertThat(context).doesNotHaveBean(AlphaAssistant.class);
            assertThat(context).doesNotHaveBean(AlphaSecondAssistant.class);
            assertThat(context).doesNotHaveBean(ZetaAssistant.class);
        });
    }

    @Test
    @DisplayName("② basePackages：只扫指定包；范围外的接口一个都不注册")
    void basePackagesLimitsScope() {
        appRunner().withUserConfiguration(AlphaScanApplication.class).run(context -> {
            assertThat(context).hasNotFailed();

            // 范围内的两个接口都注册了（整个包，不是一个）。
            assertThat(context.getBean(AlphaAssistant.class)).isNotNull();
            assertThat(context.getBean(AlphaSecondAssistant.class)).isNotNull();
            // ⚠️ 这里查的是 <b>FactoryBean 本身</b>的名字，所以带 "&" 前缀 ——
            // `getBeanNamesForType(LlmAgentFactoryBean.class)` 返回的是"工厂 bean"的注册名，
            // Spring 对 FactoryBean 用 `&name` 表示工厂实例、`name` 表示它产出的对象。
            // 想拿"代理对象"的名字，请看上面那两行 getBean(...)。
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class))
                    .contains("&alphaAssistant", "&alphaSecond");

            // 范围外的：另一个包里的 ZetaAssistant，以及主应用包里的那两个。
            // 注意 AlphaScanApplication 自己的包是 com.benxin.exp.overridefixture.alpha，
            // 所以"主应用包"本身就换成了 alpha —— 这也说明"扫描范围"与"主应用包"是两件事。
            assertThat(context).doesNotHaveBean(SupportAssistant.class);
            assertThat(context).doesNotHaveBean(ZetaAssistant.class);
            assertThatThrownBy(() -> context.getBean(ZetaAssistant.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        });
    }

    @Test
    @DisplayName("② 附：扫描范围不只作用于 @LlmAgent —— 同包的 @LlmLoop / @LlmGuard 也成了 bean")
    void basePackagesAlsoRegistersLoopAndGuard() {
        appRunner().withUserConfiguration(AlphaScanApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AlphaOnlyLoop.class);
            assertThat(context).hasSingleBean(AlphaGuardInterceptor.class);
            // 而且 Loop 真的进了注册表（注册名取注解值）。
            LoopRegistry loops = context.getBean(LoopRegistry.class);
            assertThat(loops.contains("alpha-only-loop")).isTrue();
            assertThat(loops.get("alpha-only-loop")).isInstanceOf(AlphaOnlyLoop.class);
            // AgentLoop bean 也真的在容器里 —— 这是 LlmComponentRegistrar 的功劳。
            assertThat(context.getBeanNamesForType(AgentLoop.class)).contains("alphaOnlyLoop");
        });
    }

    @Test
    @DisplayName("③ basePackageClasses：按类所在包扫，效果与 basePackages 等价（但包名由编译器保证）")
    void basePackageClassesLimitsScope() {
        appRunner().withUserConfiguration(AlphaPackageClassScanApplication.class).run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(AlphaAssistant.class)).isNotNull();
            assertThat(context.getBean(AlphaSecondAssistant.class)).isNotNull();
            assertThat(context).doesNotHaveBean(SupportAssistant.class);
            assertThat(context).doesNotHaveBean(ZetaAssistant.class);

            // 与 basePackages 那条的结论完全一致 —— 两条路径在 LlmAgentRegistrar 里
            // 都归一到同一个 Set<String> packages。
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class))
                    .containsExactlyInAnyOrder("&alphaAssistant", "&alphaSecond");
        });
    }

    @Test
    @DisplayName("④ 不写 @EnableLlmAgents：一个 @LlmAgent 接口都不会注册（这个注解是必写的）")
    void withoutAnnotationNothingIsRegistered() {
        appRunner().withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            // 上下文起得来 —— 本心的自动配置照样工作，只是没人替你扫描接口。
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(com.benxin.llm.core.model.ModelRegistry.class);
            assertThat(context).hasSingleBean(LoopRegistry.class);

            // 但一个代理 bean 都没有。
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class)).isEmpty();
            assertThatThrownBy(() -> context.getBean(SupportAssistant.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("SupportAssistant");
        });
    }

    @Test
    @DisplayName("④ 附：写了注解但不给参数 ≠ 不写注解 —— 前者回退到主应用包，后者一个都不扫")
    void annotationWithExplicitEmptyRangeStillFallsBack() {
        // @EnableLlmAgents 的 basePackages / basePackageClasses 都留空时，
        // LlmAgentRegistrar 走的是一条三级回退链：
        //   注解参数 → AutoConfigurationPackages（主应用包） → importingClassMetadata 所在包。
        // 而"完全不写注解"连 ImportBeanDefinitionRegistrar 都没有，所以是零注册。
        appRunner().withUserConfiguration(SpringOverrideApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class))
                    .contains("&supportAssistant", "&toolAssistant");
        });
        appRunner().withUserConfiguration(NoAnnotationApplication.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class)).isEmpty();
        });
    }
}
