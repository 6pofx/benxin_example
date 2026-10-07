package com.benxin.exp.loops.config;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.tool.CodexPatchTools;
import com.benxin.exp.loops.tool.FlakyTools;
import com.benxin.exp.loops.tool.SubAgentProbeTools;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.spring.LlmBuiltinToolRegistrar;
import com.benxin.llm.spring.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本模块自己产出的工具与监听器。
 *
 * <p>{@code commonTools} 来自 exp-common（天气 / 时间 / 计算 / 上下文自述），
 * 这里补上三个"只为演示某个 Loop 分支而存在"的工具集：</p>
 * <ul>
 *   <li>{@link SubAgentProbeTools} —— 工具里派生子代理（{@code ToolContext.spawn}）；</li>
 *   <li>{@link CodexPatchTools} —— {@code apply_patch} 专用格式（{@code ApplyPatchParser}）；</li>
 *   <li>{@link FlakyTools} —— 一个必然失败的工具，用来触发 claude-code 的错误熔断。</li>
 * </ul>
 *
 * <p>它们都是普通类，靠 {@code @Bean} 交给容器；容器里的 {@code LlmToolCatalog}
 * 是 BeanPostProcessor，会扫描这些 bean 上的 {@code @LlmTool} 方法并收进全局工具表。</p>
 */
@Configuration
public class ToolConfiguration {

    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    @Bean
    public SubAgentProbeTools subAgentProbeTools() {
        return new SubAgentProbeTools();
    }

    @Bean
    public FlakyTools flakyTools() {
        return new FlakyTools();
    }

    /**
     * 补丁工具与内置工具共用同一道围栏根目录（{@code llm.tools.workdir}）。
     *
     * <p><b>为什么参数是 {@link LlmProperties} 而不是 {@code SandboxConfig}：</b>
     * {@code SandboxConfig} 虽然由 {@code llm.tools.*} 配出来，但 starter 里
     * <b>没有任何一处把它注册成 bean</b> —— 它只是在 {@code llmToolSandbox(...)} 与
     * {@code llmBuiltinToolRegistrar(...)} 内部现算现用的一个值对象。
     * 按类型注入 {@code SandboxConfig} 会直接报 "No qualifying bean of type 'SandboxConfig'"。
     * 官方给的那条"配置 → 沙箱"通道是
     * {@code LlmBuiltinToolRegistrar.toSandboxConfig(properties.getTools())}，
     * 这里复用它而不是自己拼一份：两处一旦各拼一份，
     * "启用写工具即等于授权"那条推导就会在内置工具与补丁工具之间漂移。</p>
     *
     * <p><b>顺带一条实测口径：真正拦住补丁的不是这里，而是容器里的
     * {@code ToolSandbox}。</b>Agent 装配时拿的是自动配置给出的那一个
     * （{@code LlmAgentFactory} 构造参数里的 {@code sandbox}），补丁工具自己持有的
     * {@code SandboxConfig} 只用来算围栏根目录。所以
     * {@code llm.tools.allow-write} 才是补丁能不能落盘的那道闸门 ——
     * 本模块因此把它打开（见 {@code application.yml} 的注释）。</p>
     */
    @Bean
    public CodexPatchTools codexPatchTools(LlmProperties properties) {
        return new CodexPatchTools(LlmBuiltinToolRegistrar.toSandboxConfig(properties.getTools()));
    }

    /**
     * 注册自己的 {@link com.benxin.llm.core.hook.AgentListener}。
     *
     * <p>它同时是"内置日志监听器会让位"的开关：{@code llmLoggingListener} 的
     * {@code @ConditionalOnMissingBean(AgentListener.class)} 会因为这一个 bean 而不生效，
     * 于是本模块的 {@code subAgentAuditListener} 是唯一的监听器，事件不会被重复打印两遍。</p>
     *
     * <p><b>返回类型刻意写具体类</b>而不是 {@code AgentListener} 接口：
     * 演示与测试都要按 {@code SubAgentAuditListener} 这个类型注入它来读事件；
     * 若 {@code @Bean} 方法声明成接口，容器里就只有接口类型的 bean，
     * 按具体类注入会直接 "No qualifying bean"。实现类仍然是监听器，两者不冲突。</p>
     */
    @Bean
    public SubAgentAuditListener subAgentAuditListener() {
        return new SubAgentAuditListener();
    }
}
