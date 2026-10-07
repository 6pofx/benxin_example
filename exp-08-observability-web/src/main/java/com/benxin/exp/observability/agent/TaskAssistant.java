package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 子代理：被 {@code ObservabilityAssistant} 通过自定义 Loop 里的 {@code ctx.spawnSubAgent(...)} 派生。
 *
 * <p>它自己也是一个普通的声明式 Agent —— 这正是本心"子代理就是另一个 Agent"的口径：
 * 没有第二套 API，父代理只是按名字拿到它并调用一次。</p>
 *
 * <p>工具表被显式收紧为 {@code toolNames = {"get_current_time"}}，用来对照
 * "什么都不写就拿全部工具"那条口径：<b>想收紧必须显式列出</b>。</p>
 */
@LlmAgent(
        name = "task",
        model = "mock",
        loop = "dsh-minimal",
        toolNames = {"get_current_time"},
        systemPrompt = "你是执行具体子任务的代理，回答要短。",
        maxSteps = 2,
        memory = false)
public interface TaskAssistant {

    String run(String task);
}
