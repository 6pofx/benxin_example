package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * {@code basePackageClasses} 写法的实验上下文。
 *
 * <p>与 {@link AlphaScanApplication} 的唯一差别是扫描范围的写法：
 * 这里按<b>类</b>指定，{@code LlmAgentRegistrar} 会用
 * {@code ClassUtils.getPackageName(clazz)} 把它换算成包名。
 * 好处是重构包名时编译器会报错，不用靠字符串 ——
 * 本模块搬过一次包（{@code scan.alpha} → {@code fixture.alpha}），
 * 正是那次搬家让"字符串写错"与"编译器帮你对"的差别变得很具体。</p>
 *
 * <p>为什么不用 {@code @SpringBootApplication}、为什么不加 {@code @ComponentScan}：
 * 见 {@link AlphaScanApplication} 的类注释。</p>
 */
@Configuration
@AutoConfigurationPackage
@EnableAutoConfiguration
@EnableLlmAgents(basePackageClasses = AlphaSecondAssistant.class)
public class AlphaPackageClassScanApplication {
}