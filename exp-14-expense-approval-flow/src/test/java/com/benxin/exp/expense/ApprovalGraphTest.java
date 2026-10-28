package com.benxin.exp.expense;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.expense.flow.ExpenseApprovalFlow;
import com.benxin.exp.expense.node.AutoApproveNode;
import com.benxin.exp.expense.node.GradeNode;
import com.benxin.exp.expense.node.IntakeNode;
import com.benxin.exp.expense.node.RejectNode;
import com.benxin.exp.expense.node.SupplementNode;
import com.benxin.exp.expense.node.VoucherNode;
import com.benxin.exp.expense.rule.AccountingService;
import com.benxin.exp.expense.rule.ExpensePolicy;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.CodeFlowLoop;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.Mermaid;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图的形状与每条路径的走向。
 *
 * <p><b>这里用脚本模型，与"演示只用真模型"不矛盾</b>：本类验证的是<b>图的接线</b>
 * （哪条结论该走哪条边、回边会不会真的绕回去、无法受理时会不会误调模型），
 * 这些必须是确定性的 —— 真模型的判断本身就不该被钉死在测试里。
 * 真实模型的端到端验证在 {@link ExpenseApprovalRealTest}，演示在
 * {@code ExpenseApprovalDemo}，两者都只连 LM Studio。</p>
 *
 * <p>整类跑在纯 JUnit 上：不启 Spring、不连网络。</p>
 */
@DisplayName("报销审批图（形状与路径）")
class ApprovalGraphTest {

    private static final int SINGLE_LIMIT = 20_000;
    private static final int AUTO_LIMIT = 2_000;
    private static final int MANAGER_LIMIT = 10_000;
    private static final int ROUNDS = 2;

    private static final String SMALL_CLAIM =
            "销售部 张伟，2026-10-12 出差上海参加客户拜访。高铁票 380 元，发票号 FP20261012004。";
    private static final String MID_CLAIM =
            "销售部 李娜，2026-10-12 至 2026-10-14 出差杭州。酒店 2 晚共 3600 元，发票号 FP20261012015。";
    private static final String BIG_CLAIM =
            "技术部 王强，2026-10-15 至 2026-10-18 出差深圳。机票 8600 元，酒店 4200 元，发票号 FP20261012026。";
    private static final String NO_INVOICE_CLAIM =
            "市场部 赵敏，2026-10-20 出差成都参加展会。餐饮费 520 元，发票稍后补。";
    private static final String HOPELESS_CLAIM = "上个月出差花了一些钱，具体多少记不清了，帮忙报一下。";

    // ------------------------------------------------------------------
    // 组装（与 Spring 里那份完全同构，只是依赖手动 new）
    // ------------------------------------------------------------------

    private static WorkflowDefinition flow() {
        ExpensePolicy policy = new ExpensePolicy(SINGLE_LIMIT, AUTO_LIMIT, MANAGER_LIMIT, ROUNDS);
        AccountingService accounting = new AccountingService();
        return ExpenseApprovalFlow.build(
                new IntakeNode(policy),
                new GradeNode(policy),
                new AutoApproveNode(),
                new VoucherNode(accounting),
                new RejectNode(),
                new SupplementNode(ROUNDS));
    }

    /**
     * 造一个"按类别/风险/结论回话"的脚本模型。
     *
     * <p>规则顺序有讲究：审批节点的提示词里同时出现"部门经理身份"和"风险等级：低"，
     * 因此身份规则必须排在风险规则之前，否则会被风险规则抢先命中。</p>
     */
    private static ScriptedLlmModel model(String category, String risk, String verdict) {
        return ScriptedLlmModel.builder("scripted")
                .rule("属于哪一类", category)
                .rule("部门经理身份", verdict)
                .rule("财务总监身份", verdict)
                .rule("风险等级", risk)
                .build();
    }

    private static AgentResult run(ScriptedLlmModel model, String claim) {
        Agent agent = Agent.builder("expense-test")
                .model(model)
                .loop(CodeFlowLoop.of(flow()))
                .maxSteps(8)
                .build();
        return agent.call(claim);
    }

