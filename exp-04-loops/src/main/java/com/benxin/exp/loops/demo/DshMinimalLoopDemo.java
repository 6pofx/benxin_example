package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.GenericAgent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ①：{@code dsh-minimal} —— 最小骨架 + 工具闭环。
 *
 * <p>它证明的是一件"否定式"的事实：一个能用的 Agent Loop 只需要
 * "调模型 → 有工具就执行 → 再调模型"，其余能力（压缩、计划、子代理、内置工具）
 * 全都可以不要。源码不到 25 行，正文只有一句 {@code runToolCallingLoop(ctx)}，
 * 且没有第二个终止条件 —— 唯一的停法是"模型不再请求工具"或"步数预算用尽"。</p>
 */
@Component
public class DshMinimalLoopDemo {

    private final GenericAgent genericAgent;
    private final LoopRegistry loopRegistry;
    private final AgentRegistry agentRegistry;

    public DshMinimalLoopDemo(GenericAgent genericAgent, LoopRegistry loopRegistry,
                              AgentRegistry agentRegistry) {
        this.genericAgent = genericAgent;
        this.loopRegistry = loopRegistry;
        this.agentRegistry = agentRegistry;
    }

    void run(Demo demo) {
        demo.section("dsh-minimal —— 最小骨架，工具闭环");

        AgentLoop loop = loopRegistry.get("dsh-minimal");
        demo.info("注册名 = %s，实现类 = %s", loop.name(), loop.getClass().getSimpleName());
        demo.info("description = %s", loop.description());
        demo.info("它连内置兜底系统提示词都没有 —— 提示词为空时它什么都不注入，完全交给使用者。");
        demo.info("genericAgent 实际装配到的 Loop = %s（来自 llm.agent.default-loop）",
                agentRegistry.get(AgentNames.GENERIC).loop().name());

        AgentResult result = genericAgent.ask("现在几点了？");
        LoopReport.result(demo, result);
        demo.ok("工具闭环成立：模型请求工具 → 框架执行 → 结果回灌 → 模型收尾（steps = %d，工具 %d 次）",
                result.steps(), result.toolCalls().size());
        demo.info("对照点：claude-code / codex 会往里注入环境、待办、错误熔断等提醒，"
                + "它们的结果里会出现 benxin.claude-code.* / benxin.codex.* 属性；dsh-minimal 一个都没有。");
    }
}
