package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.config.SubAgentAuditListener;
import com.benxin.exp.loops.tool.FlakyTools;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即跑的演示：把 8 个内置 Loop + 自定义 Loop + 子代理 + 注册表 + 预算 + 属性挨个打一遍。
 *
 * <p>与 exp-01 的 {@code DeclarativeDemo} 一样，整段演示都包在
 * {@link Demo#guard(String, Runnable)} 里：某一条因为配置原因跑不起来时，
 * 打印原因而不是让整个演示崩在中间 —— 示例应用的职责是<b>展示</b>，
 * 真正需要强断言的场合请用测试（本模块配了 {@code LoopsTest}）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoopsDemo implements ApplicationRunner {

    private final DshMinimalLoopDemo dshMinimal;
    private final ReactLoopDemo react;
    private final ClaudeCodeLoopDemo claudeCode;
    private final CodexLoopDemo codex;
    private final PlanExecuteLoopDemo planExecute;
    private final ReflexionLoopDemo reflexion;
    private final StagedLoopDemo staged;
    private final WorkflowLoopDemo workflow;
    private final CustomLoopDemo customLoop;
    private final SubAgentAssemblyDemo subAgentAssembly;
    private final LoopRegistryDemo loopRegistryDemo;
    private final LoopBudgetDemo loopBudget;
    private final AgentAttributesDemo agentAttributes;

    private final AgentRegistry agentRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmAgentFactory agentFactory;
    private final List<ScriptedLlmModel> scriptedModels;
    private final CommonTools commonTools;
    private final FlakyTools flakyTools;
    private final SubAgentProbeTools probeTools;
    private final SubAgentAuditListener auditListener;

    // CHECKSTYLE:OFF —— 演示编排需要一次性注入全部演示器与观测点
    public LoopsDemo(DshMinimalLoopDemo dshMinimal,
                     ReactLoopDemo react,
                     ClaudeCodeLoopDemo claudeCode,
                     CodexLoopDemo codex,
                     PlanExecuteLoopDemo planExecute,
                     ReflexionLoopDemo reflexion,
                     StagedLoopDemo staged,
                     WorkflowLoopDemo workflow,
                     CustomLoopDemo customLoop,
                     SubAgentAssemblyDemo subAgentAssembly,
                     LoopRegistryDemo loopRegistryDemo,
                     LoopBudgetDemo loopBudget,
                     AgentAttributesDemo agentAttributes,
                     AgentRegistry agentRegistry,
                     LoopRegistry loopRegistry,
                     LlmAgentFactory agentFactory,
                     ObjectProvider<ScriptedLlmModel> models,
                     CommonTools commonTools,
                     FlakyTools flakyTools,
                     SubAgentProbeTools probeTools,
                     SubAgentAuditListener auditListener) {
        this.dshMinimal = dshMinimal;
        this.react = react;
        this.claudeCode = claudeCode;
        this.codex = codex;
        this.planExecute = planExecute;
        this.reflexion = reflexion;
        this.staged = staged;
        this.workflow = workflow;
        this.customLoop = customLoop;
        this.subAgentAssembly = subAgentAssembly;
        this.loopRegistryDemo = loopRegistryDemo;
        this.loopBudget = loopBudget;
        this.agentAttributes = agentAttributes;
        this.agentRegistry = agentRegistry;
        this.loopRegistry = loopRegistry;
        this.agentFactory = agentFactory;
        this.scriptedModels = models.orderedStream().toList();
        this.commonTools = commonTools;
        this.flakyTools = flakyTools;
        this.probeTools = probeTools;
        this.auditListener = auditListener;
    }
    // CHECKSTYLE:ON

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 04 · Agent Loop —— 8 个内置 Loop + 自定义 Loop + 子代理");
        demo.banner("脚本模型: %s", scriptedModels.stream().map(ScriptedLlmModel::name).toList());
        // 模型注册表里会多出 mock-todo-aware：它不是"第 11 个模型"，
        // 而是 mock 套了一层只补 todo_write 参数的装饰器（见 TodoAwareModel）。
        // 不解释一句的话，这行输出会让人以为模块里凭空多了一个模型。
        demo.info("模型清单里多出的 [%s] 不是第 11 个模型：它是 [%s] 套了一层只补 todo_write 参数的装饰器"
                + "（离线脚本模型的 #tool: 正则抠不出嵌套 JSON）。只有 claudeCodeAgent 用它。",
                OfflineModelConfiguration.TODO_AWARE_BEAN, OfflineModelConfiguration.GENERIC);
        demo.banner("Loop: %s", loopRegistry.names());
        demo.banner("默认 Loop: %s", loopRegistry.defaultName());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("内置 Loop 名单: %s", BuiltinLoops.NAMES);

        resetAll();

        demo.guard("① dsh-minimal", () -> dshMinimal.run(demo));
        demo.guard("② react", () -> react.run(demo));
        demo.guard("③ claude-code", () -> claudeCode.run(demo));
        demo.guard("④ codex", () -> codex.run(demo));
        demo.guard("⑤ plan-execute", () -> planExecute.run(demo));
        demo.guard("⑥ reflexion", () -> reflexion.run(demo));
        demo.guard("⑦ staged", () -> staged.run(demo));
        demo.guard("⑧ workflow（点名）", () -> workflow.run(demo));
        demo.guard("⑨ 自定义 Loop", () -> customLoop.run(demo));
        demo.guard("⑩ 子代理装配", () -> subAgentAssembly.run(demo));
        demo.guard("⑪ LoopRegistry", () -> loopRegistryDemo.run(demo));
        demo.guard("⑫ 步数预算", () -> loopBudget.run(demo));
        demo.guard("⑬ AgentAttributes", () -> agentAttributes.run(demo));

        printAssembly(demo);
        demo.done();
    }

    /** 每个演示开始前清一次：脚本模型计数、工具计数、监听器记账、以及全部 Agent 的会话历史。 */
    void resetAll() {
        scriptedModels.forEach(ScriptedLlmModel::reset);
        commonTools.reset();
        flakyTools.reset();
        probeTools.reset();
        auditListener.reset();
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是"惰性条目"（registerLazy），
        // resolved() 只列出已经被解析过的那些，直接用它遍历会一个都清不掉。
        //
        // 注意：声明式 Agent 默认开记忆，但不传 sessionId 时都落在 "<agentName>-default" 上；
        // 本模块的 Agent 大多显式写了 memory = false，这里只是兜底。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            agent.clearHistory(agent.name());
        }));
    }

    /** 演示里用到的装配视图（供人肉核对注解 → Spec 的翻译结果）。 */
    void printAssembly(Demo demo) {
        demo.info("LoopsApplication 的默认 Loop = %s", loopRegistry.defaultName());
        demo.info("agentFactory 已装配 %d 个 Agent", agentRegistry.names().size());
        demo.info("AgentNames 常量与容器里的一致 = %s",
                agentRegistry.names().containsAll(List.of(
                        AgentNames.GENERIC, AgentNames.CLAUDE_CODE, AgentNames.CODEX,
                        AgentNames.PLAN_EXECUTE, AgentNames.REFLEXION,
                        AgentNames.PARENT_TASK, AgentNames.CHILD)));
        demo.info("agentFactory = %s", agentFactory.getClass().getSimpleName());
    }
}
