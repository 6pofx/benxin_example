package com.benxin.exp.loops.loop;

import com.benxin.exp.loops.agent.LoopNames;
import com.benxin.llm.core.loop.AgentLoop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link LoopVariants} 里三个变体 Loop 交给容器。
 *
 * <p>这里只负责 {@code @Bean}，注册名由类上的 {@code @LlmLoop} 决定 ——
 * 分工明确：{@code @Bean} 说明"这是个容器组件"，{@code @LlmLoop} 说明"它在 Loop 注册表里叫什么"。</p>
 */
@Configuration
public class LoopConfiguration {

    @Bean
    public AgentLoop strictReflexionLoop() {
        return new LoopVariants.StrictReflexionLoop();
    }

    @Bean
    public AgentLoop noVerifyStagedLoop() {
        return new LoopVariants.NoVerifyStagedLoop();
    }

    @Bean
    public AgentLoop lenientStagedLoop() {
        return new LoopVariants.LenientStagedLoop();
    }

    @Bean
    public AgentLoop unrestrictedCodexLoop() {
        return new LoopVariants.UnrestrictedCodexLoop();
    }

    /** 演示与测试共用：变体 Loop 的注册名。 */
    public static String strictReflexionName() {
        return LoopNames.REFLEXION_STRICT;
    }
}
