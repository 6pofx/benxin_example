package com.benxin.exp.expense.node;

import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.util.Map;

/**
 * 自动通过：低风险 + 小额，系统直接放行，不惊动任何人。
 *
 * <p>这一步<b>没有模型调用</b>，却是一个真实的决策点 —— 它把"制度允许自动放行"这件事
 * 变成了图上的一个节点，而不是藏在某个 if 里。审批审计时要回答"这笔为什么没人批"，
 * 看图上走了哪个节点就够了。</p>
 */
public class AutoApproveNode implements WorkflowHandler {

    @Override
    public NodeOutput handle(NodeInput input) {
        String amount = input.variable("amount");
        return NodeOutput.of(
                "系统自动通过（金额 " + amount + " 元，低风险且未超过自动通过线）",
                Map.of("approver", "系统自动",
                        "approved", true));
    }
}
