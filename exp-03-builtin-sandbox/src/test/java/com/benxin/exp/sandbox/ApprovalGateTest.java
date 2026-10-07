package com.benxin.exp.sandbox;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.sandbox.agent.SandboxProbe;
import com.benxin.exp.sandbox.audit.ToolAuditListener;
import com.benxin.exp.sandbox.sandbox.RecordingApprovalHandler;
import com.benxin.exp.sandbox.sandbox.SandboxAgentFactory;
import com.benxin.exp.sandbox.sandbox.WorkdirScopedApprovalHandler;
import com.benxin.exp.sandbox.support.BuiltinToolTable;
import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.CommandSandbox;
import com.benxin.llm.core.sandbox.CompositeSandbox;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批与"守门人可替换"的断言：{@code ApprovalPolicy} 四态、{@code ApprovalHandler} 三种实现、
 * 沙箱拒绝的硬约束、自定义 {@code ToolSandbox}、纵深防御、以及"沙箱与内置开关无关"。
 *
 * <p>全部走程序化装配（{@link SandboxAgentFactory}）：审批策略是应用级配置，一个容器只能有一个
 * 取值，想在同一份上下文里对照四态就必须逐个换 {@code AgentBuilder.approvalPolicy(...)}。</p>
 */
@SpringBootTest
@DisplayName("示例 03 · 审批四态与可替换守门人")
class ApprovalGateTest {

    @Autowired
    private SandboxProbe probe;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private ToolAuditListener audit;

    @Autowired
    private SandboxWorkspace workspace;

    @Autowired
    private SandboxAgentFactory factory;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LlmProperties properties;

    @Autowired
    private ToolSandbox containerSandbox;

    @Autowired
    private SandboxConfig containerConfig;

    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        audit.reset();
        workspace.ensure();
        workspace.resetOutputs();
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    // ------------------------------------------------------------------
    // 四态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NEVER：不问、直接放行 —— 四态里最宽松的一档（很容易猜反）")
    void neverMeansNeverAskAndAlwaysAllow() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.denyAll();
        Agent agent = factory.probe("t-never", ApprovalPolicy.NEVER, handler);

        Observed read = run(agent, "#tool:read {\"path\":\"a.txt\"}");
        Observed write = run(agent, "#tool:write {\"path\":\"tests/never.txt\",\"content\":\"x\"}");

