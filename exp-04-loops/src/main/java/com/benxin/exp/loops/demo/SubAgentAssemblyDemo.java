package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.ChildWeatherAgent;
import com.benxin.exp.loops.agent.ParentDelegateAgent;
import com.benxin.exp.loops.agent.ParentNoSubAgent;
import com.benxin.exp.loops.config.SubAgentAuditListener;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 子代理的另外两种装配方式（第 ① 种在 {@link ClaudeCodeLoopDemo} 里已经跑过）。
 *
 * <table border="1">
 *   <caption>三种装配方式</caption>
 *   <tr><th>#</th><th>写法</th><th>本模块的 Agent</th></tr>
 *   <tr><td>①</td><td>{@code @LlmAgent(subAgents = {...})} + 内置 {@code task} 工具</td>
 *       <td>{@code parentTaskAgent}（见 ClaudeCodeLoopDemo）</td></tr>
 *   <tr><td>②</td><td>程序化 {@code AgentBuilder.subAgent(name, agent)}</td>
 *       <td>本节现场装配的 {@code exp-programmatic-parent}</td></tr>
 *   <tr><td>③</td><td>{@code ToolContext.spawn(...)} / {@code LoopContext.spawnSubAgent(...)}</td>
 *       <td>{@code parentDelegateAgent}（工具里派生）</td></tr>
 * </table>
 *
 * <p>还有一条隐性但重要的：<b>A ⇄ B 的环状派生由惰性壳兜住</b>。
 * 声明式路径给每个子代理包的是 {@code LazyAgent}（starter 里的包级私有类），
 * 于是"建 A 需要 B、建 B 需要 A"不会死在启动期。本节用
 * "解析父代理之后 / 真实派生之后 {@code AgentRegistry.resolved()} 的差"
 * 把这个惰性过程观察出来 —— 这比打印一行类名更有说服力。</p>
 */
@Component
public class SubAgentAssemblyDemo {

    private static final String PROGRAMMATIC_PARENT = "exp-programmatic-parent";

    private final ParentDelegateAgent parentDelegateAgent;
    private final ParentNoSubAgent parentNoSubAgent;
    private final SubAgentProbeTools probeTools;
    private final SubAgentAuditListener auditListener;
    private final AgentRegistry agentRegistry;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmToolCatalog toolCatalog;
    private final ToolSandbox sandbox;
    private final ApprovalHandler approvalHandler;
    private final SystemPromptProvider systemPromptProvider;

    public SubAgentAssemblyDemo(ParentDelegateAgent parentDelegateAgent,
                                ParentNoSubAgent parentNoSubAgent,
                                ChildWeatherAgent childWeatherAgent,
                                SubAgentProbeTools probeTools,
                                SubAgentAuditListener auditListener,
                                AgentRegistry agentRegistry,
                                ModelRegistry modelRegistry,
                                LoopRegistry loopRegistry,
                                LlmToolCatalog toolCatalog,
                                ToolSandbox sandbox,
                                ApprovalHandler approvalHandler,
                                SystemPromptProvider systemPromptProvider) {
        this.parentDelegateAgent = parentDelegateAgent;
        this.parentNoSubAgent = parentNoSubAgent;
        this.probeTools = probeTools;
        this.auditListener = auditListener;
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.toolCatalog = toolCatalog;
        this.sandbox = sandbox;
        this.approvalHandler = approvalHandler;
        this.systemPromptProvider = systemPromptProvider;
    }

    void run(Demo demo) {
        demo.section("子代理装配方式②：程序化 AgentBuilder.subAgent(name, agent)");
        programmaticAssembly(demo);
        demo.section("子代理装配方式③：ToolContext.spawn（在自己的工具里派生）");
        spawnFromTool(demo);
        demo.section("惰性解析：子代理是「用的时候才建」的");
        lazyResolution(demo);
    }

