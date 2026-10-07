package com.benxin.exp.context.config;

import com.benxin.exp.context.context.Exp06ContextManager;
import com.benxin.exp.context.context.Exp06TokenEstimator;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.SummarizingCompactor;
import com.benxin.llm.core.util.TokenEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文切面的装配：分词估算 → 压缩器 → 上下文管理器，三者串成一条链。
 *
 * <p>插件的默认装配是
 * {@code DefaultContextManager(new SummarizingCompactor(), TokenEstimator.DEFAULT, 0.8, 8, 4096)}
 * （见 {@code LlmAutoConfiguration#llmContextManager}）。本类把三个环节全部替换成自己的实现，
 * 顺带把默认装配里一个值得注意的地方暴露出来：</p>
 *
 * <p><b>默认的 {@code ContextCompactor} 不消费容器里的 {@code TokenEstimator} bean</b>：
 * {@code llmContextCompactor(...)} 直接 {@code new SummarizingCompactor()}，
 * 而该构造器自己拿 {@code TokenEstimator.DEFAULT}。因此只注册一个 {@code TokenEstimator} bean 时，
 * {@code ContextManager} 的阈值判定会用它、压缩器内部的预算判定却仍用默认实现。
 * 本示例显式把估算器注入压缩器（见 {@link #contextCompactor(TokenEstimator)}），
 * 让两者口径一致 —— 这也是"能自己装配就自己装配"的一个具体理由。</p>
 */
@Configuration
public class ContextPipelineConfiguration {

    /** 触发线占模型窗口的比例。 */
    public static final double THRESHOLD = 0.8;

    /** 压缩时保留的最近消息条数。 */
    public static final int KEEP_RECENT = 4;

    /** 为模型输出预留的 token：不参与输入预算。 */
    public static final int RESERVED_OUTPUT_TOKENS = 4096;

    /** 下发给模型的历史条数上限（0 = 不裁剪）。 */
    public static final int HISTORY_WINDOW = 8;

    @Bean
    public Exp06TokenEstimator tokenEstimator() {
        return new Exp06TokenEstimator();
    }

    /**
     * 默认策略仍是"摘要式压缩"（长任务里比直接丢弃旧消息更不容易丢关键信息），
     * 但估算器换成容器里的那一个，并且指令用默认值（{@code null} 即默认指令）——
     * 这样脚本模型能靠指令里的特征句认出"这是一次压缩请求"。
     */
    @Bean
    public SummarizingCompactor contextCompactor(TokenEstimator tokenEstimator) {
        return new SummarizingCompactor(tokenEstimator, THRESHOLD, null);
    }

    /**
     * 返回类型写成具体实现类：既满足 {@code @ConditionalOnMissingBean(ContextManager.class)}
     * 的类型匹配，也让演示与测试可以按具体类型注入、直接读它的统计与配置。
     */
    @Bean
    public Exp06ContextManager contextManager(ContextCompactor contextCompactor,
                                              TokenEstimator tokenEstimator) {
        return new Exp06ContextManager(contextCompactor, tokenEstimator,
                THRESHOLD, KEEP_RECENT, RESERVED_OUTPUT_TOKENS, HISTORY_WINDOW);
    }
}
