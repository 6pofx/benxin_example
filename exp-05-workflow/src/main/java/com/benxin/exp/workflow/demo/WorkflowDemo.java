package com.benxin.exp.workflow.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.workflow.agent.WorkflowAgents;
import com.benxin.exp.workflow.hook.WorkflowEventCollector;
import com.benxin.exp.workflow.tool.FlakyTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.StagedLoop;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 启动即跑的演示：把工作流模式的两条路线、三类装配、六种节点、三道防线都打一遍。
 *
 * <p>输出刻意保持"看得见结论"：每一节都会打印 {@code WorkflowRun.visited()} 画出来的路径
 * （{@code begin → classify → route → ...}）。这是<b>"控制流由我们决定、而不是模型自述"</b>
 * 最直观的证据 —— 路径在跑之前就能从 YAML 里读出来，跑完之后又被如实记下来，两者应当一致。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowDemo implements ApplicationRunner {

    private final WorkflowAgents.TriageAgent triage;
    private final WorkflowAgents.LoopAgent loopAgent;
    private final WorkflowAgents.FlakyAgent flakyAgent;
    private final WorkflowAgents.BudgetAgent budgetAgent;
    private final WorkflowAgents.StagedAgent stagedAgent;
    private final WorkflowAgents.ReleaseNoteAgent releaseNoteAgent;
    private final WorkflowAgents.InlineAgent inlineAgent;

    private final AgentRegistry agentRegistry;
    private final LoopRegistry loopRegistry;
    private final RecordingModel model;
    private final WorkflowEventCollector collector;
    private final CommonTools commonTools;
    private final FlakyTools flakyTools;
    private final ResourceLoader resourceLoader;
    private final PureEngineDemo pureEngineDemo;

    public WorkflowDemo(WorkflowAgents.TriageAgent triage,
                        WorkflowAgents.LoopAgent loopAgent,
                        WorkflowAgents.FlakyAgent flakyAgent,
                        WorkflowAgents.BudgetAgent budgetAgent,
                        WorkflowAgents.StagedAgent stagedAgent,
                        WorkflowAgents.ReleaseNoteAgent releaseNoteAgent,
                        WorkflowAgents.InlineAgent inlineAgent,
                        AgentRegistry agentRegistry,
                        LoopRegistry loopRegistry,
                        RecordingModel mockLlmModel,
                        WorkflowEventCollector collector,
                        CommonTools commonTools,
                        FlakyTools flakyTools,
                        ResourceLoader resourceLoader,
                        PureEngineDemo pureEngineDemo) {
        this.triage = triage;
        this.loopAgent = loopAgent;
        this.flakyAgent = flakyAgent;
        this.budgetAgent = budgetAgent;
        this.stagedAgent = stagedAgent;
        this.releaseNoteAgent = releaseNoteAgent;
        this.inlineAgent = inlineAgent;
        this.agentRegistry = agentRegistry;
        this.loopRegistry = loopRegistry;
        this.model = mockLlmModel;
        this.collector = collector;
        this.commonTools = commonTools;
        this.flakyTools = flakyTools;
        this.resourceLoader = resourceLoader;
        this.pureEngineDemo = pureEngineDemo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 05 · 工作流模式 —— 控制流由我们决定，而不是模型自述");
        demo.banner("模型: %s（默认 %s）", List.of(model.name()), model.name());
        demo.banner("装配出来的工作流 Loop: %s",
                loopRegistry.names().stream().filter(name -> name.startsWith("workflow")).toList());
        demo.banner("全部 Loop: %s", loopRegistry.names());
        demo.banner("Agent: %s", List.of(
                "triageAgent", "inlineAgent", "loopAgent",
                "flakyAgent", "budgetAgent", "stagedAgent", "releaseNoteAgent"));

        // ---- 声明式工作流 ----
        demo.guard("装配路径① location", () -> declaredFromLocation(demo));
        demo.guard("装配路径② inline", () -> declaredFromInline(demo));
        demo.guard("装配路径③ 逐次传入定义", () -> runtimeDefinition(demo));
        demo.guard("回边循环", () -> loopWorkflow(demo));
        demo.guard("节点级容错", () -> faultTolerance(demo));
        demo.guard("三道防线", () -> threeGuards(demo));
        demo.guard("轨迹口径与事件", () -> trajectory(demo));

        // ---- 小语言：条件表达式与模板 ----
        ConditionsDemo.show(demo, "条件表达式全谱（直接调用 Conditions.evaluate）",
                ConditionsDemo.rows());
        ConditionsDemo.show(demo, "几处容易猜错的口径（全部是实测出来的）", ConditionsDemo.pitfalls());
        ConditionsDemo.showValidate(demo);
        TemplatesDemo.show(demo);
        TemplatesDemo.showApis(demo);

        // ---- WorkflowIo 与校验 ----
        demo.guard("WorkflowIo API", () -> WorkflowIoDemo.show(demo, resourceLoader,
                new ClassPathResource("workflows/triage.yml")));
        demo.guard("加载期校验", () -> WorkflowIoDemo.showValidation(demo, resourceLoader));

        // ---- 纯引擎 ----
        demo.guard("纯引擎（不依赖 Agent / 模型）", () -> pureEngineDemo.run(demo));

        // ---- staged 路线与对照 ----
        demo.guard("staged：验收一次通过", () -> stagedPasses(demo));
        demo.guard("staged：验收不通过 → 修复轮", () -> stagedRepairs(demo));
        demo.guard("staged：关掉验收闸门", () -> stagedWithoutVerify(demo));
        demo.guard("两条路线对照", () -> twoRoutes(demo));

        demo.done();
    }

    // ------------------------------------------------------------------
    // 一、三条装配路径
    // ------------------------------------------------------------------

    /** ① {@code llm.workflows.triage.location: classpath:workflows/triage.yml}。 */
    private void declaredFromLocation(Demo demo) {
        demo.section("装配路径① location —— 一个 YAML + 一行配置 = 一个 workflow:<key> Loop");
        reset();
        demo.info("LoopRegistry 里的名字 = %s",
                loopRegistry.names().stream().filter(n -> n.startsWith("workflow")).toList());
        demo.info("   ★ 注册名是配置键（workflow:triage），不是 YAML 里的 name（triage）——");
        demo.info("     两者不一致时必须显式传 loopName，否则 /llm/loops 里会查不到这个名字。");

        String ticket = "线上支付接口大面积超时，影响全部下单用户";
        AgentResult result = agentRegistry.get("triageAgent").call(ticket, "demo-triage", null);
        WorkflowRun run = runOf(result);

        demo.ok("visited = %s", path(run));
        demo.info("executions=%d truncated=%s", run.executions(), run.truncated());
        demo.ok("最终输出:\n%s", indent(run.output()));
        demo.info("黑板变量 = %s", variablesOf(result).keySet());
        demo.info("   注意 channel / severity 两个变量只在**一条**分支上被 set ——");
        demo.info("   这就是「两条分支各写各的变量再汇聚」的正确写法。");
        demo.info("本期模型被调用了 %d 次（agent 节点各一次；tool / branch / set 节点不花 token）",
                scripted().calls());

        // 同一条装配路径、同一张图，换一张普通工单 —— 走默认边的那条路
        reset();
        AgentResult mild = agentRegistry.get("triageAgent")
                .call("文档里的错别字订正", "demo-triage-normal", null);
        WorkflowRun mildRun = runOf(mild);
        demo.ok("普通工单 visited = %s（默认边：route → normal → report → check → done，不回炉）",
                path(mildRun));
        demo.info("   ${stamp:-未打戳} 的兜底生效：%s",
                flatten(mildRun.output().lines()
                        .filter(line -> line.startsWith("时间戳")).findFirst().orElse("(没有时间戳这一行)")));
        demo.info("   这条路上一次工具调用都没有：callCount=%d", commonTools.callCount());
    }

    /** ② {@code llm.workflows.inline-demo.inline: |} —— 就地写一段 YAML。 */
    private void declaredFromInline(Demo demo) {
        demo.section("装配路径② inline —— 不加文件、不改 Java，一段配置就多一个工作流");
        reset();
        // inlineAgent 的 loop 就是 workflow:inline-demo（application.yml 里那段内联 YAML），
        // 所以这一节跑的是**真的内联定义**，而不是把某个 YAML 文件读出来充数。
        WorkflowDefinition inline = ((WorkflowLoop) loopRegistry.get("workflow:inline-demo"))
                .definition().orElseThrow();
        demo.info("inline 定义的 name = %s，节点 = %s", inline.name(),
                inline.nodes().stream().map(node -> node.type().wireName() + ":" + node.id()).toList());
        demo.info("   ★ 定义直接来自 llm.workflows.inline-demo.inline，磁盘上没有对应文件。");
        demo.info("   ★ inline 与 location 只能二选一 —— 同时配置会在启动期抛：");
        demo.info("     「llm.workflows.x 同时配置了 location 与 inline，请二选一」（见 WorkflowPropertiesTest）");

        AgentResult result = inlineAgent.runInline("inline 演示任务");
        WorkflowRun run = runOf(result);
        demo.ok("visited = %s", path(run));
        demo.ok("输出 = %s", flatten(run.output()));
        demo.info("   ★ ${mark} 这种「set 节点的 id」取不到值 —— set 不产生输出，");
        demo.info("     它只写自己 set: 里声明的变量（这里是 ${note}），所以模板会保留原文。");
    }

    /**
     * ③ 逐次传入定义：{@code agent.call(input, sessionId, Map.of(WorkflowLoop.ATTRIBUTE, text), stream)}。
     *
     * <p>键名用 {@link WorkflowLoop#ATTRIBUTE}（{@code "benxin.workflow"}）。属性优先于
     * Loop 绑定的默认定义 —— 属性是"这一次调用"的意思表示，意图更近。</p>
     */
    private void runtimeDefinition(Demo demo) {
        demo.section("装配路径③ 逐次传入定义 —— 同一个 Agent 每次跑不同的图（YAML 与 JSON 各一遍）");
        reset();
        Agent agent = agentRegistry.get("triageAgent");

        // 值可以是 YAML / JSON 文本、Map，或者一个 WorkflowDefinition 对象
        String runtimeYaml = """
                name: runtime-yaml
                description: 调用时传进来的 YAML 定义
                nodes:
                  - id: begin
                    type: start
                    prompt: "runtime 定义收到：${input}"
                  - id: mark
                    type: set
                    set:
                      where: "来自 benxin.workflow 属性"
                  - id: done
                    type: end
                    output: "YAML 定义跑完了：${where} / 输入=${begin}"
                edges:
                  - from: begin
                    to: mark
                  - from: mark
                    to: done
                """;
        AgentResult yamlResult = agent.call("session-runtime-yaml",
                List.of(ChatMessage.user("传定义给我")),
                Map.of(WorkflowLoop.ATTRIBUTE, runtimeYaml), null);
        demo.ok("YAML 定义：visited = %s", path(runOf(yamlResult)));
        demo.ok("   输出 = %s", flatten(yamlResult.text()));
        demo.info("   ★ 取到值的是 ${where}（set: 里声明的变量名），不是 ${mark}（set 节点的 id）——");
        demo.info("     set 节点不产生输出，所以写成 ${mark} 时它会原样留在答案里。");

        // 对照：故意把 set 节点的 id 当变量引用 —— 取不到就保留原文，而不是变成空串
        AgentResult misspelled = agent.call("session-runtime-misspelled",
                List.of(ChatMessage.user("传定义给我")),
                Map.of(WorkflowLoop.ATTRIBUTE, runtimeYaml.replace("${where}", "${mark}")), null);
        demo.warn("   写成 ${mark} 时输出 = %s", flatten(misspelled.text()));

        // 同一张图，JSON 文本形式；顺便证明 WorkflowIo 两条入口共用一套校验
        String runtimeJson = WorkflowIo.writeJson(WorkflowIo.read(runtimeYaml));
        AgentResult jsonResult = agent.call("session-runtime-json",
                List.of(ChatMessage.user("传定义给我")),
                Map.of(WorkflowLoop.ATTRIBUTE, runtimeJson), null);
        demo.ok("JSON 定义：visited = %s", path(runOf(jsonResult)));
        demo.ok("   输出 = %s", flatten(jsonResult.text()));
        demo.info("   ★ 两条入口共用一套校验：JSON 只是同一份定义的另一种写法");

        // 别名键也认
        AgentResult alias = agent.call("session-runtime-alias",
                List.of(ChatMessage.user("走别名键")),
                Map.of(WorkflowLoop.DEFINITION_ATTRIBUTE, runtimeYaml), null);
        demo.ok("别名键 %s 也认：%s", WorkflowLoop.DEFINITION_ATTRIBUTE, flatten(alias.text()));

        demo.info("跑完之后 Loop 会把两份证据写回运行时属性：");
        demo.info("   %s = %s", WorkflowLoop.RUN_ATTRIBUTE, path(runOf(yamlResult)));
        demo.info("   %s = %s", WorkflowLoop.VARIABLES_ATTRIBUTE,
                variablesOf(yamlResult));
    }

    // ------------------------------------------------------------------
    // 二、回边循环
    // ------------------------------------------------------------------

    private void loopWorkflow(Demo demo) {
        demo.section("回边循环 —— 把边指回上游就是循环，不需要任何专门的循环节点");
        reset();
        AgentResult result = loopAgent.runLoop("检查一下计数器");
        WorkflowRun run = runOf(result);

        demo.ok("visited = %s", path(run));
        demo.info("   distinctVisited = %s（去重后就是「图上的节点集合」）", run.distinctVisited());
        demo.ok("executions = %d，truncated = %s", run.executions(), run.truncated());
        demo.ok("输出:\n%s", indent(run.output()));
        demo.info("★ 循环体是 set + tool，**一个模型都没调**（本次调用模型 %d 次）——",
                scripted().calls());
        demo.info("  这说明步数预算对它完全无效（没有模型调用就不消耗步数），见下一节的三道防线。");
    }

    // ------------------------------------------------------------------
    // 三、节点级容错
    // ------------------------------------------------------------------

    private void faultTolerance(Demo demo) {
        demo.section("节点级容错 —— retry 与 continueOnError 对 agent / tool 节点一视同仁");
        reset();
        AgentResult result = flakyAgent.withRetry("走一遍容错图");
        WorkflowRun run = runOf(result);

        demo.ok("visited = %s", path(run));
        demo.ok("输出:\n%s", indent(run.output()));
        demo.info("工具侧证据：flaky_stamp 成功 %d 次、消耗掉失败配额 %d 次",
                flakyTools.successes(), flakyTools.consumedFailures());
        demo.info("事件表里的重试与失败：");
        demo.info("   workflow.node-retry × %d", collector.count("workflow.node-retry"));
        demo.info("   workflow.node-error × %d", collector.count("workflow.node-error"));
        for (Object payload : collector.payloads("workflow.node-error")) {
            demo.info("   → %s", payload);
        }
        demo.info("★ 关键：charge 节点重试用尽后**硬失败**，但它照样出现在 visited 里 ——");
        demo.info("  visited 证明的是「尝试过」哪些节点，不只是「成功走过」哪些节点。");
        demo.info("★ 关键：同一个失败会出现在四个可观测面上（事件负载、访问表 error 列、");
        demo.info("  ${error} 模板、出边条件），它们用的是同一句话：「节点 [id]（类型）执行失败：原因」。");
    }

    // ------------------------------------------------------------------
    // 四、三道防线
    // ------------------------------------------------------------------

    private void threeGuards(Demo demo) {
        demo.section("三道防线 —— 针对三种不同的失控");

        // ① 步数预算
        demo.info("① 步数预算：拦住「模型一直在调工具」");
        reset();
        AgentResult strained = budgetAgent.strained("预算一定不够的任务");
        WorkflowRun budgetRun = runOf(strained);
        demo.ok("   maxStepsReached = %s（与其它 Loop 同一口径）", strained.maxStepsReached());
        demo.ok("   truncated = %s，completed = %s", budgetRun.truncated(), budgetRun.completed());
        demo.ok("   visited = %s（begin 不调模型；step1/step2 各花一步，轮到 step3 时预算已尽）",
                path(budgetRun));
        demo.info("   事件 workflow.truncated × %d", collector.count("workflow.truncated"));
        demo.info("   ★ 只有「调模型」才消耗步数 —— 这条对下面是完全无效的。");

        // ② 单节点访问上限：必须证明"预算拦不住它"
        demo.info("② 单节点访问上限 maxVisitsPerNode（默认 100）：拦住「不调模型的死循环」");
        reset();
        WorkflowDefinition deadloop = readWorkflow("workflows/deadloop.yml");
        demo.info("   deadloop.yml 是一张**故意跑不完**的图：spin(branch) ⇄ again(set)，maxVisitsPerNode=%d",
                deadloop.maxVisitsPerNode());
        try {
            callWithDefinition(agentRegistry.get("triageAgent"), "永远转下去", deadloop);
            demo.warn("   本该被护栏拦下，却正常结束了");
        } catch (WorkflowException e) {
            demo.ok("   如期被护栏拦下：%s", flatten(e.getMessage()));
            demo.info("   nodeId = %s", e.nodeId());
        }
        demo.ok("   护栏事件 workflow.loop-guard × %d，visits = %s",
                collector.count("workflow.loop-guard"),
                collector.payloads("workflow.loop-guard"));
        demo.info("   ★★ 关键证明：这张图一个 token 都不烧 ⇒ 派发次数永远是 0 ⇒");
        demo.info("      步数预算**一步都不会增加**，跑到天荒地老也不会 truncated。");
        demo.info("      唯一的闸门就是这条访问上限。这就是为什么「三道防线」里必须有第二条。");

        // ③ 节点级容错
        demo.info("③ 节点级 retry / continueOnError：把「偶发失败」与「整张图崩掉」分开");
        demo.info("   § 节点级容错 那一节已经跑过：stamp 重试后成功，charge 失败但不拖垮全图。");
        demo.info("   三条防线分别对着：模型停不下来 / 图自己绕不出来 / 某个工具偶发挂掉。");
    }

    // ------------------------------------------------------------------
    // 五、轨迹口径与事件
    // ------------------------------------------------------------------

    private void trajectory(Demo demo) {
        demo.section("轨迹口径与事件 —— visited / distinctVisited / executions / truncated");
        reset();
        // 声明的返回类型是 String，所以这里走裸 Agent API 拿完整的 AgentResult
        AgentResult result = agentRegistry.get("triageAgent")
                .call("待分诊工单：磁盘使用率 91%，需要评估", "demo-trajectory", null);
        WorkflowRun run = runOf(result);

        demo.ok("visited()         = %s", run.visited());
        demo.ok("distinctVisited() = %s", run.distinctVisited());
        demo.info("executions() = %d（= 访问次数，**不含重试**；一次访问 = 一条 workflow.node 事件）",
                run.executions());
        demo.info("truncated() = %s，completed() = %s", run.truncated(), run.completed());
        demo.info("output() 首行 = %s", firstLine(run.output()));

        demo.info("── 自定义 AgentListener 收到的事件（顺序即执行顺序）:");
        for (String line : collector.describe().split("\n")) {
            demo.info("   %s", line);
        }
        demo.info("★ workflow.node 事件条数 = %d，与 executions 一致",
                collector.count("workflow.node"));
        demo.info("★ 事件里的 node 序列 = %s", collector.nodeSequence("workflow.node"));
        demo.info("  与 visited 完全一致 —— 两个可观测面口径不同那天，审计结论就开始含糊了。");
        demo.info("★ 硬失败的节点也会发一条 failed=true 的 workflow.node（见容错那一节的输出），");
        demo.info("  所以 visited 证明的是「尝试过」哪些节点；被护栏拦下的那一次不进访问表（它没有执行）。");
    }

    // ------------------------------------------------------------------
    // 六、staged 路线
    // ------------------------------------------------------------------

    private void stagedPasses(Demo demo) {
        demo.section("staged：规划 → 执行 → 验收 → 修复 → 汇总（验收一次通过）");
        reset();
        scripted().reset();
        AgentResult result = stagedAgent.run("调研一下本心的工作流模式");

        demo.info("阶段属性（Loop 会在每个阶段写回 attributes）:");
        demo.info("   %s = %s", StagedLoop.STAGE_ATTRIBUTE, result.attributes().get(StagedLoop.STAGE_ATTRIBUTE));
        demo.info("   %s = %s", StagedLoop.PLAN_ATTRIBUTE, result.attributes().get(StagedLoop.PLAN_ATTRIBUTE));
        demo.info("   %s = %s", StagedLoop.VERDICT_ATTRIBUTE, result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE));
        demo.info("   %s = %s（没进修复轮）",
                StagedLoop.REPAIR_ROUNDS_ATTRIBUTE, result.attributes().get(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE));
        demo.ok("loop = %s，steps = %d，maxStepsReached = %s",
                result.loopName(), result.steps(), result.maxStepsReached());
        demo.ok("最终输出 = %s", flatten(result.text()));
        demo.info("事件: staged.stage × %d，staged.plan × %d，staged.verdict × %d",
                collector.count("staged.stage"), collector.count("staged.plan"),
                collector.count("staged.verdict"));
        demo.info("★ 阶段定义（写死在代码里，不依赖模型自觉）：%s",
                loopRegistry.get(StagedLoop.NAME).description());
    }

    private void stagedRepairs(Demo demo) {
        demo.section("staged：验收不通过 → 回到修复阶段 → 再验收");
        reset();
        // 换一个验收结论为"不通过"的脚本：staged 会带着具体问题进修复轮
        ScriptedLlmModel failing = ScriptedLlmModel.builder("staged-fail")
                .rule("编号计划", """
                        1. 收集工作流模式的关键事实
                        2. 归纳两条路线的差别
                        3. 给出选择建议""")
                .verifyPass(false)
                .fallback("（离线模型）已收到：{input}")
                .build();
        Agent agent = Agent.builder("stagedRepairAgent")
                .model(failing)
                .loop(loopRegistry.get(StagedLoop.NAME))
                .maxSteps(24)
                .memory(false)
                // ★ 手工造的 Agent 不会自动挂上容器里的监听器，必须显式加 ——
                //   否则事件表是空的，下面那行"阶段轨迹"就会打印出一个空列表。
                .listener(collector)
                .build();
        AgentResult result = agent.call("调研一下本心的工作流模式");

        demo.ok("verdict = %s（验收者给了「结论：不通过」）",
                result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE));
        demo.ok("repair-rounds = %s（带着问题回修复阶段跑了一轮）",
                result.attributes().get(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE));
        demo.info("阶段轨迹（事件里的 staged.stage 序列）= %s", collector.payloads("staged.stage"));
        demo.info("最终输出 = %s", flatten(result.text()));
        demo.info("★ 同一份代码、同一个 Loop，只是验收结论不同 —— 这就是「先做后验」的收益。");
    }

    private void stagedWithoutVerify(Demo demo) {
        demo.section("staged：new StagedLoop(false, n) 关掉验收闸门 → 退化成「带汇总的 plan-execute」");
        reset();
        AgentResult result = stagedAgent.run("关掉验收再跑一遍");
        demo.info("注册表里的 staged = %s", loopRegistry.get(StagedLoop.NAME).description());

        // 自己造一个关掉验收的 staged：这是"阶段强度可调"的最小示范
        StagedLoop lenient = new StagedLoop(false, 0);
        Agent agent = Agent.builder("stagedLenientAgent")
                .model(model)
                .loop(lenient)
                .maxSteps(24)
                .memory(false)
                .listener(collector)
                .build();
        collector.reset();
        AgentResult lenientResult = agent.call("关掉验收再跑一遍");

        demo.ok("new StagedLoop(false, 0).description() = %s", lenient.description());
        demo.info("verdict 属性 = %s（关掉验收后压根不会写）",
                lenientResult.attributes().get(StagedLoop.VERDICT_ATTRIBUTE));
        demo.ok("staged.verdict 事件 × %d（= 0，验收阶段被整段跳过）",
                collector.count("staged.verdict"));
        demo.info("先前那个开验收的跑了 %d 步，这个关验收的跑了 %d 步",
                result.steps(), lenientResult.steps());
        demo.info("★ 关掉验收后就只剩「规划 → 执行 → 汇总」，也就是 plan-execute 加一次收口。");
    }

    // ------------------------------------------------------------------
    // 七、两条路线对照
    // ------------------------------------------------------------------

    private void twoRoutes(Demo demo) {
        demo.section("两条路线对照 —— 同一个任务，workflow 与 staged 各跑一遍");
        String task = "把「支付接口超时」这次变更写成一条发布说明";
        reset();

        AgentResult byWorkflow = agentRegistry.get("triageAgent").call(task, "route-workflow", null);
        WorkflowRun workflowRun = runOf(byWorkflow);
        demo.ok("workflow（图由外部定义）: visited = %s", path(workflowRun));
        demo.info("   输出 = %s", firstLine(workflowRun.output()));
        demo.info("   控制流：写死在 YAML 里，跑之前就能说出来会经过哪些节点");

        reset();
        AgentResult byStaged = stagedAgent.run(task);
        demo.ok("staged（流程由代码固化）: loop = %s，steps = %d", byStaged.loopName(), byStaged.steps());
        demo.info("   输出 = %s", flatten(byStaged.text()));
        demo.info("   控制流：PLAN → EXECUTE → VERIFY → SYNTHESIZE，写死在 StagedLoop 里");

        demo.ok("选型建议：流程固定、要可审计可复现 → workflow；任务形态多变、想要先做后验 → staged。");
        demo.info("★ 声明式那条改流程只改 YAML；staged 那条改流程要改代码。反过来，");
        demo.info("  图定不下来的时候声明式完全帮不上忙，而 staged 照样能跑。两者都不适合探索性任务。");
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 用属性把定义喂给一个 loop=workflow 的 Agent（第三条装配路径）。 */
    private AgentResult callWithDefinition(Agent agent, String input, WorkflowDefinition definition) {
        return agent.call("session-" + UUID.randomUUID(),
                List.of(ChatMessage.user(input)),
                Map.of(WorkflowLoop.ATTRIBUTE, definition), null);
    }

    @SuppressWarnings("unchecked")
    private WorkflowRun runOf(AgentResult result) {
        Object run = result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        if (run instanceof WorkflowRun workflowRun) {
            return workflowRun;
        }
        throw new IllegalStateException("AgentResult.attributes() 里没有 " + WorkflowLoop.RUN_ATTRIBUTE
                + "（当前属性: " + result.attributes().keySet() + "）");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> variablesOf(AgentResult result) {
        Object variables = result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private WorkflowDefinition readWorkflow(String classpath) {
        return WorkflowIo.read(loadText("classpath:" + classpath));
    }

    private String loadText(String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + location + " 失败", e);
        }
    }

    private ScriptedLlmModel scripted() {
        return (ScriptedLlmModel) model.unwrap();
    }

    /**
     * 每一节演示前复位。
     *
     * <p>四件事都要做：模型计数、工具失败配额、事件表、Agent 记忆。声明式 Agent 默认开着记忆，
     * 不清历史的话上一节的对话会进入下一节的消息体。</p>
     *
     * <p>注意模型要复位两次：{@code RecordingModel.reset()} <b>只清它自己录下的请求</b>，
     * 不动被装饰的 {@code ScriptedLlmModel} 的调用计数 —— 而示例里打印的"本期调用了几次模型"
     * 读的正是后者。这是 {@code exp-common} 的既有口径，用之前得先知道。</p>
     */
    private void reset() {
        model.reset();
        scripted().reset();
        commonTools.reset();
        flakyTools.reset();
        collector.reset();
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    /** 把 visited 画成 {@code a → b → c}，控制流一眼可见。 */
    static String path(WorkflowRun run) {
        return String.join(" → ", run.visited());
    }

    static String indent(String text) {
        return "      " + flatten(text);
    }

    static String flatten(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        return text.strip().split("\n")[0];
    }
}
