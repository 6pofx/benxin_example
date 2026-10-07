package com.benxin.exp.tools.agent;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 工具来源 ①：{@code tools = {类}} —— 按<b>来源类整包</b>拿。
 *
 * <p>"整包"是字面意思：{@code CommonTools} 里有 4 个 {@code @LlmTool} 方法，
 * 这个 Agent 就拿到 4 个，一个不多一个不少。它适合"工具天然按模块分组"的场景。</p>
 *
 * <p>反面同样重要：<b>没被列进来的工具，这个 Agent 根本看不见</b> ——
 * 模型不会收到它的 schema，也就无从调用它。这不是"调用时被拒绝"，
 * 而是"列表里没有"。</p>
 */
@LlmAgent(
        name = "byClassAssistant",
        tools = {CommonTools.class},
        systemPrompt = "你是按来源类取工具的助手，只使用被授予的工具。")
public interface ByClassAssistant {

    String ask(@User String question);
}
