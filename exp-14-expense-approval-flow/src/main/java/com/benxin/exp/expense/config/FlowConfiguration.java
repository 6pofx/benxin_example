package com.benxin.exp.expense.config;

import com.benxin.exp.expense.flow.ExpenseApprovalFlow;
import com.benxin.exp.expense.node.AutoApproveNode;
import com.benxin.exp.expense.node.GradeNode;
import com.benxin.exp.expense.node.IntakeNode;
import com.benxin.exp.expense.node.RejectNode;
import com.benxin.exp.expense.node.SupplementNode;
import com.benxin.exp.expense.node.VoucherNode;
import com.benxin.exp.expense.rule.AccountingService;
import com.benxin.exp.expense.rule.ExpensePolicy;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.CodeFlowLoop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把代码编排的图注册成一个 Loop。
 *
 * <p>注册名是 {@code flow}（{@link CodeFlowLoop#NAME}），于是 {@code @LlmAgent(loop = "flow")}
 * 就能按名字引用它 —— 与 YAML 模式的 {@code workflow:<key>} 在使用方式上是对称的：
 * <b>声明式接口那边完全不知道图是代码写的还是配置写的</b>。</p>
 *
 * <p>节点是这里 new 出来的普通对象，依赖从容器注入：制度阈值来自
 * {@link ExpensePolicy}，科目与凭证号来自 {@link AccountingService}。
 * 换一套阈值、换一个会计系统实现，只要改这两个 bean，图的形状一行不用动。</p>
 */
@Configuration(proxyBeanMethods = false)
public class FlowConfiguration {

    @Bean
    public AgentLoop expenseApprovalFlow(ExpensePolicy policy, AccountingService accounting) {
        IntakeNode intake = new IntakeNode(policy);
        GradeNode grade = new GradeNode(policy);
        AutoApproveNode autoApprove = new AutoApproveNode();
        VoucherNode voucher = new VoucherNode(accounting);
        RejectNode reject = new RejectNode();
        SupplementNode supplement = new SupplementNode(policy.maxSupplementRounds());

        return CodeFlowLoop.of(ExpenseApprovalFlow.build(
                intake, grade, autoApprove, voucher, reject, supplement));
    }
}
