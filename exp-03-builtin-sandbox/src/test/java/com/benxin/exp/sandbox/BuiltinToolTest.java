package com.benxin.exp.sandbox;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.sandbox.agent.ResearchAssistant;
import com.benxin.exp.sandbox.agent.SandboxProbe;
import com.benxin.exp.sandbox.audit.ToolAuditListener;
import com.benxin.exp.sandbox.tool.SandboxGuardTools;
import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.builtin.TodoWriteTool;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8 个内置工具的真实调用：把 {@code BuiltinSandboxDemo} 展示的每一条结论断言成可回归的用例。
 *
 * <p>全部离线：模型是 {@code exp-common} 的脚本化模型，测试既不联网也不消耗 token，
 * 却完整跑通了「模型决策 → 五道关卡 → 工具执行 → 结果回灌 → 收尾」。</p>
 */
@SpringBootTest
@DisplayName("示例 03 · 8 个内置工具")
class BuiltinToolTest {

    @Autowired
    private SandboxProbe probe;

    @Autowired
    private ResearchAssistant research;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private ToolAuditListener audit;

    @Autowired
    private SandboxWorkspace workspace;

    @Autowired
    private SandboxGuardTools guardTools;

    @Autowired
    private ToolSandbox sandbox;

    @Autowired
    private LlmToolCatalog catalog;

