package com.benxin.exp.context.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.User;

/**
 * claude-code 循环示例 Agent：用来演示"压缩发生在 Loop 层"。
 *
 * <p>{@code ClaudeCodeLoop} 自带一个 {@code ContextManager} 做不到的能力：
 * 它会在运行过程中<b>就地改写历史</b>（把较早的工具结果替换成占位摘要），
 * 于是长任务的历史体积真的会收敛，而不只是"这一次下发的副本变小了"。</p>
 *
 * <p>演示所需的三个条件都在这里定死：</p>
 * <ul>
 *   <li>{@code loop = "claude-code"} —— 压缩逻辑在这个 Loop 里；</li>
 *   <li>{@code model = "tiny"}（窗口 6000）—— 它的触发线是 {@code 0.7 × 6000 = 4200} token；</li>
 *   <li>{@code maxSteps = 14} —— 默认每 12 步检查一次，14 步刚好跨过一次压缩检查，
 *       同时把历史撑到几万 token，压缩必然发生。</li>
 * </ul>
 */
@LlmAgent(
        name = "claudeCodeAssistant",
        model = "tiny",
        loop = "claude-code",
        // 系统提示词非空时，claude-code 不会再注入它那段很长的内置兜底提示词
        systemPrompt = "你是本心的 claude-code 循环示例助手；需要资料时先调用工具。",
        maxSteps = 14,
        memory = true)
public interface ClaudeCodeAssistant {

    /**
     * 输入里带 {@code #tool:read_document {}} 时，离线脚本模型每一轮都会请求同一个工具，
     * 于是可以稳定地跑满 {@code maxSteps} 步、把历史撑过压缩触发线。
     */
    String run(@Memory String sessionId, @User String question);
}
