package com.benxin.exp.workflow;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式工作流的端到端断言：三条装配路径、六种节点、回边循环、节点级容错、三道防线。
 *
 * <p>全部离线：模型是 {@code exp-common} 的脚本化模型，因此这个类既不联网也不消耗 token，
 * 却覆盖了"YAML → 定义 → 引擎 → Agent 运行时 → 真实工具"的完整链路。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("声明式工作流 workflow")
class WorkflowRoutesTest {

    @Autowired
    private WorkflowAgents.TriageAgent triage;

    @Autowired
    private WorkflowAgents.LoopAgent loopAgent;

    @Autowired
    private WorkflowAgents.FlakyAgent flakyAgent;

    @Autowired
    private WorkflowAgents.BudgetAgent budgetAgent;

    @Autowired
    private WorkflowAgents.StagedAgent stagedAgent;

    @Autowired
    private WorkflowAgents.ReleaseNoteAgent releaseNoteAgent;

    @Autowired
    private WorkflowAgents.InlineAgent inlineAgent;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LoopRegistry loopRegistry;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private WorkflowEventCollector collector;

    @Autowired
    private CommonTools commonTools;

    @Autowired
    private FlakyTools flakyTools;

    @Autowired
    private ResourceLoader resourceLoader;

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        // ★ 实测口径：RecordingModel.reset() 只清它自己录下的请求，**不动**被装饰的
        //   ScriptedLlmModel 的调用计数 —— 而这里要断言的"本轮调了几次模型"读的是后者。
        //   少了这一行，计数会跨用例累加（本类曾经因此把 10 当成 0 的期望值）。
        scripted().reset();
        commonTools.reset();
        flakyTools.reset();
        collector.reset();
        // 声明式 Agent 默认开着记忆，不传 sessionId 时都落在 "<agentName>-default" 上。
        // 测试之间共享同一个 ApplicationContext，因此必须显式清空历史 ——
        // 否则前一个用例的对话会成为后一个用例的上下文。
        //
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出"已经被解析过"的那些，用它对遍历会一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    // ------------------------------------------------------------------
    // 一、三条装配路径
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("装配路径① location：llm.workflows.<key>.location 注册出了 workflow:<key> Loop")
    void locationPathRegistersNamedLoop() {
        assertThat(loopRegistry.names()).contains(
                "workflow:triage", "workflow:loop-workflow", "workflow:inline-demo",
                "workflow:flaky-tools", "workflow:budget-probe");
        // 注册名是配置键，描述里的工作流名来自 YAML
        assertThat(loopRegistry.get("workflow:triage").name()).isEqualTo("workflow:triage");
        assertThat(loopRegistry.get("workflow:triage").description()).contains("工单分诊");

        // location 那条路径读的是文件，所以描述来自 loop-workflow.yml 自己写的 description
        assertThat(loopRegistry.get("workflow:loop-workflow").description()).contains("回边循环");
        assertThat(((WorkflowLoop) loopRegistry.get("workflow:loop-workflow")).definition()
                .orElseThrow().name()).isEqualTo("loop-workflow");
    }

