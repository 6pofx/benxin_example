package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;

/**
 * 与那两个接口同包的自定义 Loop，用来演示
 * {@code LlmAgentRegistrar} 那句注释里承诺的事：
 *
 * <blockquote>同一批包里若还有 {@code @LlmLoop} / {@code @LlmGuard} 标注的类，一并注册 ——
 * 用户显式指定了扫描范围，就应该把"本心的注解"整体覆盖到，而不是只认 {@code @LlmAgent} 一种。</blockquote>
 *
 * <p>所以它<b>不需要</b> {@code @Component}，也不需要落在主应用包下面 ——
 * 只要在 {@code @EnableLlmAgents} 指定的范围里就会被注册成 bean。
 * 注意它同时<b>不</b>标 {@code defaultLoop}：本类只验证"进没进 LoopRegistry"。</p>
 */
@LlmLoop("alpha-only-loop")
public class AlphaOnlyLoop extends AbstractAgentLoop {

    @Override
    public String name() {
        return "alpha-only-loop";
    }

    @Override
    public String description() {
        return "只在 @EnableLlmAgents 扫描范围里才会被注册的 Loop。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        return runToolCallingLoop(ctx);
    }
}
