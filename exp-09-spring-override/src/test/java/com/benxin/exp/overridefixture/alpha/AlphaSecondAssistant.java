package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 与 {@link AlphaAssistant} 同包的第二个接口：用来证明"整个包都被扫了"，而不是只扫到第一个。
 */
@LlmAgent(name = "alphaSecond")
public interface AlphaSecondAssistant {

    String ask(String question);
}
