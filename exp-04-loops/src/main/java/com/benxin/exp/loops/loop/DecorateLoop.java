package com.benxin.exp.loops.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.loop.SubAgentObserver;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自定义 Loop 之二：{@code exp-decorate} —— 一条"先复述、再动手、最后记账"的思考流程，
 * 并且实现 {@link SubAgentObserver} 记录子代理派生。
 *
 * <p>它存在的意义是把 {@code LoopContext} 的能力<b>逐个用一遍</b>，
 * 让"写自定义 Loop 到底能碰到什么"有一份可运行的清单：</p>
 * <table border="1">
 *   <caption>本类用到的 LoopContext 能力</caption>
 *   <tr><th>方法</th><th>用在哪</th></tr>
 *   <tr><td>{@code append(...)}</td><td>① 复述阶段注入一条 user 消息</td></tr>
 *   <tr><td>{@code callModel()}</td><td>① 复述、② 主循环、④ 汇总</td></tr>
 *   <tr><td>{@code executeTools(...)}</td><td>② 手动执行一轮工具并回灌</td></tr>
 *   <tr><td>{@code callTool(name, args)}</td><td>② 手动取一次数据（不经过模型决策）</td></tr>
 *   <tr><td>{@code emit(事件, 负载)}</td><td>①② 每步发一个 {@code exp-loop.*} 事件</td></tr>
 *   <tr><td>{@code attributes()}</td><td>①②③④ 全程记账</td></tr>
 *   <tr><td>{@code messages()}</td><td>读数：确认历史里已经有工具结果</td></tr>
 *   <tr><td>{@code step()} / {@code maxSteps()}</td><td>自己控制预算</td></tr>
 *   <tr><td>{@code outOfBudget()}</td><td>由基类提供，语义与内置 Loop 完全一致</td></tr>
 *   <tr><td>{@code toResult(text)}</td><td>④ 自己封装结果（标准上下文才支持）</td></tr>
 *   <tr><td>{@code finish()}</td><td>④ 的兜底：拿最后一段模型文本收尾</td></tr>
 *   <tr><td>{@code runToolCallingLoop(ctx)}</td><td>③ 直接复用标准骨架</td></tr>
 *   <tr><td>{@code supportsSubAgents()}</td><td>③ 执行阶段是否需要发 {@code task} 提示</td></tr>
 * </table>
 *
 * <p><b>关于 {@code callTool}：</b>它绕过模型决策直接执行一个工具，仍然会经过沙箱、审批、
 * 拦截器与审计轨迹（走的是 {@code DefaultLoopContext} 的同一条执行路径），
 * 只是"要不要调、调什么"由 Loop 自己决定。这里用它取一次系统时间 ——
 * 这就是"Loop 想自己动手时不必求模型"的直观例子。</p>
 */
@LlmLoop("exp-decorate")
public class DecorateLoop extends AbstractAgentLoop implements SubAgentObserver {

    /** 运行属性键：本 Loop 的自记账。 */
    public static final String ATTR_RESTATED = "exp.decorate.restated";
    public static final String ATTR_DIRECT_TOOL = "exp.decorate.directToolResult";
    public static final String ATTR_BUDGET_EXHAUSTED = "exp.decorate.budgetExhausted";
    public static final String ATTR_EXECUTION = "exp.decorate.execution";

    /** 子代理记账：开始/结束各记一份，证明真实派生路径会回调本 Loop。 */
    public static final String ATTR_SUBAGENT_STARTED = "exp.decorate.subAgentStarted";
    public static final String ATTR_SUBAGENT_EVENTS = "exp.decorate.subAgentEvents";

    /** ③ 走的工具名。 */
    private static final String DIRECT_TOOL = "get_current_time";

    @Override
    public String name() {
        return "exp-decorate";
    }

    @Override
    public String description() {
        return "自定义 Loop 示例：先复述任务并直接取一次数据，再决定是「手动跑一轮工具」"
                + "还是「交给标准骨架」，最后自己封装结果；同时实现 SubAgentObserver 记录子代理派生。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        if (outOfBudget(ctx)) {
            return finishTruncated(ctx);
        }

        // ---------- ① 复述：Loop 有权改历史，这是"自定义思考方式"最直接的入口 ----------
        ctx.append(ChatMessage.user(
                "请先用一句话复述这个任务，然后开始动手。不要调用工具，复述完直接给出你的判断。"));
        ChatResponse restate = ctx.callModel();
        String restatement = restate.message() == null ? "" : restate.message().text();
        ctx.attributes().put(ATTR_RESTATED, restatement);
        ctx.emit("exp-loop.restated", Map.of(
                "step", ctx.step(),
                "chars", restatement.length(),
                "maxSteps", ctx.maxSteps()));

