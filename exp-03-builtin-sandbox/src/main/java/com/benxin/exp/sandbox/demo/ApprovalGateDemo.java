package com.benxin.exp.sandbox.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.sandbox.audit.ToolAuditListener;
import com.benxin.exp.sandbox.agent.SandboxProbe;
import com.benxin.exp.sandbox.sandbox.RecordingApprovalHandler;
import com.benxin.exp.sandbox.sandbox.SandboxAgentFactory;
import com.benxin.exp.sandbox.sandbox.WorkdirScopedApprovalHandler;
import com.benxin.exp.sandbox.support.BuiltinToolTable;
import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审批与"守门人可替换"的演示：{@code ApprovalPolicy} 四态、{@code ApprovalHandler} 三种实现、
 * 沙箱拒绝的硬约束、自定义 {@code ToolSandbox} 与 {@code CompositeSandbox}、纵深防御。
 *
 * <p>这一组全部走<b>程序化装配</b>（{@link SandboxAgentFactory}）：审批策略是应用级配置，
 * 一个容器只能有一个取值，想在同一份上下文里对照四态就必须逐个换掉
 * {@code AgentBuilder.approvalPolicy(...)} 与 {@code approvalHandler(...)}。</p>
 */
@Component
public class ApprovalGateDemo {

    private final SandboxProbe probe;
    private final SandboxAgentFactory factory;
    private final SandboxWorkspace workspace;
    private final ToolAuditListener audit;
    private final LlmProperties properties;
    private final AgentRegistry agentRegistry;
    private final AtomicInteger sessionSeq = new AtomicInteger();

    public ApprovalGateDemo(SandboxProbe probe, SandboxAgentFactory factory,
                            SandboxWorkspace workspace, ToolAuditListener audit,
                            LlmProperties properties, AgentRegistry agentRegistry) {
        this.probe = probe;
        this.factory = factory;
        this.workspace = workspace;
        this.audit = audit;
        this.properties = properties;
        this.agentRegistry = agentRegistry;
    }

    public void run(Demo demo) {
        policyMatrix(demo);
        handlers(demo);
        sandboxBeatsApproval(demo);
        customSandbox(demo);
        defenseInDepth(demo);
        switchIndependence(demo);
    }

    // ------------------------------------------------------------------
    // ① ApprovalPolicy 四态
    // ------------------------------------------------------------------

    private void policyMatrix(Demo demo) {
        demo.section("ApprovalPolicy 四态 —— 策略决定「问不问」，处理器决定「批不批」");

        demo.info("read 的 requiresApproval()=false，write / edit / bash 的 requiresApproval()=true");
        demo.info("%-12s | %-42s | %s", "策略", "只读工具 read（不需要审批）", "写工具 write（需要审批）");
        demo.info("-".repeat(112));

        for (ApprovalPolicy policy : ApprovalPolicy.values()) {
            RecordingApprovalHandler handler = RecordingApprovalHandler.alwaysApprove();
            RunResult read = run(factory.probe("lab-read-" + policy, policy, handler),
                    "#tool:read {\"path\":\"a.txt\"}");
            int readAsks = handler.count();

            handler.reset();
            String target = "generated/policy-" + policy.name().toLowerCase() + ".txt";
            RunResult write = run(factory.probe("lab-write-" + policy, policy, handler),
                    "#tool:write {\"path\":\"" + target + "\",\"content\":\"policy-demo\"}");
            int writeAsks = handler.count();

            demo.info("%-12s | 问了 %d 次，%s | 问了 %d 次，%s", policy.name(),
                    readAsks, read.success() ? "执行成功" : "未执行成功",
                    writeAsks, write.success() ? "执行成功" : "未执行成功");
        }

        demo.info("");
        demo.warn("NEVER 是「不问、直接放行」，是四态里最宽松的一档（这一点很容易猜反）；");
        demo.warn("想一律拒绝请用 ApprovalHandler.denyAll()，而不是 NEVER。");
        demo.warn("ON_FAILURE 在 v1.1.0 里 needsApproval() 恒为 false，行为与 NEVER 完全一致：");
        demo.warn("  「被沙箱拒绝后再问一次」这条文档口径在代码里没有对应实现（详见 README「容易猜错的口径」）。");

        // ON_FAILURE 的实测证据：问 0 次
        RecordingApprovalHandler onFailure = RecordingApprovalHandler.alwaysApprove();
        run(factory.probe("lab-onfailure", ApprovalPolicy.ON_FAILURE, onFailure),
                "#tool:write {\"path\":\"generated/on-failure.txt\",\"content\":\"x\"}");
        demo.info("实测：ON_FAILURE 下写文件被问次数 = %d", onFailure.count());
    }

