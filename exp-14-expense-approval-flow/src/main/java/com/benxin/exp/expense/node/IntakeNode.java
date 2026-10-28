package com.benxin.exp.expense.node;

import com.benxin.exp.expense.domain.ExpenseClaim;
import com.benxin.exp.expense.rule.ClaimParser;
import com.benxin.exp.expense.rule.ExpensePolicy;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收单与解析：自由文本 → 结构化报销单。
 *
 * <p>它是图里第一个代码节点，也决定了整张图的分叉依据 —— 输出的 {@code claimStatus}
 * 就是下游 {@code caseOf} 匹配的值。</p>
 *
 * <p><b>补件怎么进来</b>：员工补的材料不修改原始任务文本，而是写进 {@code pendingSupplement}
 * 变量；本节点每次都把"原始说明 + 已补材料"拼起来重新解析。这样回边回到本节点时，
 * 走的是同一段解析逻辑，而不是另写一条"补件后处理"的岔路。</p>
 */
public class IntakeNode implements WorkflowHandler {

    private final ExpensePolicy policy;

    public IntakeNode(ExpensePolicy policy) {
        this.policy = policy;
    }

    @Override
    public NodeOutput handle(NodeInput input) {
        String original = input.input();
        String supplement = input.variable("pendingSupplement");
        int attempt = attempt(input.variableRaw("attempt"));

        // 原始说明 + 已补材料；第一次跑时 supplement 为空
        String materials = supplement.isBlank() ? original : original + "\n" + supplement;
        ExpenseClaim claim = ClaimParser.parse(materials, policy.singleClaimLimit());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("materials", materials);
        vars.put("department", claim.department());
        vars.put("period", claim.period());
        // 金额用字符串交给模板：模板只做插值，${amount} 到了下游就是字符串，需要数字的地方自己转
        vars.put("amount", claim.amount().stripTrailingZeros().toPlainString());
        vars.put("invoiceCount", claim.invoices().size());
        vars.put("issues", String.join("；", claim.issues()));
        vars.put("claimStatus", claim.status());
        vars.put("attempt", attempt);

        return NodeOutput.of(claim.summary(), vars);
    }

    private static int attempt(Object raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