        // ---------- ② 直接调工具：不经过模型决策，但仍然经过沙箱 / 审批 / 审计 ----------
        if (ctx.tools() != null && ctx.tools().find(DIRECT_TOOL).isPresent()) {
            ToolResult direct = ctx.callTool(DIRECT_TOOL, Map.of());
            ctx.attributes().put(ATTR_DIRECT_TOOL, direct.content());
            // callTool 不会自己写历史（它只记审计轨迹），要回灌得 Loop 自己 append
            ctx.append(ChatMessage.toolResult("exp-direct-" + ctx.step(), DIRECT_TOOL,
                    direct.content(), direct.error()));
            ctx.emit("exp-loop.direct-tool", Map.of("tool", DIRECT_TOOL, "error", direct.error()));
        }

        // ---------- ③ 判断历史里有没有真实工具结果：决定走手写循环还是标准骨架 ----------
        ctx.attributes().put(ATTR_EXECUTION, hasToolMessage(ctx) ? "manual-tool-round" : "runToolCallingLoop");
        LoopResult result = hasToolMessage(ctx) ? manualToolRound(ctx) : runToolCallingLoop(ctx);

        // ---------- ④ 自己封装结果：把预算是否耗尽也如实记下来 ----------
        boolean exhausted = outOfBudget(ctx);
        ctx.attributes().put(ATTR_BUDGET_EXHAUSTED, exhausted);
        ctx.emit("exp-loop.done", Map.of(
                "steps", ctx.step(),
                "budgetExhausted", exhausted,
                "subAgentEvents", subAgentEvents(ctx).size()));

        if (exhausted) {
            markMaxStepsReached(ctx);
        }
        return ctx instanceof com.benxin.llm.core.loop.DefaultLoopContext standard
                ? standard.toResult(result.text())
                : result;
    }

    /**
     * 手写的一轮"模型 → 工具 → 回灌"，与 {@code runToolCallingLoop} 做的是同一件事。
     *
     * <p>刻意手写一遍：它让"基类到底替你省了什么"变得可见 ——
     * 省掉的是 while 循环、预算判断、工具执行与结果追加，不是别的。</p>
     */
    private LoopResult manualToolRound(LoopContext ctx) {
        while (!outOfBudget(ctx)) {
            ChatResponse response = ctx.callModel();
            if (response.message() == null) {
                return buildResult(ctx, "");
            }
            List<ToolUsePart> toolUses = response.message().toolUses();
            if (toolUses.isEmpty()) {
                return buildResult(ctx, response.message().text());
            }
            List<ChatMessage> results = ctx.executeTools(toolUses);
            results.forEach(ctx::append);
            Optional<String> direct = directAnswer(ctx, toolUses, results);
            if (direct.isPresent()) {
                return buildResult(ctx, direct.get());
            }
        }
        return finishTruncated(ctx);
    }

    private static boolean hasToolMessage(LoopContext ctx) {
        return ctx.messages().stream().anyMatch(m -> m.role() == com.benxin.llm.core.message.Role.TOOL);
    }

    // ------------------------------------------------------------------
    // SubAgentObserver：子代理的**真实**派生路径会回调这里
    // ------------------------------------------------------------------

    @Override
    public void onSubAgentStart(LoopContext ctx, String subAgentName, String prompt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", "start");
        event.put("subAgent", subAgentName);
        event.put("promptChars", prompt == null ? 0 : prompt.length());
        record(ctx, event);
        ctx.emit("exp-loop.subagent-start", event);
    }

    @Override
    public void onSubAgentEnd(LoopContext ctx, String subAgentName, AgentResult result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", "end");
        event.put("subAgent", subAgentName);
        event.put("steps", result == null ? -1 : result.steps());
        event.put("inputTokens", result == null ? 0 : result.usage().inputTokens());
        event.put("outputTokens", result == null ? 0 : result.usage().outputTokens());
        record(ctx, event);
        ctx.emit("exp-loop.subagent-end", event);
    }

    private void record(LoopContext ctx, Map<String, Object> event) {
        if (event == null) {
            return;
        }
        Map<String, Object> attributes = ctx.attributes();
        if (attributes == null) {
            return;
        }
        attributes.put(ATTR_SUBAGENT_STARTED, "start".equals(event.get("phase")));
        List<Map<String, Object>> events = new ArrayList<>(subAgentEvents(ctx));
        events.add(Map.copyOf(event));
        attributes.put(ATTR_SUBAGENT_EVENTS, List.copyOf(events));
    }

    /** 从属性里读回子代理记账（演示与测试共用同一份读取逻辑）。 */
    public static List<Map<String, Object>> subAgentEvents(LoopContext ctx) {
        Object raw = ctx.attributes() == null ? null : ctx.attributes().get(ATTR_SUBAGENT_EVENTS);
        List<Map<String, Object>> events = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    events.add(copy);
                }
            }
        }
        return List.copyOf(events);
    }

    /** 演示里用来判断"这个 Agent 到底有没有派生过子代理"。 */
    public static boolean derivedAnySubAgent(LoopContext ctx) {
        return !subAgentEvents(ctx).isEmpty();
    }
}
