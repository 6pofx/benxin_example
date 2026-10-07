package com.benxin.exp.loops;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 04 · Agent Loop。
 *
 * <p>本模块把本心的 <b>8 个内置 Agent Loop</b> 挨个跑一遍，再补上三件"Loop 之所以是 Loop"
 * 的东西：自定义 Loop（{@code @LlmLoop}）、子代理装配、以及 Loop 的运行时预算与属性。</p>
 *
 * <p>两点与 exp-01 一致的前提：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 是<b>必写</b>的 —— starter 里没有任何自动配置会替你扫描
 *       {@code @LlmAgent} 接口；不写 {@code basePackages} 时它从主应用包推断。</li>
 *   <li>全部离线：模型是 {@code exp-common} 的 {@code ScriptedLlmModel}，
 *       因此 {@code mvn spring-boot:run} 与 {@code mvn test} 都不联网、不消耗 token。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.loops")
public class LoopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoopsApplication.class, args);
    }
}
