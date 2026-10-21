package com.benxin.exp.wfnode;

import com.benxin.exp.wfnode.agent.ReleaseAgents;
import com.benxin.exp.wfnode.node.FlakyAuditHandler;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两张<b>不含 agent 节点</b>的图：它们一次模型调用都不发生，所以即使 LM Studio 没开也照跑。
 *
 * <p>这两张图的存在本身就是论点：Java 节点的失败语义、以及"业务规则恒定"这件事，
 * <b>与模型无关</b>，因此可以被确定性地、零成本地钉住。真实模型只出现在
 * {@link ContentReleaseTest} 里，那一张图才是"该模型做的事"。</p>
 *
 * <p>还有一个隐含断言：{@code usage.isEmpty()} 为真 —— 证明这两次运行确实没有联网。</p>
 */
@SpringBootTest(properties = {"exp.demo.enabled=false"})
@DisplayName("Java 节点 · 不依赖模型的图")
class LocalOnlyWorkflowsTest {

    @Autowired
    private ReleaseAgents.FaultAgent faultAgent;

    @Autowired
    private ReleaseAgents.GateAgent gateAgent;

    @Autowired
    private FlakyAuditHandler flakyAudit;

    private static WorkflowRun runOf(AgentResult result) {
        Object run = result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run).as("跑完之后应当有工作流轨迹").isInstanceOf(WorkflowRun.class);
        return (WorkflowRun) run;
    }

    // ------------------------------------------------------------------
    // 失败语义：retry + continueOnError
    // ------------------------------------------------------------------

    @Test
    @DisplayName("java-fault：audit 重试 2 次后成功，charge 失败但走补偿分支")
    void retryAndCompensation() {
        flakyAudit.reset();

        AgentResult result = faultAgent.run("交付前审计");

        assertThat(flakyAudit.attempts("audit")).as("retry: 3 应当让前两次失败后再试一次").isEqualTo(3);
        assertThat(runOf(result).visited())
                .as("失败的那次也在访问表里")
                .contains("audit", "charge", "compensate");
        assertThat(result.text())
                .contains("已进入补偿流程")
                .contains("磁盘配额不足");
        assertThat(result.usage().isEmpty()).as("这张图没有 agent 节点，不该产生任何 token").isTrue();
    }

    @Test
    @DisplayName("java-fault：清掉计数再跑一遍，行为完全一致（不依赖上一次的状态）")
    void retryIsRepeatableAfterReset() {
        flakyAudit.reset();
        String first = faultAgent.run("第一次").text();

        flakyAudit.reset();
        String second = faultAgent.run("第二次").text();

        // 两次的入参不同，所以只比较"结构"而不是全文
        assertThat(first.split("\n")).hasSameSizeAs(second.split("\n"));
        assertThat(second).contains("已进入补偿流程");
    }

    // ------------------------------------------------------------------
    // 合规闸门：两条分支都能确定性跑出来
    // ------------------------------------------------------------------

    @Test
    @DisplayName("compliance-gate：干净文案 → 放行")
    void cleanTextPasses() {
        AgentResult result = gateAgent.screen("这是一段干净的发布文案。");

        assertThat(result.text()).contains("放行").contains("通过");
        assertThat(result.usage().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("compliance-gate：命中敏感词 → 拦截，并说明命中了哪个词")
    void bannedWordIsRejected() {
        AgentResult result = gateAgent.screen("这段文案里混进了违禁词，要拦住。");

        assertThat(result.text())
                .contains("拦截")
                .contains("不通过")
                .contains("违禁词");
    }

    @Test
    @DisplayName("compliance-gate：超过字数上限 → 拦截，并给出实际字数")
    void tooLongIsRejected() {
        String longText = "很长的一段文案，".repeat(6);
        AgentResult result = gateAgent.screen(longText);

        assertThat(result.text()).contains("拦截").contains("超过上限");
    }
}
