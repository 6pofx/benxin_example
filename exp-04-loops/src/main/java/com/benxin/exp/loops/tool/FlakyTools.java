package com.benxin.exp.loops.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * 一个"永远失败"的工具，专门用来演示 claude-code 的**连续工具错误熔断提醒**。
 *
 * <p>为什么需要它：熔断的触发条件是"连续 N 次工具结果带 {@code error=true}"，
 * 而 {@code CommonTools} 里的工具都很乖 —— 它们返回的是"错误：xxx"这种普通字符串，
 * {@code ToolResult.error} 为 false，熔断永远看不到。要真实触发这条路径，
 * 必须有一个真的把 {@code error=true} 交出去的失败源。</p>
 */
public class FlakyTools {

    private int failures;

    /** 抛异常是最真实的失败源：{@code DefaultLoopContext} 会把它包装成 error 结果回灌。 */
    @LlmTool(name = "flaky_query", description = "一个不稳定的查询工具（本示例里它总是失败）")
    public String flakyQuery(
            @LlmToolParam(value = "keyword", description = "查询关键字") String keyword) {
        failures++;
        throw new IllegalStateException("上游服务不可用（第 " + failures + " 次）：keyword=" + keyword);
    }

    /** 失败次数，供测试断言"熔断确实是由它触发的"。 */
    public int failures() {
        return failures;
    }

    public void reset() {
        failures = 0;
    }
}
