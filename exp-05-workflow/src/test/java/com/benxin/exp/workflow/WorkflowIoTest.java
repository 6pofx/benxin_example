package com.benxin.exp.workflow;

import com.benxin.exp.workflow.demo.WorkflowIoDemo;
import com.benxin.exp.workflow.engine.FakeWorkflowRuntime;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.core.workflow.WorkflowState;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkflowIo} 的全部 API + 加载期校验 + 五份 YAML 的结构断言。
 *
 * <p>用 {@code @SpringBootTest} 而不是纯 JUnit，是因为要拿到真实的 {@code classpath:}
 * 资源与"Spring Boot 应用自带 SnakeYAML"这个前提 —— YAML 入口可用本身就是一条要断言的结论。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@DisplayName("WorkflowIo 与加载期校验")
class WorkflowIoTest {

    @Autowired
    private ResourceLoader resourceLoader;

    private String text(String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + location + " 失败", e);
        }
    }

    // ------------------------------------------------------------------
    // API 存在性与格式判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Spring Boot 应用里 SnakeYAML 在类路径上 ⇒ YAML 入口开箱可用")
    void yamlEntryIsAvailable() {
        assertThat(WorkflowIo.yamlAvailable()).isTrue();
        assertThat(WorkflowIo.yamlUnavailableReason()).isNotNull();
    }

    @Test
    @DisplayName("formatOfFileName：只有 .json 是 JSON，其余按 YAML")
    void formatOfFileNameRules() {
        assertThat(WorkflowIo.formatOfFileName("a.json")).isEqualTo(WorkflowIo.Format.JSON);
        assertThat(WorkflowIo.formatOfFileName("A.JSON")).isEqualTo(WorkflowIo.Format.JSON);
        assertThat(WorkflowIo.formatOfFileName("a.yml")).isEqualTo(WorkflowIo.Format.YAML);
        assertThat(WorkflowIo.formatOfFileName("a.yaml")).isEqualTo(WorkflowIo.Format.YAML);
        assertThat(WorkflowIo.formatOfFileName("a.txt")).isEqualTo(WorkflowIo.Format.YAML);
        assertThat(WorkflowIo.formatOfFileName(null)).isEqualTo(WorkflowIo.Format.YAML);
    }

    @Test
    @DisplayName("read(String)：按首个非空字符自动识别 JSON / YAML")
    void readAutoDetectsFormat() {
        WorkflowDefinition fromYaml = WorkflowIo.read(text("classpath:workflows/triage.yml"));
        WorkflowDefinition fromJson = WorkflowIo.read(WorkflowIo.writeJson(fromYaml));

        assertThat(fromJson.name()).isEqualTo(fromYaml.name());
        assertThat(fromJson.nodeIds()).isEqualTo(fromYaml.nodeIds());
        assertThat(fromJson.edges()).isEqualTo(fromYaml.edges());
    }

    @Test
    @DisplayName("read(String, Format)：可以强制格式；内容为空直接拒绝")
    void readWithExplicitFormat() {
        String json = WorkflowIo.writeJson(WorkflowIo.read(text("classpath:workflows/triage.yml")));
        assertThat(WorkflowIo.read(json, WorkflowIo.Format.JSON).name()).isEqualTo("triage");

        assertThatThrownBy(() -> WorkflowIo.read("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流定义内容为空");
    }

    @Test
    @DisplayName("read(InputStream) 与 read(Path) 走的是同一套校验")
    void readsFromStreamAndPath() throws IOException {
        WorkflowDefinition fromStream;
        try (InputStream in = new ClassPathResource("workflows/triage.yml").getInputStream()) {
            fromStream = WorkflowIo.read(in);
        }

        Path temp = Files.createTempFile("exp05-triage-", ".json");
        try {
            Files.writeString(temp, WorkflowIo.writeJson(fromStream), StandardCharsets.UTF_8);
            WorkflowDefinition fromPath = WorkflowIo.read(temp);
            assertThat(fromPath.nodeIds()).isEqualTo(fromStream.nodeIds());
            assertThat(fromPath.edges()).isEqualTo(fromStream.edges());
        } finally {
            Files.deleteIfExists(temp);
        }

        assertThatThrownBy(() -> WorkflowIo.read((Path) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流定义路径为空");
        assertThatThrownBy(() -> WorkflowIo.read((InputStream) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流定义输入流为空");
    }

    // ------------------------------------------------------------------
    // 往返与快照
    // ------------------------------------------------------------------

    @Test
    @DisplayName("YAML ↔ JSON 往返：图不丢信息")
    void roundTripsBetweenYamlAndJson() {
        WorkflowDefinition original = WorkflowIo.read(text("classpath:workflows/triage.yml"));

        String json = WorkflowIo.writeJson(original);
        assertThat(json).contains("\"name\"").contains("\"nodes\"").contains("\"edges\"");
        assertThat(WorkflowIo.read(json, WorkflowIo.Format.JSON).nodes())
                .hasSameSizeAs(original.nodes());

        String yaml = WorkflowIo.writeYaml(original).orElseThrow();
        WorkflowDefinition back = WorkflowIo.read(yaml, WorkflowIo.Format.YAML);
        assertThat(back.name()).isEqualTo(original.name());
        assertThat(back.entry()).isEqualTo(original.entry());
        assertThat(back.nodeIds()).isEqualTo(original.nodeIds());
        assertThat(back.edges()).isEqualTo(original.edges());
    }

    @Test
    @DisplayName("toMap 快照：刻意不写默认值（retry: 0 / maxSteps: -1 / maxVisitsPerNode: 100）")
    void toMapOmitsDefaults() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/loop-workflow.yml"));
        Map<String, Object> snapshot = WorkflowIo.toMap(definition);

        assertThat(snapshot).containsKeys("name", "description", "nodes", "edges");
        // loop-workflow.yml 用的都是默认值 ⇒ 这几个键不该出现
        assertThat(snapshot).doesNotContainKeys("maxVisitsPerNode", "entry");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) snapshot.get("nodes");
        assertThat(nodes).isNotEmpty();
        assertThat(nodes.get(0)).containsEntry("id", "begin").containsEntry("type", "start");
        // retry / continueOnError / maxSteps 的默认值也不写进去
        for (Map<String, Object> node : nodes) {
            assertThat(node).doesNotContainKeys("retry", "continueOnError", "maxSteps");
        }
    }

    @Test
    @DisplayName("toMap 快照：非默认值必须写出来（flaky-tools.yml 的 retry / continueOnError）")
    void toMapKeepsNonDefaults() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/flaky-tools.yml"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) WorkflowIo.toMap(definition).get("nodes");

        Map<String, Object> stamp = nodes.stream()
                .filter(node -> "stamp".equals(node.get("id"))).findFirst().orElseThrow();
        Map<String, Object> charge = nodes.stream()
                .filter(node -> "charge".equals(node.get("id"))).findFirst().orElseThrow();

        assertThat(stamp).containsEntry("retry", 3).containsEntry("tool", "flaky_stamp");
        assertThat(charge).containsEntry("continueOnError", true).containsEntry("retry", 1);
    }

    // ------------------------------------------------------------------
    // 加载期校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺 entry（指向不存在的节点）→ 加载期报错，消息里带已有节点清单")
    void rejectsMissingEntry() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                entry: nowhere
                nodes:
                  - id: a
                    type: start
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在节点 [nowhere]")
                .hasMessageContaining("已有节点");
    }

    @Test
    @DisplayName("边指向不存在的节点 → 加载期报错")
    void rejectsEdgeToUnknownNode() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a
                    type: start
                edges:
                  - from: a
                    to: ghost
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在节点 [ghost]");
    }

    @Test
    @DisplayName("节点 id 与保留变量重名 → 加载期报错，并列出保留名")
    void rejectsReservedNodeIds() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: last
                    type: start
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与内置变量重名")
                .hasMessageContaining("last")
                .hasMessageContaining("保留名");
    }

    @Test
    @DisplayName("条件表达式语法错误 → 加载期报错，消息里带原始表达式")
    void rejectsBrokenCondition() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a
                    type: branch
                  - id: b
                    type: end
                edges:
                  - from: a
                    to: b
                    when: "${x} =="
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("条件表达式解析失败")
                .hasMessageContaining("${x} ==");
    }

    @Test
    @DisplayName("节点自身缺必填项 / id 带点号 / 节点为空 → 加载期报错")
    void rejectsMalformedNodes() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a
                    type: agent
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 prompt");

        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a
                    type: tool
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 tool 名称");

        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: mark
                    type: set
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供至少一个 set 键值");

        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a.b
                    type: start
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许包含 '.'");

        assertThatThrownBy(() -> WorkflowIo.read("name: broken\nnodes: []\nedges: []\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何节点");
    }

    @Test
    @DisplayName("节点 id 重复 → 加载期报错")
    void rejectsDuplicateNodeIds() {
        assertThatThrownBy(() -> WorkflowIo.read("""
                name: broken
                nodes:
                  - id: a
                    type: start
                  - id: a
                    type: end
                    output: x
                edges: []
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点 id 重复");
    }

    @Test
    @DisplayName("YAML 语法错误 → 报「YAML 语法错误」，而不是 500 行堆栈")
    void rejectsBrokenYamlSyntax() {
        assertThatThrownBy(() -> WorkflowIo.read("name: [unclosed\nnodes: ---\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("「默认边之前还有条件边」→ 只是警告，图照样能读进来（语义仍然确定）")
    void unreachableEdgeIsOnlyAWarning() {
        WorkflowDefinition definition = WorkflowIo.read("""
                name: dead-edge
                nodes:
                  - id: route
                    type: branch
                  - id: a
                    type: end
                    output: A
                  - id: b
                    type: end
                    output: B
                edges:
                  - from: route
                    to: b
                  - from: route
                    to: a
                    when: "${x} == 1"
                """);

        assertThat(definition.nodeIds()).containsExactly("route", "a", "b");
        assertThat(definition.outgoing("route")).hasSize(2);
        // 第二条边的条件永远不可能被求值：第一条是无条件的默认边，先命中先返回
        assertThat(definition.outgoing("route").get(0).unconditional()).isTrue();
        assertThat(definition.outgoing("route").get(1).when()).isEqualTo("${x} == 1");
    }

    @Test
    @DisplayName("演示里那几条本该失败的定义确实都失败了")
    void demoFailureCasesAllFail() {
        // 演示与测试共用同一份判定：read 抛异常 ⇒ 返回非空消息
        String[] cases = {
                "name: broken\nentry: nowhere\nnodes:\n  - id: a\n    type: start\nedges: []\n",
                "name: broken\nnodes:\n  - id: a\n    type: start\nedges:\n  - from: a\n    to: ghost\n",
                "name: broken\nnodes:\n  - id: error\n    type: start\nedges: []\n",
                "name: broken\nnodes:\n  - id: a\n    type: agent\nedges: []\n",
        };
        for (String definition : cases) {
            assertThatThrownBy(() -> WorkflowIo.read(definition))
                    .as("本该失败的加载：%s", definition)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // starter 侧的装配校验（location / inline）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("location 与 inline 同时配置 → LlmProperties 能表达这种冲突（启动期会被拦下）")
    void workflowPropertiesCarryBothSources() {
        LlmProperties.WorkflowProperties conflict = new LlmProperties.WorkflowProperties();
        conflict.setLocation("classpath:workflows/triage.yml");
        conflict.setInline("name: x\nnodes: []\n");

        // 两个字段都能被绑定出来 —— 拦截点在 LlmAutoConfiguration.loadWorkflow，
        // 由 WorkflowPropertiesTest 里的 @SpringBootTest 真正触发。
        assertThat(conflict.getLocation()).isNotBlank();
        assertThat(conflict.getInline()).isNotBlank();
    }

    @Test
    @DisplayName("WorkflowIoDemo.expectFailure 与断言口径一致")
    void demoHelperMatchesAssertions() {
        String message = WorkflowIoDemo.expectFailure(
                com.benxin.exp.common.demo.Demo.begin("（测试内的静默演示）"),
                "缺 entry", "name: broken\nentry: nowhere\nnodes:\n  - id: a\n    type: start\nedges: []\n");
        assertThat(message).isNotBlank().contains("nowhere");
    }

    // ------------------------------------------------------------------
    // 五份 YAML 的结构断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("triage.yml：六种节点全部用上，边里既有条件边也有默认边与回边")
    void triageYamlCoversEveryNodeType() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/triage.yml"));

        assertThat(definition.name()).isEqualTo("triage");
        assertThat(definition.resolveEntry().id()).isEqualTo("begin");
        assertThat(definition.nodes()).extracting(node -> node.type().wireName())
                .contains("start", "agent", "tool", "branch", "set", "end");

        // 回边：check → rework → check
        assertThat(definition.outgoing("rework")).extracting(edge -> edge.to()).containsExactly("check");
        assertThat(definition.incoming("check")).extracting(edge -> edge.from()).contains("rework");

        // 默认边必须放在条件边之后（route 的第一条是条件边，最后一条是无条件边）
        assertThat(definition.outgoing("route").get(0).unconditional()).isFalse();
        assertThat(definition.outgoing("route").get(1).unconditional()).isTrue();

        // 两条分支各自 set 自己的变量：channel 分别写在 mark-urgent 与 normal 上
        assertThat(definition.require("mark-urgent").set()).containsKey("channel");
        assertThat(definition.require("normal").set()).containsKey("channel");
    }

    @Test
    @DisplayName("loop-workflow.yml：回边循环，循环体是 tool + set（不调模型）")
    void loopWorkflowYamlIsModelFreeLoop() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/loop-workflow.yml"));

        // 循环体 = bump(tool) + tally(set)：工具算 +1，set 把结果写回 ${count}
        assertThat(definition.nodes()).extracting(node -> node.type().wireName())
                .containsExactlyInAnyOrder("start", "set", "branch", "tool", "set", "end");
        assertThat(definition.nodes()).noneMatch(node -> node.type()
                == com.benxin.llm.core.workflow.NodeType.AGENT);
        assertThat(definition.require("bump").args()).containsEntry("n", "${count}");
        assertThat(definition.require("counter").output()).isNull();
        // ★ 关键的一格：工具输出（${bump}）必须由 set 显式写回循环条件读的那个变量
        assertThat(definition.require("tally").set()).containsEntry("count", "${bump}");
    }

    @Test
    @DisplayName("flaky-tools.yml：retry 与 continueOnError 都配上了")
    void flakyToolsYamlHasBothTolerances() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/flaky-tools.yml"));

        assertThat(definition.require("stamp").retry()).isEqualTo(3);
        assertThat(definition.require("stamp").continueOnError()).isFalse();
        assertThat(definition.require("charge").retry()).isEqualTo(1);
        assertThat(definition.require("charge").continueOnError()).isTrue();
        // 补偿分支的条件就是读保留变量 ${error}
        assertThat(definition.outgoing("charge").get(0).when()).isEqualTo("${error} is not empty");
    }

    @Test
    @DisplayName("deadloop.yml：两张互指的边 + 调小的 maxVisitsPerNode，且刻意没注册进 llm.workflows")
    void deadloopYamlIsTheGuardTarget() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/deadloop.yml"));

        assertThat(definition.maxVisitsPerNode()).isEqualTo(5);
        assertThat(definition.outgoing("spin")).extracting(edge -> edge.to()).containsExactly("again");
        assertThat(definition.outgoing("again")).extracting(edge -> edge.to()).containsExactly("spin");
        // 一个 agent 节点都没有 ⇒ 步数预算对它无效
        assertThat(definition.nodes()).noneMatch(node -> node.type()
                == com.benxin.llm.core.workflow.NodeType.AGENT);
    }

    @Test
    @DisplayName("budget-probe.yml：四个串联的 agent 节点")
    void budgetProbeYamlHasChainedAgents() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/budget-probe.yml"));

        assertThat(definition.nodes()).filteredOn(node -> node.type()
                == com.benxin.llm.core.workflow.NodeType.AGENT).hasSize(4);
        assertThat(definition.resolveEntry().id()).isEqualTo("begin");
        assertThat(definition.edges()).hasSize(5);
    }

    // ------------------------------------------------------------------
    // 用假运行时把 YAML 真跑一遍：证明"读进来的图"就是"能跑的图"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("YAML 读进来就能跑：loop-workflow.yml 转 3 圈后干净退出")
    void loadedYamlActuallyRuns() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/loop-workflow.yml"));
        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .sequence("bump", "1", "2", "3");
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("跑一遍"), runtime);

        assertThat(run.visited()).containsExactly(
                "begin", "seed", "counter", "bump", "tally", "counter", "bump", "tally",
                "counter", "bump", "tally", "counter", "done");
        assertThat(run.executions()).isEqualTo(13);
        assertThat(run.truncated()).isFalse();
        assertThat(run.output()).contains("共转了 3 圈").contains("计数器最后一次输出：3");
        // 工具被调了 3 次、每次拿到的都是**上一圈写回后**的计数
        assertThat(runtime.attempts("bump")).isEqualTo(3);
        assertThat(runtime.toolArgs("bump")).containsEntry("n", "2");
        // 循环体全程没有一次 callAgent
        assertThat(runtime.agentNodes()).isEmpty();
        assertThat(runtime.toolNames()).containsOnly("next_attempt");
    }

    @Test
    @DisplayName("YAML 读进来就能跑：triage.yml 的紧急路径回炉 3 次后由 done 收口")
    void triageYamlRunsTheReworkLoop() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/triage.yml"));
        // 用假运行时跑：agent 节点返回"分级：紧急"，于是 route 走紧急分支、check 走回边
        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime()
                .output("classify", "分级：紧急")
                .output("urgent", "处置建议")
                .output("stamp", "2026-01-01 00:00:00")
                .sequence("bump", "1", "2", "3");
        WorkflowRun run = new WorkflowEngine().run(definition, new WorkflowState("线上不可用"), runtime);

        assertThat(run.visited()).containsExactly(
                "begin", "classify", "route", "urgent", "stamp", "mark-urgent", "report",
                "check", "bump", "rework", "check", "bump", "rework", "check", "bump", "rework",
                "check", "done");
        assertThat(run.output()).contains("通道：加急通道")
                .contains("已回炉第 1 次").contains("已回炉第 3 次");
        assertThat(run.truncated()).isFalse();
        // ★ report 必须是 set 而不是 end：是 end 的话引擎在 report 就收工，
        //   check / bump / rework / done 会整段变成死代码（回边一次都转不了）。
        assertThat(definition.require("report").type())
                .isEqualTo(com.benxin.llm.core.workflow.NodeType.SET);
        assertThat(definition.require("done").type())
                .isEqualTo(com.benxin.llm.core.workflow.NodeType.END);
    }

    @Test
    @DisplayName("YAML 读进来就能跑：deadloop.yml 被 maxVisitsPerNode 拦下，且一个模型都没调")
    void loadedDeadloopIsGuarded() {
        WorkflowDefinition definition = WorkflowIo.read(text("classpath:workflows/deadloop.yml"));
        // 预算给得极小：如果预算真的有效，它应该很快就 truncated —— 但它永远不会
        FakeWorkflowRuntime runtime = new FakeWorkflowRuntime().budget(1);
        WorkflowState state = new WorkflowState("x");

        assertThatThrownBy(() -> new WorkflowEngine().run(definition, state, runtime))
                .isInstanceOf(com.benxin.llm.core.workflow.WorkflowException.class)
                .hasMessageContaining("死循环")
                .hasMessageContaining("maxVisitsPerNode");

        assertThat(runtime.agentNodes()).isEmpty();
        assertThat(runtime.events()).contains("workflow.loop-guard");
        // ★ 预算机制对它完全无效：派发次数（含工具）就是 0
        assertThat(runtime.dispatches()).isZero();
    }
}