    private static WorkflowRun runOf(AgentResult result) {
        return (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
    }

    // ------------------------------------------------------------------
    // 三条审批线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("低风险小额 → 系统自动通过：经过 G1，不经过任何审批人节点")
    void autoApprovePath() {
        ScriptedLlmModel model = model("交通", "低", "通过");
        AgentResult result = run(model, SMALL_CLAIM);

        assertThat(runOf(result).visited()).containsExactly("A", "B", "D", "E", "F", "G1", "L", "L1");
        assertThat(result.text()).contains("【记账凭证】").contains("差旅费-城市间交通");
        // 两次模型往返：类别 + 风险；自动通过不额外调模型
        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("中等金额 → 部门经理：经过 H，不经过总监")
    void managerPath() {
        ScriptedLlmModel model = model("住宿", "低", "通过");
        AgentResult result = run(model, MID_CLAIM);

        assertThat(runOf(result).visited()).containsExactly("A", "B", "D", "E", "F", "H", "L", "L1");
        assertThat(result.text()).contains("差旅费-住宿费").contains("部门经理");
    }

    @Test
    @DisplayName("大额 → 财务总监：经过 I")
    void directorPath() {
        ScriptedLlmModel model = model("交通", "中", "通过");
        AgentResult result = run(model, BIG_CLAIM);

        assertThat(runOf(result).visited()).containsExactly("A", "B", "D", "E", "F", "I", "L", "L1");
        assertThat(result.text()).contains("财务总监");
    }

    @Test
    @DisplayName("审批驳回 → M 驳回通知，不产生凭证")
    void rejectionPath() {
        ScriptedLlmModel model = model("餐饮", "低", "驳回");
        AgentResult result = run(model, MID_CLAIM);

        assertThat(runOf(result).visited()).containsExactly("A", "B", "D", "E", "F", "H", "M", "M1");
        assertThat(result.text()).contains("【驳回通知】").contains("部门经理");
        assertThat(result.text()).doesNotContain("记账凭证");
    }

    // ------------------------------------------------------------------
    // 回边与提前退出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺发票号 → 补件 → 回边重新解析 → 继续走完整流程（B 走了两次）")
    void supplementLoopsBackToIntake() {
        ScriptedLlmModel model = model("餐饮", "低", "通过");
        AgentResult result = run(model, NO_INVOICE_CLAIM);

        assertThat(runOf(result).visited())
                .containsExactly("A", "B", "N", "N1", "B", "D", "E", "F", "G1", "L", "L1");
        // 回边让收单节点跑了两次：第一次 NEED_INFO，第二次拿补件材料重新解析通过
        assertThat(runOf(result).visited().stream().filter("B"::equals).count()).isEqualTo(2);
        assertThat(result.text()).contains("【记账凭证】");
    }

    @Test
    @DisplayName("连金额都没有 → 无法受理：C1/C2 结束，一次模型都不调用")
    void hopelessClaimNeverCallsModel() {
        ScriptedLlmModel model = model("其他", "低", "通过");
        AgentResult result = run(model, HOPELESS_CLAIM);

        assertThat(runOf(result).visited()).containsExactly("A", "B", "C1", "C2");
        assertThat(model.calls()).isZero();
        assertThat(result.text()).contains("【退回通知】").contains("无法受理");
    }

    // ------------------------------------------------------------------
    // 结构、导出与不可序列化
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("图的静态结构")
    class Structure {

        @Test
        @DisplayName("17 个节点 / 23 条边 / 4 个出口")
        void shapeIsStable() {
            WorkflowDefinition definition = flow();

            assertThat(definition.nodes()).hasSize(17);
            assertThat(definition.edges()).hasSize(23);
            assertThat(definition.nodes().stream()
                    .filter(node -> "end".equals(node.type().wireName())))
                    .hasSize(4);
        }

        @Test
        @DisplayName("Mermaid 导出：flowchart TD、分支标签来自 caseOf、回边指向 B")
        void exportsMermaid() {
            String mermaid = Mermaid.toMermaid(flow());

            assertThat(mermaid).startsWith("flowchart TD");
            assertThat(mermaid).contains("B -->|\"OK\"| D");
            assertThat(mermaid).contains("G1 --> L");
            // 回边在图上看得见：N1 判 true 就回到收单节点
            assertThat(mermaid).contains("N1 -->|\"true\"| B");
        }

        @Test
        @DisplayName("代码编排不可序列化：WorkflowIo 明确拒绝，而不是写出一份会变形的文件")
        void refusesSerialization() {
            assertThatThrownBy(() -> WorkflowIo.writeJson(flow()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
