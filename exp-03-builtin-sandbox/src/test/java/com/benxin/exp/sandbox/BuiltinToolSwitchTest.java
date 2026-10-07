package com.benxin.exp.sandbox;

import com.benxin.llm.core.sandbox.CommandSandbox;
import com.benxin.llm.core.sandbox.CompositeSandbox;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.spring.LlmAutoConfiguration;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmBuiltinToolRegistrar;
import com.benxin.llm.spring.LlmProperties;
import com.benxin.llm.spring.LlmToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三重开关的真实语义 —— 用 {@link ApplicationContextRunner} 起<b>多个不同配置的容器</b>对照验证。
 *
 * <p>为什么不用 {@code @SpringBootTest}：三重开关是"装配期"行为，一个容器只能有一种取值；
 * 想在一份测试里对照七八种组合，{@code ApplicationContextRunner} 既快又干净
 * （不启动 Web、不跑 ApplicationRunner）。</p>
 *
 * <p>顺便验证了两条容易猜错的口径：{@code enabled} 是<b>增量</b>而非白名单；
 * {@code denied-commands} 是<b>追加</b>而非替换。</p>
 */
@DisplayName("示例 03 · 三重开关（ApplicationContextRunner 多容器对照）")
class BuiltinToolSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class,
                    LlmAgentAutoConfiguration.class));

    private static final String WORKDIR = "target/sandbox";

    // ------------------------------------------------------------------
    // 总开关
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builtin-enabled 默认 false：一个内置工具都不会进工具表")
    void builtinToolsAreOptIn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmToolCatalog.class).global().names()).isEmpty();
        });
    }

    @Test
    @DisplayName("builtin-enabled=false 时沙箱依然是真正的 PathSandbox + CommandSandbox")
    void sandboxStaysRealWhenBuiltinsAreOff() {
        runner.withPropertyValues("llm.tools.builtin-enabled=false")
                .run(context -> {
                    ToolSandbox sandbox = context.getBean(ToolSandbox.class);

                    assertThat(sandbox).isNotSameAs(ToolSandbox.permissive());
                    assertThat(sandbox).isInstanceOf(CompositeSandbox.class);
                    assertThat(((CompositeSandbox) sandbox).members())
                            .hasAtLeastOneElementOfType(PathSandbox.class)
                            .hasAtLeastOneElementOfType(CommandSandbox.class);

                    // 自己写的工具同样受围栏约束
                    assertThat(sandbox.check(new ToolInvocation("t", "my_own_tool",
                            Map.of("path", "../../etc/passwd"), "a", "s", 1)).denied()).isTrue();
                    assertThat(sandbox.check(new ToolInvocation("t", "my_own_tool",
                            Map.of("path", "a.txt"), "a", "s", 1)).allowed()).isTrue();
                });
    }

    // ------------------------------------------------------------------
    // 只读基线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builtin-enabled=true：默认基线就是只读三件套 read/glob/grep")
    void builtinBaselineIsReadOnlyTriple() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR)
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "glob", "grep"));
    }

    @Test
    @DisplayName("allow-write=true 放出 write/edit；allow-exec=true 放出 bash")
    void authorisationSwitchesAddTools() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-write=true")
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "glob", "grep", "write", "edit"));

        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-exec=true")
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "glob", "grep", "bash"));
    }

    // ------------------------------------------------------------------
    // enabled：增量而非白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("enabled 是增量：enabled=[read] 的最终结果是 read + glob + grep")
    void enabledIsIncrementalNotAnAllowlist() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.enabled=read")
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "glob", "grep"));
    }

    @Test
    @DisplayName("todo_write 与 task 不在任何默认基线里，必须显式点名")
    void todoWriteAndTaskMustBeNamedExplicitly() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.enabled=todo_write,task")
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "glob", "grep", "todo_write", "task"));
    }

    @Test
    @DisplayName("enabled 里点名写类工具等价于同时授权：enabled=[write] 会连 edit 一起放出，沙箱也同步 allowWrite")
    void namingWriteToolImpliesWriteAuthorisation() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.enabled=write")
                .run(context -> {
                    assertThat(names(context)).containsExactlyInAnyOrder(
                            "read", "glob", "grep", "write", "edit");

                    SandboxConfig config = pathSandboxConfig(context);
                    assertThat(config.allowWrite()).isTrue();
                    // 授权与工具集同源：写工具不会"看得见却跑不动"
                    assertThat(context.getBean(ToolSandbox.class).check(new ToolInvocation(
                            "t", "write", Map.of("path", "a.txt", "content", "x"),
                            "a", "s", 1)).allowed()).isTrue();
                });
    }

    @Test
    @DisplayName("enabled 里点名执行类工具等价于同时授权：enabled=[bash] 会同步 allowExec")
    void namingExecToolImpliesExecAuthorisation() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.enabled=bash")
                .run(context -> {
                    assertThat(names(context)).containsExactlyInAnyOrder(
                            "read", "glob", "grep", "bash");
                    assertThat(pathSandboxConfig(context).allowExec()).isTrue();
                });
    }

    @Test
    @DisplayName("八个全开：builtin-enabled + allow-write + allow-exec + enabled=[todo_write, task]")
    void allEightToolsRequireAllThreeSwitches() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-write=true",
                        "llm.tools.allow-exec=true",
                        "llm.tools.enabled=todo_write,task")
                .run(context -> assertThat(names(context)).containsExactlyInAnyOrder(
                        "read", "write", "edit", "glob", "grep", "bash", "todo_write", "task"));
    }

    // ------------------------------------------------------------------
    // 命令白/黑名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("allowed-commands 是替换语义；denied-commands 是追加在内置 29 条之上")
    void commandListsSemantics() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-exec=true",
                        "llm.tools.allowed-commands=echo,cd",
                        "llm.tools.denied-commands=docker")
                .run(context -> {
                    SandboxConfig config = pathSandboxConfig(context);

                    assertThat(config.allowedCommands()).containsExactly("echo", "cd");
                    assertThat(config.deniedCommands())
                            .hasSize(SandboxConfig.DEFAULT_DENIED_COMMANDS.size() + 1)
                            .contains("docker")
                            .containsAll(SandboxConfig.DEFAULT_DENIED_COMMANDS);

                    assertThat(CommandSandbox.denyReason(config, "echo hi")).isNull();
                    assertThat(CommandSandbox.denyReason(config, "git status"))
                            .contains("未命中命令白名单");
                    assertThat(CommandSandbox.denyReason(config, "rm -rf /"))
                            .contains("命中高危命令黑名单条目");
                    assertThat(CommandSandbox.denyReason(config, "docker run --privileged x"))
                            .contains("docker");
                });
    }

    @Test
    @DisplayName("allowed-commands 留空 = 不做白名单限制，仅受黑名单约束")
    void emptyAllowlistMeansBlacklistOnly() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-exec=true")
                .run(context -> {
                    SandboxConfig config = pathSandboxConfig(context);

                    assertThat(config.allowedCommands()).isEmpty();
                    assertThat(CommandSandbox.denyReason(config, "git status")).isNull();
                    assertThat(CommandSandbox.denyReason(config, "shutdown -h now")).isNotNull();
                });
    }

    // ------------------------------------------------------------------
    // 自定义 ToolSandbox bean
    // ------------------------------------------------------------------

    @Test
    @DisplayName("定义一个同类型 bean 即整体接管默认沙箱（@ConditionalOnMissingBean）")
    void customToolSandboxBeanTakesOver() {
        ToolSandbox custom = invocation -> ToolSandbox.Decision.deny("我的沙箱说了算");

        runner.withPropertyValues("llm.tools.builtin-enabled=false")
                .withBean("mySandbox", ToolSandbox.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolSandbox.class);
                    assertThat(context.getBean(ToolSandbox.class)).isSameAs(custom);
                    assertThat(context.getBean(ToolSandbox.class).check(new ToolInvocation(
                            "t", "read", Map.of("path", "a.txt"), "a", "s", 1)).reason())
                            .isEqualTo("我的沙箱说了算");
                });
    }

    @Test
    @DisplayName("自定义沙箱与内置围栏组合：取最严结论")
    void customSandboxCanBeCombinedWithBuiltins() {
        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR)
                .run(context -> {
                    SandboxConfig config = pathSandboxConfig(context);
                    ToolSandbox noBash = invocation -> "bash".equals(invocation.toolName())
                            ? ToolSandbox.Decision.deny("组合沙箱：禁止命令执行")
                            : ToolSandbox.Decision.allow();
                    ToolSandbox combined = CompositeSandbox.of(noBash, new PathSandbox(config));

                    assertThat(combined.check(new ToolInvocation("a", "bash",
                            Map.of("command", "echo hi"), "a", "s", 1)).reason())
                            .contains("组合沙箱");
                    assertThat(combined.check(new ToolInvocation("b", "read",
                            Map.of("path", "../../etc/passwd"), "a", "s", 1)).reason())
                            .contains("已越过沙箱围栏");
                    assertThat(combined.check(new ToolInvocation("c", "read",
                            Map.of("path", "a.txt"), "a", "s", 1)).allowed()).isTrue();
                });
    }

    // ------------------------------------------------------------------
    // 与"演算台"交叉验证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BuiltinToolTable 的离线演算结果与真实容器一致")
    void offlineTableMatchesRealContainer() {
        LlmProperties.ToolProperties tools = com.benxin.exp.sandbox.support.BuiltinToolTable.tools(
                java.nio.file.Paths.get(WORKDIR), true, true, true,
                List.of("todo_write", "task"), List.of("echo", "cd"), List.of("docker"));

        runner.withPropertyValues(
                        "llm.tools.builtin-enabled=true",
                        "llm.tools.workdir=" + WORKDIR,
                        "llm.tools.allow-write=true",
                        "llm.tools.allow-exec=true",
                        "llm.tools.enabled=todo_write,task",
                        "llm.tools.allowed-commands=echo,cd",
                        "llm.tools.denied-commands=docker")
                .run(context -> {
                    Set<String> computed = com.benxin.exp.sandbox.support.BuiltinToolTable.toolNames(tools);
                    assertThat(names(context)).isEqualTo(computed);

                    SandboxConfig expected = LlmBuiltinToolRegistrar.toSandboxConfig(tools);
                    SandboxConfig actual = pathSandboxConfig(context);
                    assertThat(actual.allowWrite()).isEqualTo(expected.allowWrite());
                    assertThat(actual.allowExec()).isEqualTo(expected.allowExec());
                    assertThat(actual.allowedCommands()).isEqualTo(expected.allowedCommands());
                    assertThat(actual.deniedCommands()).isEqualTo(expected.deniedCommands());
                    assertThat(actual.workdirAbsolute()).isEqualTo(expected.workdirAbsolute());
                });
    }

    // ------------------------------------------------------------------

    private static Set<String> names(org.springframework.context.ApplicationContext context) {
        return context.getBean(LlmToolCatalog.class).global().names();
    }

    private static SandboxConfig pathSandboxConfig(org.springframework.context.ApplicationContext context) {
        ToolSandbox sandbox = context.getBean(ToolSandbox.class);
        if (sandbox instanceof PathSandbox pathSandbox) {
            return pathSandbox.config();
        }
        for (ToolSandbox member : ((CompositeSandbox) sandbox).members()) {
            if (member instanceof PathSandbox pathSandbox) {
                return pathSandbox.config();
            }
        }
        throw new IllegalStateException("容器里没有 PathSandbox 成员: " + sandbox);
    }
}
