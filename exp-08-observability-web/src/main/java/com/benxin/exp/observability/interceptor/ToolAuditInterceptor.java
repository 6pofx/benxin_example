package com.benxin.exp.observability.interceptor;

import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code afterTool} 的<b>记账</b>示范：工具跑完之后把"谁、第几步、成功还是失败、结果多长"记下来。
 *
 * <p>{@code afterTool} 与监听器的 {@code onToolResult} 看着重叠，差别在三处，
 * 本示例用断言把它们钉住：</p>
 * <ol>
 *   <li><b>时机</b>：{@code afterTool} 先于 {@code onToolResult}（拦截器在
 *       {@code DefaultLoopContext.executeOne} 里先跑，监听器通知紧随其后）；</li>
 *   <li><b>能力</b>：拦截器能改行为，监听器不能 —— 想"根据工具结果决定下一步"必须用拦截器；</li>
 *   <li><b>范围</b>：拦截器可以用 {@code @LlmGuard} / {@code supports()} 收窄到指定 Agent，
 *       监听器是全局广播。</li>
 * </ol>
 *
 * <p>本类用 {@code @Component} 而不是 {@code @LlmGuard}：它要全局生效，而
 * {@code @LlmGuard} 是"声明作用范围"的注解，想全局生效还得显式写 {@code global = true}。
 * 两种写法都能让类进容器，{@code LlmComponentScanner} 发现同名 bean 已存在时会跳过，
 * 因此不存在重复注册。</p>
 */
@Component
public class ToolAuditInterceptor implements AgentInterceptor {

    /** 一条工具记账：工具名 + 步数 + 是否错误 + 结果长度。 */
    public record Entry(String toolName, int step, boolean error, int contentLength) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicInteger errors = new AtomicInteger();

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void afterTool(ToolInvocation invocation, ToolResult result) {
        entries.add(new Entry(invocation.toolName(), invocation.step(),
                result.error(), result.content().length()));
        if (result.error()) {
            errors.incrementAndGet();
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int errorCount() {
        return errors.get();
    }

    public void reset() {
        entries.clear();
        errors.set(0);
    }

    @Override
    public String toString() {
        return "ToolAuditInterceptor[记账 " + entries.size() + " 条 / 失败 " + errors.get() + " 条]";
    }
}
