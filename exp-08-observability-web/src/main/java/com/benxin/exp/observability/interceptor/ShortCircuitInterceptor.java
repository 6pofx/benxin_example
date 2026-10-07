package com.benxin.exp.observability.interceptor;

import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code beforeTool} 的<b>短路</b>示范：返回非 {@code null} 时，工具根本不会被执行，
 * 模型收到的是拦截器给出的那个 {@link ToolResult}。
 *
 * <p>这是拦截器最"重"的一项能力：缓存、降级、熔断、权限兜底都能靠它实现 ——
 * 例如"这个 Agent 不允许查天气"可以在不删除工具的前提下，直接返回一个说明性结果。</p>
 *
 * <p>{@code @LlmGuard(agents = {"toolGuard"})} 把作用范围收窄到这一个 Agent：
 * 其余 Agent 的 {@code get_weather} 照常真实执行。范围包装由
 * {@code LlmAutoConfiguration.resolveInterceptors(...)} 完成（包成 {@code SelectiveInterceptor}），
 * 因此消费端必须依赖 {@code LlmInterceptorChain} 才拿得到这份包装。</p>
 */
@LlmGuard(agents = {"toolGuard"})
public class ShortCircuitInterceptor implements AgentInterceptor {

    /** 被短路时返回给模型的固定结果 —— 演示与测试都断言这段文本。 */
    public static final String CANNED = "【拦截器短路】天气查询在本示例里被禁用，这是拦截器直接给出的结果。";

    private final AtomicInteger shortCircuits = new AtomicInteger();
    private final AtomicInteger passThroughs = new AtomicInteger();

    @Override
    public int order() {
        // 排在顺序探针（-30/-20/-10）之后、改写拦截器之前：短路一旦命中就不会再执行工具，
        // 顺序对结果无影响，但显式写出来让"谁先谁后"可读。
        return 0;
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        if ("get_weather".equals(invocation.toolName())) {
            shortCircuits.incrementAndGet();
            return ToolResult.ok(CANNED);
        }
        passThroughs.incrementAndGet();
        return null;   // null = 放行，走真正的工具执行
    }

    public int shortCircuits() {
        return shortCircuits.get();
    }

    public int passThroughs() {
        return passThroughs.get();
    }

    public void reset() {
        shortCircuits.set(0);
        passThroughs.set(0);
    }

    @Override
    public String toString() {
        return "ShortCircuitInterceptor[短路 " + shortCircuits() + " 次 / 放行 " + passThroughs() + " 次]";
    }
}
