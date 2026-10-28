package com.benxin.exp.expense.node;

import com.benxin.exp.expense.rule.ExpensePolicy;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 分级：把"模型给的风险等级"和"解析出来的金额"合起来，算出该走哪条审批线。
 *
 * <p>为什么要有这一步，而不是直接在边条件里写 {@code v.raw("risk")...}？
 * 因为分级规则是<b>制度</b>：它需要被单独测试、需要能改阈值、需要在日志里说得清。
 * 塞进谓词里，规则就散落在图的边上，谁也不敢动。</p>
 *
 * <p>注意它只<b>写结论</b>（{@code approvalPath}），不决定下一条边 ——
 * 走哪条边仍由 {@code routeFrom} 的 {@code caseOf} 决定。这与 Java 节点的边界是一致的：
 * 处理逻辑与路由分开，图才读得懂。</p>
 */
public class GradeNode implements WorkflowHandler {

    private final ExpensePolicy policy;

    public GradeNode(ExpensePolicy policy) {
        this.policy = policy;
    }

    @Override
    public NodeOutput handle(NodeInput input) {
        String risk = input.arg("risk").trim();
        BigDecimal amount = parseAmount(input.arg("amount"));
        String path = policy.route(risk, amount);

        return NodeOutput.of(
                "风险=%s，金额=%s 元 ⇒ 审批线=%s".formatted(
                        risk.isEmpty() ? "?" : risk,
                        amount.stripTrailingZeros().toPlainString(),
                        path),
                Map.of("approvalPath", path,
                        "riskLevel", risk,
                        "amountValue", amount.stripTrailingZeros().toPlainString()));
    }

    private static BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (RuntimeException e) {
            // 金额解析不出来时按 0 处理：分级会落到 AUTO，但上游 intake 已经拦过一次，
            // 真走到这里说明是异常输入，让流程继续比抛异常更适合演示
            return BigDecimal.ZERO;
        }
    }
}
