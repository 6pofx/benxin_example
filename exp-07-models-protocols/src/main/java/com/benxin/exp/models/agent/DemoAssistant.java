package com.benxin.exp.models.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 声明式 Agent —— 用来证明"协议换了，调用方代码一行都不用改"。
 *
 * <p>它的 {@code model = "demo"} 指向 {@code llm.models.demo}，而那条配置写的是
 * {@code protocol: demo}（一个自造协议）。整条链路上没有任何一处 import 了
 * demo 协议的东西：接口不知道、Loop 不知道、工具也不知道，
 * 只有 {@code ProtocolRegistry} 里多了一个条目。</p>
 *
 * <p>这就是 README 里"加自己的协议 … 调用方代码一行都不用改"那句话的可执行版本。</p>
 */
@LlmAgent(name = "demoAssistant",
        model = "demo",
        systemPrompt = "你是 demo 协议演示用的助手，回答尽量短。",
        maxSteps = 3,
        memory = false)
public interface DemoAssistant {

    /** 一句话问答。 */
    String ask(@User String question);

    /** 换一个系统提示词：验证系统提示在 demo 协议里被放进 {@code directive.system}。 */
    @SystemPrompt("只回答一个数字。")
    String brief(@User String question);
}
