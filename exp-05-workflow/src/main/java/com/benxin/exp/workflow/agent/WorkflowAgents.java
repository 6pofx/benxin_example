package com.benxin.exp.workflow.agent;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.workflow.tool.FlakyTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.agent.AgentResult;

/**
 * 声明式工作流的四个入口：<b>同一套 {@code @LlmAgent} 声明式写法，换一个 {@code loop} 就换一种编排</b>。
 *
 * <p>注意这里出现了两种 loop 名：</p>
 * <ul>
 *   <li>{@code workflow:triage} / {@code workflow:flaky-tools} —— 装配期由
 *       {@code llm.workflows.<key>.location} 注册出来的"带默认定义的工作流 Loop"。
 *       改了 YAML 就改了控制流，Java 一行不用动。</li>
 *   <li>{@code workflow}（光秃秃的名字）—— 没有绑定任何定义，<b>每次调用都得把定义传进来</b>。
 *       {@code WorkflowDemo.runtimeDefinition(...)} 演示这条路径。</li>
 * </ul>
 *
 * <p>还刻意挑了两种返回类型：{@link TriageAgent} 返回 {@code String}（只要答案），
 * 其余返回 {@link AgentResult}（要跑完之后的轨迹与属性）。工作流的价值有一半在可观测性上，
 * 而 {@code AgentResult.attributes()} 里就带着 {@code benxin.workflow.run}
 * 与 {@code benxin.workflow.variables} 两份跑完之后的证据。</p>
 */
public final class WorkflowAgents {

    private WorkflowAgents() {
    }

    /**
     * 主演示：工单分诊。loop 指向配置里 {@code llm.workflows.triage} 注册出的工作流。
     *
     * <p>{@code tools = {CommonTools.class}} 不是为了给模型用 —— 工作流里的 {@code tool} 节点
     * 会<b>直接</b>调 {@code get_current_time}（不经过模型）。工具必须装配进 Agent，
     * 因为节点执行时的工具表来自这次运行的上下文。</p>
     *
     * <p>额外带上 {@link FlakyTools} 是为了它的 {@code next_attempt}：triage.yml 的回边
     * 循环用它做"回炉次数 +1"。{@code set} 的 {@code ${}} 只做插值不做算术，
     * 所以"加一"这件事只能由工具来做。</p>
     */
    @LlmAgent(
            name = "triageAgent",
            model = "mock",
            loop = "workflow:triage",
            tools = {CommonTools.class, FlakyTools.class},
            systemPrompt = "你是工单分诊台。",
            maxSteps = 24)
    public interface TriageAgent {

        /** 工单原文进、分诊单出。 */
        String triage(@User String ticket);
    }

    /**
     * 装配路径②（{@code llm.workflows.inline-demo.inline}）的入口：loop 指向
     * 那段写在 {@code application.yml} 里的内联定义 —— 一个 YAML 文件都没有。
     */
    @LlmAgent(
            name = "inlineAgent",
            model = "mock",
            loop = "workflow:inline-demo",
            maxSteps = 8)
    public interface InlineAgent {

        /** 跑一遍内联注册出来的工作流。 */
        AgentResult runInline(@User String task);
    }

    /**
     * 回边循环：{@code branch ↔ set/tool} 互指，转 3 圈后条件退出。
     *
     * <p>这张图里一个 agent 节点都没有，所以它同时是"步数预算拦不住这类环"的证人 ——
     * 能拦住它的是 {@code maxVisitsPerNode}。</p>
     */
    @LlmAgent(
            name = "loopAgent",
            model = "mock",
            loop = "workflow:loop-workflow",
            tools = {FlakyTools.class},
            maxSteps = 16)
    public interface LoopAgent {

        /** 跑一圈回边循环，返回"转了几圈、轨迹长什么样"。 */
        AgentResult runLoop(@User String task);
    }

    /**
     * 节点级容错：{@code retry} 重试成功后继续，{@code continueOnError} 让失败节点不拖垮整张图。
     */
    @LlmAgent(
            name = "flakyAgent",
            model = "mock",
            loop = "workflow:flaky-tools",
            tools = {CommonTools.class, FlakyTools.class},
            maxSteps = 16)
    public interface FlakyAgent {

        /** 跑一遍容错演示图。 */
        AgentResult withRetry(@User String task);
    }

    /**
     * 步数预算：{@code maxSteps = 2}，而图里有四个串联的 agent 节点 —— 必然中途耗尽。
     *
     * <p>断言口径与其它 Loop 一致：{@code AgentResult.maxStepsReached() == true}，
     * 同时 {@code WorkflowRun.truncated() == true}。</p>
     */
    @LlmAgent(
            name = "budgetAgent",
            model = "mock",
            loop = "workflow:budget-probe",
            maxSteps = 2)
    public interface BudgetAgent {

        /** 跑一张预算必然不够的图，看框架会不会如实标记"半成品"。 */
        AgentResult strained(@User String task);
    }

    /**
     * 第三条装配路径（逐次传入定义）走的是<b>裸 {@code Agent} API</b>，不需要一个声明式接口。
     *
     * <p>{@code agent.call(input, sessionId, attributes, stream)} 才是"带运行时属性"的那条路；
     * 声明式接口的方法签名里没有 attributes 的位置，所以那一层故意不做这件事。
     * 见 {@code WorkflowDemo.dynamicDefinition(...)}。</p>
     */

    /**
     * {@code staged} 路线：规划 → 执行 → 验收 → 修复 → 汇总。
     *
     * <p>与 workflow 的分工是"图定不下来时用它"：任务形态多变，但希望稳定拿到
     * "先做后验"的收益。验收闸门由 {@code ScriptedLlmModel.verifyPass(...)} 脚本化控制。</p>
     */
    @LlmAgent(
            name = "stagedAgent",
            model = "mock",
            loop = "staged",
            systemPrompt = "你是调研分析师。",
            maxSteps = 24)
    public interface StagedAgent {

        /** 跑完整的 staged 流程。 */
        AgentResult run(@User String task);
    }

    /**
     * 发布说明的"声明式"写法：与 {@link StagedAgent} 跑同一个任务，
     * 一个用固定的 release-notes 图，一个用 staged 阶段机 —— README 里的对照表就来自这一对。
     */
    @LlmAgent(
            name = "releaseNoteAgent",
            model = "mock",
            loop = "workflow:triage",
            tools = {CommonTools.class},
            systemPrompt = "你是发布经理。",
            maxSteps = 24)
    public interface ReleaseNoteAgent {

        /** 同一个任务，两条路线各跑一遍。 */
        String draft(@User String change);
    }
}
