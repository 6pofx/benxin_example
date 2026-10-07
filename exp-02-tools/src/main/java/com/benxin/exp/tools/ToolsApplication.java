package com.benxin.exp.tools;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 02 —— 工具的切面。
 *
 * <p>本模块只回答一个问题：<b>"一个普通 Java 方法，怎么变成模型能调用的工具"</b> ——
 * 以及这条路径上所有可以被你控制的开关（注解属性、参数类型、返回值类型、异常、
 * 运行上下文、直达答案、并行安全、程序化实现、动态登记、来自哪个类）。</p>
 *
 * <p>三点先记住：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 是<b>必写</b>的：starter 里没有任何自动配置会替你扫描
 *       {@code @LlmAgent} 接口。本模块只用它来做"三种工具来源"的对照演示。</li>
 *   <li>工具不是"注册进某个 Agent"的，而是<b>先进容器总表</b>：任何交给 Spring 的 bean
 *       （或任何手工 {@code ToolCallback} bean）都会被 {@code LlmToolCatalog} 扫成工具，
 *       再由 Agent 决定要哪几个。详见 {@code agent/} 下三个接口的对照。</li>
 *   <li>默认离线：模型是 {@code exp-common} 的脚本化模型与本地夹具
 *       {@code support/ScriptedToolCallModel}，{@code mvn spring-boot:run} 能打印完整演示，
 *       且<b>不联网、不消耗一个 token</b>。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.tools")
public class ToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolsApplication.class, args);
    }
}
