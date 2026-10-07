package com.benxin.exp.overridefixture.zeta;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 扫描范围实验里的"范围外"接口（包 {@code com.benxin.exp.overridefixture.zeta}）。
 *
 * <p>它与 {@link com.benxin.exp.overridefixture.alpha.AlphaAssistant} 唯一的差别就是包名 ——
 * 因此"扫到 / 扫不到"这件事可以直接归因到扫描范围，而不是"注解写错了"。</p>
 */
@LlmAgent
public interface ZetaAssistant {

    String ask(String question);
}
