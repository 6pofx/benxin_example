package com.benxin.exp.loops;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.BudgetProbeAgent;
import com.benxin.exp.loops.agent.ClaudeCodeAgent;
import com.benxin.exp.loops.agent.ClaudeCodeFlakyAgent;
import com.benxin.exp.loops.agent.CodexAgent;
import com.benxin.exp.loops.agent.CodexPatchAgent;
import com.benxin.exp.loops.agent.GenericAgent;
import com.benxin.exp.loops.agent.ParentDelegateAgent;
import com.benxin.exp.loops.agent.ParentNoSubAgent;
import com.benxin.exp.loops.agent.ParentTaskAgent;
import com.benxin.exp.loops.agent.PlanExecuteAgent;
import com.benxin.exp.loops.agent.ReactNativeAgent;
import com.benxin.exp.loops.agent.ReactTextAgent;
import com.benxin.exp.loops.agent.ReflexionAgent;
import com.benxin.exp.loops.agent.StagedFailAgent;
import com.benxin.exp.loops.agent.StagedNoVerifyAgent;
import com.benxin.exp.loops.agent.StagedPassAgent;
import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.config.SubAgentAuditListener;
import com.benxin.exp.loops.config.TodoAwareModel;
import com.benxin.exp.loops.demo.ReactLoopDemo;
import com.benxin.exp.loops.loop.DecorateLoop;
import com.benxin.exp.loops.tool.CodexPatchTools;
import com.benxin.exp.loops.tool.FlakyTools;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.agent.AgentAttributes;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.ClaudeCodeLoop;
import com.benxin.llm.core.loop.CodexLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.loop.PlanExecuteLoop;
import com.benxin.llm.core.loop.ReflexionLoop;
import com.benxin.llm.core.loop.StagedLoop;
import com.benxin.llm.core.loop.SubAgentObserver;
import com.benxin.llm.core.tool.builtin.BuiltinToolkit;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 {@code LoopsDemo} 里展示的每一条行为都断言成可回归的用例。
 *
 * <p>全部离线：模型来自 {@code exp-common} 的 {@link ScriptedLlmModel}，
 * 因此这个测试类既不联网也不消耗 token，却覆盖了
 * "注解 → 装配 → Loop 阶段机 → 模型 → 工具 → 结果属性"的完整链路。</p>
 */
@SpringBootTest
@DisplayName("示例 04 · Agent Loop")
class LoopsTest {

    @Autowired
    private GenericAgent genericAgent;
    @Autowired
    private ReactTextAgent reactTextAgent;
    @Autowired
    private ReactNativeAgent reactNativeAgent;
    @Autowired
    private ClaudeCodeAgent claudeCodeAgent;
    @Autowired
    private ClaudeCodeFlakyAgent claudeCodeFlakyAgent;
    @Autowired
    private CodexAgent codexAgent;
    @Autowired
    private CodexPatchAgent codexPatchAgent;
    @Autowired
    private PlanExecuteAgent planExecuteAgent;
    @Autowired
    private ReflexionAgent reflexionAgent;
    @Autowired
    private StagedPassAgent stagedPassAgent;
    @Autowired
    private StagedFailAgent stagedFailAgent;
    @Autowired
    private StagedNoVerifyAgent stagedNoVerifyAgent;
    @Autowired
    private ParentTaskAgent parentTaskAgent;
    @Autowired
    private ParentDelegateAgent parentDelegateAgent;
    @Autowired
    private ParentNoSubAgent parentNoSubAgent;
    @Autowired
    private BudgetProbeAgent budgetProbeAgent;

    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private LoopRegistry loopRegistry;
    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private CommonTools commonTools;
    @Autowired
    private FlakyTools flakyTools;
    @Autowired
    private SubAgentProbeTools probeTools;
    @Autowired
    private SubAgentAuditListener auditListener;

    @Autowired
    @Qualifier(OfflineModelConfiguration.REACT_TEXT)
    private ScriptedLlmModel reactTextModel;
    @Autowired
    private TodoAwareModel todoAwareModel;

