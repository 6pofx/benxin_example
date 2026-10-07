package com.benxin.exp.observability.agent;

import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 专供"一次运行里只发生一次工具调用（且这次调用失败）"的场景。
 *
 * <p><b>为什么需要单独一个 Agent：</b>{@code ScriptedLlmModel} 的规则求值顺序里，
 * "用户显式点名 {@code #tool:<名字>}"排在"已有工具结果就直接收尾"<b>之前</b>。
 * 于是同一条 {@code #tool:xxx} 输入在<b>每一步</b>都会重新命中，工具被反复调用，
 * 直到步数预算耗尽 —— 这本身是个值得演示的口径（见
 * {@code MonitorListenerTest.forcedToolRepeatsEveryStep}），却会让
 * "工具失败时 onToolResult 只收到一条 error=true"这类断言变成 N 条。</p>
 *
 * <p>改用"关键字触发工具"就回到标准闭环：第 1 步请求工具，第 2 步拿到结果后收尾。
 * 本 Agent 因此把 {@code maxSteps} 设为 2，结果恰好是"1 次工具调用、1 次工具结果、2 步"。</p>
 *
 * <p><b>失败来自哪里：</b>触发词是 {@link FailingProbeTool#TRIGGER}，命中的是那个
 * "必定失败"的 {@code probe_fail} 工具（{@code CommonTools} 里没有任何工具会真的失败 ——
 * {@code calculate} 的失败只是内容里的一段错误文本，{@code error} 标志仍是 {@code false}）。</p>
 *
 * <p>它刻意<b>不</b>出现在任何 {@code @LlmGuard(agents = {...})} 名单里，
 * 于是工具会真实执行（而不是被短路掉），失败路径才演示得到。</p>
 */
@LlmAgent(
        name = "toolErrorProbe",
        model = "mock",
        loop = "dsh-minimal",
        systemPrompt = "你是工具演示对象：需要外部信息时先调工具。",
        maxSteps = 2)
public interface ToolErrorProbe {

    /** 声明成 {@link AgentResult} 才能读到工具调用记录（断言失败路径用）。 */
    AgentResult ask(String question);
}
