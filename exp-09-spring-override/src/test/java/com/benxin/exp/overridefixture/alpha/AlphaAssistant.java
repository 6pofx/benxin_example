package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 扫描范围实验里的"范围内"接口之一（包 {@code com.benxin.exp.overridefixture.alpha}）。
 *
 * <p>它放在这里而不是主包，就是为了让
 * {@code @EnableLlmAgents(basePackages = "com.benxin.exp.overridefixture.alpha")} 与
 * {@code basePackageClasses = AlphaMarker.class} 两种写法都能被断言到。</p>
 */
@LlmAgent
public interface AlphaAssistant {

    String ask(String question);
}
