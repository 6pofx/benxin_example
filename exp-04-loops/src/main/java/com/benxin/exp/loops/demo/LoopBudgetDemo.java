package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.BudgetProbeAgent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 步数预算：{@code maxSteps} 与 {@code hard-max-steps} 的夹取。
 *
 * <p>两道闸门的分工：</p>
 * <ol>
 *   <li>{@code @LlmAgent(maxSteps = N)} / {@code llm.agent.max-steps} —— <b>每个 Agent 的预算</b>；</li>
 *   <li>{@code llm.agent.hard-max-steps} —— <b>全局防呆上限</b>，在
 *       {@code AgentBuilder.build()} 里对前者做一次夹取（唯一一处），
 *       因此无论注解写多少都越不过它。</li>
 * </ol>
 *
 * <p>预算耗尽的表现是：{@code AgentResult.maxStepsReached() == true}，
 * {@code finishReason} 保持最后一次模型响应的值（通常是 TOOL_CALLS），
 * 而 {@code text()} 是"最后一段有文本的 assistant 消息"。
 * 换句话说，<b>它不会替你宣布任务完成，也不会抛异常</b> —— 是否把这次结果当作半成品，
 * 是调用方的事。</p>
 */
@Component
public class LoopBudgetDemo {

    private final BudgetProbeAgent budgetProbeAgent;
    private final AgentRegistry agentRegistry;
    private final LlmAgentFactory agentFactory;
    private final CommonTools commonTools;
    private final int hardMaxSteps;

    public LoopBudgetDemo(BudgetProbeAgent budgetProbeAgent,
                          AgentRegistry agentRegistry,
                          LlmAgentFactory agentFactory,
                          CommonTools commonTools,
                          @Value("${llm.agent.hard-max-steps:200}") int hardMaxSteps) {
        this.budgetProbeAgent = budgetProbeAgent;
        this.agentRegistry = agentRegistry;
        this.agentFactory = agentFactory;
        this.commonTools = commonTools;
        this.hardMaxSteps = hardMaxSteps;
    }

    void run(Demo demo) {
        demo.section("步数预算：注解写 100 也会被 hard-max-steps 夹回去");

        int annotated = 100;
        int effective = agentRegistry.get(AgentNames.BUDGET).spec().maxSteps();
        demo.ok("注解 maxSteps = %d，llm.agent.hard-max-steps = %d，装配后的 spec.maxSteps = %d",
                annotated, hardMaxSteps, effective);
        demo.ok("夹取生效 = %s（发生在 AgentBuilder.build()，两条装配路径都过它）",
                effective == Math.min(annotated, hardMaxSteps));
        demo.info("agentFactory.specOf(...) 的值 = %d —— 夹取发生在**构建期**而不是 specOf，"
                + "所以「从注解读到的数」与「实际生效的数」确实可能不同。",
                agentFactory.specOf(com.benxin.exp.loops.agent.BudgetProbeAgent.class).maxSteps());

        commonTools.reset();
        AgentResult result = budgetProbeAgent.ask("请查一下北京的天气。");
        LoopReport.result(demo, result);

        demo.ok("maxStepsReached() = %s（预算被用完了）", result.maxStepsReached());
        demo.ok("步数正好等于预算 = %s（steps = %d）",
                result.steps() == effective, result.steps());
        demo.ok("工具被反复执行 %d 次，说明模型确实没有收尾 —— 预算就是为这种情况准备的",
                commonTools.callCount());
        demo.info("它的 text() 是「最后一段有文本的 assistant 消息」；本次模型一直在请求工具，"
                + "所以文本可能为空：%s", LoopReport.oneLine(result.text(), 120));
        demo.info("拿到 maxStepsReached() 之后该做什么由调用方决定：重试、换 Loop、"
                + "还是把半成品交给人 —— 框架只负责如实标记。");
    }
}
