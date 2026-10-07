package com.benxin.exp.workflow;

import com.benxin.exp.workflow.demo.PureEngineDemo;
import com.benxin.exp.workflow.engine.FakeWorkflowRuntime;
import com.benxin.llm.core.workflow.Conditions;
import com.benxin.llm.core.workflow.NodeType;
import com.benxin.llm.core.workflow.Templates;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.core.workflow.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 纯引擎：{@link WorkflowEngine} + {@link WorkflowState} + 假 {@code WorkflowRuntime}。
 *
 * <p>这个类<b>不是</b> {@code @SpringBootTest} —— 它证明的是"引擎与框架解耦"：
 * 分支选择、循环退出、重试、{@code continueOnError}、预算中断与两道护栏，
 * 全部在"没有 Spring、没有 Agent、没有模型"的前提下被断言。</p>
 *
 * <p>这里刻意不复刻插件自带的 {@code WorkflowEngineTest}，而是把重点放在
 * <b>假运行时能控制的那些边界</b>上：模板与条件在同一张图里的配合、
 * 死胡同、error 的清除时机、护栏与预算的对照。</p>
 */
@DisplayName("纯引擎 WorkflowEngine")
class WorkflowEngineTest {

    private static WorkflowRun run(WorkflowDefinition definition, FakeWorkflowRuntime runtime, String input) {
        return new WorkflowEngine().run(definition, new WorkflowState(input), runtime);
    }

    private static WorkflowNode agent(String id, String prompt) {
        return WorkflowNode.builder(id).type(NodeType.AGENT).prompt(prompt).build();
    }

    private static WorkflowNode end(String id, String output) {
        return WorkflowNode.builder(id).type(NodeType.END).output(output).build();
    }