        assertThat(handler.count()).isZero();
        assertThat(read.error()).isFalse();
        // 连"永远拒绝"的 handler 都拦不住它：NEVER 根本不问
        assertThat(write.error()).isFalse();
    }

    @Test
    @DisplayName("ON_REQUEST：只有 requiresApproval()=true 的工具才问（默认）")
    void onRequestAsksOnlyForApprovalTools() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.alwaysApprove();
        Agent agent = factory.probe("t-on-request", ApprovalPolicy.ON_REQUEST, handler);

        run(agent, "#tool:read {\"path\":\"a.txt\"}");
        assertThat(handler.count()).isZero();

        run(agent, "#tool:write {\"path\":\"tests/on-request.txt\",\"content\":\"x\"}");
        assertThat(handler.count()).isEqualTo(1);
        assertThat(handler.toolNames()).containsExactly("write");
    }

    @Test
    @DisplayName("ALWAYS：每次工具调用都要审批，连只读工具也不例外")
    void alwaysAsksForEveryTool() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.alwaysApprove();
        Agent agent = factory.probe("t-always", ApprovalPolicy.ALWAYS, handler);

        run(agent, "#tool:read {\"path\":\"a.txt\"}");
        assertThat(handler.count()).isEqualTo(1);
        assertThat(handler.toolNames()).containsExactly("read");

        run(agent, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        assertThat(handler.count()).isEqualTo(2);
        assertThat(handler.toolNames()).containsExactly("read", "bash");
    }

    @Test
    @DisplayName("ON_FAILURE：v1.1.0 里恒不询问，行为与 NEVER 完全一致")
    void onFailureBehavesLikeNever() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.denyAll();
        Agent agent = factory.probe("t-on-failure", ApprovalPolicy.ON_FAILURE, handler);

        Observed write = run(agent, "#tool:write {\"path\":\"tests/on-failure.txt\",\"content\":\"x\"}");

        // 文档（docs/ARCHITECTURE.md 六、README 内置工具章节）说 ON_FAILURE 是
        // "被沙箱拒绝或执行失败后再问一次"，但 DefaultLoopContext#needsApproval 对它直接返回 false，
        // 且沙箱拒绝分支根本不会走到审批 —— 所以实际行为 = NEVER。见 README「插件问题」第 1 条。
        assertThat(handler.count()).isZero();
        assertThat(write.error()).isFalse();
    }

    // ------------------------------------------------------------------
    // ApprovalHandler 三种实现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("autoApprove / denyAll 是内置的两种极端实现")
    void builtinHandlerFactoriesWork() {
        RecordingApprovalHandler approving = RecordingApprovalHandler.alwaysApprove();
        Observed ok = run(factory.probe("t-auto", ApprovalPolicy.ON_REQUEST, approving),
                "#tool:write {\"path\":\"tests/auto.txt\",\"content\":\"x\"}");
        assertThat(ok.error()).isFalse();
        assertThat(approving.count()).isEqualTo(1);

        RecordingApprovalHandler denying = RecordingApprovalHandler.denyAll();
        Observed denied = run(factory.probe("t-deny", ApprovalPolicy.ON_REQUEST, denying),
                "#tool:write {\"path\":\"tests/denied.txt\",\"content\":\"x\"}");
        assertThat(denied.error()).isTrue();
        // 模型收到的是一句可读的中文错误，而不是异常
        assertThat(denied.content()).isEqualTo("用户拒绝执行工具 [write]");
    }

    @Test
    @DisplayName("denyAll + ON_REQUEST 不会拦住只读工具（拒绝一切 ≠ 拒绝一切工具）")
    void denyAllDoesNotBlockReadOnlyToolsUnderOnRequest() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.denyAll();
        Agent agent = factory.probe("t-deny-read", ApprovalPolicy.ON_REQUEST, handler);

        Observed read = run(agent, "#tool:read {\"path\":\"a.txt\"}");

        assertThat(handler.count()).isZero();
        assertThat(read.error()).isFalse();
        // 同一个 handler 在 ALWAYS 下就真的拦得住了
        Agent always = factory.probe("t-deny-read-always", ApprovalPolicy.ALWAYS, handler);
        assertThat(run(always, "#tool:read {\"path\":\"a.txt\"}").error()).isTrue();
    }

    @Test
    @DisplayName("自定义 ApprovalHandler：按工具名 + 参数 + sessionId 决定批不批")
    void customHandlerDecidesByToolArgumentAndSession() {
        WorkdirScopedApprovalHandler handler =
                new WorkdirScopedApprovalHandler(workspace.root().toString());
        Agent agent = factory.probe("t-custom", ApprovalPolicy.ON_REQUEST, handler);

        Observed inside = run(agent, "#tool:write {\"path\":\"tests/custom.txt\",\"content\":\"x\"}");
        Observed bash = run(agent, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        Observed untrusted = runIn(agent, "untrusted-42",
                "#tool:write {\"path\":\"tests/evil.txt\",\"content\":\"x\"}");

        assertThat(inside.error()).isFalse();
        assertThat(bash.error()).isTrue();
        assertThat(bash.content()).isEqualTo("用户拒绝执行工具 [bash]");
        assertThat(untrusted.error()).isTrue();
        assertThat(untrusted.content()).isEqualTo("用户拒绝执行工具 [write]");

        List<WorkdirScopedApprovalHandler.Decision> decisions = handler.decisions();
        assertThat(decisions).hasSize(3);
        assertThat(decisions.get(0).approved()).isTrue();
        assertThat(decisions.get(1).reason()).contains("命令执行的审批一律交人工");
        assertThat(decisions.get(2).reason()).contains("untrusted-42").contains("不可信");
    }

    // ------------------------------------------------------------------
    // 沙箱拒绝是硬约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("沙箱拒绝是硬约束：ON_FAILURE + 永远批准，越界路径依然被拒绝")
    void sandboxDenialCannotBeOverriddenByApproval() {
        RecordingApprovalHandler alwaysApprove = RecordingApprovalHandler.alwaysApprove();
        Agent agent = factory.probe("t-hard", ApprovalPolicy.ON_FAILURE, alwaysApprove);

        Observed escape = run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}");
        Observed secret = run(agent, "#tool:read {\"path\":\".env\"}");
        Observed writeEscape = run(agent,
                "#tool:write {\"path\":\"../../escape.txt\",\"content\":\"x\"}");

        assertThat(escape.error()).isTrue();
        assertThat(escape.content()).contains("沙箱拒绝执行").contains("已越过沙箱围栏");
        assertThat(secret.content()).contains("命中沙箱禁止访问规则");
        assertThat(writeEscape.content()).contains("已越过沙箱围栏");
        // 沙箱拒绝压根不会走到审批那一步
        assertThat(alwaysApprove.count()).isZero();

        // 对照：同一 Agent 读围栏内文件完全正常
        assertThat(run(agent, "#tool:read {\"path\":\"a.txt\"}").error()).isFalse();
    }

    @Test
    @DisplayName("ALWAYS 策略下沙箱仍然先拦下：审批顺序在沙箱之后")
    void approvalComesAfterSandbox() {
        RecordingApprovalHandler handler = RecordingApprovalHandler.alwaysApprove();
        Agent agent = factory.probe("t-order", ApprovalPolicy.ALWAYS, handler);

        Observed escape = run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}");

        assertThat(escape.error()).isTrue();
        assertThat(handler.count()).isZero();
    }

    // ------------------------------------------------------------------
    // 自定义 ToolSandbox
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自定义 ToolSandbox：实现 check() 即整体接管默认沙箱")
    void customSandboxTakesOver() {
        ToolSandbox noBash = invocation -> "bash".equals(invocation.toolName())
                ? ToolSandbox.Decision.deny("自定义沙箱：本环境禁止一切命令执行")
                : ToolSandbox.Decision.allow();
        Agent agent = factory.probe("t-custom-sandbox", noBash, ApprovalPolicy.ON_REQUEST,
                RecordingApprovalHandler.alwaysApprove());

        Observed bash = run(agent, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        Observed read = run(agent, "#tool:read {\"path\":\"a.txt\"}");

        assertThat(bash.error()).isTrue();
        assertThat(bash.content()).contains("自定义沙箱");
        assertThat(read.error()).isFalse();
        // 自定义沙箱不认路径 —— 但内置 read 自己仍然守着围栏（纵深防御）：
        // 想让"越界"这件事被沙箱层拦下，就必须把自定义沙箱与 PathSandbox 组合起来。
        Observed escape = run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}");
        assertThat(escape.error()).isTrue();
        assertThat(escape.content()).contains("已越过沙箱围栏");
        // 注意：这一条拒绝来自工具自身的自查，而不是来自那个只认工具名的自定义沙箱
        assertThat(noBash.check(new ToolInvocation("p", "read",
                Map.of("path", "../../etc/passwd"), "t", "s", 1)).allowed()).isTrue();
    }

    @Test
    @DisplayName("CompositeSandbox.of(自定义, PathSandbox)：取最严结论，越界照样被拦")
    void compositeSandboxTakesStrictestDecision() {
        ToolSandbox noBash = invocation -> "bash".equals(invocation.toolName())
                ? ToolSandbox.Decision.deny("自定义沙箱：禁止命令执行")
                : ToolSandbox.Decision.allow();
        ToolSandbox composite = CompositeSandbox.of(noBash, new PathSandbox(containerConfig));

        assertThat(composite.check(new ToolInvocation("a", "bash",
                Map.of("command", "echo hi"), "t", "s", 1)).reason()).contains("自定义沙箱");
        assertThat(composite.check(new ToolInvocation("b", "read",
                Map.of("path", "../../etc/passwd"), "t", "s", 1)).reason()).contains("已越过沙箱围栏");
        assertThat(composite.check(new ToolInvocation("c", "read",
                Map.of("path", "a.txt"), "t", "s", 1)).allowed()).isTrue();

        Agent agent = factory.probe("t-composite", composite, ApprovalPolicy.ON_REQUEST,
                RecordingApprovalHandler.alwaysApprove());
        assertThat(run(agent, "#tool:bash {\"command\":\"echo hi\"}").error()).isTrue();
        assertThat(run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}").error()).isTrue();
        assertThat(run(agent, "#tool:read {\"path\":\"a.txt\"}").error()).isFalse();

        // 组合体不会比任何单个成员更宽松
        assertThat(composite.timeout()).isLessThanOrEqualTo(containerConfig.execTimeout());
        assertThat(composite.maxResultChars()).isEqualTo(containerConfig.maxOutputChars());
    }

    // ------------------------------------------------------------------
    // 纵深防御
    // ------------------------------------------------------------------

    @Test
    @DisplayName("纵深防御：沙箱换成 permissive，内置工具自己也守得住")
    void builtinToolsKeepTheirOwnFence() {
        Agent agent = factory.probe("t-permissive", ToolSandbox.permissive(),
                ApprovalPolicy.ON_REQUEST, RecordingApprovalHandler.alwaysApprove());

        Observed escape = run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}");
        Observed secret = run(agent, "#tool:read {\"path\":\".env\"}");
        Observed write = run(agent, "#tool:write {\"path\":\"../../escape.txt\",\"content\":\"x\"}");
        Observed inside = run(agent, "#tool:read {\"path\":\"a.txt\"}");

        assertThat(escape.error()).isTrue();
        assertThat(escape.content()).contains("已越过沙箱围栏");
        assertThat(secret.content()).contains("命中沙箱禁止访问规则");
        assertThat(write.content()).contains("已越过沙箱围栏");
        assertThat(inside.error()).isFalse();
    }

    @Test
    @DisplayName("纵深防御（命令侧）：permissive 沙箱下 bash 仍被自己的黑名单拦住")
    void bashToolKeepsItsOwnCommandGate() {
        Agent agent = factory.probe("t-permissive-bash", ToolSandbox.permissive(),
                ApprovalPolicy.NEVER, ApprovalHandler.autoApprove());

        // 这里只是让"命令工具内部的自查"可被观察：BashTool 会先跑 CommandSandbox.denyReason
        Observed benign = run(agent, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        assertThat(benign.error()).isFalse();
        assertThat(benign.content()).contains("benxin-sandbox-ok");

        SandboxConfig readOnly = SandboxConfig.builder().workdir(workspace.root()).build();
        assertThat(CommandSandbox.denyReason(readOnly, "rm -rf /")).contains("allowExec=false");
    }

    // ------------------------------------------------------------------
    // 沙箱与内置工具开关无关
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builtin-enabled=false 时工具表为空，但沙箱依旧是真的围栏")
    void sandboxIsIndependentOfBuiltinSwitch() {
        LlmProperties.ToolProperties off = BuiltinToolTable.tools(workspace.root(), false,
                false, false, List.of(), List.of(), List.of());

        assertThat(BuiltinToolTable.toolNames(off)).isEmpty();

        SandboxConfig offConfig = BuiltinToolTable.toConfig(off);
        assertThat(offConfig.allowWrite()).isFalse();
        assertThat(offConfig.allowExec()).isFalse();
        assertThat(offConfig.workdirAbsolute()).isEqualTo(workspace.root());

        ToolSandbox offFence = CompositeSandbox.of(
                new PathSandbox(offConfig), new CommandSandbox(offConfig));
        assertThat(offFence.check(new ToolInvocation("x", "guarded_echo",
                Map.of("path", "../../etc/passwd"), "t", "s", 1)).denied()).isTrue();
    }

    @Test
    @DisplayName("自己写的 @LlmTool 同样受围栏约束（容器 builtin-enabled=true 时也一样）")
    void selfWrittenToolsAreFencedToo() {
        Observed escape = run(probe, "#tool:guarded_echo {\"path\":\"../../etc/passwd\"}");
        Observed inside = run(probe, "#tool:guarded_echo {\"path\":\"docs/guide.txt\"}");

        assertThat(escape.error()).isTrue();
        assertThat(escape.content()).contains("沙箱拒绝执行").contains("已越过沙箱围栏");
        assertThat(inside.error()).isFalse();
        assertThat(inside.content()).contains("docs/guide.txt").contains("CompositeSandbox");

        // 容器里的沙箱确实来自 real 装配，而不是演算出来的
        assertThat(properties.getTools().isBuiltinEnabled()).isTrue();
        assertThat(SandboxWorkspace.pathSandboxOf(containerSandbox)).isNotNull();
    }

    // ------------------------------------------------------------------

    /** 一次探针运行的两个观察点：工具结果（含 error）与 AgentResult。 */
    private record Observed(boolean error, String content) {
    }

    private Observed run(Agent agent, String instruction) {
        return runIn(agent, "t-session-" + sessions.incrementAndGet(), instruction);
    }

    private Observed runIn(Agent agent, String sessionId, String instruction) {
        audit.reset();
        agent.call(instruction, sessionId);
        ToolAuditListener.ToolObservation observation = audit.latest();
        return observation == null
                ? new Observed(true, "(没有工具被调用)")
                : new Observed(observation.error(), observation.content());
    }

    private Observed run(SandboxProbe declarative, String instruction) {
        audit.reset();
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
        declarative.runForResult(instruction);
        ToolAuditListener.ToolObservation observation = audit.latest();
        return observation == null
                ? new Observed(true, "(没有工具被调用)")
                : new Observed(observation.error(), observation.content());
    }
}
