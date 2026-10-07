package com.benxin.exp.observability.web;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.hook.AgentInterceptor;
import org.springframework.stereotype.Component;

/**
 * 只管一件事的拦截器：把每次运行用到的 sessionId 登记进 {@link SessionIds}。
 *
 * <p>这是"全局生效拦截器"的最小形态：<b>不写</b> {@code @LlmGuard} 就作用于所有 Agent
 * （与 {@code @LlmGuard(agents = {...})} 形成对照）。</p>
 *
 * <p>{@code order()} 给一个很小的负数，让登记发生在所有其它拦截器之前 ——
 * 后面任何一个拦截器抛异常时，这次运行用过的会话仍然已经被记下来。</p>
 */
@Component
public class SessionTrackingInterceptor implements AgentInterceptor {

    private final SessionIds sessions;

    public SessionTrackingInterceptor(SessionIds sessions) {
        this.sessions = sessions;
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        sessions.remember(invocation.sessionId());
    }
}