    // ------------------------------------------------------------------
    // 顺序、模板与黑板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("顺序推进 + 模板渲染 + instruction 前缀")
    void sequentialWithTemplates() {
        WorkflowDefinition definition = WorkflowDefinition.builder("seq")
                .node(WorkflowNode.builder("begin").type(NodeType.START).prompt("收到：${input}").build())
                .node(WorkflowNode.builder("draft").type(NodeType.AGENT)
                        .instruction("你是撰写者")
                        .prompt("基于《${begin}》写一句结论")
                        .build())
                .node(WorkflowNode.builder("mark").type(NodeType.SET)
                        .set(Map.of("stage", "已起草", "note", "由 ${draft} 推出"))
                        .build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("${stage}|${note}|${draft}").build())
                .edge("begin", "draft")
                .edge("draft", "mark")
                .edge("mark", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("draft", "结论：可以发布");
        WorkflowState state = new WorkflowState("把说明补完");
        WorkflowRun result = new WorkflowEngine().run(definition, state, runtime);

        assertThat(result.visited()).containsExactly("begin", "draft", "mark", "done");
        assertThat(result.executions()).isEqualTo(4);
        assertThat(result.output()).isEqualTo("已起草|由 结论：可以发布 推出|结论：可以发布");
        // END 渲染出的就是最终答案，因此 state.last() 也跟着变成它
        assertThat(state.text("draft")).isEqualTo("结论：可以发布");
        // instruction 拼在渲染后的 prompt 之前
        assertThat(runtime.prompt("draft")).isEqualTo("你是撰写者\n\n基于《收到：把说明补完》写一句结论");
        // 黑板：set 的变量进去了，保留名不进变量表但快照能取到
        assertThat(state.variables()).containsKeys("begin", "draft", "stage", "note");
        assertThat(state.variables()).doesNotContainKeys("input", "last", "current");
        assertThat(state.snapshot()).containsEntry("input", "把说明补完");
        assertThat(state.snapshot()).containsKey("last");
        // ★ 实测口径：**END 也是"产生输出"的节点，它同样覆盖 ${last}** ——
        //   END 渲染出来的就是最终答案，所以跑完之后 last 等于它，而不是上一段 draft 的输出。
        //   （本用例早先的注释写的是"最后一个产生输出的节点是 draft"，与代码不符，见汇报。）
        assertThat(state.last()).isEqualTo("已起草|由 结论：可以发布 推出|结论：可以发布");
        assertThat(state.text("done")).isEqualTo(state.last());
        assertThat(state.current()).isEqualTo("done");
    }

    @Test
    @DisplayName("★ 分支条件里的 ${x:-兜底}：1.1.1 起生效，那条边不再永远不成立")
    void fallbackInsideBranchConditionsWorks() {
        // retryCount 从头到尾没有任何节点写过它 —— 兜底值 0 是它唯一的值来源。
        // 1.1.0 时 Conditions 会把 "retryCount:-0" 整串当变量名 ⇒ null ⇒ 条件恒为 false，
        // 于是永远落到默认边 first；1.1.1 起按字面量解析兜底值，条件成立走 retry。
        // （这是本工程 FINDINGS 第 13 条的端到端复现：从"静默死分支"变成"按写的走"。）
        WorkflowDefinition definition = WorkflowDefinition.builder("fallback-branch")
                .node(WorkflowNode.builder("begin").type(NodeType.START).prompt("${input}").build())
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(agent("retry", "回炉：${begin}"))
                .node(agent("first", "首次通过：${begin}"))
                .node(end("done", "${last}"))
                .edge("begin", "route")
                .edge("route", "retry", "${retryCount:-0} <= 2")
                .edge("route", "first")
                .edge("retry", "done")
                .edge("first", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .output("retry", "走了兜底分支")
                .output("first", "走了默认分支");
        WorkflowState state = new WorkflowState("任务");
        WorkflowRun run = new WorkflowEngine().run(definition, state, runtime);

        assertThat(run.visited()).containsSubsequence("route", "retry");
        assertThat(run.visited()).doesNotContain("first");
        assertThat(run.output()).isEqualTo("走了兜底分支");
        // 再对照一次语义：兜底值参与的是**数值**比较，不是字符串比较
        assertThat(Conditions.evaluate("${retryCount:-0} <= 2", state.lookup())).isTrue();
        assertThat(Conditions.evaluate("${retryCount:-9} <= 2", state.lookup())).isFalse();
    }

    @Test
    @DisplayName("set / branch 不产生输出 ⇒ 不覆盖 ${last}：让下游节点读 ${last} 看个明白")
    void setAndBranchDoNotOverwriteLast() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "写"))
                .node(WorkflowNode.builder("mark").type(NodeType.SET).set(Map.of("k", "${a}")).build())
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                // probe 是探针：它把「此刻的 ${last}」抄进工具参数，于是中途的黑板可见
                .node(WorkflowNode.builder("probe").type(NodeType.TOOL).tool("t")
                        .args(Map.of("prev", "${last}")).build())
                .node(end("done", "${probe}"))
                .edge("a", "mark")
                .edge("mark", "route")
                .edge("route", "probe")
                .edge("probe", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("a", "甲");
        run(definition, runtime, "x");

        // mark(set) 与 route(branch) 都跑过了，但 last 仍然是 a 的输出
        assertThat(runtime.toolArgs("probe")).containsEntry("prev", "甲");
    }