    @Autowired
    private AgentRegistry agentRegistry;

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        audit.reset();
        guardTools.reset();
        // 样例文件被 edit 改过、generated/tests 被 write 写过，每轮重建，保证用例之间互不影响
        workspace.ensure();
        workspace.resetOutputs();
        clearHistories();
    }

    // ------------------------------------------------------------------
    // 工具表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三重开关全开 + enabled=[todo_write, task] 时，8 个内置工具全部在表里")
    void allEightBuiltinToolsAreRegistered() {
        assertThat(catalog.global().names()).contains(
                "read", "write", "edit", "glob", "grep", "bash", "todo_write", "task");
        // 自写工具与内置工具同在全局工具表里 —— 沙箱对二者一视同仁
        assertThat(catalog.global().names()).contains("guarded_echo", "current_todos");
        assertThat(SandboxConfig.DEFAULT_DENIED_COMMANDS).hasSize(29);
    }

    @Test
    @DisplayName("一次运行只强制一次工具：toolCalls() 恰好一条，随后由模型收尾")
    void oneForcedToolCallPerRun() {
        AgentResult result = probeResult("#tool:read {\"path\":\"a.txt\"}");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("read");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        // 2 步 = 「调用工具」+「拿结果收尾」，说明确实走完了完整闭环
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.text()).contains("根据工具返回的结果");
    }

    // ------------------------------------------------------------------
    // read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("read：输出带行号的 cat -n 风格内容，meta 里带行数与字节数")
    void readReturnsNumberedLines() {
        ToolAuditListener.ToolObservation observation = probe("#tool:read {\"path\":\"a.txt\"}");

        assertThat(observation.error()).isFalse();
        assertThat(observation.content())
                .contains("1\talpha line one")
                .contains("2\tbenxin sandbox baseline");
        assertThat(observation.meta())
                .containsEntry("tool", "read")
                .containsEntry("path", "a.txt")
                .containsEntry("linesTotal", 4)
                .containsEntry("linesShown", 4)
                .containsEntry("offset", 1);
    }

    @Test
    @DisplayName("read：offset/limit 分段读取，并在结尾提示下一段的 offset")
    void readHonoursOffsetAndLimit() {
        ToolAuditListener.ToolObservation observation =
                probe("#tool:read {\"path\":\"a.txt\",\"offset\":2,\"limit\":2}");

        assertThat(observation.content())
                .contains("2\tbenxin sandbox baseline")
                .contains("3\tBENXIN uppercase marker")
                .doesNotContain("1\talpha line one")
                .doesNotContain("4\tdelta line four")
                .contains("offset=4");
        assertThat(observation.meta())
                .containsEntry("offset", 2)
                .containsEntry("linesShown", 2)
                .containsEntry("linesTotal", 4);
    }

    @Test
    @DisplayName("read：目录、不存在的文件都给可读的中文错误，而不是抛异常")
    void readFailsReadablyOnBadPaths() {
        assertThat(probe("#tool:read {\"path\":\"docs\"}").content()).contains("是一个目录");
        assertThat(probe("#tool:read {\"path\":\"ghost.txt\"}").content()).contains("文件不存在");
    }

    // ------------------------------------------------------------------
    // write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("write：自动建父目录；第二次写同一路径 meta.overwritten=true")
    void writeCreatesThenOverwrites() throws Exception {
        ToolAuditListener.ToolObservation created = probe(
                "#tool:write {\"path\":\"tests/report.txt\",\"content\":\"第一行\\n第二行\"}");

        assertThat(created.error()).isFalse();
        assertThat(created.content()).contains("已创建 tests/report.txt");
        assertThat(created.meta()).containsEntry("overwritten", false).containsEntry("lines", 2);
        assertThat(Files.readString(workspace.resolve("tests/report.txt"))).isEqualTo("第一行\n第二行");

        ToolAuditListener.ToolObservation overwritten = probe(
                "#tool:write {\"path\":\"tests/report.txt\",\"content\":\"覆盖后的内容\"}");
        assertThat(overwritten.content()).contains("已覆盖写入");
        assertThat(overwritten.meta()).containsEntry("overwritten", true);
        assertThat(Files.readString(workspace.resolve("tests/report.txt"))).isEqualTo("覆盖后的内容");
    }

    // ------------------------------------------------------------------
    // edit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("edit：唯一匹配时替换 1 处")
    void editReplacesUniqueMatch() {
        ToolAuditListener.ToolObservation observation = probe(
                "#tool:edit {\"path\":\"a.txt\",\"old_string\":\"alpha line one\","
                        + "\"new_string\":\"alpha line ONE\"}");

        assertThat(observation.error()).isFalse();
        assertThat(observation.meta()).containsEntry("replacements", 1);
        assertThat(observation.content()).contains("替换 1 处");
    }

    @Test
    @DisplayName("edit：命中多处且 replace_all=false 时直接拒绝（安全设计，绝不改第一处）")
    void editRejectsMultipleMatches() {
        ToolAuditListener.ToolObservation observation = probe(
                "#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"TODO\",\"new_string\":\"DONE\"}");

        assertThat(observation.error()).isTrue();
        assertThat(observation.content())
                .contains("匹配到 2 处")
                .contains("replace_all=true");
        // 被拒绝 = 磁盘一个字节都没动
        assertThat(notes()).contains("TODO 补一个越界路径的用例").contains("TODO 补一个命令黑名单的用例");
    }

    @Test
    @DisplayName("edit：replace_all=true 才允许批量替换；old_string 找不到也会明确报错")
    void editReplaceAllAndMissingOldString() {
        ToolAuditListener.ToolObservation replaced = probe(
                "#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"TODO\","
                        + "\"new_string\":\"DONE\",\"replace_all\":true}");
        assertThat(replaced.error()).isFalse();
        assertThat(replaced.meta()).containsEntry("replacements", 2);
        assertThat(notes()).doesNotContain("TODO");

        workspace.ensure();
        ToolAuditListener.ToolObservation missing = probe(
                "#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"不存在的片段\","
                        + "\"new_string\":\"x\"}");
        assertThat(missing.error()).isTrue();
        assertThat(missing.content()).contains("未找到待替换片段");
    }

    // ------------------------------------------------------------------
    // glob / grep
    // ------------------------------------------------------------------

    @Test
    @DisplayName("glob：**/*.txt 递归（含顶层），*.txt 只匹配当前层")
    void globRecursiveAndTopLevel() {
        ToolAuditListener.ToolObservation recursive = probe("#tool:glob {\"pattern\":\"**/*.txt\"}");
        assertThat(recursive.error()).isFalse();
        assertThat(recursive.content())
                .contains("a.txt")
                .contains("docs/guide.txt")
                .contains("docs/notes.txt")
                .contains("data/config.txt");
        assertThat((Integer) recursive.meta().get("matches")).isGreaterThanOrEqualTo(4);

        ToolAuditListener.ToolObservation topLevel = probe("#tool:glob {\"pattern\":\"*.txt\"}");
        assertThat(topLevel.content()).contains("a.txt").doesNotContain("docs/guide.txt");
        assertThat(topLevel.meta()).containsEntry("matches", 1);
    }

    @Test
    @DisplayName("glob：敏感路径连名字都不列出来，只在结尾说明被过滤了多少个")
    void globHidesDeniedPaths() {
        ToolAuditListener.ToolObservation observation = probe("#tool:glob {\"pattern\":\"**/*\"}");

        assertThat(observation.content())
                .doesNotContain("server.pem")
                .doesNotContain("id_rsa")
                .doesNotContain("credentials.json")
                .contains("个敏感路径结果被沙箱规则过滤");
    }

    @Test
    @DisplayName("grep：include 限定文件、ignore_case 忽略大小写、max_results 截断")
    void grepIncludeIgnoreCaseAndMaxResults() {
        ToolAuditListener.ToolObservation capped = probe(
                "#tool:grep {\"pattern\":\"benxin\",\"ignore_case\":true,\"max_results\":2}");
        assertThat(capped.content())
                .contains("a.txt:2:benxin sandbox baseline")
                .contains("a.txt:3:BENXIN uppercase marker")   // ignore_case 生效
                .contains("已达 max_results 上限");
        assertThat(capped.meta()).containsEntry("limitReached", true).containsEntry("matches", 2);

        ToolAuditListener.ToolObservation scoped = probe(
                "#tool:grep {\"pattern\":\"benxin\",\"include\":\"*.txt\",\"ignore_case\":true}");
        assertThat(scoped.content()).contains("docs/guide.txt:");

        ToolAuditListener.ToolObservation subDir = probe("#tool:grep {\"pattern\":\"围栏\",\"path\":\"docs\"}");
        assertThat(subDir.meta()).containsEntry("base", "docs");
        assertThat(subDir.content()).contains("docs/guide.txt:").contains("docs/notes.txt:");
    }

    // ------------------------------------------------------------------
    // bash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bash：只跑 echo，退出码 0，输出与 meta 都可见")
    void bashRunsHarmlessCommand() {
        ToolAuditListener.ToolObservation observation =
                probe("#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");

        assertThat(observation.error()).isFalse();
        assertThat(observation.content()).contains("benxin-sandbox-ok").contains("[退出码: 0]");
        assertThat(observation.meta())
                .containsEntry("tool", "bash")
                .containsEntry("exitCode", 0)
                .containsEntry("truncated", false);
    }

    @Test
    @DisplayName("bash：命令在围栏目录下执行（meta.workdir 就是沙箱根目录）")
    void bashRunsInsideTheFence() {
        ToolAuditListener.ToolObservation observation =
                probe("#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");

        assertThat(String.valueOf(observation.meta().get("workdir")))
                .isEqualTo(workspace.root().toString());
    }

    // ------------------------------------------------------------------
    // todo_write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("todo_write：清单落到运行属性 benxin.todos，并广播 todos.updated 事件")
    void todoWriteLandsInRunAttributes() {
        AgentResult result = probeResult("#tool:todo_write {\"todos\":["
                + "{\"content\":\"读完 a.txt\",\"status\":\"completed\"},"
                + "{\"content\":\"跑一遍围栏校验\",\"status\":\"in_progress\"}]}");

        Object todos = result.attributes().get(TodoWriteTool.ATTRIBUTE_KEY);
        assertThat(todos).isInstanceOf(List.class);
        assertThat((List<?>) todos).hasSize(2);
        assertThat(String.valueOf(((List<?>) todos).get(0))).contains("读完 a.txt").contains("completed");

        assertThat(audit.latest().content()).contains("[x] 1. 读完 a.txt").contains("[~] 2.");
        assertThat(audit.latest().meta()).containsEntry("total", 2).containsEntry("completed", 1L);
        assertThat(audit.lastEventPayload("todos.updated")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("todo_write：非法 status 会被拒绝，并给出正确格式示例")
    void todoWriteValidatesStatus() {
        ToolAuditListener.ToolObservation observation = probe(
                "#tool:todo_write {\"todos\":[{\"content\":\"x\",\"status\":\"whatever\"}]}");

        assertThat(observation.error()).isTrue();
        assertThat(observation.content())
                .contains("status 非法")
                .contains("pending / in_progress / completed");
    }

    // ------------------------------------------------------------------
    // task
    // ------------------------------------------------------------------

    @Test
    @DisplayName("task：派生子代理，只把结论摘要拿回来；meta 里能看到子代理名与步数")
    void taskSpawnsSubAgent() {
        ToolAuditListener.ToolObservation observation = probe(
                "#tool:task {\"description\":\"统计沙箱文件\","
                        + "\"prompt\":\"统计沙箱目录里的文件数量并给出结论\","
                        + "\"subagent_type\":\"researchAssistant\"}");

        assertThat(observation.error()).isFalse();
        assertThat(observation.content())
                .contains("[子代理 researchAssistant 的结论]")
                .contains("统计沙箱文件");
        assertThat(observation.meta())
                .containsEntry("subAgent", "researchAssistant")
                .containsEntry("steps", 1)
                .containsEntry("maxStepsReached", false);

        // 子代理确实被派生过：监听器收到了 onSubAgentStart/End 这一对
        assertThat(audit.subAgentObservations()).hasSize(1);
        assertThat(audit.subAgentObservations().get(0).parent()).isEqualTo("sandboxProbe");
        assertThat(audit.subAgentObservations().get(0).child()).isEqualTo("researchAssistant");
        assertThat(audit.subAgentObservations().get(0).conclusion()).contains("子代理结论");
    }

    @Test
    @DisplayName("task：subAgents 里没声明的名字会被明确拒绝，而不是悄悄什么都不做")
    void taskRejectsUndeclaredSubAgent() {
        ToolAuditListener.ToolObservation observation = probe(
                "#tool:task {\"description\":\"x\",\"prompt\":\"y\",\"subagent_type\":\"notDeclared\"}");

        assertThat(observation.error()).isTrue();
        assertThat(observation.content()).contains("未注册子代理").contains("notDeclared");
    }

    @Test
    @DisplayName("task 的前置条件：Agent 必须在 subAgents 里声明子代理")
    void taskRequiresDeclaredSubAgents() {
        Agent probeAgent = agentRegistry.get("sandboxProbe");

        assertThat(probeAgent.spec().subAgents()).contains("researchAssistant");
        // 子代理自己是可以单独注入使用的 Agent（memory=false，历史不落地）
        assertThat(research.research("统计沙箱目录里的文件数量")).contains("子代理结论");
        assertThat(agentRegistry.get("researchAssistant")
                .history("researchAssistant-default")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 自写工具与内置工具的协作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自写工具读内置工具写下的运行属性：另一次运行里读不到（属性按「一次 call」隔离）")
    void runAttributesAreScopedToOneCall() {
        assertThat(probe("#tool:current_todos {}").content()).contains("还不存在");
    }

    @Test
    @DisplayName("容器里的沙箱是 PathSandbox + CommandSandbox 的组合体")
    void containerSandboxIsComposite() {
        assertThat(sandbox.getClass().getSimpleName()).isEqualTo("CompositeSandbox");
        assertThat(SandboxWorkspace.pathSandboxOf(sandbox)).isNotNull();
        assertThat(SandboxWorkspace.pathSandboxOf(sandbox).config().allowWrite()).isTrue();
        assertThat(SandboxWorkspace.pathSandboxOf(sandbox).config().allowExec()).isTrue();
        assertThat(SandboxWorkspace.pathSandboxOf(sandbox).config().workdirAbsolute())
                .isEqualTo(workspace.root());
    }

    @Test
    @DisplayName("yml 的 llm.agent.approval-policy 会传到声明式 Agent 上（本上下文是默认的 on-request）")
    void approvalPolicyComesFromYml() {
        assertThat(((com.benxin.llm.core.agent.DefaultAgent) agentRegistry.get("sandboxProbe"))
                .approvalPolicy())
                .isEqualTo(com.benxin.llm.core.sandbox.ApprovalPolicy.ON_REQUEST);
    }

    // ------------------------------------------------------------------

    private ToolAuditListener.ToolObservation probe(String instruction) {
        probeResult(instruction);
        return audit.latest();
    }

    private AgentResult probeResult(String instruction) {
        audit.reset();
        clearHistories();
        return probe.runForResult(instruction);
    }

    private void clearHistories() {
        // 必须走 names() + find()：@LlmAgent 接口是惰性条目，resolved() 看不到它们。
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    private String notes() {
        try {
            return Files.readString(workspace.resolve("docs/notes.txt"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
