package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.GenericAgent;
import com.benxin.exp.loops.loop.DecorateLoop;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

/**
 * 自定义 Loop 之一：{@code exp-decorate}（{@code new DecorateLoop()} 由注解自动注册）。
 *
 * <p>它演示的是"写一个 Loop 到底要写多少代码"：{@code @LlmLoop("exp-decorate")}
 * 标在类上就进注册表（<b>不需要 {@code @Component}</b> —— starter 里的
 * {@code LlmComponentRegistrar} 会扫描 {@code @LlmLoop} 并补注册 bean）；
 * 真正的逻辑只有"先复述、再直接取一次数据、然后自己决定走手写循环还是标准骨架"。</p>
 *
 * <p>{@link DecorateLoop} 把 {@code LoopContext} 的能力逐个用了一遍
 * （{@code append} / {@code callModel} / {@code executeTools} / {@code callTool} /
 * {@code emit} / {@code attributes} / {@code messages} / {@code step} / {@code maxSteps} /
 * {@code outOfBudget} / {@code toResult} / {@code finish} / {@code runToolCallingLoop}），
 * 具体在哪一步用哪个见那个类的表格。</p>
 */
@Component
public class CustomLoopDemo {

    private final GenericAgent genericAgent;
    private final CommonTools commonTools;
    private final LoopRegistry loopRegistry;
    private final AgentRegistry agentRegistry;

    public CustomLoopDemo(GenericAgent genericAgent,
                          CommonTools commonTools,
                          LoopRegistry loopRegistry,
                          AgentRegistry agentRegistry) {
        this.genericAgent = genericAgent;
        this.commonTools = commonTools;
        this.loopRegistry = loopRegistry;
        this.agentRegistry = agentRegistry;
    }

    void run(Demo demo) {
        demo.section("自定义 Loop：@LlmLoop 注册 + LoopContext 全能力 + SubAgentObserver");

        demo.info("两个自定义 Loop 都在注册表里（它们没有 @Component，靠注解被发现）：");
        demo.ok("exp-decorate 在注册表里 = %s，实现类 = %s",
                loopRegistry.contains("exp-decorate"),
                loopRegistry.get("exp-decorate").getClass().getSimpleName());
        demo.info("description = %s", loopRegistry.get("exp-decorate").description());
        demo.info("genericAgent 未写 loop，回落到 llm.agent.default-loop = %s",
                agentRegistry.get("genericAgent").loop().name());

        commonTools.reset();
        AgentResult result = genericAgent.ask("北京天气怎么样？");
        LoopReport.result(demo, result);

        demo.ok("① append + callModel + attributes：复述阶段写进了 %s = %s",
                DecorateLoop.ATTR_RESTATED,
                LoopReport.oneLine(String.valueOf(result.attributes().get(DecorateLoop.ATTR_RESTATED)), 90));
        demo.ok("② callTool：Loop 不经过模型直接调了一次 get_current_time = %s",
                result.attributes().get(DecorateLoop.ATTR_DIRECT_TOOL));
        demo.ok("③ 分支判定：历史里已有工具结果 → 走 %s",
                result.attributes().get(DecorateLoop.ATTR_EXECUTION));
        demo.ok("④ toResult：预算是否耗尽 = %s（maxSteps = %d，实际用 %d 步）",
                result.attributes().get(DecorateLoop.ATTR_BUDGET_EXHAUSTED),
                agentRegistry.get("genericAgent").spec().maxSteps(), result.steps());

        demo.info("Loop 自己 append 的那条复述指令留在历史里：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().startsWith("请先用一句话复述这个任务"))
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 140)));

        demo.info("SubAgentObserver 的记账属性：%s（本演示没有派生，因此为空；"
                + "派生路径的演示见下一节 SubAgentAssemblyDemo）",
                result.attributes().get(DecorateLoop.ATTR_SUBAGENT_EVENTS));
        demo.info("Base 类替你做的事：步数预算、模型调用、工具执行、沙箱/审批/拦截器/截断、结果封装 —— "
                + "自定义 Loop 里一行都不用写。");
    }
}
