package com.benxin.exp.workflow.engine;

import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的工作流运行时：不调模型、不调工具，按脚本返回内容或抛异常。
 *
 * <p>这是「引擎与框架解耦」最直接的证据。<b>引擎只依赖 {@link WorkflowRuntime} 这一个四方法接口</b>，
 * 于是工作流里最容易出错的地方 —— 走错边、绕不出来、失败没兜住、预算没兜住 ——
 * 全部可以用本类精确断言，不需要模型、不需要网络，连 {@code exp-common} 的脚本模型都不需要。</p>
 *
 * <p>它能编排的脚本：</p>
 * <ul>
 *   <li>{@link #output} 固定输出；{@link #sequence} 第 n 次返回第 n 个值（用来证明"循环真的转了"）；</li>
 *   <li>{@link #failFirst} 前 N 次抛异常（用来证明 retry 真的重试了）；</li>
 *   <li>{@link #budget} 派发满 N 次之后报告预算耗尽（用来证明 truncated 是真的）；</li>
 *   <li>{@link #recordPrompts} 记下每次收到的提示词（用来证明 {@code instruction} 前缀与模板渲染）。</li>
 * </ul>
 *
 * <p>记录的全部调用都是"派发次数"：{@link #callAgent} 与 {@link #callTool} 各算一次，
 * <b>失败的那次也算</b>。这与真实运行时"每次尝试都消耗一步"的口径一致。</p>
 */
public final class FakeWorkflowRuntime implements WorkflowRuntime {

    private final Map<String, String> outputs = new LinkedHashMap<>();
    private final Map<String, List<String>> sequences = new LinkedHashMap<>();
    private final Map<String, Integer> failTimes = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> attempts = new LinkedHashMap<>();
    private final Map<String, String> prompts = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> toolArgs = new LinkedHashMap<>();

    private final List<String> events = new ArrayList<>();
    private final List<String> agentNodes = new ArrayList<>();
    private final List<String> toolNodes = new ArrayList<>();
    private final List<String> toolNames = new ArrayList<>();

    /** 派发总次数：callAgent + callTool，含失败与重试。 */
    private int dispatches;

    /** 超过这个派发次数就报告"预算耗尽"；默认永不耗尽。 */
    private int budgetLimit = Integer.MAX_VALUE;

    // ------------------------------------------------------------------
    // 脚本配置（链式）
    // ------------------------------------------------------------------

    /** 让某节点固定返回一段文本。 */
    public FakeWorkflowRuntime output(String nodeId, String text) {
        outputs.put(nodeId, text);
        return this;
    }

    /** 让某节点按次序返回不同内容；超出长度后一直返回最后一个。 */
    public FakeWorkflowRuntime sequence(String nodeId, String... values) {
        sequences.put(nodeId, List.of(values));
        return this;
    }

    /** 让某节点的前 {@code times} 次尝试都抛异常。 */
    public FakeWorkflowRuntime failFirst(String nodeId, int times) {
        failTimes.put(nodeId, times);
        return this;
    }

    /** 让某节点次次失败（用来演示 continueOnError 与 retry 用尽）。 */
    public FakeWorkflowRuntime alwaysFail(String nodeId) {
        return failFirst(nodeId, Integer.MAX_VALUE);
    }

    /** 派发满 {@code limit} 次之后报告预算耗尽。 */
    public FakeWorkflowRuntime budget(int limit) {
        this.budgetLimit = limit;
        return this;
    }

    /** 记录 {@code callAgent} 收到的提示词（默认就记，这个方法只是让意图显式）。 */
    public FakeWorkflowRuntime recordPrompts() {
        return this;
    }

    // ------------------------------------------------------------------
    // 观测
    // ------------------------------------------------------------------

    /** 某节点被尝试执行的次数（含失败的尝试）。 */
    public int attempts(String nodeId) {
        AtomicInteger counter = attempts.get(nodeId);
        return counter == null ? 0 : counter.get();
    }

    /** 某节点最后一次收到的提示词。 */
    public String prompt(String nodeId) {
        return prompts.get(nodeId);
    }

    /** 某次 {@code callTool} 收到的、已经渲染好的参数。 */
    public Map<String, Object> toolArgs(String nodeId) {
        return toolArgs.get(nodeId);
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    public List<String> agentNodes() {
        return List.copyOf(agentNodes);
    }

    public List<String> toolNodes() {
        return List.copyOf(toolNodes);
    }

    public List<String> toolNames() {
        return List.copyOf(toolNames);
    }

    /** 派发总次数（含失败与重试）。 */
    public int dispatches() {
        return dispatches;
    }

    // ------------------------------------------------------------------
    // WorkflowRuntime
    // ------------------------------------------------------------------

    @Override
    public String callAgent(String prompt, WorkflowNode node) {
        dispatches++;
        agentNodes.add(node.id());
        prompts.put(node.id(), prompt);
        int attempt = begin(node.id());
        List<String> sequence = sequences.get(node.id());
        if (sequence != null && !sequence.isEmpty()) {
            return sequence.get(Math.min(attempt - 1, sequence.size() - 1));
        }
        return outputs.getOrDefault(node.id(), "agent-" + node.id());
    }

    @Override
    public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
        dispatches++;
        toolNodes.add(node.id());
        toolNames.add(tool);
        toolArgs.put(node.id(), args);
        int attempt = begin(node.id());
        List<String> sequence = sequences.get(node.id());
        if (sequence != null && !sequence.isEmpty()) {
            return sequence.get(Math.min(attempt - 1, sequence.size() - 1));
        }
        return outputs.getOrDefault(node.id(), "tool-" + tool);
    }

    @Override
    public void emit(String type, Object payload) {
        events.add(type);
    }

    @Override
    public boolean outOfBudget() {
        return dispatches >= budgetLimit;
    }

    /** 计数并决定这次是否失败；返回本次是第几次尝试（从 1 开始）。 */
    private int begin(String nodeId) {
        int attempt = attempts.computeIfAbsent(nodeId, key -> new AtomicInteger()).incrementAndGet();
        Integer limit = failTimes.get(nodeId);
        if (limit != null && attempt <= limit) {
            throw new IllegalStateException("模拟失败:" + nodeId + "#" + attempt);
        }
        return attempt;
    }
}
