package com.benxin.exp.workflow.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.workflow.engine.FakeWorkflowRuntime;
import com.benxin.llm.core.workflow.NodeType;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.core.workflow.WorkflowState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 纯引擎演示：{@link WorkflowEngine} + {@link WorkflowState} + 假 {@code WorkflowRuntime}。
 *
 * <p>这个类<b>完全不依赖 Spring</b>，也不依赖 Agent 与模型 —— 它 {@code new} 出引擎、
 * {@code new} 出黑板、{@code new} 出假运行时就能跑。这正是 {@code core/workflow} 那个包的
 * 设计目标：领域模型与引擎不认识框架，因此编排逻辑（分支、循环、重试、预算、护栏）
 * 可以在"没有模型、没有网络"的前提下被精确验证。</p>
 *
 * <p>它演示的顺序刻意与"{@code mvn spring-boot:run} 之后最容易出问题的顺序"一致：
 * 先走通，再分支，再失败，再护栏。三道防线放在最后两节，因为它们是"图写错了之后"的兜底。</p>
 */
@Component
public class PureEngineDemo {

    /** 跑完整个纯引擎演示，返回失败的节数（0 表示全过）。 */
    public int run(Demo demo) {
        demo.section("纯引擎：顺序推进、模板渲染与黑板快照");
        sequential(demo);

        demo.section("纯引擎：分支选边（顺序即优先级）与循环退出");
        branching(demo);

        demo.section("纯引擎：retry 重试后成功，重试用尽才失败");
        retry(demo);

        demo.section("纯引擎：continueOnError 让失败节点走补偿分支");
        continueOnError(demo);

        demo.section("纯引擎·第一道防线：步数预算耗尽 → truncated");
        budgetGuard(demo);

        demo.section("纯引擎·第二道防线：不调模型的死循环 → 只有 maxVisitsPerNode 拦得住");
        loopGuard(demo);
        return demo.failures();
    }

    // ------------------------------------------------------------------
    // 一、顺序推进
    // ------------------------------------------------------------------

    private void sequential(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("seq")
                .description("start → agent → set → end")
                .node(WorkflowNode.builder("begin").type(NodeType.START).prompt("收到任务：${input}").build())
                .node(WorkflowNode.builder("draft").type(NodeType.AGENT)
                        .instruction("你是撰写者，只写事实。")
                        .prompt("基于《${begin}》写一句结论")
                        .build())
                .node(WorkflowNode.builder("mark").type(NodeType.SET)
                        .set(Map.of("stage", "已起草", "note", "由 ${draft} 推出"))
                        .build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("阶段=${stage}\n备注=${note}\n草稿=${draft}")
                        .build())
                // 便捷写法：edge(from, to) 就是无条件边
                .edge("begin", "draft")
                .edge("draft", "mark")
                .edge("mark", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("draft", "结论：可以发布");
        WorkflowState state = new WorkflowState("把上次的发布说明补完");
        WorkflowRun run = new WorkflowEngine().run(definition, state, runtime);

        demo.info("图: %s", definition);
        demo.ok("visited = %s", path(run));
        demo.info("executions = %d，truncated = %s，completed = %s",
                run.executions(), run.truncated(), run.completed());
        demo.ok("最终输出:\n%s", indent(run.output()));
        demo.info("假运行时收到的提示词（instruction 前缀 + 模板渲染的结果）:");
        demo.info("   %s", flatten(runtime.prompt("draft")));
        demo.info("黑板快照（含保留名 input/last/current）: %s", state.snapshot());
        demo.info("★ 关键：SET / BRANCH 节点**不产生输出**，所以它们不覆盖 ${last} ——");
        demo.info("   中途看 ${last} 一直是 draft 的输出。但 END 自己会产生输出（它就是最终答案），");
        demo.info("   所以跑完之后 last = [%s]。要观察「set 没动 last」，得在中途看，或者让下游节点读 ${last}。",
                flatten(state.last()));
    }

    // ------------------------------------------------------------------
    // 二、分支与循环
    // ------------------------------------------------------------------

    private void branching(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("branch-and-loop")
                .node(WorkflowNode.builder("probe").type(NodeType.AGENT).prompt("先探一下").build())
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("rework").type(NodeType.SET)
                        .set(Map.of("round", "已回炉")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END).output("收工：${probe}").build())
                .edge("probe", "route")
                // 条件边在前、默认边最后：顺序即优先级
                .edge("route", "rework", "${probe} contains 继续")
                .edge("route", "done")
                .edge("rework", "probe")
                .build();

        // 第一次说"继续做"，第二次说"停" —— 于是循环转一圈后从 done 出去
        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .sequence("probe", "继续做", "停");
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("检查一下"), runtime);