    // ------------------------------------------------------------------
    // ② ApprovalHandler 三种实现
    // ------------------------------------------------------------------

    private void handlers(Demo demo) {
        demo.section("ApprovalHandler 三种实现 —— autoApprove / denyAll / 自定义");

        // autoApprove：内置默认 bean 就是它（无人值守环境下弹窗式审批只会把请求挂死）
        RecordingApprovalHandler auto = RecordingApprovalHandler.alwaysApprove();
        RunResult ok = run(factory.probe("lab-auto", ApprovalPolicy.ON_REQUEST, auto),
                "#tool:write {\"path\":\"generated/auto.txt\",\"content\":\"auto\"}");
        demo.ok("autoApprove：被问 %d 次 → %s", auto.count(), ok.oneLine());

        // denyAll：被拒绝时模型收到的是一句可读的中文错误，而不是异常
        RecordingApprovalHandler deny = RecordingApprovalHandler.denyAll();
        RunResult denied = run(factory.probe("lab-deny", ApprovalPolicy.ON_REQUEST, deny),
                "#tool:write {\"path\":\"generated/denied.txt\",\"content\":\"denied\"}");
        demo.warn("denyAll：被问 %d 次 → 模型收到的工具结果：%s", deny.count(), denied.toolContent());

        // denyAll 不会拦住只读工具：ON_REQUEST 下 read 的 requiresApproval()=false，根本不问
        RecordingApprovalHandler deny2 = RecordingApprovalHandler.denyAll();
        RunResult readOk = run(factory.probe("lab-deny-read", ApprovalPolicy.ON_REQUEST, deny2),
                "#tool:read {\"path\":\"a.txt\"}");
        demo.info("denyAll + ON_REQUEST 下 read：被问 %d 次，%s（拒绝一切 ≠ 拒绝一切工具）",
                deny2.count(), readOk.success() ? "照常执行" : "被拦住了");

        // 自定义：按 工具名 + 参数 + sessionId 决定
        WorkdirScopedApprovalHandler custom =
                new WorkdirScopedApprovalHandler(workspace.root().toString());
        Agent agent = factory.probe("lab-custom", ApprovalPolicy.ON_REQUEST, custom);

        RunResult writeInside = run(agent, "#tool:write {\"path\":\"generated/custom.txt\",\"content\":\"ok\"}");
        RunResult bashDenied = run(agent, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        RunResult untrusted = run(agent, "untrusted-session-7",
                "#tool:write {\"path\":\"generated/evil.txt\",\"content\":\"x\"}");

        demo.info("");
        demo.info("自定义 WorkdirScopedApprovalHandler（围栏 %s）：", workspace.root());
        custom.decisions().forEach(d -> demo.info("   %s", d.summary()));
        demo.ok("围栏内 write → %s", writeInside.success() ? "执行成功" : "未执行");
        demo.warn("bash → %s", bashDenied.toolContent());
        demo.warn("untrusted 会话里的 write → %s", untrusted.toolContent());
    }

    // ------------------------------------------------------------------
    // ③ 沙箱拒绝是硬约束
    // ------------------------------------------------------------------

    private void sandboxBeatsApproval(Demo demo) {
        demo.section("沙箱拒绝是硬约束 —— 换成「永远批准」也推翻不了它（v1.1.0 的行为变更）");

        RecordingApprovalHandler always = RecordingApprovalHandler.alwaysApprove();
        Agent agent = factory.probe("lab-hard-fence", ApprovalPolicy.ON_FAILURE, always);

        RunResult escape = run(agent, "#tool:read {\"path\":\"../../etc/passwd\"}");
        demo.warn("ON_FAILURE + autoApprove 读 ../../etc/passwd → %s", escape.toolContent());
        demo.info("审批被问次数 = %d（沙箱拒绝压根不会走到审批那一步）", always.count());

        RunResult secret = run(agent, "#tool:read {\"path\":\".env\"}");
        demo.warn("ON_FAILURE + autoApprove 读 .env → %s", secret.toolContent());

        // 再换 ALWAYS：审批确实被问了，但沙箱仍然先拦下
        RecordingApprovalHandler alwaysAsked = RecordingApprovalHandler.alwaysApprove();
        Agent alwaysAgent = factory.probe("lab-hard-fence-always", ApprovalPolicy.ALWAYS, alwaysAsked);
        RunResult escape2 = run(alwaysAgent, "#tool:read {\"path\":\"../../etc/passwd\"}");
        demo.info("ALWAYS 下同样越界读：审批被问 %d 次（沙箱在审批之前就拦下了）→ %s",
                alwaysAsked.count(), escape2.toolContent());

        // 对照：同一套配置下，围栏内的读为什么能成
        RunResult inside = run(agent, "#tool:read {\"path\":\"a.txt\"}");
        demo.ok("同一 Agent 读围栏内的 a.txt → %s", inside.success() ? "成功" : "失败");
    }

    // ------------------------------------------------------------------
    // ④ 自定义 ToolSandbox
    // ------------------------------------------------------------------

    private void customSandbox(Demo demo) {
        demo.section("自定义 ToolSandbox —— 实现 check() 即可整体接管默认沙箱");

        ToolSandbox noBash = invocation -> {
            String tool = invocation.toolName() == null ? "" : invocation.toolName();
            return "bash".equals(tool)
                    ? ToolSandbox.Decision.deny("自定义沙箱：本环境禁止一切命令执行（工具 [" + tool + "]）")
                    : ToolSandbox.Decision.allow();
        };

        Agent custom = factory.probe("lab-custom-sandbox", noBash, ApprovalPolicy.ON_REQUEST,
                RecordingApprovalHandler.alwaysApprove());
        RunResult bash = run(custom, "#tool:bash {\"command\":\"echo benxin-sandbox-ok\"}");
        RunResult read = run(custom, "#tool:read {\"path\":\"a.txt\"}");
        demo.warn("自定义沙箱拦 bash → %s", bash.toolContent());
        demo.ok("自定义沙箱放行 read → %s", read.success() ? "成功" : "失败");

        demo.info("");
        SandboxConfig config = factory.containerConfig();
        ToolSandbox composite = CompositeSandbox.of(noBash, new PathSandbox(config));
        demo.info("CompositeSandbox.of(自定义, PathSandbox) = %s", composite);
        demo.info("   members() = %s", ((CompositeSandbox) composite).members().stream()
                .map(member -> member.getClass().getSimpleName()).toList());
        demo.warn("   组合体判 bash          → %s", oneLine(composite.check(
                invocation("bash", Map.of("command", "echo hi"))).reason(), 100));
        demo.warn("   组合体判越界 read      → %s", oneLine(composite.check(
                invocation("read", Map.of("path", "../../etc/passwd"))).reason(), 100));
        demo.ok("   组合体判围栏内 read    → allowed=%s", composite.check(
                invocation("read", Map.of("path", "a.txt"))).allowed());
        demo.info("   组合语义是「与」不是「或」：任何一层拒绝即拒绝，多装一层只会更严");

        demo.info("");
        demo.info("想让它成为容器级的守门人？定义一个同类型 bean 即可（内置带 @ConditionalOnMissingBean）：");
        demo.info("   @Bean ToolSandbox mySandbox() { return CompositeSandbox.of(自定义, 内置); }");
        demo.info("   测试里用 ApplicationContextRunner + withBean 验证了这条接管路径。");
    }

    // ------------------------------------------------------------------
    // ⑤ 纵深防御
    // ------------------------------------------------------------------

    private void defenseInDepth(Demo demo) {
        demo.section("纵深防御 —— 沙箱被换成 permissive，内置工具自己也守得住");

        Agent permissive = factory.probe("lab-permissive", ToolSandbox.permissive(),
                ApprovalPolicy.ON_REQUEST, RecordingApprovalHandler.alwaysApprove());

        RunResult escape = run(permissive, "#tool:read {\"path\":\"../../etc/passwd\"}");
        demo.warn("permissive 沙箱 + read 越界 → %s", escape.toolContent());
        demo.warn("permissive 沙箱 + read 敏感路径 .env → %s",
                run(permissive, "#tool:read {\"path\":\".env\"}").toolContent());
        demo.warn("permissive 沙箱 + write 越界 → %s",
                run(permissive, "#tool:write {\"path\":\"../../escape.txt\",\"content\":\"x\"}").toolContent());
        demo.ok("permissive 沙箱 + read 围栏内 a.txt → %s",
                run(permissive, "#tool:read {\"path\":\"a.txt\"}").success() ? "照常成功" : "失败");

        demo.info("原因：每个内置工具都持有自己的 SandboxConfig，落地前会再走一遍 PathSandbox.resolve。");
    }

    // ------------------------------------------------------------------
    // ⑥ 沙箱与内置工具开关无关
    // ------------------------------------------------------------------

    private void switchIndependence(Demo demo) {
        demo.section("沙箱与内置工具开关无关 —— builtin-enabled=false 时围栏依然在");

        ToolSandbox containerSandbox = factory.containerSandbox();
        PathSandbox pathSandbox = SandboxWorkspace.pathSandboxOf(containerSandbox);
        demo.info("容器里的 ToolSandbox = %s（%s）", containerSandbox.getClass().getSimpleName(),
                pathSandbox == null ? "取不到 PathSandbox 成员" : pathSandbox.config().fence());

        LlmProperties.ToolProperties off = BuiltinToolTable.tools(workspace.root(), false,
                false, false, List.of(), List.of(), List.of());
        SandboxConfig offConfig = BuiltinToolTable.toConfig(off);
        demo.info("builtin-enabled=false 时：工具表 = %s（空集：一个内置工具都不装配）",
                BuiltinToolTable.toolNames(off));
        demo.info("但同一份配置翻译出来的沙箱仍是真的围栏：allowWrite=%s allowExec=%s 围栏=%s",
                offConfig.allowWrite(), offConfig.allowExec(), offConfig.fence());

        ToolSandbox offFence = CompositeSandbox.of(new PathSandbox(offConfig), new CommandSandbox(offConfig));
        ToolInvocation selfWritten = invocation("guarded_echo", Map.of("path", "../../etc/passwd"));
        demo.warn("自写工具 guarded_echo 越界 → %s", oneLine(offFence.check(selfWritten).reason(), 110));

        demo.info("");
        demo.info("容器里（builtin-enabled=true）让自写工具真的跑一次越界路径：");
        RunResult escape = run(probe, "#tool:guarded_echo {\"path\":\"../../etc/passwd\"}");
        demo.warn("   %s", escape.toolContent());
        RunResult ok = run(probe, "#tool:guarded_echo {\"path\":\"docs/guide.txt\"}");
        demo.ok("   围栏内 → %s", ok.toolContent());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ToolInvocation invocation(String tool, Map<String, Object> arguments) {
        return new ToolInvocation("lab-" + tool, tool, arguments, "lab", "lab-session", 1);
    }

    /** 一次探针运行的结果视图：既要"成功与否"，也要"模型到底收到了什么"。 */
    private record RunResult(AgentResult result, ToolAuditListener.ToolObservation observation) {

        boolean success() {
            return observation != null && !observation.error();
        }

        String toolContent() {
            return observation == null ? "(没有工具被调用)"
                    : ApprovalGateDemo.oneLine(observation.content(), 150);
        }

        String oneLine() {
            return ApprovalGateDemo.oneLine(result == null ? "" : result.text(), 90);
        }
    }

    private RunResult run(Agent agent, String instruction) {
        return run(agent, "lab-session-" + sessionSeq.incrementAndGet(), instruction);
    }

    private RunResult run(Agent agent, String sessionId, String instruction) {
        audit.reset();
        AgentResult result = agent.call(instruction, sessionId);
        return new RunResult(result, audit.latest());
    }

    /** 声明式 Agent 的重载：先清历史，免得上一轮的 TOOL 消息把这一轮误判成"已有结果"。 */
    private RunResult run(SandboxProbe declarative, String instruction) {
        audit.reset();
        clearHistories();
        AgentResult result = declarative.runForResult(instruction);
        return new RunResult(result, audit.latest());
    }

    private void clearHistories() {
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    private static String oneLine(String text, int max) {
        return SandboxFenceDemo.oneLine(text, max);
    }
}
