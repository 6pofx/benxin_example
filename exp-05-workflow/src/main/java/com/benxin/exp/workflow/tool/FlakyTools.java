package com.benxin.exp.workflow.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 工作流演示专用的工具集：把"工具会失败"这件事变成可复现的脚本。
 *
 * <p>为什么必须自带工具而不复用 {@code exp-common} 的 {@link com.benxin.exp.common.tool.CommonTools}？
 * 因为工作流最值得演示的两件事 —— <b>{@code retry}</b> 与 <b>{@code continueOnError}</b> ——
 * 都需要一个"会按次数失败的工具"。真实工具当然也会失败（网络抖动、限流、下游 5xx），
 * 但那不可控；这里用一个"前 N 次必失败"的脚本把偶发故障搬到演示台上，
 * 于是"重试真的重试了"可以被断言，而不是靠祈祷。</p>
 *
 * <p>另一个细节：{@link #nextAttempt} 刻意做成<b>工具</b>而不是 {@code set} 节点。
 * 因为 {@code set} 的模板只做 {@code ${}} 插值、不做算术 —— {@code n: "${n} + 1"} 得到的是
 * 字符串 {@code "0 + 1"}，而 {@code loop-workflow.yml} 的循环条件要用 {@code ${count} < 3} 做
 * <b>数值</b>比较。这条差异值得亲手撞一次。</p>
 *
 * <p>注意它是普通类，不是 {@code @Component}：由 {@code MockModelConfiguration} 用
 * {@code @Bean} 交给容器，这样才会被 {@code LlmToolCatalog} 扫到。</p>
 */
public class FlakyTools {

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    /** 已消耗的失败配额，用来把"前 N 次失败"这件事记在实例上。 */
    private final AtomicInteger consumedFailures = new AtomicInteger();

    /** 脚本：前几次调用必定失败（0 表示一直成功）。 */
    private final int failuresBeforeSuccess;

    /** 已成功返回的次数，用于断言"重试之后确实成功了"。 */
    private final AtomicInteger successes = new AtomicInteger();

    /** 每次都失败的账号名 —— 模拟"这个账号被禁用"这类不可重试的错误。 */
    private static final String BLOCKED_ACCOUNT = "禁用账号";

    public FlakyTools() {
        this(2);
    }

    public FlakyTools(int failuresBeforeSuccess) {
        this.failuresBeforeSuccess = Math.max(0, failuresBeforeSuccess);
    }

    /**
     * 计数 +1。
     *
     * <p>承 {@code ${count}} 进来，返回它的下一个整数。之所以不用 {@code set} 节点算，
     * 是因为 {@code set} 的 {@code ${}} 只做插值不做算术（见类注释）。</p>
     */
    @LlmTool(name = "next_attempt", description = "把传入的整数加一并返回，用于工作流里做循环计数")
    public String nextAttempt(
            @LlmToolParam(value = "n", description = "当前计数，例如 0") String n,
            @LlmToolParam(value = "prev", description = "可选的上一节点输出，n 为空时用它兜底",
                    required = false) String prev) {
        String raw = n == null || n.isBlank() ? prev : n;
        if (raw == null || !INTEGER.matcher(raw.trim()).matches()) {
            // 报一句能直接照做的话，而不是让 NumberFormatException 冒到用户面前
            throw new IllegalArgumentException("next_attempt 需要一个整数，实际收到 [" + raw + "]");
        }
        return String.valueOf(Long.parseLong(raw.trim()) + 1);
    }

    /**
     * 前 {@code failuresBeforeSuccess} 次抛异常，之后成功。
     *
     * <p>用来演示 {@code retry: N}：节点重试若干次后成功，工作流照常走完，
     * 而 {@code workflow.node-retry} 事件里能看到中间失败过几次。</p>
     */
    @LlmTool(name = "flaky_stamp", description = "打一个时间戳；这是一个会偶发失败的工具（前几次必失败）")
    public String flakyStamp(
            @LlmToolParam(value = "payload", description = "要盖章的内容，可省略",
                    required = false, defaultValue = "无") String payload) {
        int used = consumedFailures.get();
        if (used < failuresBeforeSuccess) {
            consumedFailures.incrementAndGet();
            throw new IllegalStateException("下游服务暂时不可用（第 " + (used + 1)
                    + " 次尝试，脚本设定前 " + failuresBeforeSuccess + " 次必失败）");
        }
        successes.incrementAndGet();
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " 已盖章";
    }

    /**
     * 账号被禁用时<b>次次失败</b> —— 这类错误重试没有意义。
     *
     * <p>配上 {@code continueOnError: true}，它演示的是「让这一个节点失败，
     * 而不是让整张图崩掉」，并且失败的出边能读到 {@code ${error}} 走补偿分支。</p>
     */
    @LlmTool(name = "flaky_charge", description = "扣款；账号为『禁用账号』时一定会失败")
    public String flakyCharge(
            @LlmToolParam(value = "account", description = "账号名") String account,
            @LlmToolParam(value = "amount", description = "金额", required = false,
                    defaultValue = "0") String amount) {
        if (BLOCKED_ACCOUNT.equals(account)) {
            throw new IllegalStateException("账号 [" + account + "] 已被禁用，拒绝扣款 " + amount);
        }
        return "扣款成功：" + account + " / " + amount;
    }

    /** 已成功返回的次数。 */
    public int successes() {
        return successes.get();
    }

    /** 已消耗掉的失败配额。 */
    public int consumedFailures() {
        return consumedFailures.get();
    }

    /** 演示之间要复位，否则第二组演示会遇到"配额已经用光"的假象。 */
    public void reset() {
        consumedFailures.set(0);
        successes.set(0);
    }
}
