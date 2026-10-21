package com.benxin.exp.wfnode;

import com.benxin.exp.wfnode.agent.ReleaseAgents;
import com.benxin.exp.wfnode.config.LocalEndpoint;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowRun;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实调用：本机 LM Studio 上的 Qwen3-VL-8B 参与主演示图。
 *
 * <p><b>端点不可用时整类 skip</b>（{@link Assumptions#assumeTrue}）：本模块刻意不提供离线 mock，
 * 所以"没开 LM Studio"时正确的姿态是跳过、并说明原因，而不是偷偷换一个脚本模型假装跑过。</p>
 *
 * <p>断言分成两半，这个分界正是这张图要讲的事：</p>
 * <ul>
 *   <li><b>模型负责的部分</b>只断言"结构事实"（有摘要、非空、与原文不同）——
 *       小模型的措辞每次都变，钉死措辞的测试只会变成噪声；</li>
 *   <li><b>Java 节点负责的部分</b>则做精确断言：{@code chars} 必须等于正文的码点数、
 *       {@code verdict} 必须与合规规则一致。这两件事与模型说了什么无关。</li>
 * </ul>
 */
@SpringBootTest(properties = {"exp.demo.enabled=false"})
@DisplayName("Java 节点 · 真实本机模型（LM Studio / Qwen3-VL-8B）")
class ContentReleaseTest {

    private static final String DRAFT = "本心（benxin）是一个 Spring Boot 插件，它用注解声明式地调用大模型，"
            + "模型、协议、循环、工具、上下文、记忆、提示词全都可以被替换，而调用方的写法保持不变。"
            + "新增的工作流 Java 节点允许把一段本地逻辑注册进图里，用来做计数、校验、格式转换这类不该交给模型的事。";

    @Autowired
    private ReleaseAgents.ReleaseAgent releaseAgent;

    @Autowired
    private LocalEndpoint endpoint;

    @BeforeEach
    void requireLocalModel() {
        Assumptions.assumeTrue(endpoint.enabled(),
                () -> "本机 LM Studio 不可用，跳过真实调用测试：" + endpoint.describe());
    }

    @Test
    @DisplayName("整条流水线：模型写摘要，Java 节点算字数与判合规，最后打包")
    void pipelineMixesRealModelAndLocalNodes() {
        AgentResult result = releaseAgent.publish(DRAFT);

        // ---- 模型确实参与了（而不是某个脚本模型） ----
        assertThat(result.modelName()).as("用的必须是本机端点里配的那个模型").isEqualTo("local-lmstudio");
        assertThat(result.usage().isEmpty()).as("真实调用一定有 token 用量").isFalse();

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run).as("跑完之后应当有工作流轨迹").isNotNull();
        assertThat(run.visited())
                .as("三个 Java 节点与两个分支节点都应当被走到")
                .contains("begin", "summarize", "stats", "route", "screen", "decide", "done");

        @SuppressWarnings("unchecked")
        Map<String, Object> variables =
                (Map<String, Object>) result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);

        // ---- 模型负责的那一半：只断言结构，不钉措辞 ----
        String summary = String.valueOf(variables.get("summarize"));
        assertThat(summary).as("模型应当产出一段摘要").isNotBlank();
        assertThat(summary.codePointCount(0, summary.length()))
                .as("摘要应当比原文短").isLessThan(DRAFT.codePointCount(0, DRAFT.length()));

        // ---- Java 节点负责的那一半：精确断言，与模型说了什么无关 ----
        String finalText = String.valueOf(variables.get("text"));
        int expectedChars = finalText.codePointCount(0, finalText.length());
        assertThat(variables.get("chars"))
                .as("chars 必须是正文的码点数（本地算出来的，不是模型说的）")
                .isEqualTo(expectedChars);
        assertThat(variables.get("level")).isIn("偏长", "适中");
        assertThat(variables.get("verdict")).isEqualTo("通过");

        // ---- 终点引用的是 packed 变量：两条分支共用一个名字 ----
        assertThat(result.text())
                .as("通过分支应当渲染出发布包")
                .contains("【发布包】")
                .contains("字数：");
    }
}
