package com.benxin.exp.wfnode;

import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试用的假运行时：<b>不调模型、不调工具</b>，只把 {@code callHandler} 转给注册表。
 *
 * <p>它证明"Java 节点是引擎能力，不是 Spring 能力"：引擎只依赖
 * {@link WorkflowRuntime} 这个接口，于是 args 渲染、两条输出通道、失败传播、
 * retry 与 continueOnError 全部可以在"没有容器、没有模型、没有网络"的前提下被断言。</p>
 */
final class FakeNodeRuntime implements WorkflowRuntime {

    private final WorkflowHandlerRegistry handlers;
    private final List<String> events = new ArrayList<>();
    private final List<String> handlerCalls = new ArrayList<>();

    FakeNodeRuntime(WorkflowHandlerRegistry handlers) {
        this.handlers = handlers;
    }

    @Override
    public String callAgent(String prompt, WorkflowNode node) {
        return "agent:" + node.id() + ":" + prompt;
    }

    @Override
    public String callTool(String tool, Map<String, Object> args, WorkflowNode node) {
        return "tool:" + tool;
    }

    @Override
    public NodeOutput callHandler(String handler, NodeInput input, WorkflowNode node) {
        handlerCalls.add(handler + "@" + input.nodeId());
        // 与真实运行时一致：找不到就抛，由引擎转成带节点 id 的失败
        return handlers.require(handler).handle(input);
    }

    @Override
    public void emit(String type, Object payload) {
        events.add(type);
    }

    @Override
    public boolean outOfBudget() {
        return false;
    }

    List<String> events() {
        return List.copyOf(events);
    }

    List<String> handlerCalls() {
        return List.copyOf(handlerCalls);
    }
}