    /**
     * ② 程序化装配：父与子都是普通 Java 对象，不进容器。
     *
     * <p>注意 {@code loopRegistry} 必须显式给出：{@code AgentBuilder} 的默认值是 null，
     * 给了 {@code loop("react")} 却不给注册表的话，{@code DefaultAgent} 构造时会
     * 直接 NPE（{@code this.loopRegistry.get(b.loopName)}）。这是"程序化装配"
     * 相对声明式路径要自己担的那部分责任。</p>
     */
    private void programmaticAssembly(Demo demo) {
        Agent child = agentRegistry.get(AgentNames.CHILD);

        Agent parent = Agent.builder(PROGRAMMATIC_PARENT)
                .spec(AgentSpec.builder()
                        .name(PROGRAMMATIC_PARENT)
                        .loop("react")
                        .maxSteps(4)
                        .memory(false)
                        .systemPrompt("程序化装配的父代理：接到请求就把子任务交给 [" + AgentNames.CHILD + "]。")
                        .build())
                .modelRegistry(modelRegistry)
                .loopRegistry(loopRegistry)
                .toolRegistry(ToolRegistry.of(toolCatalog.forNames(new String[]{"task"}, PROGRAMMATIC_PARENT).all()))
                .sandbox(sandbox)
                .approvalHandler(approvalHandler)
                .systemPromptProvider(systemPromptProvider)
                .subAgent(AgentNames.CHILD, child)
                .build();

        demo.info("装配结果：%s", parent);
        demo.ok("父的 Loop = %s，工具 = %s", parent.loop().name(), parent.tools().names());

        String input = "#tool:task {\"description\":\"查询北京天气\","
                + "\"prompt\":\"请告诉我北京今天的天气与气温。\",\"subagent_type\":\""
                + AgentNames.CHILD + "\"}";
        AgentResult result = parent.call(input, "exp04-programmatic-parent-session");
        LoopReport.result(demo, result);
        demo.ok("程序化装配的父代理也能派生：工具轨迹 = %s",
                LoopReport.describeToolCalls(result.toolCalls()));
        demo.info("这一步与 ① 的唯一差别是「子代理实例从哪来」："
                + "① 走 AgentRegistry + 惰性壳，② 直接持有引用。");
    }

    /** ③ 工具里派生：走 ToolContext.spawn。 */
    private void spawnFromTool(Demo demo) {
        probeTools.reset();
        auditListener.reset();
        String input = "#tool:delegate_task {\"instruction\":\"请告诉我北京今天的天气与气温。\","
                + "\"subagent_type\":\"" + AgentNames.CHILD + "\",\"trace\":\"exp04-trace\"}";
        AgentResult result = parentDelegateAgent.ask(input);

        LoopReport.result(demo, result);
        demo.ok("工具里派生成功：工具轨迹 = %s", LoopReport.describeToolCalls(result.toolCalls()));
        demo.ok("父侧送给子代理的属性 = %s", LoopReport.shortValue(probeTools.spawnLog()));
        demo.ok("Loop（exp-decorate）作为 SubAgentObserver 记到 %d 条事件 = %s",
                subAgentEventCount(result),
                LoopReport.shortValue(result.attributes().get("exp.decorate.subAgentEvents")));
        demo.ok("AgentListener 侧同时记到 %d 条事件（onSubAgentStart + onSubAgentEnd）",
                auditListener.events().size());
        demo.ok("父的 usage 含子代理消耗：合计 %d tokens", result.usage().totalTokens());

        demo.section("对照组：工具在、但 Agent 没有声明任何子代理");
        AgentResult denied = parentNoSubAgent.ask(input);
        demo.info("工具照样被调用，但 canSpawnSubAgent() = false，于是返回一条可读的工具错误：");
        denied.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 200)));
        demo.ok("没有抛异常炸链路，也没有假装成功：toolCalls 里那条是 error = %s",
                denied.toolCalls().stream().anyMatch(r -> r.error()));
    }

    /** 惰性解析：观察"子代理是什么时候被真正建出来的"。 */
    private void lazyResolution(Demo demo) {
        Set<String> names = agentRegistry.names();
        demo.ok("names() 里包含**已声明但可能还没构建**的子代理 [%s] = %s",
                AgentNames.CHILD, names.contains(AgentNames.CHILD));
        demo.info("names() = %d 个（含惰性条目），resolved() = %d 个（只看已构建的真身）",
                names.size(), agentRegistry.resolved().size());

        Agent parent = agentRegistry.get(AgentNames.PARENT_TASK);
        demo.ok("解析父代理成功：%s；此时子代理通常仍只是惰性条目", parent.name());

        AgentResult result = parentDelegateAgent.ask(
                "#tool:delegate_task {\"instruction\":\"请告诉我北京今天的天气与气温。\","
                        + "\"subagent_type\":\"" + AgentNames.CHILD + "\"}");
        demo.ok("真实派生之后 resolved() = %d 个，子代理已在其中 = %s",
                agentRegistry.resolved().size(),
                agentRegistry.resolved().containsKey(AgentNames.CHILD));
        demo.info("这就是惰性壳的全部作用：把「子代理实例什么时候建」推迟到第一次真正用它的时刻，"
                + "于是 A ⇄ B 这种环在启动期根本不会被触发。");
        demo.info("顺带：本次派生的 loopName = %s，usage 合计 %d tokens",
                result.loopName(), result.usage().totalTokens());
        demo.warn("口径提醒：惰性壳兜住的是「构建期的循环依赖」。如果它内部解析失败"
                + "（子代理名拼错、Agent 未注册），异常会沿 spawn 路径原样抛出 —— "
                + "能否变成一条可读的工具错误，取决于调用方有没有像 SubAgentProbeTools 那样包一层。");
    }

    private static int subAgentEventCount(AgentResult result) {
        Object raw = result.attributes().get("exp.decorate.subAgentEvents");
        return raw instanceof java.util.List<?> list ? list.size() : 0;
    }
}
