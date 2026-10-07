package com.benxin.exp.loops.agent;

import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * {@code reflexion} Agent：执行 → 自省评分 → 低于阈值带着批评重做。
 *
 * <p>阈值不在 {@code @LlmAgent} 的注解里（那个注解没有这个属性），它是
 * {@code ReflexionLoop(int maxAttempts, double threshold)} 的构造参数。
 * 因此"换个阈值"这件事的正确写法是：在 {@code @Bean} 方法里 new 一个实例，
 * 再给它一个注册名（本模块用的是 {@code exp-reflexion-strict}，阈值 0.9）。</p>
 *
 * <p><b>注意阈值是 0-1 的小数</b>，不是百分制也不是 1-10 分制 ——
 * 写 {@code 90} 会被 {@code Math.min(1d, ...)} 夹成 1.0，于是永远达不到，
 * 每一轮都会重做直到 {@code maxAttempts} 用尽。</p>
 */
@LlmAgent(model = OfflineModelConfiguration.REFLEXION, loop = LoopNames.REFLEXION_STRICT,
        memory = false, maxSteps = 12,
        systemPrompt = "你是一个会给自己打分的 Agent，分数不够就带着批评重做。")
public interface ReflexionAgent {

    AgentResult ask(@User String question);
}