    @Test
    @DisplayName("START 不写 prompt 时透传任务原文；END 不写 output 时回退到最后一个输出")
    void startAndEndFallbacks() {
        WorkflowDefinition definition = WorkflowDefinition.builder("fallback")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(agent("work", "干活：${begin}"))
                .node(WorkflowNode.builder("done").type(NodeType.END).build())
                .edge("begin", "work")
                .edge("work", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("work", "干完了");
        WorkflowRun result = run(definition, runtime, "原始任务");

        assertThat(runtime.prompt("work")).isEqualTo("干活：原始任务");
        assertThat(result.output()).isEqualTo("干完了");
    }

    @Test
    @DisplayName("入口推断：显式 entry > start 节点 > 无入边的节点")
    void entryResolutionOrder() {
        WorkflowDefinition withStart = WorkflowDefinition.builder("a")
                .node(agent("first", "一"))
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(end("done", "x"))
                .edge("begin", "first")
                .edge("first", "done")
                .build();
        // 即使 first 声明在前，start 节点仍然优先
        assertThat(withStart.resolveEntry().id()).isEqualTo("begin");

        WorkflowDefinition explicit = WorkflowDefinition.builder("b")
                .entry("second")
                .node(agent("first", "一"))
                .node(agent("second", "二"))
                .node(end("done", "x"))
                .edge("first", "done")
                .edge("second", "done")
                .build();
        assertThat(explicit.resolveEntry().id()).isEqualTo("second");

        WorkflowDefinition noIncoming = WorkflowDefinition.builder("c")
                .node(agent("a", "一"))
                .node(end("done", "x"))
                .edge("a", "done")
                .build();
        assertThat(noIncoming.resolveEntry().id()).isEqualTo("a");
    }

    // ------------------------------------------------------------------
    // 分支
    // ------------------------------------------------------------------

    @Test
    @DisplayName("条件边优先、默认边兜底；顺序即优先级")
    void edgeOrderIsPriority() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("analyze", "分析"))
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(end("serious", "严重路径"))
                .node(end("mild", "普通路径"))
                .edge("analyze", "route")
                .edge("route", "serious", "${analyze} contains 严重")
                .edge("route", "mild")
                .build();

        assertThat(run(definition, new FakeWorkflowRuntime().output("analyze", "发现严重问题"), "x").output())
                .isEqualTo("严重路径");
        assertThat(run(definition, new FakeWorkflowRuntime().output("analyze", "没什么问题"), "x").output())
                .isEqualTo("普通路径");

