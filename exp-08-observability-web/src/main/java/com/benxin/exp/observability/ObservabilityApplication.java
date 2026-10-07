package com.benxin.exp.observability;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 08 —— 可观测性（监听器 / 拦截器 / 流式）与 HTTP 端点。
 *
 * <p>这个子项目只做一件事：把"跑起来之后能看见什么、能改什么"这条路径演示透。
 * 它刻意不碰工具沙箱、工作流编排、真实厂商这些切面 —— 那些各有专门的子项目。</p>
 *
 * <p>三件事值得先记住：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 是<b>必写</b>的开关；它同时也是 {@code @LlmGuard} /
 *       {@code @LlmLoop} 的扫描入口（{@code LlmAgentRegistrar} 会把同一批包交给
 *       {@code LlmComponentScanner}），所以"打了注解却要再补一个 {@code @Component}"
 *       在这里是不必要的。</li>
 *   <li>{@code llm.web.enabled=true} 会把 Agent 暴露成 REST + SSE；
 *       {@code server.port=0} 让端口由操作系统分配，演示从 {@code Environment} 里读实际端口。</li>
 *   <li>默认模型是 {@code exp-common} 的离线脚本化模型，因此
 *       {@code mvn spring-boot:run} 立刻能看到完整输出，且<b>不消耗任何 token</b>。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.observability")
public class ObservabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityApplication.class, args);
    }
}
