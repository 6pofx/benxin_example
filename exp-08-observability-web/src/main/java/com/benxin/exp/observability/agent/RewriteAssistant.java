package com.benxin.exp.observability.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code @LlmAgent(interceptors = {...})} 的示范：<b>只给这个 Agent</b>追加一个拦截器。
 *
 * <p>与 {@code @LlmGuard} 的分工：{@code @LlmGuard} 是"拦截器声明自己作用于哪些 Agent"，
 * 本属性是"Agent 声明自己要带哪些拦截器"。解析逻辑在
 * {@code LlmAgentFactory.annotationInterceptors(...)}：<b>优先从容器取同类型 bean，
 * 取不到再直接实例化</b> —— 这里刻意用 {@code RewriteInterceptor}（它<b>没有</b>交给容器），
 * 因此走的是"直接 new"这条路径，正好把两个分支里的后一个演示到。</p>
 *
 * <p>注意追加进来的拦截器不经过 {@code @LlmGuard} 包装，它天然只作用于本 Agent。</p>
 */
@LlmAgent(
        name = "rewrite",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是一个普通的助手。",
        maxSteps = 4,
        interceptors = {com.benxin.exp.observability.interceptor.RewriteInterceptor.class})
public interface RewriteAssistant {

    String ask(@User String question);
}
