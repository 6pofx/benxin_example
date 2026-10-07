package com.benxin.exp.override;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 09 —— 装配与覆盖。
 *
 * <p>本模块只做一件事：把「一切皆可插拔」这句话在 <b>Spring 层面</b>是怎么落地的，
 * 一步一步摆出来给你看。它不碰新的 Agent 能力（那是 exp-01 ~ exp-08 的事），
 * 它碰的是"这些能力是怎么被装进容器、又怎么被你顶掉"的机制。</p>
 *
 * <p>三条主线：</p>
 * <ol>
 *   <li><b>{@code @ConditionalOnMissingBean}</b>：{@code LlmAutoConfiguration} 里 14 个可替换切面，
 *       本模块逐个用自定义 bean 顶掉，并断言"容器里生效的是我的实现"。
 *       见 {@code config/DefaultOverrideConfiguration} 与 {@code SpringOverrideTest}。</li>
 *   <li><b>两个开关</b>：{@code llm.enabled=false} 整体关停；{@link EnableLlmAgents} 决定
 *       {@code @LlmAgent} 接口扫到谁 —— 它必写，但可以不写参数。</li>
 *   <li><b>不经 Spring 的两条路</b>：程序化装配（{@code Agent.builder()}）与裸 core 门面
 *       （{@code Benxin}）。它们是"这个插件的每一层都不依赖 Spring"的直接证据。</li>
 * </ol>
 *
 * <p>{@link EnableLlmAgents} 这里<b>刻意不写参数</b>：扫描范围会从 {@code @SpringBootApplication}
 * 所在包（{@code com.benxin.exp.override}）推断出来。三种扫描范围（不写参数 / basePackages /
 * basePackageClasses）的差别由 {@code EnableLlmAgentsScanTest} 用 {@code ApplicationContextRunner} 摆开。</p>
 */
@SpringBootApplication
@EnableLlmAgents
public class SpringOverrideApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringOverrideApplication.class, args);
    }
}
