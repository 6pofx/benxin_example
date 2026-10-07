package com.benxin.exp.override;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.override.agent.SupportAssistant;
import com.benxin.exp.override.component.RecordingMemoryStore;
import com.benxin.exp.overridefixture.ctx.ReplaceMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「用户 bean 优先」的第二种形态：<b>不继承</b>默认实现，直接给一个自己的。
 *
 * <p>{@link SpringOverrideTest} 里那些自定义切面大多用继承（子类 is-a 父类，条件照样让位）。
 * 这个类换成"从零实现一个接口"，证明机制与"能不能继承"无关 ——
 * {@code @ConditionalOnMissingBean} 只看<b>类型</b>。</p>
 *
 * <p>它顺带还把 Spring 容器<b>自己</b>的那套覆盖规则摆出来，好把两者分清楚：
 * 这里用 {@code @ContextConfiguration(classes = {...})} 显式指定"主应用类 + 我的配置类"，
 * 再用 {@code spring.main.allow-bean-definition-overriding=true} 打开容器的
 * "同名后定义者胜"，于是容器里留下的 {@code MemoryStore} 是这里的实现。
 * 而本心承诺的那条插拔通道是 {@code @ConditionalOnMissingBean} ——
 * 它按<b>类型</b>判断，<b>不需要</b>这个开关（见
 * {@code ConditionalOnMissingBeanMechanismTest} 的有/无对照实验）。
 * 一句话：开关属于容器，条件属于本心，别把两者当成一回事。</p>
 *
 * <p><b>为什么要显式列配置类、而且夹具要放在 overridefixture 包里</b>：
 * 嵌套的 {@code @TestConfiguration} 会被 {@code ConfigurationClassPostProcessor}
 * 自动注册 —— 只要外层测试类被当成配置类处理一次，它就跟着进容器；
 * 一旦它与 {@code DefaultOverrideConfiguration} 定义了<b>同名</b> bean，
 * 别的测试类（任何引用 {@code SpringOverrideApplication} 的 {@code ApplicationContextRunner} 实验）
 * 都会跟着抛 {@code BeanDefinitionOverrideException}。搬出去 + 显式引用之后，
 * 每个测试都只看得见自己那一份。</p>
 */
@SpringBootTest(properties = {
        "exp.demo.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
@ContextConfiguration(classes = {SpringOverrideApplication.class, ReplaceMemoryStore.class})
@DisplayName("示例 09 · 用户 bean 优先（接口实现 + 独立的 @TestConfiguration）")
class UserBeanPriorityTest {

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private RecordingModel stubModel;

    @Autowired
    private SupportAssistant support;

    @Autowired
    private AgentRegistry agentRegistry;

    @Test
    @DisplayName("把 MemoryStore 换成「什么都不存」的实现：容器里生效的是它，历史读出来永远是空的")
    void customInterfaceImplementationWins() {
        assertThat(memoryStore).isNotInstanceOf(RecordingMemoryStore.class);
        assertThat(memoryStore.toString()).startsWith("EphemeralMemoryStore");

        support.ask("你好");

        // 记忆开着（llm.agent.memory=true），所以 load/save 一定会被调到 —— 只是这次写进去就没了。
        assertThat(memoryStore.toString()).contains("load(supportAssistant-default)");
        assertThat(memoryStore.toString()).contains("save(supportAssistant-default, 2 条)");
        assertThat(agentRegistry.get("supportAssistant").history("supportAssistant-default")).isEmpty();
        assertThat(stubModel.count()).isEqualTo(1);
    }
}
