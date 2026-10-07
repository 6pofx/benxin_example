package com.benxin.exp.real.tool;

import com.benxin.llm.core.annotation.LlmTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本示例唯一的工具：一个"参数为空"的取时间工具。
 *
 * <p>为什么自己写一个、而不复用 {@code exp-common} 的 {@code CommonTools}？
 * 因为 token 是被<b>每一次请求</b>里的工具 schema 吃掉的：</p>
 * <ul>
 *   <li>不写 {@code tools} / {@code toolNames} 的 Agent 会<b>继承容器全局工具表</b>，
 *       所以全局表里有几个工具，每一个 Agent 的每一次请求就要多背几份 schema；</li>
 *   <li>{@code CommonTools} 有 4 个工具（含带参数的天气与计算器），
 *       实测每个请求会多出 200 token 上下 —— 对"总消耗 2000 以内"的目标来说太贵；</li>
 *   <li>本工具名字只有一个单词 {@code now}、描述 8 个字、参数为空，
 *       schema 压到最小，并且它同时满足了"参数为空或极少"的演示要求。</li>
 * </ul>
 *
 * <p>换句话说：<b>全局工具表要当成"每次请求都要付的固定成本"来设计</b>，
 * 这与"工具越全越好"的直觉相反，但在真实计费端点上就是这么算账的。</p>
 */
public class TinyTools {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * 取服务器当前时间。
     *
     * <p>返回 {@code HH:mm:ss} 而不是完整时间戳，同样是为了省 token：
     * 工具结果会被回灌进下一次请求，它的长度也是要付费的。</p>
     */
    @LlmTool(name = "now", description = "服务器当前时间")
    public String now() {
        callCount.incrementAndGet();
        return LocalDateTime.now().format(FORMAT);
    }

    /** 被真实执行过几次（演示"工具真的跑了"，而不是模型自己编了个时间）。 */
    public int callCount() {
        return callCount.get();
    }

    public void reset() {
        callCount.set(0);
    }
}
