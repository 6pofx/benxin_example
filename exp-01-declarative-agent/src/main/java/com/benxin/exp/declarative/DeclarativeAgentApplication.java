package com.benxin.exp.declarative;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 01 —— 声明式 Agent。
 *
 * <p>这个子项目只做一件事：把 {@code @LlmAgent} 这条"接口 + 注解 = 可注入的 Agent"的路径
 * 拆开揉碎演示一遍。它刻意<b>不</b>碰工具、沙箱、工作流这些切面 —— 那些各有专门的子项目。</p>
 *
 * <p>两点值得先记住：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 是<b>必写</b>的：starter 里没有任何自动配置会替你扫描
 *       {@code @LlmAgent} 接口。它不写 {@code basePackages} 时会从主应用包推断，
 *       所以"写在哪"决定了"扫到谁"。</li>
 *   <li>默认模型是离线脚本化模型（{@code exp-common} 提供），因此
 *       {@code mvn spring-boot:run} 立刻能看到完整输出，且<b>不消耗任何 token</b>。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.declarative")
public class DeclarativeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeclarativeAgentApplication.class, args);
    }
}
