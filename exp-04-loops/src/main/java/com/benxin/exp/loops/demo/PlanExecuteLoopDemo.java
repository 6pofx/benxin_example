package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.PlanExecuteAgent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.PlanExecuteLoop;
import com.benxin.llm.core.message.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置 Loop ⑤：{@code plan-execute} —— 先规划、逐步执行、最后追加一轮汇总。
 *
 * <p>它比 {@code react} 多的是"一份可审计的清单"：计划一旦落进对话历史，
 * 人就能中途看到它、也能据此判断 Agent 有没有跑偏。代价写在类注释里，
 * 这里也演示得到 —— 它是"模型这一轮不再请求工具 = 当前这一步做完了"这个信号换来的。</p>
 */
@Component
public class PlanExecuteLoopDemo {

    private final PlanExecuteAgent planExecuteAgent;
    private final CommonTools commonTools;

    public PlanExecuteLoopDemo(PlanExecuteAgent planExecuteAgent, CommonTools commonTools) {
        this.planExecuteAgent = planExecuteAgent;
        this.commonTools = commonTools;
    }

    void run(Demo demo) {
        demo.section("plan-execute —— 计划被解析成多步、逐步回显、最后追加一轮汇总");

        commonTools.reset();
        AgentResult result = planExecuteAgent.ask(
                "用离线模型把 plan-execute 的完整流程演示一遍：先规划，再逐步执行，最后汇总。");

        LoopReport.result(demo, result);

        Object plan = result.attributes().get(PlanExecuteLoop.PLAN_ATTRIBUTE);
        demo.ok("计划写进了 attributes[%s]（PlanExecuteLoop.PLAN_ATTRIBUTE）", PlanExecuteLoop.PLAN_ATTRIBUTE);
        demo.ok("计划被解析成 %s 步：%s", plan instanceof List<?> list ? list.size() : 0,
                LoopReport.shortValue(plan));

        demo.info("逐步回显：历史里每一轮的进度提醒（「已完成 N/M，剩余步骤」）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().startsWith("已完成 "))
                .forEach(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 160)));

        demo.info("最后追加的一轮汇总（SYNTHESIS_PROMPT）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().startsWith("计划的全部步骤都已执行完毕"))
                .forEach(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 160)));

        demo.info("工具被调用 %d 次，模型往返 %d 步", commonTools.callCount(), result.steps());
        demo.ok("收尾文本来自**汇总那一轮**而不是最后一步：%s",
                LoopReport.oneLine(result.text(), 120));
        demo.info("口径提醒：plan-execute 把「步骤都跑完了」直接当成「任务完成」，"
                + "没有独立验收 —— 那道闸门是 staged 的职责。");
    }
}
