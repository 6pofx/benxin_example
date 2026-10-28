package com.benxin.exp.expense.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.local.LocalProbe;
import com.benxin.exp.expense.agent.ExpenseAgents;
import com.benxin.exp.expense.rule.AccountingService;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.Mermaid;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 演示 14 主线：五个真实报销场景跑同一张代码编排的图。
 *
 * <p>五个场景覆盖这张图的四条出口与一条回边：</p>
 * <ol>
 *   <li>小额 + 资料齐全 → 系统自动通过（<b>零审批人，但有两次模型判断</b>）；</li>
 *   <li>中等金额 → 部门经理审批；</li>
 *   <li>大额 → 财务总监审批；</li>
 *   <li>缺发票号 → 补件 → <b>回边重新解析</b> → 通过；</li>
 *   <li>连金额都没有 → 无法受理（这条路径<b>一次模型都不调用</b>）。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "exp.demo.enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseApprovalDemo implements ApplicationRunner {

    /** 一张合法发票号：由 10 位主体 + 校验位组成（见 InvoiceNumbers）。 */
    private static final String INVOICE = "FP20261012004";

    private final ExpenseAgents.ExpenseAgent agent;
    private final AgentLoop flowLoop;
    private final AccountingService accounting;
    private final String baseUrl;
    private final String model;
    private final boolean printGraph;

    public ExpenseApprovalDemo(ExpenseAgents.ExpenseAgent agent,
                               AgentLoop flowLoop,
                               AccountingService accounting,
                               @Value("${exp.local.base-url:http://127.0.0.1:1234}") String baseUrl,
                               @Value("${exp.local.model:qwen/qwen3-vl-8b}") String model,
                               @Value("${exp.demo.print-graph:true}") boolean printGraph) {
        this.agent = agent;
        this.flowLoop = flowLoop;
        this.accounting = accounting;
        this.baseUrl = baseUrl;
        this.model = model;
        this.printGraph = printGraph;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 14 · 代码编排（flow）—— 差旅报销审批");
        LocalProbe.Result probe = LocalProbe.probe(baseUrl);
        demo.banner("模型：%s（本机 LM Studio）", model);
        demo.info("端点：%s", probe.describe(baseUrl));
        if (!probe.contains(model)) {
            demo.warn("列表里暂时没看到 %s —— LM Studio 会按需装载，首次调用可能要多等几秒", model);
        }

        showGraph(demo);
        runAll(demo);
        summarize(demo);
        demo.done();
    }

    // ------------------------------------------------------------------
    // 图长什么样
    // ------------------------------------------------------------------

    private void showGraph(Demo demo) {
        demo.section("这张图：代码写拓扑，Mermaid 导出来对设计稿");
        WorkflowDefinition definition = flowLoop instanceof WorkflowLoop workflow
                ? workflow.definition().orElse(null)
                : null;
        if (definition == null) {
            demo.warn("拿不到图定义（%s）", flowLoop.getClass().getSimpleName());
            return;
        }
        demo.ok("节点 %d 个 / 边 %d 条 / 出口 %d 个 —— 全部写在 %s 里",
                definition.nodes().size(), definition.edges().size(),
                definition.nodes().stream()
                        .filter(node -> node.type().wireName().equals("end")).count(),
                "flow/ExpenseApprovalFlow.java");
        if (printGraph) {
            demo.info("  Mermaid.toMermaid(definition) 导出的图（粘到任何支持 Mermaid 的地方即可渲染）：");
            for (String line : Mermaid.toMermaid(definition).split("\n")) {
                demo.info("    %s", line);
            }
        }
    }

    // ------------------------------------------------------------------
    // 五个场景
    // ------------------------------------------------------------------

    private void runAll(Demo demo) {
        scenario(demo, "场景一：小额 + 资料齐全（预期：系统自动通过）",
                "销售部 张伟，2026-10-12 出差上海参加客户拜访。高铁票 380 元，发票号 " + INVOICE + "。");

        scenario(demo, "场景二：中等金额（预期：部门经理审批）",
                "销售部 李娜，2026-10-12 至 2026-10-14 出差杭州。酒店 2 晚共 3600 元，发票号 FP20261012015。");

        scenario(demo, "场景三：大额（预期：财务总监审批）",
                "技术部 王强，2026-10-15 至 2026-10-18 出差深圳。机票 8600 元，酒店 4200 元，发票号 FP20261012026。");

        scenario(demo, "场景四：缺发票号（预期：补件 → 回边重新解析 → 通过）",
                "市场部 赵敏，2026-10-20 出差成都参加展会。餐饮费 520 元，发票稍后补。");

        scenario(demo, "场景五：写法太随意、连金额都没有（预期：无法受理）",
                "上个月出差花了一些钱，具体多少记不清了，帮忙报一下。");
    }

    private void scenario(Demo demo, String title, String claim) {
        demo.section(title);
        demo.info("  申请人原文：%s", claim);

        demo.guard(title, () -> {
            long started = System.currentTimeMillis();
            AgentResult result = agent.approve(claim);
            long cost = System.currentTimeMillis() - started;

            WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
            @SuppressWarnings("unchecked")
            Map<String, Object> vars =
                    (Map<String, Object>) result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);

            demo.info("  走过的节点：%s%s",
                    run == null ? "（无轨迹）" : run.visited(),
                    run != null && run.distinctVisited().size() < run.visited().size()
                            ? "（含回边，回边让某个节点跑了两次）" : "");
            demo.ok("  金额：%s 元 ｜ 类别：%s ｜ 风险：%s ｜ 审批线：%s",
                    text(vars, "amount"), text(vars, "D"), text(vars, "E"), text(vars, "approvalPath"));
            demo.ok("  结论：%s", firstLines(result.text(), 4));
            demo.info("  模型调用 %d 次，用量 %d tokens（in %d / out %d），耗时 %d ms（模型推理 %d ms）",
                    result.steps(), result.usage().totalTokens(),
                    result.usage().inputTokens(), result.usage().outputTokens(),
                    cost, result.durationMillis());
        });
    }

    private void summarize(Demo demo) {
        demo.section("小结：这张图把什么交给了模型，什么没有");
        demo.info("  交给模型的：费用类别、风险等级、审批结论 —— 都是'判断'，且都带输出契约（只能吐枚举值）。");
        demo.info("  没交给模型的：金额解析、发票校验位、分级、科目映射、凭证号 —— 都是有唯一答案的'规则'。");
        demo.info("  回边：补件不另写一条路，而是把补的材料拼进解析输入、绕回收单节点重跑同一段逻辑。");
        demo.info("  图：%s", flowLoop.description());
        accounting.reset();
    }

    // ------------------------------------------------------------------

    private static String text(Map<String, Object> vars, String key) {
        Object value = vars == null ? null : vars.get(key);
        return value == null || String.valueOf(value).isBlank() ? "—" : String.valueOf(value);
    }

    private static String firstLines(String text, int lines) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        List<String> all = List.of(text.split("\n"));
        return String.join(" / ", all.subList(0, Math.min(lines, all.size()))).strip();
    }
}
