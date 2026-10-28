package com.benxin.exp.expense.node;

import com.benxin.exp.expense.rule.AccountingService;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记账：审批通过后生成凭证。
 *
 * <p>这是整张图的"落账"环节，也是<b>绝对不能由模型做</b>的一步：科目映射与凭证号
 * 一旦错，账就平不了。类别由模型判断（它擅长），科目由代码映射（表是死的）。</p>
 *
 * <p>它演示了代码编排的一个直接好处：节点是普通对象，构造时注入
 * {@link AccountingService}，测试里换一个实现即可，图的形状一行不用改。</p>
 */
public class VoucherNode implements WorkflowHandler {

    private final AccountingService accounting;

    public VoucherNode(AccountingService accounting) {
        this.accounting = accounting;
    }

    @Override
    public NodeOutput handle(NodeInput input) {
        String category = input.arg("category").trim();
        String amount = input.arg("amount").trim();
        String department = input.variable("department");
        // 审批人由"走的哪条审批线"决定，而不是由一个可能不存在的变量决定：
        // 自动通过的路径上没有经理/总监，硬引用 ${H} 只会渲染出字面量
        String approver = approverOf(input.variable("approvalPath"));

        String subject = accounting.subjectFor(category);
        String voucherNo = accounting.nextVoucherNo();

        String text = """
                【记账凭证】%s
                　科目：%s
                　金额：%s 元
                　部门：%s
                　审批人：%s
                　费用类别：%s""".formatted(voucherNo, subject, amount,
                department.isEmpty() ? "?" : department, approver, category);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("voucherNo", voucherNo);
        vars.put("subject", subject);
        vars.put("voucher", text);
        vars.put("approver", approver);
        return NodeOutput.of(text, vars);
    }

    /** 审批线 → 审批人署名。自动通过的是系统，其余是制度规定的两级审批人。 */
    private static String approverOf(String approvalPath) {
        return switch (approvalPath == null ? "" : approvalPath) {
            case "AUTO" -> "系统自动";
            case "MANAGER" -> "部门经理";
            case "DIRECTOR", "ESCALATE" -> "财务总监";
            default -> "审批人";
        };
    }
}
