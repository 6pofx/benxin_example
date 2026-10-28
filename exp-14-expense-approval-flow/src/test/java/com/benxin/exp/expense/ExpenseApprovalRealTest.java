package com.benxin.exp.expense;

import com.benxin.exp.common.local.LocalProbe;
import com.benxin.exp.expense.agent.ExpenseAgents;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实端到端：本机 LM Studio 上的模型跑完整条报销审批流程。
 *
 * <p><b>这是本模块唯一连模型的测试</b>，端点不可用时整类 skip 而不是 fail：
 * 同事 clone 下来没装 LM Studio、CI 上没有桌面环境，属于"可选依赖不可用"，
 * 不该让一个演示工程变红。</p>
 *
 * <p>断言刻意分成两层：</p>
 * <ul>
 *   <li><b>确定性的事实</b>（结构、轨迹、账务字段）做精确断言；</li>
 *   <li><b>模型的判断</b>（类别、风险、结论）只断言"内部自洽" ——
 *       比如轨迹必须与 {@code approvalPath} 对得上。把小模型的判断钉死，
 *       测试就会变成"今天模型心情如何"的记录，而不是代码质量的证据。</li>
 * </ul>
 */
@SpringBootTest(properties = {"exp.demo.enabled=false", "exp.demo.print-graph=false"})
@DisplayName("报销审批 · 真实本机模型")
class ExpenseApprovalRealTest {

    @Autowired
    private ExpenseAgents.ExpenseAgent agent;

    @Value("${exp.local.base-url:http://127.0.0.1:1234}")
    private String baseUrl;

    @Value("${exp.local.model:qwen/qwen3-vl-8b}")
    private String model;

    @BeforeEach
    void requireLocalModel() {
        LocalProbe.Result probe = LocalProbe.probe(baseUrl);
        Assumptions.assumeTrue(probe.reachable(),
                () -> "本机 LM Studio 不可用，跳过真实调用测试：" + probe.describe(baseUrl));
    }

    private static WorkflowRun runOf(AgentResult result) {
        Object run = result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run).as("跑完之后应当有工作流轨迹").isInstanceOf(WorkflowRun.class);
        return (WorkflowRun) run;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> varsOf(AgentResult result) {
        return (Map<String, Object>) result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);
    }

    @Test
    @DisplayName("资料齐全的小额报销：模型给出类别与风险，代码算出审批线与凭证")
    void smallClaimGoesThroughRealModel() {
        AgentResult result = agent.approve(
                "销售部 张伟，2026-10-12 出差上海参加客户拜访。高铁票 380 元，发票号 FP20261012004。");

        assertThat(result.modelName()).isEqualTo("local-lmstudio");
        assertThat(result.usage().isEmpty()).as("真实调用一定有 token 用量").isFalse();

        List<String> visited = runOf(result).visited();
        assertThat(visited).startsWith("A", "B");

        Map<String, Object> vars = varsOf(result);
        // 模型负责的字段：断言"有一个值"，而不是断言具体是哪个值
        assertThat(vars.get("D")).as("类别应当被模型判出来").isIn("交通", "住宿", "餐饮", "其他");
        assertThat(vars.get("E")).as("风险等级应当被模型判出来").isIn("低", "中", "高");
        // 代码负责的字段：金额必须精确（模型完全不参与）
        assertThat(vars.get("amount")).isEqualTo("380");

        // 轨迹必须与分级结果自洽 —— 这是"图的接线对不对"最可靠的实时断言
        String path = String.valueOf(vars.get("approvalPath"));
        switch (path) {
            case "AUTO" -> {
                assertThat(visited).contains("G1");
                assertThat(result.text()).contains("系统自动");
            }
            case "MANAGER" -> assertThat(visited).contains("H");
            case "DIRECTOR", "ESCALATE" -> assertThat(visited).contains("I");
            default -> throw new AssertionError("未知的审批线：" + path);
        }
        assertThat(result.text()).contains("记账凭证");
    }

    @Test
    @DisplayName("缺发票号：先补件再回边重新解析，最终仍然落到凭证")
    void missingInvoiceIsSupplementedThenApproved() {
        AgentResult result = agent.approve(
                "市场部 赵敏，2026-10-20 出差成都参加展会。餐饮费 520 元，发票稍后补。");

        List<String> visited = runOf(result).visited();
        // 回边的证据：收单节点 B 出现了两次，且中间经过补件节点 N
        assertThat(visited.indexOf("N")).as("应当先走补件").isPositive();
        assertThat(visited.stream().filter("B"::equals).count())
                .as("补件后应当回到 B 重新解析").isEqualTo(2);
        assertThat(result.text()).contains("记账凭证");
        assertThat(varsOf(result).get("amount")).isEqualTo("520");
    }

    @Test
    @DisplayName("连金额都没有：无法受理，且不产生任何模型调用")
    void hopelessClaimUsesNoTokens() {
        AgentResult result = agent.approve("上个月出差花了一些钱，具体多少记不清了，帮忙报一下。");

        assertThat(runOf(result).visited()).containsExactly("A", "B", "C1", "C2");
        assertThat(result.usage().totalTokens()).as("无法受理的路径不该花 token").isZero();
        assertThat(result.text()).contains("退回通知");
    }
}