    @Test
    @Order(2)
    @DisplayName("装配路径① 端到端：紧急工单走 urgent 分支，回边转满 3 圈后收工")
    void triageUrgentBranch() {
        Agent agent = agentRegistry.get("triageAgent");
        AgentResult result = agent.call(
                "线上支付接口大面积超时，影响全部下单用户", "triage-urgent", null);
        WorkflowRun run = runOf(result);

        assertThat(run.workflow()).isEqualTo("triage");
        // begin → classify → route → urgent → stamp → mark-urgent → report → check
        //   → (bump → rework → check) ×3 → done
        assertThat(run.visited()).containsExactly(
                "begin", "classify", "route", "urgent", "stamp", "mark-urgent", "report",
                "check", "bump", "rework", "check", "bump", "rework", "check", "bump", "rework",
                "check", "done");
        assertThat(run.executions()).isEqualTo(18);
        assertThat(run.truncated()).isFalse();
        assertThat(run.completed()).isTrue();

        assertThat(run.output())
                .contains("通道：加急通道")
                .contains("判级：紧急")
                .contains("处置：")
                .contains("分析：分级：紧急");
        // 时间戳来自 tool 节点直调 get_current_time（不经过模型）
        assertThat(run.output()).containsPattern("时间戳：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        // 回边转满 3 圈：报告里留下了"已回炉第 1/2/3 次"
        assertThat(run.output()).contains("已回炉第 1 次").contains("已回炉第 3 次");
        assertThat(collector.count("workflow.node")).isEqualTo(run.executions());

        // 工具确实被直调了：只有 get_current_time（next_attempt 在 FlakyTools 里，不算它）
        assertThat(commonTools.callCount()).isEqualTo(1);
        // 变量快照：两条分支各写各的变量，只有走过的那条生效；
        // 循环计数走的是 rounds（set 写回），工具输出留在 bump 名下。
        // 注意 rework 是 **set 节点的 id**，它不产生输出 ⇒ 变量表里没有这个名字（只有它写的 report/rounds）。
        assertThat(variablesOf(result))
                .containsEntry("channel", "加急通道")
                .containsEntry("severity", "紧急")
                .containsEntry("rounds", "3")
                .doesNotContainKey("rework")
                .containsKeys("report", "bump", "stamp");
    }

    @Test
    @Order(3)
    @DisplayName("装配路径① 端到端：普通工单走默认分支，不经过 urgent / stamp，也不回炉")
    void triageNormalBranch() {
        AgentResult result = agentRegistry.get("triageAgent")
                .call("文档里的错别字订正", "triage-normal", null);
        WorkflowRun run = runOf(result);

        assertThat(run.visited()).containsExactly(
                "begin", "classify", "route", "normal", "report", "check", "done");
        assertThat(run.output()).contains("通道：常规通道").contains("判级：一般");
        // ${stamp:-未打戳} 的兜底值生效 —— stamp 这个节点根本没被访问
        assertThat(run.output()).contains("时间戳：未打戳");
        // 走默认分支时一次工具调用都没有
        assertThat(commonTools.callCount()).isZero();
        assertThat(collector.nodeSequence("workflow.node")).doesNotContain("urgent", "stamp", "rework");
    }

    @Test
    @Order(4)
    @DisplayName("装配路径② inline：内联 YAML 注册成了 workflow:inline-demo Loop，且能真跑")
    void inlinePathIsRegistered() {
        assertThat(loopRegistry.contains("workflow:inline-demo")).isTrue();
        WorkflowLoop inline = (WorkflowLoop) loopRegistry.get("workflow:inline-demo");
        assertThat(inline.description()).contains("内联 YAML");
        // 定义来自 application.yml 的那段块标量，磁盘上没有对应文件
        WorkflowDefinition definition = inline.definition().orElseThrow();
        assertThat(definition.name()).isEqualTo("inline-demo");
        assertThat(definition.nodeIds()).containsExactly("begin", "mark", "done");

        AgentResult result = inlineAgent.runInline("内联演示任务");
        WorkflowRun run = runOf(result);
        assertThat(run.workflow()).isEqualTo("inline-demo");
        assertThat(run.visited()).containsExactly("begin", "mark", "done");
        assertThat(run.output())
                .isEqualTo("内联工作流完成：内联定义里的 set 节点 / 输入=内联工作流收到：内联演示任务");
    }

    @Test
    @Order(5)
    @DisplayName("装配路径③ 逐次传入定义：同一个 Agent 跑三张不同的图（YAML / JSON / 别名键）")
    void runtimeDefinitionPath() {
        Agent agent = agentRegistry.get("triageAgent");

        String yaml = """
                name: runtime-yaml
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
                    output: "跑完了：${where} / 输入=${begin}"
                edges:
                  - from: begin
                    to: mark
                  - from: mark
                    to: done
                """;

        AgentResult byYaml = callWith(agent, "传定义给我", yaml);
        assertThat(runOf(byYaml).workflow()).isEqualTo("runtime-yaml");
        assertThat(runOf(byYaml).visited()).containsExactly("begin", "mark", "done");
        // ★ 实测口径：set 节点**不产生输出**，所以它没有以「节点 id」命名的黑板值；
        //   能取到的是它 set: 里声明的变量名。上面那段 YAML 写的是 ${where}，因此渲染成功。
        assertThat(byYaml.text()).isEqualTo("跑完了：来自 benxin.workflow 属性 / 输入=runtime 定义收到：传定义给我");

        // 对照：把 set 节点的 id（mark）当变量引用 —— 取不到 ⇒ 模板保留原文，而不是变成空串
        String wrongRef = yaml.replace("${where}", "${mark}");
        assertThat(runOf(callWith(agent, "传定义给我", wrongRef)).visited())
                .containsExactly("begin", "mark", "done");
        assertThat(callWith(agent, "传定义给我", wrongRef).text())
                .isEqualTo("跑完了：${mark} / 输入=runtime 定义收到：传定义给我");

        // 同一张图的 JSON 写法：WorkflowIo 两条入口共用一套校验
        String json = WorkflowIo.writeJson(WorkflowIo.read(yaml));
        AgentResult byJson = callWith(agent, "传定义给我", json);
        assertThat(byJson.text()).isEqualTo(byYaml.text());
        assertThat(runOf(byJson).visited()).isEqualTo(runOf(byYaml).visited());

        // 别名键 benxin.workflow.definition 也认
        AgentResult byAlias = agent.call("session-" + UUID.randomUUID(),
                List.of(ChatMessage.user("走别名键")),
                Map.of(WorkflowLoop.DEFINITION_ATTRIBUTE, yaml), null);
        assertThat(runOf(byAlias).workflow()).isEqualTo("runtime-yaml");

        // 定义也可以直接给 WorkflowDefinition 对象
        AgentResult byObject = callWith(agent, "传对象", WorkflowIo.read(yaml));
        assertThat(runOf(byObject).workflow()).isEqualTo("runtime-yaml");
    }

    @Test
    @Order(6)
    @DisplayName("运行时属性优先于 Loop 绑定的默认定义")
    void runtimeAttributeWinsOverBoundDefinition() {
        Agent agent = agentRegistry.get("triageAgent");
        AgentResult result = callWith(agent, "紧急的活", """
                name: override
                nodes:
                  - id: only
                    type: end
                    output: "我覆盖了 triage"
                edges: []
                """);
        assertThat(runOf(result).workflow()).isEqualTo("override");
        assertThat(result.text()).isEqualTo("我覆盖了 triage");
    }

    // ------------------------------------------------------------------
    // 二、六种节点与回边循环
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("六种节点在一张图里各司其职：start 透传 / agent 子任务 / tool 直调 / branch 路由 / set 写变量 / end 渲染")
    void allSixNodeTypesWork() {
        // 工单原文不带前缀：triage.yml 的 begin 节点自己会加一层「待分诊工单：」包装，
        // 断言里因此能看出"start 渲染的是自己的 prompt，而不是裸透传 input"。
        AgentResult result = agentRegistry.get("triageAgent")
                .call("线上支付接口大面积超时", "six-nodes", null);
        WorkflowRun run = runOf(result);

        // start 渲染 prompt（而不是裸透传），因此 begin 的输出带了包装
        assertThat(variablesOf(result)).containsEntry("begin", "待分诊工单：线上支付接口大面积超时");
        // agent 的输出进了黑板，并且不含 instruction 前缀（instruction 只是发给模型的前缀）
        assertThat(String.valueOf(variablesOf(result).get("classify")))
                .startsWith("分级：紧急")
                .doesNotContain("你是值班工程师");
        // tool 节点的输出是工具返回值本身（get_current_time → 时间戳；"已盖章"那句是 flaky_stamp 的）
        assertThat(String.valueOf(variablesOf(result).get("stamp"))).matches("\\d{4}-\\d{2}-\\d{2} .*");
        // branch 不产生输出 ⇒ 它不在变量表里
        assertThat(variablesOf(result)).doesNotContainKey("route").doesNotContainKey("check");
        // set 节点也不产生输出 ⇒ 它自己的 id 不在变量表里；进去的是它声明的变量名
        assertThat(variablesOf(result))
                .doesNotContainKey("mark-urgent")
                .doesNotContainKey("rework")
                .containsEntry("channel", "加急通道");
        assertThat(run.visited()).contains("route", "check", "rework");
        assertThat(collector.nodeSequence("workflow.node")).contains("route", "check");
    }

    @Test
    @Order(8)
    @DisplayName("回边循环：branch ↔ tool/set 互指，转 3 圈后条件退出")
    void loopWorkflowRunsThreeRounds() {
        AgentResult result = loopAgent.runLoop("检查一下计数器");
        WorkflowRun run = runOf(result);

        assertThat(run.visited()).containsExactly(
                "begin", "seed", "counter", "bump", "tally", "counter", "bump", "tally",
                "counter", "bump", "tally", "counter", "done");
        assertThat(run.executions()).isEqualTo(13);
        assertThat(run.distinctVisited()).containsExactly("begin", "seed", "counter", "bump", "tally", "done");
        assertThat(run.output()).contains("共转了 3 圈").contains("计数器最后一次输出：3");
        assertThat(run.truncated()).isFalse();

        // ★ 循环体一个模型都没调：模型调用次数为 0（这条也正是"步数预算拦不住它"的证据）
        assertThat(scripted().calls()).isZero();
        assertThat(result.steps()).isZero();
    }

    @Test
    @Order(9)
    @DisplayName("instruction 会作为前缀拼在 prompt 之前，且都经过模板渲染")
    void instructionIsPrependedToRenderedPrompt() {
        triage.triage("线上支付接口大面积超时");
        // classify 是第一个 agent 节点，它的 prompt 里含渲染后的 ${begin}
        String sent = null;
        for (var request : mockLlmModel.requests()) {
            String text = lastUserText(request);
            if (text.contains("请对下面这张工单分级")) {
                sent = text;
                break;
            }
        }
        assertThat(sent).isNotNull();
        assertThat(sent).startsWith("你是值班工程师。");
        assertThat(sent).contains("\n\n请对下面这张工单分级");
        // ${begin} 已经被 begin 节点的输出（带包装的那一层）替换掉了
        assertThat(sent).contains("待分诊工单：线上支付接口大面积超时");
    }

    // ------------------------------------------------------------------
    // 三、节点级容错
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("retry：工具前 2 次抛异常，重试第 3 次成功；continueOnError：另一节点失败但走补偿")
    void retryAndContinueOnError() {
        AgentResult result = flakyAgent.withRetry("走一遍容错图");
        WorkflowRun run = runOf(result);

        // begin → stamp（失败2次+成功1次）→ charge（失败2次、硬失败但继续）→ compensate → done
        assertThat(run.visited()).containsExactly(
                "begin", "stamp", "charge", "compensate", "done");
        // ★ 硬失败的 charge 也在访问表里 —— visited 证明的是「尝试过」哪些节点
        assertThat(run.visited()).contains("charge");
        assertThat(run.executions()).isEqualTo(5);

        assertThat(run.output())
                .contains("结果：已进入补偿流程")
                .contains("失败详情：")
                .contains("禁用账号");
        assertThat(run.output()).containsPattern("时间戳（重试之后才拿到的）：\\d{4}-\\d{2}-\\d{2}");

        // 工具侧证据：stamp 成功了 1 次，并且消耗掉 2 次失败配额
        assertThat(flakyTools.successes()).isEqualTo(1);
        assertThat(flakyTools.consumedFailures()).isEqualTo(2);

        // 事件：stamp 有 2 条重试、1 条节点失败事件（charge 的失败）
        assertThat(collector.count("workflow.node-retry")).isEqualTo(3);
        assertThat(collector.count("workflow.node-error")).isEqualTo(1);
        assertThat(collector.count("workflow.node")).isEqualTo(run.executions());

        // 失败文案只有一份：事件负载里就是 message 那一句
        Map<?, ?> errorPayload = (Map<?, ?>) collector.payloads("workflow.node-error").get(0);
        assertThat(String.valueOf(errorPayload.get("node"))).isEqualTo("charge");
        assertThat(String.valueOf(errorPayload.get("error")))
                .contains("节点 [charge]（tool）执行失败")
                .contains("禁用账号");
        assertThat(errorPayload.get("continueOnError")).isEqualTo(true);
    }

    @Test
    @Order(11)
    @DisplayName("没有 continueOnError 时，重试用尽就把整张图打挂（异常里带工作流名与节点 id）")
    void exhaustedRetryKillsTheWholeGraph() {
        // flaky_stamp 的失败配额调到 999 ⇒ retry: 3 也用不完，stamp 必然硬失败，
        // 而它没有 continueOnError ⇒ 整张图以 WorkflowException 终止
        //
        // ★ 这里必须显式 .listener(collector)：WorkflowEventCollector 是 Spring 容器里的 bean，
        //   只有容器造出来的 Agent 才会自动带上它。手工 Agent.builder(...) 造出来的实例
        //   默认挂的是 noop 监听器 —— 不加这一行，事件表会是空的（本用例早先就栽在这上面）。
        Agent agent = Agent.builder("hardFailAgent")
                .model(mockLlmModel)
                .loop(loopRegistry.get("workflow:flaky-tools"))
                .tools(com.benxin.llm.core.tool.ToolScanner.scan(new FlakyTools(999)))
                .maxSteps(16)
                .memory(false)
                .listener(collector)
                .build();

        assertThatThrownBy(() -> agent.call("走一遍容错图"))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("节点 [stamp]（tool）执行失败")
                .hasMessageContaining("下游服务暂时不可用");

        // 硬失败的 stamp 依然进了访问表（尝试过），而 charge 根本没轮到
        assertThat(collector.nodeSequence("workflow.node"))
                .containsExactly("begin", "stamp");
        assertThat(collector.count("workflow.node-retry")).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 四、三道防线
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("第一道防线：步数预算耗尽 ⇒ maxStepsReached == true 且 truncated == true")
    void firstGuardBudget() {
        AgentResult result = budgetAgent.strained("预算一定不够的任务");
        WorkflowRun run = runOf(result);

        assertThat(result.maxStepsReached()).isTrue();
        assertThat(run.truncated()).isTrue();
        assertThat(run.completed()).isFalse();
        // ★ 实测口径：预算是在**准备执行下一个节点时**才发现的 ——
        //   begin 是 start 节点不花步数；step1 / step2 各花 1 步（maxSteps = 2）；
        //   轮到 step3 时 outOfBudget 为真，于是停在 [begin, step1, step2]。
        assertThat(run.visited()).containsExactly("begin", "step1", "step2");
        assertThat(run.executions()).isEqualTo(3);
        assertThat(collector.has("workflow.truncated")).isTrue();
        assertThat(collector.has("workflow.end")).isFalse();
        // 半成品也要如实交出去
        assertThat(run.output()).isNotBlank();
    }

    @Test
    @Order(13)
    @DisplayName("第二道防线：不调模型的死循环，预算拦不住、只有 maxVisitsPerNode 拦得住")
    void secondGuardVisitsCap() {
        WorkflowDefinition deadloop = readWorkflow("workflows/deadloop.yml");
        assertThat(deadloop.maxVisitsPerNode()).isEqualTo(5);

        assertThatThrownBy(() -> callWith(agentRegistry.get("triageAgent"), "永远转下去", deadloop))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("死循环")
                .hasMessageContaining("maxVisitsPerNode")
                .hasMessageContaining("已被访问 5 次");

        // ★ 一个 token 都没烧：模型调用次数为 0
        assertThat(scripted().calls()).isZero();
        // 护栏事件可读：workflow.loop-guard 带 node 与 visits
        assertThat(collector.count("workflow.loop-guard")).isEqualTo(1);
        Map<?, ?> guard = (Map<?, ?>) collector.payloads("workflow.loop-guard").get(0);
        assertThat(guard.get("node")).isEqualTo("spin");
        assertThat(guard.get("visits")).isEqualTo(5);
        // 事件里没有 node-error（护栏不是节点失败），也没有 truncated
        assertThat(collector.has("workflow.truncated")).isFalse();

        // 对照：这张图上"步数预算"这个旋钮怎么拧都没用 —— 它连一次派发都没有
        assertThat(collector.count("workflow.node")).isEqualTo(10);
    }

    @Test
    @Order(14)
    @DisplayName("第三道防线：节点级 retry / continueOnError 把「偶发失败」与「整张图崩掉」分开")
    void thirdGuardNodeLevelTolerance() {
        // 同一张图、同一个工具：把失败配额用光之后它能跑通（retry 兜住了偶发失败）
        AgentResult result = flakyAgent.withRetry("再跑一遍");
        assertThat(runOf(result).output()).contains("结果：已进入补偿流程");
        assertThat(flakyTools.consumedFailures()).isEqualTo(2);

        // 再跑一遍：先把失败配额用光，这次 stamp 一次就成功 ⇒ 事件里只剩 charge 的那次重试
        collector.reset();
        flakyTools.reset();
        // ★ 预热必须自己接住异常：配额用光之前 flaky_stamp 就是**抛异常**的
        //   （这正是"会失败的工具"的实现方式），直接调两次会让测试自己炸掉。
        consumeStampQuota();
        AgentResult second = flakyAgent.withRetry("第三遍");
        assertThat(runOf(second).output()).contains("结果：已进入补偿流程");
        // 配额是在预热阶段被消耗的（2 次），这一遍 stamp 一次成功、不再增加
        assertThat(flakyTools.consumedFailures()).isEqualTo(2);
        assertThat(flakyTools.successes()).isEqualTo(1);
        // stamp 一次成功 ⇒ 只有 charge 重试了 1 次
        assertThat(collector.count("workflow.node-retry")).isEqualTo(1);
    }

    /** 把 flaky_stamp 的失败配额消耗光；失败会抛异常，所以这里必须自己接住。 */
    private void consumeStampQuota() {
        for (int i = 0; i < 2; i++) {
            try {
                flakyTools.flakyStamp("预热");
                throw new AssertionError("前 2 次预热本该失败，第 " + (i + 1) + " 次却成功了");
            } catch (IllegalStateException expected) {
                // 如期失败：配额被消耗掉一次
            }
        }
    }

    // ------------------------------------------------------------------
    // 五、轨迹口径与事件
    // ------------------------------------------------------------------

    @Test
    @Order(15)
    @DisplayName("轨迹口径：visited / distinctVisited / executions / truncated / completed / output 一致")
    void trajectoryContract() {
        AgentResult result = agentRegistry.get("triageAgent")
                .call("线上支付接口大面积超时", "trajectory", null);
        WorkflowRun run = runOf(result);

        assertThat(run.visited()).hasSize(run.executions());
        assertThat(run.visited()).containsExactlyElementsOf(collector.nodeSequence("workflow.node"));
        assertThat(run.distinctVisited()).isEqualTo(run.visited().stream().distinct().toList());
        assertThat(run.completed()).isTrue();
        assertThat(run.output()).isEqualTo(result.text());
    }

    @Test
    @Order(16)
    @DisplayName("事件表：start → node… → end，且 attributes 里带回 run 与 variables")
    void eventsAndAttributes() {
        AgentResult result = agentRegistry.get("triageAgent")
                .call("线上支付接口大面积超时", "events", null);

        assertThat(collector.events().get(0).type()).isEqualTo("workflow.start");
        assertThat(collector.events().get(collector.events().size() - 1).type()).isEqualTo("workflow.end");
        Map<?, ?> start = (Map<?, ?>) collector.payloads("workflow.start").get(0);
        assertThat(start.get("entry")).isEqualTo("begin");
        // triage.yml 现在有 12 个节点（report 是 set、done 是 end；循环体是 bump + rework）
        assertThat(start.get("nodes")).isEqualTo(12);

        Map<?, ?> end = (Map<?, ?>) collector.payloads("workflow.end").get(0);
        assertThat(end.get("executions")).isEqualTo(runOf(result).executions());
        assertThat(end.get("visited")).isEqualTo(runOf(result).visited());

        assertThat(result.attributes()).containsKey(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(result.attributes()).containsKey(WorkflowLoop.VARIABLES_ATTRIBUTE);
        assertThat(variablesOf(result)).containsEntry("input", "线上支付接口大面积超时");
    }

    // ------------------------------------------------------------------
    // 六、staged 路线
    // ------------------------------------------------------------------

    @Test
    @Order(17)
    @DisplayName("staged：验收一次通过 → plan / execute / verify / synthesize")
    void stagedVerifiesOnceAndPasses() {
        AgentResult result = stagedAgent.run("调研一下本心的工作流模式");

        assertThat(result.loopName()).isEqualTo(StagedLoop.NAME);
        assertThat(result.attributes()).containsEntry(StagedLoop.STAGE_ATTRIBUTE, "synthesize");
        assertThat(result.attributes()).containsEntry(StagedLoop.VERDICT_ATTRIBUTE, "pass");
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE);
        assertThat((List<?>) result.attributes().get(StagedLoop.PLAN_ATTRIBUTE)).hasSize(3);
        assertThat(collector.payloads("staged.stage"))
                .containsExactly("plan", "execute", "verify", "synthesize");
        assertThat(collector.has("staged.plan")).isTrue();
        assertThat(collector.has("staged.verdict")).isTrue();
    }

    @Test
    @Order(18)
    @DisplayName("staged：验收不通过 → 修复一轮 → 复验 → 汇总")
    void stagedRepairsOnceAndFailsVerdict() {
        ScriptedLlmModel failing = ScriptedLlmModel.builder("staged-fail")
                .rule("编号计划", "1. 收集事实\n2. 归纳差别\n3. 给出建议")
                .verifyPass(false)
                .fallback("（离线模型）已收到：{input}")
                .build();
        Agent agent = Agent.builder("stagedRepairAgent")
                .model(failing)
                .loop(loopRegistry.get(StagedLoop.NAME))
                .maxSteps(24)
                .memory(false)
                // ★ 手工造的 Agent 不会自动挂上容器里的 WorkflowEventCollector，得显式加
                .listener(collector)
                .build();

        AgentResult result = agent.call("调研一下本心的工作流模式");

        assertThat(result.attributes()).containsEntry(StagedLoop.VERDICT_ATTRIBUTE, "fail");
        assertThat(result.attributes()).containsEntry(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE, 1);
        // plan → execute → verify → repair → verify → synthesize
        assertThat(collector.payloads("staged.stage"))
                .containsExactly("plan", "execute", "verify", "repair", "verify", "synthesize");
    }

    @Test
    @Order(19)
    @DisplayName("staged：new StagedLoop(false, n) 关掉验收 → 退化成「带汇总的 plan-execute」")
    void stagedWithoutVerificationDegrades() {
        Agent agent = Agent.builder("stagedLenientAgent")
                .model(mockLlmModel)
                .loop(new StagedLoop(false, 0))
                .maxSteps(24)
                .memory(false)
                .listener(collector)
                .build();

        AgentResult result = agent.call("关掉验收再跑一遍");

        assertThat(result.attributes()).doesNotContainKey(StagedLoop.VERDICT_ATTRIBUTE);
        assertThat(collector.payloads("staged.stage")).containsExactly("plan", "execute", "synthesize");
        assertThat(collector.count("staged.verdict")).isZero();
        assertThat(new StagedLoop(false, 2).description()).contains("已关闭验收闸门");
        assertThat(new StagedLoop(true, 2).description()).contains("最多修复 2 轮");
    }

    @Test
    @Order(20)
    @DisplayName("两条路线对照：同一个任务，workflow 与 staged 的轨迹形态完全不同")
    void twoRoutesProduceDifferentShapes() {
        String task = "把支付接口超时这次变更写成一条发布说明";

        AgentResult byWorkflow = agentRegistry.get("triageAgent").call(task, "route-workflow", null);
        WorkflowRun run = runOf(byWorkflow);
        assertThat(run.visited()).startsWith("begin", "classify", "route");
        assertThat(byWorkflow.loopName()).isEqualTo("workflow:triage");

        reset();
        AgentResult byStaged = stagedAgent.run(task);
        assertThat(byStaged.loopName()).isEqualTo(StagedLoop.NAME);
        assertThat(collector.payloads("staged.stage")).contains("plan", "verify", "synthesize");
        // staged 那条路线没有 WorkflowRun 这回事
        assertThat(byStaged.attributes()).doesNotContainKey(WorkflowLoop.RUN_ATTRIBUTE);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private AgentResult callWith(Agent agent, String input, String definition) {
        return agent.call("session-" + UUID.randomUUID(),
                List.of(ChatMessage.user(input)),
                Map.of(WorkflowLoop.ATTRIBUTE, definition), null);
    }

    private AgentResult callWith(Agent agent, String input, WorkflowDefinition definition) {
        return agent.call("session-" + UUID.randomUUID(),
                List.of(ChatMessage.user(input)),
                Map.of(WorkflowLoop.ATTRIBUTE, definition), null);
    }

    private WorkflowDefinition readWorkflow(String classpath) {
        try (InputStream in = resourceLoader.getResource("classpath:" + classpath).getInputStream()) {
            return WorkflowIo.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + classpath + " 失败", e);
        }
    }

    private static WorkflowRun runOf(AgentResult result) {
        Object run = result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run).as("AgentResult.attributes() 里应当有 %s", WorkflowLoop.RUN_ATTRIBUTE)
                .isInstanceOf(WorkflowRun.class);
        return (WorkflowRun) run;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variablesOf(AgentResult result) {
        Object variables = result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);
        assertThat(variables).isInstanceOf(Map.class);
        return (Map<String, Object>) variables;
    }

    private ScriptedLlmModel scripted() {
        return (ScriptedLlmModel) mockLlmModel.unwrap();
    }

    private static String lastUserText(com.benxin.llm.core.chat.ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == com.benxin.llm.core.message.Role.USER) {
                return messages.get(i).text();
            }
        }
        return "";
    }
}
