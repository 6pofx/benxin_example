package com.benxin.exp.database.guard;

import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolInvocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 风控沙箱：<b>沙箱闸与审批闸是两道独立的闸</b>，本类负责第一道。
 *
 * <h2>两道闸的顺序与语义差别（这是本示例最容易猜错的一条）</h2>
 * <p>看 {@code DefaultLoopContext#executeOne} 的实现顺序：</p>
 * <pre>
 * 1. sandbox().check(call)          → 拒绝就是硬约束
 * 2. needsApproval(...) &amp;&amp; !approvalHandler().approve(...)  → 拒绝会变成一个错误结果
 * 3. interceptor.beforeTool(call)   → 短路
 * 4. 真正执行
 * </pre>
 *
 * <p>关键在插件自己写下的一句注释：<b>沙箱拒绝是硬约束，不可被审批覆盖</b>。
 * 因为 {@code ON_FAILURE} 策略的语义是"工具执行失败后再问一次"，而沙箱说的是
 * "这条路径/这次调用根本不该被执行" —— 后者是使用者在配置里画下的边界。
 * 于是：<b>被沙箱拒绝的调用，审批处理器连一次都不会被调用。</b>
 * 这条口径非常容易被猜反（直觉上会以为"审批放行就能覆盖沙箱"），示例把它做成了可断言的用例。</p>
 *
 * <h2>本沙箱的规则</h2>
 * <p>它只做一条示例级别的风控：<b>{@code refund_order} 的单笔退款金额不得超过
 * {@value #REFUND_LIMIT} 元</b>。超过即拒绝，理由是"需走线下风控流程" ——
 * 这是"模型永远拿不到的权限"，与"要不要人点头"（审批闸）是两个问题。</p>
 *
 * <p>沙箱还顺带定义了超时与结果截断长度：这两项也在 {@link ToolSandbox} 上，
 * 因为"一次工具调用最多跑多久、最多回多少字"本质上是资源围栏的一部分。</p>
 */
public class RefundAmountSandbox implements ToolSandbox {

    /** 单笔退款上限：超过就是"沙箱层面不允许"，不给审批留余地。 */
    public static final double REFUND_LIMIT = 5000.0;

    private static final String REFUND_ORDER = "refund_order";

    /** 拒绝留痕：断言"沙箱拦了几次"用，比去数日志可靠。 */
    private final List<String> denials = new ArrayList<>();

    @Override
    public Decision check(ToolInvocation invocation) {
        if (!REFUND_ORDER.equals(invocation.toolName())) {
            return Decision.allow();
        }
        double amount = asDouble(invocation.arguments().get("amount"));
        if (amount > REFUND_LIMIT) {
            String reason = "单笔退款 " + amount + " 元超过风控上限 " + REFUND_LIMIT
                    + " 元，需走线下风控流程（沙箱拒绝，审批不可覆盖）";
            synchronized (denials) {
                denials.add(invocation.toolName() + ":" + amount);
            }
            return Decision.deny(reason);
        }
        return Decision.allow();
    }

    @Override
    public Duration timeout() {
        // 示例里都是本地库操作，5 秒足够；真接外部系统时这里才是要调大的地方
        return Duration.ofSeconds(5);
    }

    @Override
    public int maxResultChars() {
        return 20_000;
    }

    // ------------------------------------------------------------------

    public List<String> denials() {
        synchronized (denials) {
            return List.copyOf(denials);
        }
    }

    public void reset() {
        synchronized (denials) {
            denials.clear();
        }
    }

    private static double asDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** 便于演示打印"这道闸是谁在守、守的是什么"。 */
    public String describe() {
        return "RefundAmountSandbox{refund_order 单笔上限 " + REFUND_LIMIT + " 元，超出即沙箱拒绝}";
    }

    /**
     * 说明"沙箱拒绝不可被审批覆盖"。
     *
     * <p>这里刻意去引用 {@link ApprovalHandler} 的类型，让读者一眼看到
     * "沙箱"与"审批"在插件里是被并列对待的两个概念，而不是二选一的配置项。</p>
     */
    public static String gateOrderHint() {
        return "执行顺序：沙箱(" + ToolSandbox.class.getSimpleName() + ") → 审批("
                + ApprovalHandler.class.getSimpleName() + ") → 拦截器 → 执行；"
                + "沙箱拒绝时审批不会被调用";
    }
}
