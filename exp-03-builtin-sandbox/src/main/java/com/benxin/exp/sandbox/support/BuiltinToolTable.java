package com.benxin.exp.sandbox.support;

import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.tool.builtin.BuiltinToolkit;
import com.benxin.llm.spring.LlmBuiltinToolRegistrar;
import com.benxin.llm.spring.LlmProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 三重开关的"离线演算台"：不启动容器也能算出某一组 {@code llm.tools.*} 配置下的工具表。
 *
 * <p>算的每一步都与 {@code LlmBuiltinToolRegistrar} / {@code LlmAutoConfiguration} 用的是
 * <b>同一套公开 API</b>：</p>
 * <ol>
 *   <li>{@code builtin-enabled} → {@code LlmAutoConfiguration} 上那个
 *       {@code @ConditionalOnProperty} 决定 {@code LlmBuiltinToolRegistrar} bean 存不存在；
 *       不开就是"一个内置工具都不装配"。</li>
 *   <li>{@link LlmBuiltinToolRegistrar#toSandboxConfig} → yml 到 {@link SandboxConfig} 的翻译
 *       （含"点名写/执行类工具即等价于授权"的推导、{@code denied-commands} 的追加语义）。</li>
 *   <li>{@link BuiltinToolkit.Builder#activeToolNames()} → 只读基线 + 显式 enable +
 *       授权即启用，最后减去 disable。</li>
 * </ol>
 *
 * <p>示例用它打印对照表，测试用 {@code ApplicationContextRunner} 做真刀真枪的验证 ——
 * 两条路算出来的结果必须一致，本身就是一处交叉验证。</p>
 */
public final class BuiltinToolTable {

    private BuiltinToolTable() {
    }

    /** 按"yml 风格"的参数造一份 {@code ToolProperties}。 */
    public static LlmProperties.ToolProperties tools(Path workdir, boolean builtinEnabled,
                                                     boolean allowWrite, boolean allowExec,
                                                     List<String> enabled, List<String> allowedCommands,
                                                     List<String> deniedCommands) {
        LlmProperties.ToolProperties tools = new LlmProperties.ToolProperties();
        tools.setBuiltinEnabled(builtinEnabled);
        tools.setWorkdir(workdir == null ? null : workdir.toString());
        tools.setAllowWrite(allowWrite);
        tools.setAllowExec(allowExec);
        tools.setEnabled(new ArrayList<>(enabled));
        tools.setAllowedCommands(new ArrayList<>(allowedCommands));
        tools.setDeniedCommands(new ArrayList<>(deniedCommands));
        tools.setExecTimeout(Duration.ofSeconds(10));
        tools.setMaxOutputChars(20_000);
        tools.setMaxFileBytes(1024L * 1024L);
        return tools;
    }

    /** 这组配置最终会装配出哪些工具（{@code builtin-enabled=false} 时为空集）。 */
    public static Set<String> toolNames(LlmProperties.ToolProperties tools) {
        if (!tools.isBuiltinEnabled()) {
            return Set.of();
        }
        BuiltinToolkit.Builder builder = BuiltinToolkit.builder().config(toConfig(tools));
        if (!tools.getEnabled().isEmpty()) {
            builder.enable(tools.getEnabled());
        }
        return builder.activeToolNames();
    }

    /** 这组配置对应的沙箱（与 {@code LlmAutoConfiguration#llmToolSandbox} 同一行代码）。 */
    public static SandboxConfig toConfig(LlmProperties.ToolProperties tools) {
        return LlmBuiltinToolRegistrar.toSandboxConfig(tools);
    }
}