    @BeforeEach
    void reset() {
        commonTools.reset();
        flakyTools.reset();
        probeTools.reset();
        auditListener.reset();
        reactTextModel.reset();
        todoAwareModel.resetRepairCount();

        // 清历史。必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出已被解析过的那些，用它遍历一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            agent.clearHistory(agent.name());
        }));
    }

    // ------------------------------------------------------------------
    // ① dsh-minimal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dsh-minimal：最小骨架把「模型 → 工具 → 回灌 → 收尾」跑成一条闭环")
    void dshMinimalRunsToolLoop() {
        AgentResult result = budgetProbeAgent.ask("请查一下北京的天气。");

        // budgetProbeAgent 显式写了 loop = "dsh-minimal"
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.usage().totalTokens()).isPositive();
    }

    // ------------------------------------------------------------------
    // ② react
    // ------------------------------------------------------------------

    @Test
    @DisplayName("react 原生路径：模型声明支持 function calling 时走结构化工具调用")
    void reactNativePath() {
        AgentResult result = reactNativeAgent.ask("北京天气怎么样？");

        assertThat(result.loopName()).isEqualTo("react");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(result.messages()).anyMatch(m -> m.role() == Role.TOOL);
    }

    @Test
    @DisplayName("react 降级路径：模型不支持 function calling 时注入 Action/Action Input 格式说明")
    void reactTextProtocolInjectsFormatSpec() {
        AgentResult result = reactTextAgent.ask("现在几点了？");

        ChatRequest request = reactTextModel.lastRequest();
        assertThat(ReactLoopDemo.hasProtocolPrimer(request))
                .as("模型必须收到 Action / Action Input / Final Answer 的格式说明")
                .isTrue();

        // 说明的注入位置是**新追加的一条 user 消息**，不是系统提示词 ——
        // 系统提示词在 Agent 装配时就已经渲染完成，Loop 改不动它。
        // 断言原文而不是"包含某几个词"：格式说明是可读契约，改字就是改契约。
        String primer = result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().contains("Action Input:"))
                .findFirst()
                .map(m -> m.text())
                .orElseThrow(() -> new AssertionError("历史里没有文本协议的格式说明"));
        assertThat(primer)
                .contains("Thought: <你的推理>")
                .contains("Action: <工具名，本行只写工具名，不要带参数>")
                .contains("Action Input: <该工具参数的 JSON")
                .contains("Final Answer: <最终答案>")
                .contains("可用工具");

        // 最终答案是 Final Answer 那一行的内容（不含 Thought: 前缀）
        assertThat(result.text()).contains("文本协议收尾");
        assertThat(result.text()).doesNotContain("Action:").doesNotContain("Thought:");

        // 工具结果以 Observation: 回灌 —— 这一次（只有 1 次模型往返）还没来得及产生 Observation，
        // 所以这里断言的是"脚本模型确实按文本协议在演"，而不是历史里一定有条 Observation。
        // 实测：本次模型调用 1 次就直接给了 Final Answer（脚本模型看到 "现在几点了" 即命中
        // textProtocol 的第二步），因此 toolCalls 为空、历史里没有 role=tool 的消息。
        assertThat(reactTextModel.capabilities().toolCalling()).isFalse();
        assertThat(result.loopName()).isEqualTo("react");
        assertThat(result.toolCalls()).as("降级路径下工具调用不由模型结构化表达").isEmpty();
    }

    // ------------------------------------------------------------------
    // ③ claude-code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claude-code：首轮注入环境，todo_write 把清单写进 benxin.todos")
    void claudeCodeInjectsEnvironmentAndTodos() {
        String input = OfflineModelConfiguration.TODO_WRITE_INPUT;
        AgentResult result = claudeCodeAgent.ask(input);

        assertThat(result.loopName()).isEqualTo("claude-code");
        assertThat(result.attributes().get("benxin.claude-code.initialized")).isEqualTo(Boolean.TRUE);
        assertThat(result.attributes().get("benxin.claude-code.cwd")).isNotNull();

        assertThat(result.toolCalls()).anyMatch(r -> "todo_write".equals(r.name()) && !r.error());
        // 清单真的进了运行属性，而不是"工具被调用了就算数"
        assertThat(result.attributes()).containsKey(AgentAttributes.TODOS);
        assertThat(result.attributes().get(AgentAttributes.TODOS)).isInstanceOf(List.class);
        assertThat((List<?>) result.attributes().get(AgentAttributes.TODOS)).hasSize(1);

        // 这条断言同时钉住适配层：todo_write 的参数是"对象数组"，而离线脚本模型的
        // #tool: 正则末尾那个 } 是普通字符、必须由它收尾，于是参数一定缺一层括号。
        // 没有 TodoAwareModel 补这一层，上面那条 containsKey(TODOS) 就会失败。
        assertThat(todoAwareModel.repairedCalls())
                .as("参数确实被适配层补过 —— 否则 todo_write 只会回「参数 todos 必填」")
                .isPositive();
        assertThat(todoAwareModel.delegateName()).isEqualTo(OfflineModelConfiguration.GENERIC);
    }

    @Test
    @DisplayName("claude-code：连续工具错误注入「停下来重新评估」的提醒，而不是停机")
    void claudeCodeErrorCircuitBreaker() {
        AgentResult result = claudeCodeFlakyAgent.ask("#tool:flaky_query {\"keyword\":\"连续失败\"}");

        assertThat(flakyTools.failures()).isGreaterThanOrEqualTo(2);
        assertThat(result.messages())
                .as("历史里必须出现熔断提醒")
                .anyMatch(m -> m.text() != null && m.text().contains("停下来重新评估"));
        // 提醒而不是停机：运行没被中断，仍返回一个正常的 AgentResult
        assertThat(result.loopName()).isEqualTo("claude-code");
        assertThat(result.attributes().get("benxin.claude-code.toolErrors")).isNotNull();
    }

    // ------------------------------------------------------------------
    // ④ codex
    // ------------------------------------------------------------------

    @Test
    @DisplayName("codex：长任务走「计划 → 执行 → 验证」，并如实记下权限边界")
    void codexPlanAndVerificationNote() {
        AgentResult result = codexAgent.ask(
                "请检查这个模块的配置是否合理，并给出结论；注意这是一个超过八十个字符的长任务描述，"
                        + "因此 codex 应当先出一份编号计划再动手执行。");

        assertThat(result.loopName()).isEqualTo("codex");
        assertThat(result.attributes()).containsKey("benxin.codex.initialized");
        assertThat(result.attributes().get("benxin.codex.sandboxWriteEnabled")).isEqualTo(Boolean.FALSE);
        assertThat(result.attributes().get("benxin.codex.execEnabled")).isEqualTo(Boolean.FALSE);
        assertThat(result.attributes().get("benxin.codex.verified")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.attributes().get("benxin.codex.verificationNote")))
                .contains("没有命令类工具");
    }

    @Test
    @DisplayName("codex apply_patch：模型把补丁当工具参数交出去，工具解析并真正落盘")
    void codexAppliesPatch() throws Exception {
        var target = OfflineModelConfiguration.patchDemoTarget();
        Files.deleteIfExists(target);

        AgentResult result = codexPatchAgent.ask(
                "请在工作目录下新建一个演示文件，用它说明 codex 的补丁式改动是怎么落地的。");

        // 补丁必须是**工具调用**：CodexLoop 只有 executeTools 这一条执行路径，
        // 不会去解析回复正文里的补丁。只吐文本的话文件不会被创建，
        // 而模型输出看起来完全正常 —— 这正是最初这条用例失败的原因。
        assertThat(result.toolCalls()).anyMatch(r -> "apply_patch".equals(r.name()) && !r.error());
        assertThat(Files.exists(target)).as("补丁必须真的被应用，而不是只当文本回灌").isTrue();
        assertThat(Files.readString(target)).contains("apply_patch");
        // 补丁原文同时留在历史里（真实模型也会"边说边动手"）
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().contains("*** Begin Patch"));

        // 格式说明是公共 API，示例与使用者都从它取
        assertThat(CodexPatchTools.describeFormat())
                .contains("*** Add File:")
                .contains("*** End Patch");
    }

    // ------------------------------------------------------------------
    // ⑤ plan-execute
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plan-execute：计划被解析成多步、逐步回显、最后追加一轮汇总")
    void planExecuteParsesPlanAndSynthesizes() {
        AgentResult result = planExecuteAgent.ask("请演示 plan-execute 的完整流程。");

        assertThat(result.loopName()).isEqualTo("plan-execute");
        Object plan = result.attributes().get(PlanExecuteLoop.PLAN_ATTRIBUTE);
        assertThat(plan).isInstanceOf(List.class);
        assertThat((List<?>) plan).hasSize(3);
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().startsWith("已完成 "));
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().startsWith("计划的全部步骤都已执行完毕"));
        assertThat(result.maxStepsReached()).isFalse();
    }

    // ------------------------------------------------------------------
    // ⑥ reflexion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reflexion：低于阈值 → 带批评重做 → 一直不达标就用完轮次（EXHAUSTED 键出现）")
    void reflexionRetriesWithCritique() {
        AgentResult result = reflexionAgent.ask("自评分 0.55，未达到 0.9：请写一份变更说明。");

        assertThat(result.loopName()).isEqualTo("exp-reflexion-strict");
        assertThat(result.attributes().get(ReflexionLoop.SCORE_ATTRIBUTE)).isEqualTo(0.85d);

        // 实测行为：脚本模型的 reflexion 应答是**固定分数**（reflexion(0.85, …)），
        // 所以每一轮自省都回 0.85，而 exp-reflexion-strict 的达标线是 0.9 ——
        // 永远够不着，于是跑满 3 轮后置 EXHAUSTED。这与原注释设想的
        // "先低于、后达标（EXHAUSTED 不存在）"不同：那条路需要模型第二轮给出 ≥0.9 的分数，
        // 而脚本模型只有一个 reflexion(score, critique) 配置项，做不到"两轮不同分"。
        assertThat(result.attributes().get(ReflexionLoop.ATTEMPTS_ATTRIBUTE)).isEqualTo(3);
        assertThat(result.attributes().get(ReflexionLoop.EXHAUSTED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);

        // 自省提示词与"带着批评重做"都真的进过历史
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().contains("严格按下面两行输出"));
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().contains("缺少影响范围与验证方式的说明"));
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().contains("低于达标线 0.9"));

        // 修订稿是最后一轮被采纳的答案（模型两轮给了不同答案，最终文本来自修订那一条规则）
        assertThat(result.text()).contains("修订稿").contains("验证方式");
    }

    // ------------------------------------------------------------------
    // ⑦ staged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("staged verifyPass(true)：一次通过，REPAIR_ROUNDS 键不存在")
    void stagedPassesOnFirstVerdict() {
        AgentResult result = stagedPassAgent.ask("请把这次接口改动整理成一份可交付的说明。");

        assertThat(result.loopName()).isEqualTo("staged");
        assertThat(result.attributes().get(StagedLoop.STAGE_ATTRIBUTE)).isEqualTo("synthesize");
        assertThat(result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE)).isEqualTo("pass");
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE);
        assertThat(result.attributes().get(StagedLoop.PLAN_ATTRIBUTE)).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("staged verifyPass(false)：验收不通过 → 修复 1 轮 → 最后一轮验收结论是 fail")
    void stagedRepairsAfterFailedVerdict() {
        AgentResult result = stagedFailAgent.ask("请写一份初版实现，并给出边界条件与下一步。");

        // 实测行为：VERDICT 记的是**最后一次**验收的结论，而这个脚本的验收应答是固定的
        // "结论：不通过"（ScriptedLlmModel 的 verifyPass=false 分支），所以修完之后再验收
        // 仍然是 fail。原注释设想的"修复后再次验收通过"需要模型第二轮给出 pass，
        // 而脚本模型只有一个 verifyPass 开关，做不到"两次不同结论"。
        // 因此"验收→修复→再验收"这条链路靠 repair-rounds 与阶段序列来证明，而不是靠 verdict。
        assertThat(result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE)).isEqualTo("fail");
        assertThat(result.attributes().get(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE)).isEqualTo(1);
        assertThat(result.attributes().get(StagedLoop.PLAN_ATTRIBUTE)).isInstanceOf(List.class);

        // 验收者给出的问题清单被原样带进修复阶段的提示词
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().startsWith("验收未通过"));
        // 而且"作为验收者"这个角色切换真的发生过（两次：初次验收 + 修复后复验）
        assertThat(result.messages().stream()
                .filter(m -> m.text() != null && m.text().contains("作为验收者"))
                .count())
                .as("验收请求被发出两次（初次 + 修复后复验）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("staged verifyEnabled=false：退化成带汇总的 plan-execute（不发验收请求）")
    void stagedWithoutVerifyGate() {
        AgentResult result = stagedNoVerifyAgent.ask("请把这次配置外部化的改动写清楚。");

        assertThat(result.loopName()).isEqualTo("exp-staged-no-verify");
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.VERDICT_ATTRIBUTE);
        assertThat(result.attributes()).doesNotContainKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE);
        assertThat(result.attributes().get(StagedLoop.STAGE_ATTRIBUTE)).isEqualTo("synthesize");
        assertThat(result.messages())
                .noneMatch(m -> m.text() != null && m.text().contains("作为验收者"));
    }

    // ------------------------------------------------------------------
    // ⑧ workflow（只点名）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("workflow：本模块只点名，由 exp-05 专门演示")
    void workflowIsMentionedOnly() {
        AgentLoop workflow = loopRegistry.get("workflow");

        assertThat(workflow.description()).contains("声明式工作流");
        assertThat(BuiltinLoops.NAMES).contains("workflow", "staged", "reflexion");
        assertThat(loopRegistry.names()).containsAll(BuiltinLoops.NAMES);
    }

    // ------------------------------------------------------------------
    // ⑨ 自定义 Loop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自定义 Loop：@LlmLoop 无需 @Component 即注册，且 LoopContext 能力全部生效")
    void customLoopIsRegisteredAndUsesContext() {
        AgentLoop loop = loopRegistry.get("exp-decorate");
        assertThat(loop).isInstanceOf(DecorateLoop.class);
        assertThat(loop).isInstanceOf(SubAgentObserver.class);

        AgentResult result = genericAgent.ask("北京天气怎么样？");

        assertThat(result.loopName()).isEqualTo("exp-decorate");
        assertThat(result.attributes().get(DecorateLoop.ATTR_RESTATED)).isNotNull();
        assertThat(String.valueOf(result.attributes().get(DecorateLoop.ATTR_DIRECT_TOOL))).isNotBlank();
        assertThat(result.attributes().get(DecorateLoop.ATTR_EXECUTION)).isEqualTo("manual-tool-round");
        assertThat(result.attributes().get(DecorateLoop.ATTR_BUDGET_EXHAUSTED)).isEqualTo(Boolean.FALSE);
        assertThat(result.toolCalls()).anyMatch(r -> "get_current_time".equals(r.name()));
        assertThat(result.messages())
                .anyMatch(m -> m.text() != null && m.text().startsWith("请先用一句话复述这个任务"));
    }

    @Test
    @DisplayName("默认 Loop：llm.agent.default-loop 优先于 @LlmLoop(defaultLoop = true)")
    void configuredDefaultLoopWins() {
        assertThat(loopRegistry.defaultName()).isEqualTo("exp-decorate");
        assertThat(loopRegistry.defaultLoop()).isInstanceOf(DecorateLoop.class);
        assertThat(agentRegistry.get(AgentNames.GENERIC).loop().name()).isEqualTo("exp-decorate");
    }

    // ------------------------------------------------------------------
    // ⑩ 子代理：三种装配方式
    // ------------------------------------------------------------------

    @Test
    @DisplayName("子代理①：注解声明 + task 工具，用量补记 + toolCalls 记录 + 观察点回调")
    void subAgentViaAnnotationAndTaskTool() {
        AgentResult result = parentTaskAgent.ask(
                "#tool:task {\"description\":\"查询北京天气\","
                        + "\"prompt\":\"请告诉我北京今天的天气与气温。\",\"subagent_type\":\""
                        + AgentNames.CHILD + "\"}",
                AgentNames.CHILD);

        assertThat(result.toolCalls()).anyMatch(r -> "task".equals(r.name()) && !r.error());
        assertThat(result.usage().totalTokens()).isPositive();

        // 父侧两处观察点：AgentListener（全局）与 SubAgentObserver（当前 Loop）。
        // 这一次跑的是 claude-code，它不实现 SubAgentObserver，所以只有全局那一处被回调。
        assertThat(auditListener.events()).hasSize(2);
        assertThat(auditListener.events().get(0)).containsEntry("phase", "start")
                .containsEntry("child", AgentNames.CHILD)
                .containsEntry("parent", AgentNames.PARENT_TASK);
        assertThat(auditListener.events().get(1)).containsEntry("phase", "end")
                .containsEntry("child", AgentNames.CHILD);

        // 父代理拿到子代理结论后收尾 —— 这是"派生真的发生过"最直接的证据
        assertThat(result.text()).contains(OfflineModelConfiguration.CHILD_ANSWER);
        assertThat(result.maxStepsReached()).as("父代理应当在派生后收尾，而不是烧完预算").isFalse();
    }

    @Test
    @DisplayName("子代理①：task 工具缺 subagent_type 时用 benxin.default-subagent 兜底")
    void taskToolFallsBackToDefaultSubAgent() {
        AgentResult result = parentTaskAgent.ask(
                "#tool:task {\"description\":\"读一次天气\",\"prompt\":\"请告诉我北京今天的天气。\"}",
                AgentNames.CHILD);

        assertThat(result.toolCalls()).anyMatch(r -> "task".equals(r.name()) && !r.error());
        // @Ctx("benxin.default-subagent") 的参数被写进了运行属性，task 工具从这里取默认子代理
        assertThat(result.attributes()).containsKey(AgentAttributes.DEFAULT_SUB_AGENT);
        assertThat(result.attributes().get(AgentAttributes.DEFAULT_SUB_AGENT)).isEqualTo(AgentNames.CHILD);
        assertThat(result.text()).contains(OfflineModelConfiguration.CHILD_ANSWER);
    }

    @Test
    @DisplayName("子代理③：ToolContext.spawn 走真实派生路径，SubAgentObserver 收到回调")
    void subAgentViaToolContextSpawn() {
        probeTools.reset();
        auditListener.reset();

        AgentResult result = parentDelegateAgent.ask(
                "#tool:delegate_task {\"instruction\":\"请告诉我北京今天的天气与气温。\","
                        + "\"subagent_type\":\"" + AgentNames.CHILD + "\",\"trace\":\"test-trace\"}");

        assertThat(result.toolCalls()).anyMatch(r -> "delegate_task".equals(r.name()) && !r.error());
        assertThat(probeTools.spawnLog()).hasSize(1);
        assertThat(probeTools.spawnLog().get(0)).containsEntry("probe.trace", "test-trace")
                .containsEntry("benxin.parent.agent", AgentNames.PARENT_DELEGATE);

        // Loop 侧（exp-decorate 实现了 SubAgentObserver）记到两条：start + end
        Object events = result.attributes().get(DecorateLoop.ATTR_SUBAGENT_EVENTS);
        assertThat(events).isInstanceOf(List.class);
        assertThat((List<?>) events).hasSize(2);
        List<?> loopEvents = (List<?>) events;
        assertThat(loopEvents.get(0)).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("phase", "start")
                .containsEntry("subAgent", AgentNames.CHILD);
        assertThat(loopEvents.get(1)).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("phase", "end")
                .containsEntry("subAgent", AgentNames.CHILD);

        // 注意：ATTR_SUBAGENT_STARTED 记的是"**最后一次**回调是不是 start"，
        // 派生跑完之后最后一条是 end，所以它是 false —— 原注释设想成"派生过就是 true"，
        // 那是把它当成了布尔累加器。真实语义见 DecorateLoop.record(...)。
        assertThat(result.attributes().get(DecorateLoop.ATTR_SUBAGENT_STARTED)).isEqualTo(Boolean.FALSE);

        // 全局监听器同样被回调（这是与 SubAgentObserver 并列的另一个观察点）
        assertThat(auditListener.events()).hasSize(2);
        assertThat(result.usage().totalTokens()).isPositive();
        assertThat(result.text()).contains(OfflineModelConfiguration.CHILD_ANSWER);
    }

    @Test
    @DisplayName("子代理③：没有声明 subAgents 时工具返回错误结果而不是抛异常")
    void spawnWithoutCapabilityReturnsToolError() {
        AgentResult result = parentNoSubAgent.ask(
                "#tool:delegate_task {\"instruction\":\"随便问一句。\","
                        + "\"subagent_type\":\"" + AgentNames.CHILD + "\"}");

        assertThat(result.toolCalls()).anyMatch(r -> "delegate_task".equals(r.name()) && r.error());
        assertThat(result.messages())
                .anyMatch(m -> m.role() == Role.TOOL && m.text().contains("未配置子代理能力"));
    }

    @Test
    @DisplayName("子代理：惰性条目在 names() 里，真实派生之后才出现在 resolved() 里")
    void lazySubAgentResolution() {
        assertThat(agentRegistry.names()).contains(AgentNames.CHILD);
        assertThat(agentRegistry.get(AgentNames.PARENT_TASK)).isNotNull();

        parentDelegateAgent.ask(
                "#tool:delegate_task {\"instruction\":\"请告诉我北京今天的天气与气温。\","
                        + "\"subagent_type\":\"" + AgentNames.CHILD + "\"}");

        assertThat(agentRegistry.resolved()).containsKey(AgentNames.CHILD);
    }

    // ------------------------------------------------------------------
    // ⑪ LoopRegistry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LoopRegistry：names/contains/find/get/descriptions/default* 全部可用")
    void loopRegistryReadApi() {
        assertThat(loopRegistry.names()).contains("dsh-minimal", "react", "claude-code", "codex",
                "plan-execute", "reflexion", "workflow", "staged", "exp-decorate");
        assertThat(loopRegistry.contains("react")).isTrue();
        assertThat(loopRegistry.contains("不存在的-loop")).isFalse();
        assertThat(loopRegistry.find("codex")).isPresent();
        assertThat(loopRegistry.find("不存在的-loop")).isEmpty();
        assertThat(loopRegistry.get("staged")).isInstanceOf(StagedLoop.class);
        assertThat(loopRegistry.descriptions()).anyMatch(line -> line.startsWith("exp-decorate ——"));
        assertThat(loopRegistry.isEmpty()).isFalse();
        assertThat(loopRegistry.defaultName()).isEqualTo("exp-decorate");
        assertThat(loopRegistry.defaultLoop()).isInstanceOf(DecorateLoop.class);
        assertThat(loopRegistry.get("codex")).isInstanceOf(CodexLoop.class);
        assertThat(loopRegistry.get("claude-code")).isInstanceOf(ClaudeCodeLoop.class);
    }

    @Test
    @DisplayName("LoopRegistry：register(...) 立刻可查，setDefault(...) 改变默认（用完恢复）")
    void loopRegistryWriteApi() {
        String name = "exp-test-ephemeral";
        loopRegistry.register(new TestLoop(name));
        try {
            assertThat(loopRegistry.contains(name)).isTrue();
            assertThat(loopRegistry.find(name)).isPresent();
            assertThat(loopRegistry.descriptions()).anyMatch(line -> line.startsWith(name + " ——"));

            String original = loopRegistry.defaultName();
            loopRegistry.setDefault(name);
            assertThat(loopRegistry.defaultName()).isEqualTo(name);
            assertThat(loopRegistry.defaultLoop().name()).isEqualTo(name);
            loopRegistry.setDefault(original);
            assertThat(loopRegistry.defaultName()).isEqualTo(original);
        } finally {
            // 注册表没有 unregister；把默认值恢复干净即可（演示与测试都只加不删）
            loopRegistry.setDefault("exp-decorate");
        }
    }

    @Test
    @DisplayName("BuiltinLoops：NAMES 九个名字（含代码编排 flow），DEFAULT_LOOP = dsh-minimal")
    void builtinLoopsConstants() {
        // 1.1.1 之后新增了第三种编排路线 flow（代码编排），排在 workflow 与 staged 之间；
        // 它的完整演示在 exp-14
        assertThat(BuiltinLoops.NAMES).containsExactly(
                "dsh-minimal", "react", "claude-code", "codex",
                "plan-execute", "reflexion", "workflow", "flow", "staged");
        assertThat(BuiltinLoops.DEFAULT_LOOP).isEqualTo("dsh-minimal");
        assertThat(loopRegistry.names()).containsAll(BuiltinLoops.NAMES);
        // 变体注册：同一个 Loop 类的不同构造参数，以新名字并存
        assertThat(loopRegistry.names()).contains("exp-reflexion-strict", "exp-staged-no-verify",
                "exp-staged-lenient", "exp-codex-unrestricted");
    }

    // ------------------------------------------------------------------
    // ⑫ 步数预算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("步数预算：注解写 100 被 hard-max-steps 夹到 12，且预算真的被用尽")
    void budgetIsClampedAndExhausted() {
        assertThat(agentFactory.specOf(BudgetProbeAgent.class).maxSteps())
                .as("specOf 看到的是注解值")
                .isEqualTo(100);
        assertThat(agentRegistry.get(AgentNames.BUDGET).spec().maxSteps())
                .as("装配后生效的是被夹取过的值")
                .isEqualTo(12);

        commonTools.reset();
        AgentResult result = budgetProbeAgent.ask("请查一下北京的天气。");

        // 这条路径需要一个"永远请求工具、从不给最终答案"的模型才走得通：
        // 普通的 ScriptedLlmModel 看到工具结果就会收尾（实测稳定在第 2 步 STOP），
        // 预算根本烧不完。所以 AgentNames.BUDGET 绑的是本模块的 BudgetProbeModel。
        assertThat(result.maxStepsReached()).isTrue();
        assertThat(result.steps()).isEqualTo(12);
        assertThat(commonTools.callCount()).isGreaterThan(1);
        assertThat(result.toolCalls()).hasSize(12);
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
    }

    // ------------------------------------------------------------------
    // ⑬ AgentAttributes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AgentAttributes：TODOS / PLAN 真的被写入，SYSTEM_PROMPT 则从来没有人写")
    void agentAttributesAreWritten() {
        // 实测：框架在 AgentResult.attributes() 里**从不写** benxin.systemPrompt。
        // 它把系统提示词放在 ChatRequest.systemText()（给模型看的那一份），
        // 而不是放进运行属性。这条与 SESSION_ID / AGENT_NAME 是同一类问题
        // （常量定义了、没有写入点），已一并记入 README 的「插件问题」。
        AgentResult basic = genericAgent.ask("你好");
        assertThat(basic.attributes()).doesNotContainKey(AgentAttributes.SYSTEM_PROMPT);
        // 但提示词确实被渲染并下发了 —— 用 Loop 自己读到的请求来证明，而不是看属性表
        assertThat(genericAgent.ask("你好").attributes())
                .as("自定义 Loop 的记账里有东西，说明提示词与请求都正常")
                .containsKey(DecorateLoop.ATTR_RESTATED);

        AgentResult todos = claudeCodeAgent.ask(OfflineModelConfiguration.TODO_WRITE_INPUT);
        assertThat(todos.attributes()).containsKey(AgentAttributes.TODOS);
        assertThat(todos.attributes().get(AgentAttributes.TODOS)).isInstanceOf(List.class);

        AgentResult planned = planExecuteAgent.ask("请演示计划属性是怎么写进去的。");
        assertThat(planned.attributes()).containsKey(AgentAttributes.PLAN);
    }

    @Test
    @DisplayName("AgentAttributes：1.1.1 起 SESSION_ID / AGENT_NAME 会被真的写进运行属性")
    void sessionAndAgentNameKeysAreWritten() {
        AgentResult result = genericAgent.ask("你好");

        // 1.1.0 时这两个键"在 AgentAttributes 里定义了、框架内却没有任何写入点"，
        // attributes() 里查不到它们（本工程上报为 FINDINGS 第 24 条）。
        // 1.1.1 起运行伊始就播种，于是运维/审计代码可以直接按常量取值。
        assertThat(result.attributes())
                .containsEntry(AgentAttributes.SESSION_ID, AgentNames.GENERIC + "-default");
        assertThat(result.attributes())
                .containsEntry(AgentAttributes.AGENT_NAME, AgentNames.GENERIC);

        // SYSTEM_PROMPT 仍然只在"方法级 @SystemPrompt"时才写（本 Agent 没有声明），所以这里没有它
        assertThat(result.attributes()).doesNotContainKey(AgentAttributes.SYSTEM_PROMPT);

        // AgentResult 自己的字段照旧是权威来源，两处口径一致
        assertThat(result.sessionId()).isEqualTo(AgentNames.GENERIC + "-default");
        assertThat(result.agentName()).isEqualTo(AgentNames.GENERIC);
    }

    // ------------------------------------------------------------------
    // 装配视图
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AgentRegistry：全部声明式 Agent 都在，且都能按名取到 Agent 实例")
    void allAgentsAreRegistered() {
        Set<String> names = agentRegistry.names();

        assertThat(names).contains(AgentNames.GENERIC, AgentNames.CLAUDE_CODE, AgentNames.CODEX,
                AgentNames.PLAN_EXECUTE, AgentNames.REFLEXION, AgentNames.STAGED_PASS,
                AgentNames.STAGED_FAIL, AgentNames.PARENT_TASK, AgentNames.PARENT_DELEGATE,
                AgentNames.PARENT_NO_SUB, AgentNames.BUDGET, AgentNames.CHILD,
                AgentNames.REACT_TEXT, AgentNames.REACT_NATIVE);
        assertThat(agentRegistry.get(AgentNames.CHILD).name()).isEqualTo(AgentNames.CHILD);
    }

    @Test
    @DisplayName("内置工具已打开：read/glob/grep/todo_write/task，且 write/edit/bash 不在未点名的那一侧")
    void builtinToolsAreAvailable() {
        var tools = agentRegistry.get(AgentNames.CLAUDE_CODE).tools().names();

        // 只读基线三件套 + 配置里点名的两个。
        // task 必须点名才有：BuiltinToolkit.READ_ONLY_TOOLS 只有 read/glob/grep，
        // task 与 todo_write 都靠 llm.tools.enabled 显式打开。
        assertThat(tools).contains("read", "glob", "grep", "todo_write", "task");
        // 按需求没有打开 bash（exec 关着）
        assertThat(tools).doesNotContain("bash");

        // 内置工具的名字与 BuiltinToolkit 的清单一致（不是示例自己编的名字）
        assertThat(BuiltinToolkit.ALL_TOOLS)
                .contains("read", "write", "edit", "glob", "grep", "bash", "todo_write", "task");
        assertThat(BuiltinToolkit.READ_ONLY_TOOLS)
                .as("默认基线只有只读三件套 —— 这解释了 task 为什么必须点名")
                .containsExactly("read", "glob", "grep");
    }

    @Test
    @DisplayName("沙箱写闸门一开，内置 write/edit 也会跟着进工具清单 —— 两者是同一个开关（实测）")
    void writeGateAndBuiltinWriteToolsShareOneSwitch() {
        // 实测口径（与"能不能只开闸门不放工具"这个直觉不符）：
        // BuiltinToolkit.activeToolNames() 在 effectiveConfig().allowWrite() 为真时
        // **无条件**把 write / edit 加进活跃集合（enabled 是"叠加"，不是"白名单"），
        // 因此本模块一旦为了让 apply_patch 能落盘而打开 llm.tools.allow-write，
        // Agent 的工具清单里就会同时出现内置的 write 与 edit。
        //
        // 想两者分开只能自己包一层 BuiltinToolkit.Builder.disable("write", "edit")，
        // 而 llm.tools.* 没有对应的配置项 —— 也就是说**纯配置做不到**。
        // 这是本模块为了跑通 apply_patch 演示所付的代价，已记入 README「容易猜错的口径」。
        var tools = agentRegistry.get(AgentNames.CLAUDE_CODE).tools().names();
        assertThat(tools).contains("write", "edit");
        // bash 仍然不在：exec 那条闸门关着
        assertThat(tools).doesNotContain("bash");
        // 模块自带的 apply_patch 也在，并且真的能落盘（见 codexAppliesPatch）
        assertThat(tools).contains("apply_patch");
    }

    @Test
    @DisplayName("注解 → AgentSpec：显式值生效，空缺项回落到 llm.agent.* 并被硬上限夹取")
    void specReflectsAnnotation() {
        var codexSpec = agentFactory.specOf(CodexAgent.class);
        assertThat(codexSpec.name()).isEqualTo(AgentNames.CODEX);
        assertThat(codexSpec.loop()).isEqualTo("codex");
        assertThat(codexSpec.maxSteps()).isEqualTo(12);
        assertThat(codexSpec.memory()).isFalse();

        var reactSpec = agentFactory.specOf(ReactTextAgent.class);
        assertThat(reactSpec.loop()).isEqualTo("react");
        assertThat(reactSpec.model()).isEqualTo(OfflineModelConfiguration.REACT_TEXT);

        // 子代理与预算这两条链路的模型换成了本模块自己的 LlmModel 实现，
        // 但**注解里的模型名没变** —— 换实现不该改变"这个 Agent 用哪个模型"这个声明。
        assertThat(agentFactory.specOf(ParentTaskAgent.class).model())
                .isEqualTo(OfflineModelConfiguration.SUB_AGENT);
        assertThat(agentFactory.specOf(BudgetProbeAgent.class).model())
                .isEqualTo(OfflineModelConfiguration.BUDGET);
    }

    /** 只为验证 {@code LoopRegistry.register} 而存在的最小 Loop。 */
    private static final class TestLoop implements AgentLoop {

        private final String loopName;

        private TestLoop(String loopName) {
            this.loopName = loopName;
        }

        @Override
        public String name() {
            return loopName;
        }

        @Override
        public String description() {
            return "测试用的临时 Loop";
        }

        @Override
        public LoopResult run(LoopContext context) {
            return LoopResult.of("（临时 Loop 不做任何事）");
        }
    }
}
