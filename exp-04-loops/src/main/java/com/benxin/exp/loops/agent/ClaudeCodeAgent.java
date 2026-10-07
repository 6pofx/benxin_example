package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code claude-code} 的正常路径 Agent：待办管理 + 内置只读工具。
 *
 * <p>不给 {@code tools} / {@code toolNames} 时继承**容器全局工具表**，
 * 于是它同时拿到内置的 {@code read / glob / grep / todo_write / task} 与 exp-common 的四个工具。
 * claude-code 的待办提醒只按工具名判断（{@code PromptTemplates.hasTodoTool}），
 * 因此"工具集里有没有 todo_write"决定它会不会催模型建清单 —— 这里给上，便于演示。</p>
 *
 * <p><b>它为什么是本模块唯一一个换了模型的 Agent：</b>{@code todo_write} 的
 * {@code todos} 参数是"对象数组"，而离线脚本模型的 {@code #tool:} 语法抠不出嵌套 JSON
 * （正则惰性到第一个 {@code }} 就收手），于是它永远回"参数 todos 必填"，
 * {@code benxin.todos} 也永远写不进去。这里换成
 * {@code OfflineModelConfiguration.TODO_AWARE_BEAN}（{@code mock} + 一个只补
 * {@code todo_write} 参数的装饰器），让这条链路能真的跑通。
 * 详见 {@code TodoAwareModel} 的类注释。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.TODO_AWARE_BEAN,
        loop = "claude-code", memory = false, maxSteps = 12,
        systemPrompt = "你是一个按 Claude Code 方式工作的工程助手：先建待办清单，再动手。")
public interface ClaudeCodeAgent {

    AgentResult ask(@User String question);
}
