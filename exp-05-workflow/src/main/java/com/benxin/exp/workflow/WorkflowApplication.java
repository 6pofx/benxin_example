package com.benxin.exp.workflow;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 05 —— 工作流模式。
 *
 * <p>这个子项目演示本心「工作流」这个切面的全部内容，也就是<b>两条路线</b>：</p>
 * <ol>
 *   <li><b>声明式工作流</b>（{@code workflow} Loop）：控制流写在一张 YAML/JSON 图里，
 *       模型只负责填内容。六种节点、条件表达式、{@code ${}} 模板、回边循环、
 *       节点级容错、三道防线、加载期校验、{@code WorkflowIo} 读写 —— 全在这条线上。</li>
 *   <li><b>结构化编排</b>（{@code staged} Loop）：阶段机固化在代码里，
 *       规划 → 执行 → 验收 → 修复 → 汇总，用在"任务形态多变但想要先做后验"的场合。</li>
 * </ol>
 *
 * <p>三点值得先记住：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 是<b>必写</b>的：starter 里没有任何自动配置会替你扫描
 *       {@code @LlmAgent} 接口。不写 {@code basePackages} 时会从主应用包推断。</li>
 *   <li>工作流定义有<b>三条装配路径</b>：{@code llm.workflows.*.location}、
 *       {@code llm.workflows.*.inline}，以及调用时逐次传入
 *       {@code agent.call(input, sessionId, Map.of(WorkflowLoop.ATTRIBUTE, yaml), null)}。
 *       三条都在 {@code WorkflowDemo} 里跑了一遍。</li>
 *   <li>默认模型是离线脚本化模型（{@code exp-common} 提供），因此
 *       {@code mvn spring-boot:run} 立刻能看到完整输出，且<b>不消耗任何 token</b>。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.workflow")
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
