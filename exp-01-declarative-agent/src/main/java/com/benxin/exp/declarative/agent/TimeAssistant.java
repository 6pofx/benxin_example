package com.benxin.exp.declarative.agent;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code toolNames = {...}} 的示范：按"工具名"而不是"类"来挑选工具。
 *
 * <p>两种写法的差别很实际：{@code tools} 按来源类整包拿（适合"这一组工具都属于这个 Agent"），
 * {@code toolNames} 按名字精确拿（适合"我只要其中两个"）。两者可以同时写，结果是并集。</p>
 *
 * <p>注意这里的 {@code toolNames} 指向 {@link CommonTools} 里定义的工具 ——
 * 名字必须与 {@code @LlmTool(name = ...)} 完全一致，写错时框架会打 WARN 而不是抛异常，
 * 于是"工具悄悄没生效"是这类配置最常见的坑。</p>
 */
@LlmAgent(
        name = "timeAssistant",
        model = "mock",
        loop = "dsh-minimal",
        toolNames = {"get_current_time", "calculate"},
        systemPrompt = "你是时间与算术助手。",
        maxSteps = 4,
        // 显式关掉记忆：本示例只演示"一次问答"，不需要跨会话历史
        memory = false)
public interface TimeAssistant {

    String ask(@User String question);
}
