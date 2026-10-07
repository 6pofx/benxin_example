package com.benxin.exp.overridefixture;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;

/**
 * {@code @LlmLoop(defaultLoop = true)} 的注册细节实验：它应当成为<b>默认 Loop</b>。
 *
 * <p>但"应当"有一个前提：{@code llm.agent.default-loop} 指向的 Loop 得<b>不在</b>注册表里。
 * {@code LlmAutoConfiguration.llmLoopRegistry()} 的优先级是：</p>
 * <pre>
 *   llm.agent.default-loop（且必须在表里） &gt; @LlmLoop(defaultLoop = true) &gt; 字面量 "dsh-minimal"
 * </pre>
 *
 * <p>所以本类配合两种配置各跑一次，就能把这条优先级钉死 ——
 * 见 {@code AnnotationRegistrationTest}。</p>
 */
@LlmLoop(value = "fixture-default-loop", defaultLoop = true)
public class AnnotatedDefaultLoop extends AbstractAgentLoop {

    @Override
    public String name() {
        // 刻意与注解值不同：注册名取注解，这一点也要能被断言。
        return "fixture-default-loop-from-method";
    }

    @Override
    public String description() {
        return "fixture：验证 @LlmLoop(defaultLoop = true) 会成为默认 Loop。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        return runToolCallingLoop(ctx);
    }
}
