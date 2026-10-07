package com.benxin.exp.overridefixture.alpha;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.hook.AgentInterceptor;

/**
 * 与那两个接口同包的 {@code @LlmGuard} 拦截器，验证"扫描范围覆盖的是本心的注解整体"。
 *
 * <p>它跟 {@link AlphaOnlyLoop} 一样不需要 {@code @Component}：
 * {@code LlmAgentRegistrar} 在注册完 {@code @LlmAgent} 接口之后，
 * 会顺手调 {@code LlmComponentScanner.registerAnnotatedComponents(registry, packages)}
 * 把同一批包里的 {@code @LlmLoop} / {@code @LlmGuard} 也补成 bean。</p>
 */
@LlmGuard(agents = {"alphaAssistant"})
public class AlphaGuardInterceptor implements AgentInterceptor {

    private int beforeAgentCalls;

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        beforeAgentCalls++;
    }

    public int beforeAgentCalls() {
        return beforeAgentCalls;
    }
}
