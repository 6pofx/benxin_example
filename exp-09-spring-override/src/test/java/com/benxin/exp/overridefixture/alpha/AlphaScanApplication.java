package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * {@code basePackages} 写法的实验上下文。
 *
 * <p><b>为什么每个实验都要自己一个"主应用类"</b>：{@code LlmAgentRegistrar} 在
 * {@code @EnableLlmAgents} 没写参数时会回退到 {@code AutoConfigurationPackages.get(beanFactory)}——
 * 那个值只有 {@code @AutoConfigurationPackage}（{@code @SpringBootApplication} 带着它）
 * 才会登记。所以"扫描范围"这件事必须有一个确定的"主应用包"作为参照物。</p>
 *
 * <p><b>为什么这里不直接用 {@code @SpringBootApplication}</b>：它是本模块踩过的一个坑。
 * {@code @SpringBootApplication} 有两重身份 —— "主应用类"和一个<b>会被组件扫描捡走的
 * {@code @Configuration}</b>。把它放进一个会被扫到的包里，同一个 {@code @SpringBootApplication}
 * 就会被注册两次（"用户配置"一次 + "组件扫描"一次）；而它又带着
 * {@code @EnableAutoConfiguration}，于是 starter 的自动配置也跟着被处理两次，
 * {@code @EnableLlmAgents} 的 {@code ImportBeanDefinitionRegistrar} 跑两遍，
 * {@code @LlmAgent} 接口被登记成 {@code alphaAssistant} 与 {@code alphaAssistant#1}
 * 两个 bean，{@code context.getBean(AlphaAssistant.class)} 直接抛
 * {@code NoUniqueBeanDefinitionException}。</p>
 *
 * <p>所以实验上下文改成"只带上需要的那几样"：
 * {@code @Configuration} + {@code @AutoConfigurationPackage} + {@code @EnableAutoConfiguration}
 * + {@code @EnableLlmAgents}。这正好是 {@code @SpringBootApplication} 拆开之后的样子，
 * 但不再有"被扫描两次"的隐患。</p>
 *
 * <p>特别注意这里<b>没有</b> {@code @ComponentScan}：{@code @LlmAgent} 接口由
 * {@code @EnableLlmAgents} 的注册器负责，同包的 {@code @LlmLoop} / {@code @LlmGuard} 由
 * {@code LlmComponentRegistrar}（看 {@code AutoConfigurationPackages}）负责 ——
 * 本来就轮不到组件扫描。反过来，一旦加上 {@code @ComponentScan("...fixture.alpha")}，
 * 同包里的<b>另一个实验上下文</b>也会被当成配置类捡进来，
 * 于是它的 {@code @EnableLlmAgents} 再跑一遍，重复注册照旧发生。
 * 这正是"一个包只放一个 {@code @Configuration} 形式的实验上下文"这条约定的由来。</p>
 */
@Configuration
@AutoConfigurationPackage
@EnableAutoConfiguration
@EnableLlmAgents(basePackages = "com.benxin.exp.overridefixture.alpha")
public class AlphaScanApplication {
}