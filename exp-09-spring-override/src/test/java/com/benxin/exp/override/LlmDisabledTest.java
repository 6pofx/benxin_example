package com.benxin.exp.override;

import com.benxin.exp.override.agent.SupportAssistant;
import com.benxin.exp.override.agent.ToolAssistant;
import com.benxin.exp.override.demo.SpringOverrideDemo;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmAgentFactoryBean;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code llm.enabled=false}：整个本心被关停。
 *
 * <p>这个开关是 {@code LlmAutoConfiguration} 与 {@code LlmAgentAutoConfiguration} 上共同的
 * {@code @ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true",
 * matchIfMissing = true)}。关掉它之后本心"像是没被引入过"。</p>
 *
 * <p>要断言的有两件事，第二件尤其重要：</p>
 * <ol>
 *   <li>没有 {@code ModelRegistry} / {@code LoopRegistry} / {@code AgentRegistry} 等 bean；</li>
 *   <li>{@code @EnableLlmAgents} 也不会再注册 {@code @LlmAgent} 代理 bean ——
 *       这一点是刻意的：代理 bean 依赖的 {@code LlmAgentFactory} 来自已被关停的自动配置，
 *       如果照旧注册，容器里就会留下一批"<b>一用就炸</b>"的 bean，
 *       失败被推迟到第一次调用而不是启动期暴露 —— 那是最难排查的一类延迟爆炸。
 *       {@code LlmAgentRegistrar} 里那段 {@code llmEnabled()} 判断就是为它写的。</li>
 * </ol>
 *
 * <p>注意本测试类<b>同时</b>带着 {@code @EnableLlmAgents}（在
 * {@link SpringOverrideApplication} 上）与 {@code llm.enabled=false} ——
 * 这正是最容易踩坑的那个组合。</p>
 */
@SpringBootTest(properties = {
        "llm.enabled=false",
        "exp.demo.enabled=false"})
@DisplayName("示例 09 · llm.enabled=false 整体关停")
class LlmDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("① 没有任何本心的切面 bean：ModelRegistry / LoopRegistry / AgentRegistry / LlmAgentFactory / LlmProperties")
    void noLlmBeansAtAll() {
        // 上下文本身起得来 —— 关停不等于启动失败。
        assertThat(context).isNotNull();

        assertThat(context.getBeanNamesForType(ModelRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LoopRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AgentRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LlmAgentFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LlmProperties.class)).isEmpty();

        assertThat(context.getBeanNamesForType(MemoryStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SystemPromptProvider.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContextManager.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContextCompactor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TokenEstimator.class)).isEmpty();
        assertThat(context.getBeanNamesForType(HttpTransport.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ToolSandbox.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ApprovalHandler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AgentListener.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LlmInterceptorChain.class)).isEmpty();

        // @EnableLlmAgents 仍然"在"，只是它什么也没注册。
        assertThat(context.getBeanNamesForType(LlmAgentFactoryBean.class)).isEmpty();
    }

    @Test
    @DisplayName("② @EnableLlmAgents 不再注册 @LlmAgent 代理 bean：注入直接抛 NoSuchBeanDefinitionException")
    void enableLlmAgentsRegistersNoProxyBeans() {
        assertThatThrownBy(() -> context.getBean(SupportAssistant.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("SupportAssistant");

        assertThatThrownBy(() -> context.getBean(ToolAssistant.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("ToolAssistant");

        // 连"代理 bean 的定义"都不该存在 —— 不是"存在但创建失败"，是压根没有。
        assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.equals("supportAssistant") || name.equals("toolAssistant"));
    }

    @Test
    @DisplayName("③ 我的自定义切面配置也跟着让位（否则它注入不到依赖，整个应用起不来）")
    void myOwnOverrideConfigurationAlsoBacksOff() {
        // DefaultOverrideConfiguration 上写着与自动配置同一套 @ConditionalOnProperty ——
        // 这不是抄样板，而是"替换一个组件"必须承担的义务：
        // 总开关关掉时本心的组件一个都不在，而我的 @Bean 方法全都在等它们注入。
        // 少了这一行，结果是 UnsatisfiedDependencyException，整个应用起不来。
        assertThat(context.getBeanNamesForType(com.benxin.exp.override.component.StubTransport.class))
                .isEmpty();
        // 演示组件同理：它依赖一堆已经被关掉的 bean。
        assertThat(context.getBeanNamesForType(SpringOverrideDemo.class)).isEmpty();
    }
}
