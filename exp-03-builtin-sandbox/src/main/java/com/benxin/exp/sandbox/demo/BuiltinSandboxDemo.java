package com.benxin.exp.sandbox.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.sandbox.agent.ResearchAssistant;
import com.benxin.exp.sandbox.agent.SandboxProbe;
import com.benxin.exp.sandbox.audit.ToolAuditListener;
import com.benxin.exp.sandbox.support.BuiltinToolTable;
import com.benxin.exp.sandbox.tool.SandboxGuardTools;
import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.builtin.TodoWriteTool;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmProperties;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即跑的演示：内置 8 个工具逐个真实调用（打印返回内容与 meta），
 * 然后是三重开关、路径围栏、命令门禁、审批四态、自定义沙箱与纵深防御。
 *
 * <p>输出刻意保持"看得见结论"：不只打印工具说了什么，还打印框架内部真正生效的东西 ——
 * 工具表、沙箱类型、围栏目录、审批被问了几次、meta 里的退出码与匹配数。
 * 沙箱的价值恰恰在于"那些你看不见的拦截"，所以它们必须被打印出来。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BuiltinSandboxDemo implements ApplicationRunner {

    private final SandboxProbe probe;
    private final ResearchAssistant research;
    private final RecordingModel model;
    private final ToolAuditListener audit;
    private final SandboxWorkspace workspace;
    private final SandboxGuardTools guardTools;
    private final ToolSandbox sandbox;
    private final LlmProperties properties;
    private final LlmToolCatalog catalog;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final AgentRegistry agentRegistry;
    private final SandboxFenceDemo fenceDemo;
    private final ApprovalGateDemo approvalDemo;

    public BuiltinSandboxDemo(SandboxProbe probe,
                              ResearchAssistant research,
                              RecordingModel mockLlmModel,
                              ToolAuditListener audit,
                              SandboxWorkspace workspace,
                              SandboxGuardTools sandboxGuardTools,
                              ToolSandbox sandbox,
                              LlmProperties properties,
                              LlmToolCatalog toolCatalog,
                              ModelRegistry modelRegistry,
                              LoopRegistry loopRegistry,
                              AgentRegistry agentRegistry,
                              SandboxFenceDemo fenceDemo,
                              ApprovalGateDemo approvalDemo) {
        this.probe = probe;
        this.research = research;
        this.model = mockLlmModel;
        this.audit = audit;
        this.workspace = workspace;
        this.guardTools = sandboxGuardTools;
        this.sandbox = sandbox;
        this.properties = properties;
        this.catalog = toolCatalog;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.agentRegistry = agentRegistry;
        this.fenceDemo = fenceDemo;
        this.approvalDemo = approvalDemo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 03 · 内置工具 + 沙箱 + 审批 —— 模型想动你的磁盘时会发生什么");
        demo.banner("模型: %s（默认 %s）", modelRegistry.names(), modelRegistry.defaultName());
        demo.banner("Loop: %s", loopRegistry.names());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("围栏: %s", workspace.root());

        demo.guard("环境与工具表", () -> environment(demo));
        demo.guard("read", () -> readTool(demo));
        demo.guard("write", () -> writeTool(demo));
        demo.guard("edit", () -> editTool(demo));
        demo.guard("glob", () -> globTool(demo));
        demo.guard("grep", () -> grepTool(demo));
        demo.guard("bash", () -> bashTool(demo));
        demo.guard("todo_write", () -> todoTool(demo));
        demo.guard("task", () -> taskTool(demo));
        demo.guard("三重开关对照表", () -> switchTable(demo));
        demo.guard("PathSandbox / CommandSandbox / SandboxConfig", () -> fenceDemo.run(demo));
        demo.guard("审批与可替换守门人", () -> approvalDemo.run(demo));

        demo.done();
    }

    // ------------------------------------------------------------------

    private void environment(Demo demo) {
        demo.section("演示环境 —— 工具表、开关与沙箱");

        // 先恢复样例文件、清掉上一轮的产物，让同一份输入给出同一份输出
        workspace.ensure();
        workspace.resetOutputs();

        demo.info("全局工具表 LlmToolCatalog.global().names() = %s", catalog.global().names());
        demo.info("容器里的 ToolSandbox = %s", sandbox);
        demo.info("llm.tools：builtin-enabled=%s allow-write=%s allow-exec=%s",
                properties.getTools().isBuiltinEnabled(),
                properties.getTools().isAllowWrite(), properties.getTools().isAllowExec());
        demo.info("   workdir=%s allowed-commands=%s denied-commands=%s enabled=%s",
                properties.getTools().getWorkdir(), properties.getTools().getAllowedCommands(),
                properties.getTools().getDeniedCommands(), properties.getTools().getEnabled());
        demo.info("沙箱工作区里的样例文件（敏感路径靶子也在这里，用来验证默认禁止规则）：");
        workspace.sampleFiles().forEach(file -> demo.info("   %s", file));
        demo.info("");
        demo.info("本示例的模型链: RecordingModel → OnceForcedToolModel → ScriptedLlmModel（离线、零 token）");
        demo.info("每次探针的指令形如 #tool:read {\"path\":\"a.txt\"}，工具结果与 meta 全部留痕后打印。");
    }

    private void readTool(Demo demo) {
        demo.section("内置工具 ① read —— 按行号输出，支持 offset/limit");

        print(demo, probe("#tool:read {\"path\":\"a.txt\"}"));
        print(demo, probe("#tool:read {\"path\":\"a.txt\",\"offset\":2,\"limit\":2}"));
        demo.info("越界参数与错误路径一律回一句可读的中文，而不是抛异常：");
        print(demo, probe("#tool:read {\"path\":\"docs\"}"));
        print(demo, probe("#tool:read {\"path\":\"ghost.txt\"}"));
    }

    private void writeTool(Demo demo) {
        demo.section("内置工具 ② write —— 整体覆盖写，自动创建父目录");

        AgentResult created = probeResult(
                "#tool:write {\"path\":\"generated/report.txt\",\"content\":\"第一行\\n第二行\"}");
        print(demo, audit.latest());
        demo.info("文件真的落盘了：%s", workspace.resolve("generated/report.txt").toFile().isFile());

        print(demo, probe("#tool:write {\"path\":\"generated/report.txt\",\"content\":\"覆盖后的内容\"}"));
        demo.info("第二次写同一个路径 → meta.overwritten=true（覆盖写是有意为之，不是 bug）");
        demo.info("（上一步 AgentResult.text() = %s）", oneLine(created.text(), 60));
    }

    private void editTool(Demo demo) {
        demo.section("内置工具 ③ edit —— 精确替换；多处匹配时拒绝（安全设计）");

        print(demo, probe("#tool:edit {\"path\":\"a.txt\",\"old_string\":\"alpha line one\","
                + "\"new_string\":\"alpha line ONE\"}"));

        demo.info("");
        demo.info("docs/notes.txt 里有两行 TODO，只给 \"TODO\" 这一小段上下文：");
        print(demo, probe("#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"TODO\","
                + "\"new_string\":\"DONE\"}"));
        demo.warn("这是刻意的：绝不「随手改第一处」，而是要求补足上下文或显式 replace_all");

        print(demo, probe("#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"TODO\","
                + "\"new_string\":\"DONE\",\"replace_all\":true}"));
        print(demo, probe("#tool:edit {\"path\":\"docs/notes.txt\",\"old_string\":\"不存在的片段\","
                + "\"new_string\":\"x\"}"));

        // 复原，免得后面的演示与测试读到被改过的样例文件（只重建样例，不动 generated/）
        workspace.ensure();
    }

    private void globTool(Demo demo) {
        demo.section("内置工具 ④ glob —— 表达式查找，按修改时间倒序");

        print(demo, probe("#tool:glob {\"pattern\":\"**/*.txt\"}"));
        print(demo, probe("#tool:glob {\"pattern\":\"*.txt\"}"));
        demo.info("**/*.txt 能匹配顶层的 a.txt，是因为 GlobPattern 额外编译了一份去掉前导 **/ 的变体");
        print(demo, probe("#tool:glob {\"pattern\":\"**/*\"}"));
    }

    private void grepTool(Demo demo) {
        demo.section("内置工具 ⑤ grep —— 正则检索，支持 include / ignore_case / max_results");

        print(demo, probe("#tool:grep {\"pattern\":\"benxin\",\"ignore_case\":true,\"max_results\":2}"));
        print(demo, probe("#tool:grep {\"pattern\":\"benxin\",\"include\":\"*.txt\",\"ignore_case\":true}"));
        print(demo, probe("#tool:grep {\"pattern\":\"围栏\",\"path\":\"docs\"}"));
    }

    private void bashTool(Demo demo) {
        demo.section("内置工具 ⑥ bash —— 只跑绝对安全的命令；危险命令只做静态判定");

        print(demo, probe("#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}"));
        demo.info("meta.exitCode = 0，输出里也带 [退出码: 0]；非 0 退出码会变成 error 但保留完整输出。");
        demo.info("命令门禁（总开关 / 黑名单 / 白名单）在后面的「CommandSandbox 三道闸」里用静态判定演示，");
        demo.info("一条危险命令都不会真的执行 —— denyReason() 是「先算结论、再决定跑不跑」的纯函数。");
    }

    private void todoTool(Demo demo) {
        demo.section("内置工具 ⑦ todo_write —— 待办清单落到运行属性，并广播事件");

        AgentResult result = probeResult("#tool:todo_write {\"todos\":["
                + "{\"content\":\"读完 a.txt\",\"status\":\"completed\"},"
                + "{\"content\":\"跑一遍围栏校验\",\"status\":\"in_progress\"},"
                + "{\"content\":\"写 README\",\"status\":\"pending\"}]}");
        print(demo, audit.latest());

        demo.info("");
        demo.info("① 运行属性 benxin.todos = %s", result.attributes().get(TodoWriteTool.ATTRIBUTE_KEY));
        demo.info("   （AgentResult.attributes() 就是这一次运行的属性表快照）");
        demo.info("② 自定义事件 todos.updated 的负载 = %s", audit.lastEventPayload("todos.updated"));
        demo.info("③ 自写工具 current_todos 在「另一次运行」里读同一张属性表：");
        print(demo, probe("#tool:current_todos {}"));
        demo.warn("读不到 —— 运行属性的生命周期是「一次 call」，不是「一个会话」。");
        demo.warn("想让清单跨轮次可见，请让模型把清单当参数传回来，或改用 MemoryStore 那一层。");
    }

    private void taskTool(Demo demo) {
        demo.section("内置工具 ⑧ task —— 派生子代理，只把结论拿回来");

        print(demo, probe("#tool:task {\"description\":\"统计沙箱文件\","
                + "\"prompt\":\"统计沙箱目录里的文件数量并给出结论\","
                + "\"subagent_type\":\"researchAssistant\"}"));

        demo.info("");
        audit.subAgentObservations().forEach(observation -> demo.info(
                "   派生留痕: parent=%s child=%s steps=%d", observation.parent(),
                observation.child(), observation.steps()));
        demo.info("   子代理 Agent 的 spec.subAgents = %s",
                agentRegistry.get("sandboxProbe").spec().subAgents());
        demo.info("   子代理自己也是一个可注入的 Agent：%s（历史条数 = %d，memory=false）",
                research.research("统计沙箱目录里的文件数量"),
                agentRegistry.get("researchAssistant").history("researchAssistant-default").size());

        demo.info("");
        demo.info("没有在 subAgents 里声明的名字会被明确拒绝（而不是悄悄什么都不做）：");
        print(demo, probe("#tool:task {\"description\":\"x\",\"prompt\":\"y\","
                + "\"subagent_type\":\"notDeclared\"}"));
    }

    private void switchTable(Demo demo) {
        demo.section("三重开关的真实语义 —— enabled 是增量而非白名单");

        demo.info("%-46s | %s", "llm.tools.* 配置", "最终工具表");
        demo.info("-".repeat(112));
        printSwitch(demo, "builtin-enabled=false（默认）", false, false, false, List.of(), List.of());
        printSwitch(demo, "builtin-enabled=true", true, false, false, List.of(), List.of());
        printSwitch(demo, "  + allow-write=true", true, true, false, List.of(), List.of());
        printSwitch(demo, "  + allow-exec=true", true, false, true, List.of(), List.of());
        printSwitch(demo, "  + enabled=[read]（点名只读，结果仍是三件套）", true, false, false,
                List.of("read"), List.of());
        printSwitch(demo, "  + enabled=[todo_write, task]", true, false, false,
                List.of("todo_write", "task"), List.of());
        printSwitch(demo, "  + enabled=[write]（点名写类＝同时授权）", true, false, false,
                List.of("write"), List.of());
        printSwitch(demo, "  + enabled=[bash]（点名执行类＝同时授权）", true, false, false,
                List.of("bash"), List.of());
        printSwitch(demo, "  + enabled=[read, write], disable 不在 yml 里", true, false, false,
                List.of("read", "write"), List.of());

        demo.info("");
        demo.info("推算方式与 LlmBuiltinToolRegistrar 用的是同一套公开 API（见 BuiltinToolTable）");
        demo.info("denied-commands 的追加语义：内置 %d 条 + yml 追加 1 条 = %d 条",
                com.benxin.llm.core.sandbox.SandboxConfig.DEFAULT_DENIED_COMMANDS.size(),
                BuiltinToolTable.toConfig(properties.getTools()).deniedCommands().size());
    }

    private void printSwitch(Demo demo, String label, boolean builtin, boolean write, boolean exec,
                             List<String> enabled, List<String> extraDenied) {
        LlmProperties.ToolProperties tools = BuiltinToolTable.tools(workspace.root(), builtin,
                write, exec, enabled, List.of(), extraDenied);
        demo.info("%-46s | %s", label, BuiltinToolTable.toolNames(tools));
    }

    // ------------------------------------------------------------------

    /**
     * 跑一次探针，返回这一次的「工具返回内容 + meta」留痕。
     *
     * <p>每次都先清空 {@code <agentName>-default} 会话历史：{@code SandboxProbe} 开着记忆，
     * 而"上一轮的工具结果"会留在历史里，让我这边的"一次运行只强制一次工具"误判成
     * "已经拿到结果了"。清历史是让每一组演示彼此独立的最省事做法。</p>
     */
    private ToolAuditListener.ToolObservation probe(String instruction) {
        probeResult(instruction);
        return audit.latest();
    }

    /** 与 {@link #probe(String)} 相同，但把 {@code AgentResult} 也交回调用方。 */
    private AgentResult probeResult(String instruction) {
        audit.reset();
        clearHistories();
        return probe.runForResult(instruction);
    }

    private void clearHistories() {
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出"已经被解析过"的那些，用它遍历一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    /** 打印一次工具调用的返回内容与 meta —— 这就是"看得见"的关键。 */
    private void print(Demo demo, ToolAuditListener.ToolObservation observation) {
        if (observation == null) {
            demo.warn("(本次运行没有产生工具调用)");
            return;
        }
        if (observation.error()) {
            demo.warn("%s %s", observation.toolName(), observation.arguments());
            demo.warn("   error → %s", oneLine(observation.content(), 150));
        } else {
            demo.ok("%s %s", observation.toolName(), observation.arguments());
            format(demo, observation.content());
        }
        demo.info("   meta = %s", observation.meta());
    }

    private void format(Demo demo, String content) {
        String[] lines = content == null ? new String[0] : content.split("\n", -1);
        int shown = Math.min(lines.length, 8);
        for (int i = 0; i < shown; i++) {
            demo.info("   │ %s", oneLine(lines[i], 120));
        }
        if (lines.length > shown) {
            demo.info("   │ ...（共 %d 行，此处省略 %d 行）", lines.length, lines.length - shown);
        }
    }

    private static String oneLine(String text, int max) {
        return SandboxFenceDemo.oneLine(text, max);
    }

    /** 让测试与演示都能拿到"这次运行到底调用了什么工具"。 */
    public List<String> forcedToolLog() {
        return com.benxin.exp.sandbox.model.OnceForcedToolModel.class.isInstance(model.unwrap())
                ? ((com.benxin.exp.sandbox.model.OnceForcedToolModel) model.unwrap()).toolCallLog()
                : List.of();
    }

    /** 演示中未被直接使用的依赖，保留 getter 便于测试取用。 */
    public SandboxGuardTools guardTools() {
        return guardTools;
    }

    /** 供测试确认"Agent 的全局工具表"到底是什么。 */
    public List<String> toolNamesOfProbe() {
        return agentRegistry.get("sandboxProbe").tools().names().stream().sorted().toList();
    }

    /** 供测试确认 Agent 的沙箱与审批策略。 */
    public Agent sandboxProbeAgent() {
        return agentRegistry.get("sandboxProbe");
    }
}
