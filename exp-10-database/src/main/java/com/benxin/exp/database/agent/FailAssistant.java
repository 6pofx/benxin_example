package com.benxin.exp.database.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 失败演示助手：绑定"会抛异常的模型"，专门用来把失败路径的审计口径钉住。
 *
 * <p>为什么需要单独一个 Agent，而不是让 {@code orderAssistant} 失败一次？因为
 * {@code DefaultAgent} 在<b>构造期</b>就解析模型与 Loop，所以"模型名写错"是启动期错误，
 * 走不到监听器的 {@code onError}。要在运行期制造一次真实的失败，只能让模型在
 * {@code chat()} 里抛 —— 而把 {@code orderAssistant} 换成不稳定模型会让其它演示一起失效。
 * 于是单独开这一个：它绑 {@code unstable} 模型，只在输入里出现
 * {@code 触发失败} 时才抛异常。</p>
 *
 * <p>{@code memory = false}：失败演示不需要历史，关掉能让 {@code agent_memory} 的行数
 * 断言不受它影响。</p>
 */
@LlmAgent(
        name = "failAssistant",
        model = "unstable",
        loop = "dsh-minimal",
        maxSteps = 3,
        memory = false,
        systemPrompt = "你是一个用于演示失败路径的助手。"
)
public interface FailAssistant {

    @SystemPrompt("原样回答即可。")
    String ask(@User String question);
}
