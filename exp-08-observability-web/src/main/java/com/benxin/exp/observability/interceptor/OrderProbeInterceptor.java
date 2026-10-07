package com.benxin.exp.observability.interceptor;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.hook.AgentInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 顺序探针：用来实测 {@code AgentInterceptor.order()} 的执行方向。
 *
 * <p><b>为什么需要三个实例：</b>单看源码容易得出"beforeXxx 正序、afterXxx 逆序"的
 * 洋葱模型直觉，但本心的实际口径是 —— <b>两者都按 {@code order()} 升序</b>。
 * 这一点用"注册 3 个探针 + 记录调用顺序"来验证最直接。</p>
 *
 * <p>{@code @LlmGuard} 已经足够让它进容器（{@code LlmComponentScanner} 会自动注册），
 * 所以这里<b>不需要</b>也不应该再补 {@code @Component}；补了也等价（扫描器发现同名 bean
 * 已存在就跳过），但两种写法混用会让"到底谁注册的"变得不清晰。</p>
 *
 * <p>实例需要按 order 顺序注册三个，因此本类没有 {@code @Component}，
 * 而是由 {@code InterceptorConfiguration} 用 {@code @Bean} 方法显式声明三次。</p>
 */
public class OrderProbeInterceptor implements AgentInterceptor {

    private final String id;
    private final int order;

    public OrderProbeInterceptor(String id, int order) {
        this.id = id;
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        ORDER_TRACE.add(id + ".beforeAgent");
    }

    @Override
    public void afterAgent(AgentInvocation invocation, AgentResult result) {
        ORDER_TRACE.add(id + ".afterAgent");
    }

    public String id() {
        return id;
    }

    // ------------------------------------------------------------------
    // 顺序轨迹：三个探针实例必须写进同一份列表，因此做成静态（演示/测试夹具，
    // 不是生产代码。真实项目里应当交给 metrics registry 一类的组件）。
    // ------------------------------------------------------------------

    private static final List<String> ORDER_TRACE = new CopyOnWriteArrayList<>();

    public static void resetTrace() {
        ORDER_TRACE.clear();
    }

    /** 当前顺序轨迹的快照。 */
    public static List<String> trace() {
        return new ArrayList<>(ORDER_TRACE);
    }
}