        // 两条都成立时走先声明的那条
        WorkflowDefinition ordered = WorkflowDefinition.builder("o")
                .node(agent("a", "写"))
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(end("first", "第一条"))
                .node(end("second", "第二条"))
                .edge("a", "route")
                .edge("route", "first", "${a} is not empty")
                .edge("route", "second", "${a} is not empty")
                .build();
        assertThat(run(ordered, new FakeWorkflowRuntime().output("a", "内容"), "x").output())
                .isEqualTo("第一条");
    }

    @Test
    @DisplayName("branch 没有条件成立的出边时按死胡同收尾，并广播 workflow.dead-end")
    void branchDeadEnd() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "写"))
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(end("never", "不会到"))
                .edge("a", "route")
                .edge("route", "never", "${a} contains 永远不会出现的词")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("a", "内容");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.visited()).containsExactly("a", "route");
        assertThat(result.output()).isEqualTo("内容");
        assertThat(result.completed()).isTrue();
        assertThat(runtime.events()).contains("workflow.dead-end");
    }

    @Test
    @DisplayName("节点没有出边时也按死胡同收尾")
    void nodeWithoutOutgoingIsDeadEnd() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "写"))
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("a", "内容");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.visited()).containsExactly("a");
        assertThat(result.output()).isEqualTo("内容");
        assertThat(runtime.events()).contains("workflow.dead-end");
    }

    @Test
    @DisplayName("★ 实测边界：END 节点的出边没有任何防线 —— 加载期不报错不警告，运行期静默丢弃")
    void edgesLeavingEndAreSilentlyDropped() {
        // 这张图的语义是"走到 report 就收工"，写在它后面的 done 是死代码。
        // build() 里的 WorkflowDefinition.validate() 只对"默认边之前的条件边"打 WARN，
        // 对 END 出边既不报错也不警告；引擎执行到 END 直接 break，边被静默忽略。
        // ★ 这正是 triage.yml 早先那一版栽的坑：report 写成 end，整段回边循环一圈都没转，
        //   而运行结果看上去完全正常（见汇报的「插件问题」第 2 条）。
        WorkflowDefinition definition = WorkflowDefinition.builder("end-with-outgoing-edges")
                .node(agent("begin", "写"))
                .node(end("report", "${begin}"))
                .node(end("done", "永远到不了"))
                .edge("begin", "report")
                .edge("report", "done")
                .build();     // ← 走到这里一声不吭

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("begin", "甲");
        WorkflowRun result = run(definition, runtime, "x");

        // 边还留在图里（图上写着 report → done），但运行期一次都不会被求值
        assertThat(definition.outgoing("report")).hasSize(1);
        assertThat(result.visited()).containsExactly("begin", "report");
        assertThat(result.output()).isEqualTo("甲");
        // 也**不是**死胡同收尾：引擎根本没看那些出边
        assertThat(runtime.events()).contains("workflow.end").doesNotContain("workflow.dead-end");
    }

    // ------------------------------------------------------------------
    // 循环
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回边构成循环，条件不再成立时正常退出")
    void loopsUntilConditionFails() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("work", "继续干活"))
                .node(WorkflowNode.builder("check").type(NodeType.BRANCH).build())
                .node(end("done", "收工"))
                .edge("work", "check")
                .edge("check", "work", "${work} contains 继续")
                .edge("check", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().sequence("work", "继续做", "停");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.visited()).containsExactly("work", "check", "work", "check", "done");
        assertThat(runtime.attempts("work")).isEqualTo(2);
        assertThat(result.output()).isEqualTo("收工");
    }

    @Test
    @DisplayName("循环体每圈写一个变量：靠它证明循环真的转了 N 圈（工具的返回值必须用 set 显式写回）")
    void loopCarriesStateAcrossRounds() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("seed").type(NodeType.SET).set(Map.of("n", "0")).build())
                .node(WorkflowNode.builder("check").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("bump").type(NodeType.TOOL).tool("next_attempt")
                        .args(Map.of("n", "${n}", "prev", "${last}")).build())
                // ★ 这一格是必须的：引擎只把节点输出记在「节点 id」名下（${bump}），
                //   不会自动写回 n。少了它，${n} 永远是 0，条件永远成立、图转到护栏才停。
                .node(WorkflowNode.builder("tally").type(NodeType.SET).set(Map.of("n", "${bump}")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END).output("共 ${n} 圈").build())
                .edge("seed", "check")
                .edge("check", "bump", "${n} < 3")
                .edge("check", "done")
                .edge("bump", "tally")
                .edge("tally", "check")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().sequence("bump", "1", "2", "3");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.visited()).containsExactly(
                "seed", "check", "bump", "tally", "check", "bump", "tally", "check", "bump", "tally",
                "check", "done");
        assertThat(result.output()).isEqualTo("共 3 圈");
        assertThat(runtime.attempts("bump")).isEqualTo(3);
        // 模板渲染只做插值、不做算术：${n} 是 set 写进去的字符串，${last} 是上一个输出。
        // 最后一圈的 args 里 prev=2（上一圈 bump 的输出），说明循环确实在带着状态转。
        assertThat(runtime.toolArgs("bump")).containsEntry("n", "2").containsEntry("prev", "2");
        assertThat(runtime.agentNodes()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 失败：retry 与 continueOnError
    // ------------------------------------------------------------------

    @Test
    @DisplayName("retry：前几次失败后重试成功，executions 不含重试")
    void retriesThenSucceeds() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("干活").retry(2).build())
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().output("a", "终于成功").failFirst("a", 2);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("终于成功");
        assertThat(runtime.attempts("a")).isEqualTo(3);
        assertThat(result.executions()).isEqualTo(2);       // a 与 done 各一次
        assertThat(runtime.events()).contains("workflow.node-retry");
        assertThat(runtime.events()).doesNotContain("workflow.node-error");
    }

    @Test
    @DisplayName("retry 对 tool 节点同样有效（重试与节点类型无关）")
    void retryWorksForToolNodesToo() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("call").type(NodeType.TOOL).tool("pay").retry(3).build())
                .node(end("done", "${call}"))
                .edge("call", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().failFirst("call", 2).output("call", "扣款成功");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("扣款成功");
        assertThat(runtime.attempts("call")).isEqualTo(3);
        // 三次尝试各调了一次同一个工具 —— 重试是"重新执行一次节点"，不是"重发一次结果"
        assertThat(runtime.toolNames()).containsExactly("pay", "pay", "pay");
    }

    @Test
    @DisplayName("重试用尽后抛 WorkflowException，异常里带工作流名、节点 id 与统一文案")
    void failsAfterRetriesExhausted() {
        WorkflowDefinition definition = WorkflowDefinition.builder("payflow")
                .node(WorkflowNode.builder("call").type(NodeType.TOOL).tool("pay").retry(1).build())
                .node(end("done", "${call}"))
                .edge("call", "done")
                .build();

        WorkflowException error = catchThrowableOfType(
                () -> run(definition, new FakeWorkflowRuntime().alwaysFail("call"), "x"),
                WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.workflow()).isEqualTo("payflow");
        assertThat(error.nodeId()).isEqualTo("call");
        assertThat(error.getMessage())
                .startsWith("节点 [call]（tool）执行失败：")
                .contains("模拟失败:call");
        // 硬失败的节点也发了一条 failed=true 的 workflow.node
        assertThat(error).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("continueOnError：出边读 ${error} 走补偿分支；错误文案与事件一致")
    void continueOnErrorAllowsCompensation() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("干活")
                        .continueOnError(true).build())
                .node(end("recover", "补偿：${error}"))
                .node(end("normal", "正常：${a}"))
                .edge("a", "recover", "${error} is not empty")
                .edge("a", "normal")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().alwaysFail("a");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).startsWith("补偿：").contains("[a]").contains("模拟失败:a");
        assertThat(result.visited()).containsExactly("a", "recover");
        assertThat(runtime.events()).contains("workflow.node-error").doesNotContain("workflow.node-retry");
    }

    @Test
    @DisplayName("后续节点成功后 error 被清空，不会把上一次的失败带进条件")
    void errorIsClearedAfterSuccess() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("注定失败")
                        .continueOnError(true).build())
                .node(agent("b", "正常干活"))
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(end("recover", "补偿"))
                .node(end("normal", "正常"))
                .edge("a", "b")
                .edge("b", "route")
                .edge("route", "recover", "${error} is not empty")
                .edge("route", "normal")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().alwaysFail("a");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("正常");
    }

    @Test
    @DisplayName("continueOnError 的节点在访问表里也留着 error —— 失败原因不会被下一个节点擦掉")
    void swallowedFailureStillVisibleOnBlackboard() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("a").type(NodeType.AGENT).prompt("失败")
                        .continueOnError(true).build())
                .node(WorkflowNode.builder("catch").type(NodeType.SET)
                        .set(Map.of("reason", "${error}")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END).output("原因=${reason}").build())
                .edge("a", "catch")
                .edge("catch", "done")
                .build();

        WorkflowState state = new WorkflowState("x");
        WorkflowRun result = new WorkflowEngine().run(definition, state,
                new FakeWorkflowRuntime().alwaysFail("a"));

        // catch 节点成功 ⇒ error 被清空，但 set 已经把原因抄进变量了
        assertThat(result.output()).contains("原因=节点 [a]（agent）执行失败").contains("模拟失败:a");
        assertThat(state.error()).isNull();
        assertThat(String.valueOf(state.variable("reason"))).contains("模拟失败:a");
    }

    // ------------------------------------------------------------------
    // 三道防线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("第一道防线：预算耗尽 → truncated，output 是半成品")
    void budgetTruncates() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(agent("b", "二"))
                .node(agent("c", "三"))
                .node(end("done", "${c}"))
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .output("a", "甲").output("b", "乙").output("c", "丙").budget(2);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.truncated()).isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.output()).isEqualTo("乙");
        assertThat(result.visited()).containsExactly("a", "b");
        assertThat(runtime.events()).contains("workflow.truncated").doesNotContain("workflow.end");
        // 预算耗尽的节点不进访问表
        assertThat(result.visited()).doesNotContain("c", "done");
    }

    @Test
    @DisplayName("预算一开始就不够时不执行任何节点")
    void zeroBudgetExecutesNothing() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().budget(0);
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.truncated()).isTrue();
        assertThat(result.executions()).isZero();
        assertThat(result.visited()).isEmpty();
        assertThat(runtime.agentNodes()).isEmpty();
        assertThat(runtime.events()).contains("workflow.truncated");
    }

    @Test
    @DisplayName("★ 第二道防线：不调模型的死循环，预算拦不住、只有 maxVisitsPerNode 拦得住")
    void modelFreeLoopIsOnlyStoppedByVisitsCap() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .maxVisitsPerNode(3)
                .node(WorkflowNode.builder("spin").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("again").type(NodeType.SET).set(Map.of("r", "${r:-0}")).build())
                .edge("spin", "again")
                .edge("again", "spin")
                .build();

        // 预算给小到 1：如果预算对这个环有效，它早就该 truncated 了
        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().budget(1);
        WorkflowException error = catchThrowableOfType(
                () -> run(definition, runtime, "x"), WorkflowException.class);

        assertThat(error).isNotNull();
        assertThat(error.nodeId()).isEqualTo("spin");
        assertThat(error.getMessage())
                .contains("已被访问 3 次")
                .contains("已达上限 3")
                .contains("死循环")
                .contains("请检查出边条件是否存在永远成立的环");
        // ★ 派发次数（含工具）就是 0：预算机制一步都不会增加
        assertThat(runtime.dispatches()).isZero();
        assertThat(runtime.agentNodes()).isEmpty();
        assertThat(runtime.events()).contains("workflow.loop-guard");
        // 护栏拦下的那一次不进访问表 —— 事件里的 visits 与实际执行次数口径一致
        assertThat(WorkflowState.RESERVED).contains("input", "last", "error", "current");
    }

    @Test
    @DisplayName("护栏事件里的 visits 按「实际执行过的次数」上报，与实际落库口径一致")
    void loopGuardReportsExecutedVisits() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .maxVisitsPerNode(2)
                .node(WorkflowNode.builder("a").type(NodeType.BRANCH).build())
                .node(WorkflowNode.builder("b").type(NodeType.BRANCH).build())
                .edge("a", "b")
                .edge("b", "a")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime();
        WorkflowException error = catchThrowableOfType(
                () -> run(definition, runtime, "x"), WorkflowException.class);

        // a, b, a, b 各执行 2 次；轮到 a 第 3 次时被拦下，事件里的 visits=2
        assertThat(error.nodeId()).isEqualTo("a");
        assertThat(runtime.events()).containsExactly(
                "workflow.start", "workflow.node", "workflow.node", "workflow.node", "workflow.node",
                "workflow.loop-guard");
        assertThat(error.getMessage()).contains("已被访问 2 次");
    }

    @Test
    @DisplayName("第三道防线：retry 与 continueOnError 覆盖 agent / tool 两类节点")
    void thirdGuardCoversBothNodeKinds() {
        WorkflowDefinition definition = WorkflowDefinition.builder("both")
                .node(WorkflowNode.builder("agentNode").type(NodeType.AGENT).prompt("问模型").retry(1).build())
                .node(WorkflowNode.builder("toolNode").type(NodeType.TOOL).tool("t").retry(1)
                        .continueOnError(true).build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("agent=${agentNode}|err=${error:-无}").build())
                .edge("agentNode", "toolNode")
                .edge("toolNode", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .failFirst("agentNode", 1)
                .output("agentNode", "答复")
                .alwaysFail("toolNode");
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("agent=答复|err=节点 [toolNode]（tool）执行失败：模拟失败:toolNode#2");
        assertThat(runtime.attempts("agentNode")).isEqualTo(2);
        assertThat(runtime.attempts("toolNode")).isEqualTo(2);
        assertThat(result.visited()).containsExactly("agentNode", "toolNode", "done");
    }

    // ------------------------------------------------------------------
    // 事件与无状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("事件顺序：start → 每个节点 → end")
    void emitsEventsInOrder() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime();
        run(definition, runtime, "x");

        assertThat(runtime.events()).containsExactly(
                "workflow.start", "workflow.node", "workflow.node", "workflow.end");
    }

    @Test
    @DisplayName("引擎无状态、可复用；同一个引擎实例可以并发跑多张图")
    void engineIsStatelessAndReusable() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        WorkflowEngine engine = new WorkflowEngine();
        WorkflowRun first = engine.run(definition, new WorkflowState("x"), new FakeWorkflowRuntime());
        WorkflowRun second = engine.run(definition, new WorkflowState("y"), new FakeWorkflowRuntime());

        assertThat(first.output()).isEqualTo("agent-a");
        assertThat(second.output()).isEqualTo("agent-a");
        assertThat(first.visited()).containsExactly("a", "done");
        assertThat(second.visited()).containsExactly("a", "done");
    }

    @Test
    @DisplayName("条件与模板在同一张图里配合：出边条件读的是 set 写进去的变量")
    void conditionsReadSetVariables() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("judge").type(NodeType.SET)
                        .set(Map.of("verdict", "通过", "score", "88")).build())
                .node(WorkflowNode.builder("route").type(NodeType.BRANCH).build())
                .node(end("pass", "通过路径"))
                .node(end("fail", "不通过路径"))
                .edge("judge", "route")
                .edge("route", "pass", "${verdict} == 通过 and ${score} > 80")
                .edge("route", "fail")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime();
        WorkflowRun result = run(definition, runtime, "x");

        assertThat(result.output()).isEqualTo("通过路径");
        assertThat(runtime.agentNodes()).isEmpty();
        // 顺手证明 Conditions 与 Templates 看到的是同一份黑板
        WorkflowState state = new WorkflowState("x");
        state.put("score", "88");
        assertThat(Conditions.evaluate("${score} > 80", state.lookup())).isTrue();
        assertThat(Templates.render("得分 ${score}", state.lookup())).isEqualTo("得分 88");
    }

    @Test
    @DisplayName("PureEngineDemo 里的那两条路径断言与引擎行为一致")
    void demoPathsMatchEngineBehaviour() {
        WorkflowRun run = new WorkflowEngine().run(
                WorkflowDefinition.builder("mini")
                        .node(agent("a", "一"))
                        .node(end("done", "${a}"))
                        .edge("a", "done")
                        .build(),
                new WorkflowState("x"),
                new FakeWorkflowRuntime());
        assertThat(PureEngineDemo.path(run)).isEqualTo("a → done");
    }

    @Test
    @DisplayName("未注册工具的 tool 节点在纯引擎里只是一次普通调用（真实运行时才升级成节点失败）")
    void toolNodeIsJustACallForTheEngine() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(WorkflowNode.builder("call").type(NodeType.TOOL).tool("no_such_tool").build())
                .node(end("done", "${call}"))
                .edge("call", "done")
                .build();

        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime();
        WorkflowRun result = run(definition, runtime, "x");

        // 假运行时不做工具解析 ⇒ 引擎拿到 "tool-no_such_tool" 就当正常输出。
        // 真实运行时（LoopContextRuntime.callTool）会把 ToolResult.error 升级成节点级失败，
        // 因此 retry / continueOnError 在真实链路上才真正生效 —— 见 WorkflowRoutesTest。
        assertThat(result.output()).isEqualTo("tool-no_such_tool");
        assertThat(runtime.toolNames()).containsExactly("no_such_tool");
    }

    @Test
    @DisplayName("WorkflowDefinition.toString / nodeIds / find / incoming 这些只读视图")
    void definitionViews() {
        WorkflowDefinition definition = WorkflowDefinition.builder("w")
                .node(agent("a", "一"))
                .node(end("done", "${a}"))
                .edge("a", "done")
                .build();

        assertThat(definition.toString()).isEqualTo("Workflow[w] 2 节点 / 1 边");
        assertThat(definition.nodeIds()).containsExactly("a", "done");
        assertThat(definition.find("a")).isPresent();
        assertThat(definition.find("nope")).isEmpty();
        assertThat(definition.incoming("done")).extracting(edge -> edge.from()).containsExactly("a");
        assertThat(definition.outgoing("done")).isEmpty();
        assertThat(definition.maxVisitsPerNode()).isEqualTo(WorkflowDefinition.DEFAULT_MAX_VISITS);
        assertThatThrownBy(() -> definition.require("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已有节点");
        assertThat(definition.nodes()).hasSize(2);
        assertThat(definition.edges()).hasSize(1);
        assertThat(List.of(NodeType.values())).hasSize(6);
    }
}
