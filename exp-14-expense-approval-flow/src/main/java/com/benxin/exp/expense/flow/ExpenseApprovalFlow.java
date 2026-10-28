package com.benxin.exp.expense.flow;

import com.benxin.exp.expense.node.AutoApproveNode;
import com.benxin.exp.expense.node.GradeNode;
import com.benxin.exp.expense.node.IntakeNode;
import com.benxin.exp.expense.node.RejectNode;
import com.benxin.exp.expense.node.SupplementNode;
import com.benxin.exp.expense.node.VoucherNode;
import com.benxin.llm.core.workflow.JavaFlow;
import com.benxin.llm.core.workflow.WorkflowDefinition;

/**
 * 差旅报销审批流程图 —— 用 {@link JavaFlow} 写成 Java，而不是 YAML。
 *
 * <pre>
 *   A 提交申请
 *   B 收单解析（代码：金额/发票号/校验位）
 *     ├─ REJECT    ─→ C1 退回通知 ─→ C2 结束（无法受理）
 *     ├─ NEED_INFO ─→ N 补件 ─┬─ 还能补 ─→ B（回边）
 *     │                       └─ 补件超限 ─→ O 结束（超时关闭）
 *     └─ OK ─→ D 类别识别（模型，输出契约）
 *                ─→ E 风险评估（模型，输出契约）
 *                ─→ F 分级（代码：金额 × 风险 → 审批线）
 *                     ├─ AUTO     ─→ G1 系统自动通过 ──┐
 *                     ├─ MANAGER  ─→ H 部门经理审批 ──┤
 *                     └─ DIRECTOR ─→ I 财务总监审批 ──┤
 *                                  ├─ 通过     ────────┴─→ L 记账凭证 ─→ L1 结束（通过）
 *                                  ├─ 驳回     ─→ M 驳回通知 ─→ M1 结束（驳回）
 *                                  └─ 需补充材料 ─→ N 补件（回边）
 * </pre>
 *
 * <p><b>为什么分段写？</b> 十几个节点的图如果写成一条长链，读者只能看到一屏 DSL 调用，
 * 看不到业务。这里一段一个方法，方法名就是这段图在做什么；{@link #approval()} 本身
 * 只剩六行。另一种可读性手段是 {@code Mermaid.toMermaid(...)} 把图导回去和设计稿比对
 * （演示会打印出来）。</p>
 *
 * <p><b>模型只出现在 D / E / H / I 四个节点</b>：识别类别、评估风险、给出审批结论。
 * 金额、校验、分级、记账全在代码节点里 —— 这条界线是这张图最想表达的东西。</p>
 */
public final class ExpenseApprovalFlow {

    private static final String CATEGORY_SYSTEM =
            "你是费用审核员。只输出一个费用类别，不要解释，不要输出思考过程。";
    private static final String RISK_SYSTEM =
            "你是财务风控人员。只输出一个风险等级，不要解释，不要输出思考过程。";
    private static final String MANAGER_SYSTEM =
            "你是部门经理，负责审批本部门差旅报销。只输出一个审批结论，不要解释。";
    private static final String DIRECTOR_SYSTEM =
            "你是财务总监，负责审批大额或高风险报销。只输出一个审批结论，不要解释。";

    private ExpenseApprovalFlow() {
    }

    /**
     * 组装整张图。
     *
     * <p>节点行为作为<b>对象</b>传进来（而不是配置里的名字），因此它们可以带依赖：
     * 制度阈值、会计科目服务、补件轮数上限都由外部注入。</p>
     */
    public static WorkflowDefinition build(IntakeNode intake,
                                           GradeNode grade,
                                           AutoApproveNode autoApprove,
                                           VoucherNode voucher,
                                           RejectNode reject,
                                           SupplementNode supplement) {
        JavaFlow flow = JavaFlow.builder("expense-approval")
                .description("差旅报销审批：收单解析 → 类别识别 → 风险评估 → 金额分级 → 分级审批 → 记账/驳回/补件");

        flow = intake(flow, intake, reject);
        flow = judge(flow, grade);
        flow = approval(flow, autoApprove);
        flow = settle(flow, voucher, reject);
        return supplement(flow, supplement).build();
    }

    /** A 提交 → B 收单解析；资料不全就补件，连金额都没有就直接退回。 */
    private static JavaFlow intake(JavaFlow flow, IntakeNode intake, RejectNode reject) {
        return flow.start("A", "差旅报销申请：\n${input}")
                .describe("A", "员工提交报销说明")
                .code("B", intake)
                .describe("B", "收单解析：抽金额与发票号、验校验位、对照制度上限")
                .chain("A", "B")
                // 分叉依据是 B 写进黑板的 claimStatus，而不是 B 的文本输出 —— 文本是给人看的
                .routeFrom("B", "claimStatus", c -> c
                        .caseOf("OK", "D")
                        .caseOf("NEED_INFO", "N")
                        .caseOf("REJECT", "C1"))
                .code("C1", reject)
                .describe("C1", "无法受理：生成退回通知")
                .end("C2", "【无法受理】\n${C1}")
                .chain("C1", "C2");
    }

