package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.WorkflowLoop;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ⑧：{@code workflow} —— <b>本模块只点名，不重复实现</b>。
 *
 * <p>理由：工作流模式（YAML/JSON 定义的节点与边、条件表达式、黑板变量、节点级 retry）
 * 是 exp-05 的专门职责。一个 Loop 一个示例、各写各的，才不至于两个模块
 * 各实现一半、谁都不完整。</p>
 *
 * <p>这里做三件事：说明它与 {@code staged} 的分工、打印它的 description、
 * 并指出 {@code workflow:<name>} 这种"按配置键注册出来的 Loop"是怎么来的。</p>
 */
@Component
public class WorkflowLoopDemo {

    private final LoopRegistry loopRegistry;

    public WorkflowLoopDemo(LoopRegistry loopRegistry) {
        this.loopRegistry = loopRegistry;
    }

    void run(Demo demo) {
        demo.section("workflow —— 由 exp-05 专门演示（本模块只点名）");

        AgentLoop workflow = loopRegistry.get(WorkflowLoop.NAME);
        demo.info("loopRegistry.get(\"%s\") → %s", WorkflowLoop.NAME, workflow.getClass().getSimpleName());
        demo.ok("description = %s", workflow.description());

        demo.info("它与 staged 是「工作流模式」的两条路线，不是同一个东西的两种写法：");
        demo.info("   workflow：**图由外部定义**（YAML/JSON 里的节点与边）→ 可审计、可复现、成本可控");
        demo.info("   staged  ：**流程由代码固化**（阶段机写死在 Loop 里）→ 任务形态多变时更省事");
        demo.info("提供定义的三种方式（见 WorkflowLoop.resolve）：");
        demo.info("   1) agent.call(input, sessionId, Map.of(\"benxin.workflow\", yamlOrJsonText))");
        demo.info("   2) llm.workflows.<name>.location = classpath:workflows/xxx.yml，然后用 loop=workflow:<name>");
        demo.info("   3) 代码里 new WorkflowLoop(definition) 后注册进 LoopRegistry");

        long declared = loopRegistry.names().stream()
                .filter(name -> name.startsWith(WorkflowLoop.NAME + ":"))
                .count();
        demo.info("本模块的 llm.workflows.* 声明了 %d 个工作流（exp-05 里才配），"
                + "它们会以 workflow:<key> 的形式出现在注册表里。", declared);
        demo.info("BuiltinLoops.NAMES 里 %s 排第 %d 位；内置注册表只注册了不带冒号的那一个。",
                WorkflowLoop.NAME, BuiltinLoops.NAMES.indexOf(WorkflowLoop.NAME) + 1);
        demo.warn("本模块**不实现**工作流的节点编排 —— 那是 exp-05 的职责，避免两个示例各写一半。");
    }
}
