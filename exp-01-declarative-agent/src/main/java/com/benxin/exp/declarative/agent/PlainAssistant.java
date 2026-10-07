package com.benxin.exp.declarative.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 最小声明式 Agent：注解上<b>一个属性都不写</b>。
 *
 * <p>它演示的是两条容易被忽略的默认行为：</p>
 * <ol>
 *   <li><b>名字从接口名推断</b>：不写 {@code name} / {@code value} 时，
 *       注册名是接口名的首字母小写（{@code plainAssistant}）；</li>
 *   <li><b>空缺项回落到 {@code llm.agent.*}</b>：loop 取 {@code llm.agent.default-loop}、
 *       maxSteps 取 {@code llm.agent.max-steps}、memory / stream / approval-policy 同理。
 *       断言这两条最直接的方式就是"什么都不写"，然后看装配出来的 AgentSpec。</li>
 * </ol>
 *
 * <p>另外注意：它没有声明 {@code tools} / {@code toolNames}，
 * 因此它<b>继承容器的全局工具表</b> —— "什么都不写"等于"拿到所有工具"。</p>
 */
@LlmAgent
public interface PlainAssistant {

    String ask(String question);
}
