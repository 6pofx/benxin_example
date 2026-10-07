package com.benxin.exp.loops.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 不带任何 Loop 配置的 Agent：用来演示"什么都不写时回落到哪个 Loop"。
 *
 * <p>本模块的 {@code application.yml} 把 {@code llm.agent.default-loop} 指向了自己写的
 * {@code exp-decorate}，因此 {@code genericAgent} 与 {@code loopRegistry.defaultName()}
 * 都会落在那一个自定义 Loop 上 —— 这是"注解里不写 loop，就用全局默认"的直观证据。</p>
 *
 * <p>顺带说明优先级（{@code LlmAutoConfiguration} 的实际实现）：
 * {@code llm.agent.default-loop} 配置 &gt; {@code @LlmLoop(defaultLoop = true)} &gt;
 * {@code "dsh-minimal"}。第三条不是"第一个注册的"，而是写死的兜底值。</p>
 *
 * <p><b>一个容易猜错的口径：</b>{@code @LlmAgent} 接口里的每个方法都是一次模型调用，
 * 返回 {@code String} 时拿到的永远是模型文本，<b>不是</b> Agent 的装配信息。
 * 想看"这个 Agent 被装到了哪个 Loop"，得走 {@code agentRegistry.get(name).loop().name()}。</p>
 */
@LlmAgent(model = "mock", memory = false,
        systemPrompt = "你是一个最朴素的 Agent，只负责把请求转成一次运行。")
public interface GenericAgent {

    AgentResult ask(@User String question);
}
