package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 子代理装配方式①：{@code @LlmAgent(subAgents = {...})} + 内置 {@code task} 工具。
 *
 * <p>这是"声明式"的那条路：注解里点名能派生的子代理，框架会用
 * {@code LazyAgent} 把每个子代理包一层惰性壳再交给父 Agent
 * （{@code LlmAgentFactory.create} 里的那段 {@code builder.subAgent(subAgent, new LazyAgent(...))}）。
 * 惰性壳是为了兜住 <b>A ⇄ B 互相派生</b>：真要在启动期就把 A 建好、再建 B 时又要 A，
 * 会直接死在循环依赖上。</p>
 *
 * <p>{@code task} 工具来自内置工具集（{@code llm.tools.builtin-enabled = true}），
 * 它只回传子代理的结论摘要，不回传子代理的完整对话 —— 这正是"子代理把脏活留在自己上下文里"的兑现。</p>
 *
 * <p>{@link #ask} 的第二个参数演示 {@code @Ctx}：它<b>不进消息体</b>，只渲染给系统提示词、
 * 并按注解里的名字写进本次运行的 attributes。这里用的名字恰好是
 * {@code AgentAttributes.DEFAULT_SUB_AGENT}（{@code "benxin.default-subagent"}），
 * 于是 {@code task} 工具在参数里没给 {@code subagent_type} 时能拿它当默认值。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.SUB_AGENT, loop = "claude-code", memory = false, maxSteps = 8,
        subAgents = {AgentNames.CHILD},
        toolNames = {"task"},
        systemPrompt = "你是父代理（" + OfflineModelConfiguration.PARENT_MARKER
                + "）：遇到需要独立调研的子任务就派生 [" + AgentNames.CHILD + "]，不要自己查。"
                + "默认子代理 = {defaultSubAgent}。")
public interface ParentTaskAgent {

    AgentResult ask(@User String question,
               @Ctx("benxin.default-subagent") String defaultSubAgent);
}