        demo.ok("visited = %s", path(run));
        demo.info("   probe 被执行了 %d 次（回边真的转了一圈）；rework 被执行 %d 次",
                runtime.attempts("probe"), runtime.attempts("rework"));
        demo.ok("最终输出 = %s", run.output());

        // 顺序即优先级：两条出边的条件同时成立时，走先声明的那条
        WorkflowDefinition ordered = WorkflowDefinition.builder("edge-order")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("写").build())
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("first").type(NodeType.END).output("第一条边").build())
                .node(WorkflowNode.builder("second").type(NodeType.END).output("第二条边").build())
                .edge("a", "route")
                .edge("route", "first", "${a} is not empty")
                .edge("route", "second", "${a} is not empty")
                .build();
        WorkflowRun orderedRun = new WorkflowEngine().run(ordered, new WorkflowState("x"),
                new FakeWorkflowRuntime().output("a", "有内容"));
        demo.ok("两条条件边同时成立时走到 [%s] —— 顺序即优先级", orderedRun.output());
        demo.info("★ 所以默认边（不带 when）必须放在最后一条，否则它后面的边永远不可达（加载期只给警告）。");
    }

    // ------------------------------------------------------------------
    // 三、retry
    // ------------------------------------------------------------------

    private void retry(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("retry")
                .node(WorkflowNode.builder("call").type(NodeType.AGENT).prompt("调用一个会抖的下游").retry(2).build())
                .node(WorkflowNode.builder("done").type(NodeType.END).output("结果=${call}").build())
                .edge("call", "done")
                .build();

        FakeWorkflowRuntime ok = new FakeWorkflowRuntime().failFirst("call", 2).output("call", "第三次成功");
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("x"), ok);
        demo.ok("retry=2 且前 2 次失败：attempts=%d，最终输出=%s",
                ok.attempts("call"), run.output());
        demo.info("   事件序列 = %s", ok.events());
        demo.info("   ★ executions=%d 只算了**一次访问**；重试次数只出现在 workflow.node-retry 事件里",
                run.executions());

        FakeWorkflowRuntime dead = new FakeWorkflowRuntime().alwaysFail("call");
        WorkflowException error = catchWorkflow(
                () -> new WorkflowEngine().run(definition, new WorkflowState("x"), dead));
        demo.ok("retry 用尽（attempts=%d）后抛 WorkflowException：", dead.attempts("call"));
        demo.info("   workflow=%s nodeId=%s", error.workflow(), error.nodeId());
        demo.info("   message=%s", error.getMessage());
        demo.info("   ★ 失败文案只有一份：事件负载、访问表、${error} 模板、出边条件读到的都是这一句");
    }

    // ------------------------------------------------------------------
    // 四、continueOnError
    // ------------------------------------------------------------------

    private void continueOnError(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("compensate")
                .node(WorkflowNode.builder("charge").type(NodeType.TOOL).tool("pay")
                        .args(Map.of("amount", "${input}"))
                        .continueOnError(true)
                        .build())
                .node(WorkflowNode.builder("compensate").type(NodeType.SET)
                        .set(Map.of("outcome", "已进入补偿流程")).build())
                .node(WorkflowNode.builder("ok").type(NodeType.SET)
                        .set(Map.of("outcome", "正常完成")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("结果=${outcome}\n原因=${error:-（无）}").build())
                .edge("charge", "compensate", "${error} is not empty")
                .edge("charge", "ok")
                .edge("compensate", "done")
                .edge("ok", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().alwaysFail("charge");
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("199"), runtime);

        demo.info("工具参数模板已渲染: %s", runtime.toolArgs("charge"));
        demo.ok("visited = %s", path(run));
        demo.info("   ★ 硬失败的节点也进访问表 —— visited 证明的是**尝试过**哪些节点");
        demo.ok("最终输出:\n%s", indent(run.output()));
        demo.info("事件 = %s", runtime.events());
        demo.info("   ★ 节点成功后才清空 error，所以补偿分支刚读到的 ${error} 不会被下一个节点擦掉");
    }

    // ------------------------------------------------------------------
    // 五、第一道防线：步数预算
    // ------------------------------------------------------------------

    private void budgetGuard(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("budget")
                .node(agent("a", "第一步"))
                .node(agent("b", "第二步"))
                .node(agent("c", "第三步"))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${c}").build())
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .output("a", "甲").output("b", "乙").output("c", "丙")
                .budget(2);
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("x"), runtime);

        demo.ok("预算只够 2 次派发：visited = %s，executions = %d", path(run), run.executions());
        demo.ok("truncated = %s，completed = %s，output = %s（半成品，如实交出去）",
                run.truncated(), run.completed(), run.output());
        demo.info("事件 = %s", runtime.events());
        demo.info("★ 关键：预算对**调用模型的节点**有效。下面一节会看到它对不调模型的环完全无效。");
    }

    // ------------------------------------------------------------------
    // 六、第二道防线：单节点访问上限
    // ------------------------------------------------------------------

    private void loopGuard(Demo demo) {
        WorkflowDefinition definition = WorkflowDefinition.builder("deadloop")
                // 默认是 100（WorkflowDefinition.DEFAULT_MAX_VISITS），这里调小方便演示
                .maxVisitsPerNode(5)
                .node(WorkflowNode.builder("spin").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("again").type(NodeType.SET).set(Map.of("r", "${r:-0}")).build())
                .edge("spin", "again")
                .edge("again", "spin")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().budget(3);
        WorkflowException error = catchWorkflow(
                () -> new WorkflowEngine().run(definition, new WorkflowState("x"), runtime));

        demo.ok("事件 = %s", runtime.events());
        demo.info("   workflow.loop-guard 的 visits=%s（按**实际执行过的次数**上报，与实际落库口径一致）",
                visitsOf(runtime));
        demo.ok("异常（可直接照着改）: %s", error.getMessage());
        demo.info("nodeId = %s", error.nodeId());
        demo.info("★ 关键：预算设成了 3，但这张图一个模型都没调 ⇒ dispatches 永远是 0，");
        demo.info("   预算机制**一步都不会增加**，把它跑一万年也等不到 truncated。");
        demo.info("   唯一的闸门就是单节点访问上限 —— 这就是「三道防线」里为什么需要第二条。");
    }

    // ------------------------------------------------------------------

    private static WorkflowNode agent(String id, String prompt) {
        return WorkflowNode.builder(id).type(NodeType.AGENT).prompt(prompt).build();
    }

    /** 把 visited 画成 {@code a → b → c} 的一行，控制流一眼可见。 */
    public static String path(WorkflowRun run) {
        return String.join(" → ", run.visited());
    }

    private static WorkflowException catchWorkflow(Runnable body) {
        try {
            body.run();
        } catch (WorkflowException e) {
            return e;
        }
        throw new AssertionError("预期抛 WorkflowException，但没有抛");
    }

    /** 从假运行时的事件表里取出 loop-guard 的 visits 值，演示里直接打给人看。 */
    private static String visitsOf(FakeWorkflowRuntime runtime) {
        return runtime.events().contains("workflow.loop-guard") ? "见上方事件表" : "（未触发）";
    }

    /** 多行文本缩进一下，免得演示输出的层级糊在一起。 */
    static String indent(String text) {
        return "      " + flatten(text).replace("\n", "\n      ");
    }

    /** 把多行压成一行，长文本截断 —— 演示输出是给人扫的，不是给人读的。 */
    static String flatten(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }
}