    /** D 类别识别 → E 风险评估 → F 分级。两个模型节点都带输出契约，F 是纯代码。 */
    private static JavaFlow judge(JavaFlow flow, GradeNode grade) {
        return flow.agent("D", CATEGORY_SYSTEM,
                        "请判断下面这笔报销属于哪一类（交通 / 住宿 / 餐饮 / 其他）：\n${materials}",
                        "交通", "住宿", "餐饮", "其他")
                .describe("D", "费用类别识别（输出契约：四选一）")
                .agent("E", RISK_SYSTEM,
                        "请评估这笔差旅报销的风险等级（低 / 中 / 高）。\n"
                                + "金额：${amount} 元\n类别：${D}\n说明：\n${materials}",
                        "低", "中", "高")
                .describe("E", "风险评估（输出契约：三选一）")
                .chain("D", "E")
                .code("F", grade, JavaFlow.args("risk", "${E}", "amount", "${amount}"))
                .describe("F", "分级：风险 × 金额 → 自动 / 经理 / 总监")
                .chain("E", "F")
                // 四个分支全部显式列出：制度里没有"其他情况"，漏一个就是死胡同
                .routeFrom("F", "approvalPath", c -> c
                        .caseOf("AUTO", "G1")
                        .caseOf("MANAGER", "H")
                        .caseOf("DIRECTOR", "I")
                        .caseOf("ESCALATE", "I"));
    }

    /** 三条审批线：自动通过 / 部门经理 / 财务总监。两个人工节点都是三出口。 */
    private static JavaFlow approval(JavaFlow flow, AutoApproveNode autoApprove) {
        return flow.code("G1", autoApprove)
                .describe("G1", "系统自动通过（低风险 + 小额），不产生模型调用")
                .agent("H", MANAGER_SYSTEM,
                        "请以部门经理身份审批这笔差旅报销（通过 / 驳回 / 需补充材料）。\n"
                                + "金额：${amount} 元\n类别：${D}\n风险等级：${E}\n说明：\n${materials}",
                        "通过", "驳回", "需补充材料")
                .describe("H", "部门经理审批（输出契约：三选一）")
                .agent("I", DIRECTOR_SYSTEM,
                        "请以财务总监身份审批这笔差旅报销（通过 / 驳回 / 需补充材料）。\n"
                                + "金额：${amount} 元\n类别：${D}\n风险等级：${E}\n说明：\n${materials}",
                        "通过", "驳回", "需补充材料")
                .describe("I", "财务总监审批（输出契约：三选一）")
                .routeFrom("H", "H", c -> c
                        .caseOf("通过", "L")
                        .caseOf("驳回", "M")
                        .caseOf("需补充材料", "N"))
                .routeFrom("I", "I", c -> c
                        .caseOf("通过", "L")
                        .caseOf("驳回", "M")
                        .caseOf("需补充材料", "N"))
                .chain("G1", "L");
    }

    /** 通过 → L 记账；驳回 → M 通知。两个终点各自收口。 */
    private static JavaFlow settle(JavaFlow flow, VoucherNode voucher, RejectNode reject) {
        return flow.code("L", voucher, JavaFlow.args("category", "${D}", "amount", "${amount}"))
                .describe("L", "生成记账凭证（科目由类别映射，凭证号由流水生成）")
                .end("L1", "【审批通过】\n${L}")
                .chain("L", "L1")
                .code("M", reject)
                .describe("M", "生成驳回通知")
                .end("M1", "【审批驳回】\n${M}")
                .chain("M", "M1");
    }

    /**
     * 补件回边：N 补件 → N1 判断还能不能补 → 能则回到 B 重新解析，不能则超时关闭。
     *
     * <p>回边回到的是<b>收单节点 B</b> 而不是入口 A：补件材料是通过 {@code pendingSupplement}
     * 变量拼进解析输入的，B 会重新把"原文 + 已补材料"完整解析一遍。这样"补件后"与
     * "首次提交"走的是同一段代码，不会出现两条逐渐分叉的逻辑。</p>
     */
    private static JavaFlow supplement(JavaFlow flow, SupplementNode supplement) {
        return flow.code("N", supplement, JavaFlow.args("issues", "${issues}", "attempt", "${attempt}"))
                .describe("N", "生成补件清单（模拟申请人补交材料）")
                .branchOn("N1", "supplemented", c -> c
                        .caseOf("true", "B")
                        .otherwise("O"))
                .chain("N", "N1")
                .end("O", "【补件超时关闭】\n${supplementNote}");
    }
}
