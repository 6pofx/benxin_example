package com.benxin.exp.tools.support;

import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 拦截器探针：只记录"我见过哪些工具调用"，从不短路（{@code beforeTool} 恒返回 null）。
 *
 * <p>它存在的唯一目的是给一件事提供可运行证据：<b>沙箱与拦截器谁先执行</b>。
 * {@code docs/ARCHITECTURE.md} 的"五道关卡"把 {@code ① 拦截器 beforeTool} 画在
 * {@code ② ToolSandbox.check} 之前，而 {@code DefaultLoopContext.executeOne} 的实现是
 * 先沙箱、再审批、最后才轮到拦截器 —— 于是一次被沙箱拒绝的调用<b>根本不会</b>
 * 出现在 {@link #seen()} 里。示例把这个差异钉成了测试，而不是照着文档去猜。</p>
 */
@Component
public class GateOrderProbe implements AgentInterceptor {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        seen.add(invocation.toolName());
        return null;   // 不干涉，纯观察
    }

    public void reset() {
        seen.clear();
    }

    public List<String> seen() {
        return List.copyOf(seen);
    }
}
