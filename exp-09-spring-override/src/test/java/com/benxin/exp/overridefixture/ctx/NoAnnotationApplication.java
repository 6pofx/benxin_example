package com.benxin.exp.overridefixture.ctx;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * 一个<b>不写</b> {@code @EnableLlmAgents} 的"主应用类"，用来证明"这个注解必写"。
 *
 * <p>它是 {@code @SpringBootApplication} 拆开后的样子（{@code @Configuration}
 * + {@code @AutoConfigurationPackage} + {@code @EnableAutoConfiguration}），
 * 只是<b>没有</b> {@code @EnableLlmAgents}、也没有 {@code @ComponentScan}。
 * 拆开写而不是直接用它，原因见 {@code fixture.alpha.AlphaScanApplication} 的类注释：
 * {@code @SpringBootApplication} 会被组件扫描当成配置类捡走，放进主应用包树里会让别的
 * 上下文莫名其妙多出一个 {@code @Configuration}。</p>
 */
@Configuration
@AutoConfigurationPackage
@EnableAutoConfiguration
public class NoAnnotationApplication {
}