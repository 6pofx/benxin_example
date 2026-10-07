package com.benxin.exp.override.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 一个"什么都没多写"的声明式 Agent。
 *
 * <p>它存在的意义不是能力，而是<b>装配口径</b>：</p>
 * <ul>
 *   <li>{@code model} / {@code loop} 留空 → 回落到 {@code llm.default-model} 与
 *       {@code llm.agent.default-loop}；</li>
 *   <li>所以当你在 {@code DefaultOverrideConfiguration} 里把 {@code ModelRegistry} /
 *       {@code LoopRegistry} 换成自己的实现时，<b>这个接口一个字都不用改</b>，
 *       它拿到的就是被换过的注册表 —— 这正是"用户 bean 优先"要兑现的东西。</li>
 * </ul>
 */
@LlmAgent(systemPrompt = "你是 exp-09 的装配演示助手，回答保持一句话。")
public interface SupportAssistant {

    /** 一次普通问答。 */
    String ask(String question);
}
