package com.benxin.exp.expense.node;

import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 驳回：把"谁驳的、为什么"写成一条能发给申请人的通知。
 *
 * <p>注意它<b>不接参数</b>，而是直接从黑板里读 —— 因为它要读的东西是"哪条路径走到这儿的"：
 * 经理驳的会写 {@code ${H}}、总监驳的会写 {@code ${I}}，只有其中一个存在。
 * 如果硬把 {@code ${H}} 塞进参数，走总监路径时模板会把它原样渲染成字面量
 * （{} 的规则是"解析不到就保留原文"），通知里就会出现一个 {@code ${H}}。</p>
 *
 * <p>这也顺带示范了一个原则：<b>需要"哪条路走过"的信息，就别用参数，用黑板。</b></p>
 */
public class RejectNode implements WorkflowHandler {

    @Override
    public NodeOutput handle(NodeInput input) {
        String path = input.variable("approvalPath");
        String manager = input.variable("H");
        String director = input.variable("I");
        String risk = input.variable("riskLevel");
        String amount = input.variable("amount");
        String issues = input.variable("issues");

        // 同一个节点被两条路复用：收单阶段"无法受理"与审批阶段"驳回"。
        // 用 approvalPath 是否为空来区分 —— 它为空说明还没走到分级，压根没人审批过。
        boolean intakeRejected = path.isBlank();
        String by = intakeRejected ? "受理岗" : switch (path) {
            case "MANAGER" -> "部门经理";
            case "DIRECTOR", "ESCALATE" -> "财务总监";
            default -> "审批人";
        };
        String conclusion = !manager.isBlank() ? manager : director;

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("rejectedBy", by);
        vars.put("rejectReason", conclusion);
        vars.put("approved", false);

        String title = intakeRejected ? "【退回通知】" : "【驳回通知】";
        String verdict = intakeRejected ? "无法受理（资料不足以进入审批）"
                : (conclusion.isBlank() ? "驳回" : conclusion);

        String text = """
                %s
                　处理人：%s
                　金额：%s 元%s
                　结论：%s
                　说明：%s""".formatted(
                title,
                by,
                amount,
                risk.isBlank() ? "" : "（风险等级 " + risk + "）",
                verdict,
                issues.isBlank() ? "资料齐全，但审批未通过" : issues);

        return NodeOutput.of(text, vars);
    }
}
