package com.benxin.exp.observability.listener;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 录制型监听器：把 {@link AgentListener} 的 <b>12 个回调</b>全部记下来，供演示打印与测试断言。
 *
 * <p><b>为什么需要一个"录制型"监听器：</b>可观测性的演示如果只打印日志，
 * 就只能靠肉眼确认；一旦落成"事件序列 + 计数"，就能被断言成可回归的用例 ——
 * 本模块里 {@code MonitorListenerTest} 正是靠它逐条钉住 12 个回调的触发条件。</p>
 *
 * <p><b>它只观察、不改变任何行为</b>，这是 {@code AgentListener} 与
 * {@code AgentInterceptor} 的分界线：监听器不该有副作用，改行为请用拦截器。</p>
 *
 * <p>线程安全：SSE 端点在独立线程里跑 Agent，因此用 {@link CopyOnWriteArrayList}
 * 记录事件，避免演示里因为并发写而漏事件。</p>
 */
public class MonitorListener implements AgentListener {

    /**
     * 一条事件：回调名 + 时间戳 + 已格式化的细节 + 原始载荷。
     *
     * @param detail 给人看的文本（文本增量会被加上引号，方便肉眼确认分片边界）
     * @param raw    回调收到的原始值（增量类回调是增量字符串，其余为 null）——
     *               测试靠它把增量拼回完整文本，再与 {@code AgentResult.text()} 比较
     */
    public record Event(String hook, long epochMillis, String detail, Object raw) {

        @Override
        public String toString() {
            return hook + "(" + detail + ")";
        }
    }

    private final String label;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger errors = new AtomicInteger();

    /** 完整结果：{@code onAgentEnd} 只在这一处拿到，测试靠它断言 AgentResult 的每一个字段。 */
    private volatile AgentResult lastResult;

    public MonitorListener(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // ------------------------------------------------------------------
    // 12 个回调
    // ------------------------------------------------------------------

    @Override
    public void onAgentStart(AgentInvocation invocation) {
        record("onAgentStart", "agent=" + invocation.agentName()
                + ", session=" + invocation.sessionId()
                + ", loop=" + (invocation.spec() == null ? "-" : invocation.spec().loop())
                + ", input=" + invocation.input());
    }

    @Override
    public void onStepStart(String agentName, int step) {
        record("onStepStart", agentName + "#" + step);
    }

    @Override
    public void onTextDelta(String delta) {
        record("onTextDelta", quote(delta), delta);
    }

    @Override
    public void onThinkingDelta(String delta) {
        record("onThinkingDelta", quote(delta), delta);
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        record("onToolCall", invocation.toolName() + " " + invocation.arguments());
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        record("onToolResult", invocation.toolName() + " error=" + result.error() + " → " + abbreviate(result.content()));
    }

    @Override
    public void onStepEnd(String agentName, int step, ChatResponse response) {
        record("onStepEnd", agentName + "#" + step
                + " finish=" + response.finishReason()
                + " tokens=" + response.usage().inputTokens() + "/" + response.usage().outputTokens());
    }

    @Override
    public void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
        record("onSubAgentStart", parentAgent + " → " + childAgent + " : " + prompt);
    }

    @Override
    public void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
        record("onSubAgentEnd", parentAgent + " → " + childAgent
                + " : " + result.steps() + " 步, 文本 " + abbreviate(result.text()));
    }

    @Override
    public void onCustomEvent(String type, Object payload) {
        // payload 可能是任意对象：这里统一转成字符串，保证"事件留档"这件事不会因为
        // 某个 Loop 传了个不可序列化的对象而失败。
        record("onCustomEvent", type + " = " + payload);
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        lastResult = result;
        record("onAgentEnd", result.agentName()
                + " steps=" + result.steps()
                + " finish=" + result.finishReason()
                + " maxStepsReached=" + result.maxStepsReached()
                + " duration=" + result.durationMillis() + "ms"
                + " tokens=" + result.usage().totalTokens()
                + " toolCalls=" + result.toolCalls().size()
                + " attributes=" + result.attributes().keySet());
    }

    @Override
    public void onError(Throwable error) {
        errors.incrementAndGet();
        record("onError", error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    // ------------------------------------------------------------------
    // 供演示与测试查询
    // ------------------------------------------------------------------

    private void record(String hook, String detail) {
        record(hook, detail, null);
    }

    private void record(String hook, String detail, Object raw) {
        events.add(new Event(hook, System.currentTimeMillis(), detail, raw));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    /** 事件序列（只留回调名），演示里打印成一行，测试里断言成顺序。 */
    public List<String> hookNames() {
        return events.stream().map(Event::hook).toList();
    }

    public long count(String hook) {
        return events.stream().filter(e -> e.hook().equals(hook)).count();
    }

    public boolean seen(String hook) {
        return count(hook) > 0;
    }

    public int errorCount() {
        return errors.get();
    }

    /** 第一条匹配的事件；找不到返回 null。 */
    public Event first(String hook) {
        return events.stream().filter(e -> e.hook().equals(hook)).findFirst().orElse(null);
    }

    /** 某个回调收到的所有细节文本（顺序即发生顺序）。 */
    public List<String> details(String hook) {
        return events.stream().filter(e -> e.hook().equals(hook)).map(Event::detail).toList();
    }

    /** 把某个回调的所有增量拼回完整文本 —— 与 AgentResult.text() 对照即可证明增量完整。 */
    public String joinDetails(String hook, String separator) {
        return String.join(separator, details(hook));
    }

    /**
     * 把某个回调的<b>原始增量</b>（不带引号、不做任何加工）拼起来。
     *
     * <p>与 {@link #joinDetails} 的区别：那个用的是 {@code detail}（文本增量会被加上引号，
     * 便于人读），这个用回调收到的原值 —— 所以能直接和 {@code AgentResult.text()} 比较。</p>
     */
    public String joinRaw(String hook) {
        return events.stream()
                .filter(e -> e.hook().equals(hook))
                .map(Event::raw)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining());
    }

    public AgentResult lastResult() {
        return lastResult;
    }

    public Usage lastUsage() {
        return lastResult == null ? Usage.ZERO : lastResult.usage();
    }

    public Map<String, Object> lastAttributes() {
        return lastResult == null ? Map.of() : lastResult.attributes();
    }

    public void reset() {
        events.clear();
        errors.set(0);
        lastResult = null;
    }

    @Override
    public String toString() {
        return "MonitorListener[" + label + ", " + events.size() + " 个事件]";
    }

    private static String quote(String delta) {
        return delta == null ? "null" : '"' + delta + '"';
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "...";
    }
}
