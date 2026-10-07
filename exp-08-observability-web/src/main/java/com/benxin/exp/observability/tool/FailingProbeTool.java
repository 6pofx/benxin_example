package com.benxin.exp.observability.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个<b>必定失败</b>的工具：把 {@code ToolResult.error=true} 这条可观测性路径变成可确定性复现的。
 *
 * <p><b>为什么非要有它：</b>本模块要演示"工具失败时 onToolResult / afterTool 看到 error=true"，
 * 但 {@code exp-common} 的 {@code CommonTools} 里没有任何一个工具会真的失败 ——
 * {@code calculate} 遇到非法表达式时返回的是<b>正常内容</b>
 * （{@code "错误：表达式只允许出现数字…"}），{@code ToolResult.error()} 仍然是 {@code false}。
 * 于是那条断言只能靠"猜"来写，而实测结果永远是 {@code error=false}。
 * 这里补一个真正抛异常的工具，让失败路径变成事实而不是假设。</p>
 *
 * <p><b>为什么是"抛异常"而不是"返回 ToolResult.error(...)"：</b>
 * 两条路都能得到 {@code error=true}，但抛异常额外覆盖了
 * {@code DefaultLoopContext.executeWithTimeout(...)} 的 {@code ExecutionException} 分支 ——
 * 也就是"工具自己炸了，框架把它兜成一条错误结果交回模型自我纠正"，
 * 这是真实项目里最常见的那种失败。</p>
 *
 * <p><b>怎么被模型选中：</b>它<b>不</b>靠 {@code #tool:probe_fail {}} 强制语法（那种写法在脚本模型的
 * 规则表里每一步都会重新命中，一次运行会失败 N 次），而是靠关键字
 * {@link #TRIGGER} —— 脚本模型的"关键字 → 工具"规则命中它一次，
 * 拿到失败结果后下一轮就走"已有工具结果 → 收尾"，于是恰好一次调用。</p>
 *
 * <p>它<b>不</b>出现在任何 {@code @LlmGuard(agents = {...})} 名单里：工具必须真实执行，
 * 被 {@code ShortCircuitInterceptor} 短路掉的话又看不到失败了。</p>
 */
public class FailingProbeTool {

    /** 工具名：注册进全局工具表后，{@code GET /llm/agents} 里能看到它。 */
    public static final String NAME = "probe_fail";

    /** 触发关键字：写进用户输入即可让脚本模型选中本工具（见 {@code ObservabilityConfiguration}）。 */
    public static final String TRIGGER = "必定失败";

    /** 异常文案：测试与演示都断言这段文本，保证"失败来自本工具"是确定的。 */
    public static final String MESSAGE = "演示用的工具故障：这个工具被刻意实现为必定失败";

    private final AtomicInteger calls = new AtomicInteger();

    @LlmTool(name = NAME, description = "【演示用】一个必定失败的工具，用来观察工具失败时 onToolResult / afterTool 的 error=true")
    public String probeFail(
            @LlmToolParam(value = "reason", description = "失败原因（演示用，可省略）", required = false) String reason) {
        calls.incrementAndGet();
        throw new IllegalStateException(reason == null || reason.isBlank()
                ? MESSAGE
                : MESSAGE + "（原因：" + reason + "）");
    }

    /** 被调用次数：演示里用来证明"这次真的调了、且只调了一次"。 */
    public int callCount() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }

    @Override
    public String toString() {
        return "FailingProbeTool[" + NAME + "，已调用 " + calls.get() + " 次]";
    }
}
