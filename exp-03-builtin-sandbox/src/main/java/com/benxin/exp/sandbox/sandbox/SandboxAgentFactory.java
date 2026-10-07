package com.benxin.exp.sandbox.sandbox;

import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.CompositeSandbox;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.stereotype.Component;

/**
 * 程序化装配沙箱实验用的 Agent。
 *
 * <p>为什么不用 {@code @LlmAgent} 而是要手写 {@code Agent.builder()}：
 * {@code llm.agent.approval-policy} 是<b>应用级</b>配置，一个容器只能有一个取值。
 * 想在同一份 ApplicationContext 里对照 {@code NEVER / ON_REQUEST / ALWAYS / ON_FAILURE} 四态，
 * 就必须绕开 Spring 侧的装配器，直接用 {@link com.benxin.llm.core.agent.AgentBuilder}
 * 把 {@code approvalPolicy} 与 {@code approvalHandler} 逐次换掉 ——
 * 这条路径的存在本身就是"一切皆可插拔"的证据。</p>
 */
@Component
public class SandboxAgentFactory {

    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmToolCatalog toolCatalog;
    private final ToolSandbox containerSandbox;
    private final AgentListener listener;

    public SandboxAgentFactory(ModelRegistry modelRegistry,
                               LoopRegistry loopRegistry,
                               LlmToolCatalog toolCatalog,
                               ToolSandbox containerSandbox,
                               AgentListener listener) {
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.toolCatalog = toolCatalog;
        this.containerSandbox = containerSandbox;
        this.listener = listener;
    }

    /** 容器里那个真正生效的沙箱（默认是 {@code CompositeSandbox(PathSandbox, CommandSandbox)}）。 */
    public ToolSandbox containerSandbox() {
        return containerSandbox;
    }

    /** 容器沙箱对应的 {@link SandboxConfig}（取不到时返回 {@code null}）。 */
    public SandboxConfig containerConfig() {
        PathSandbox pathSandbox = SandboxWorkspace.pathSandboxOf(containerSandbox);
        return pathSandbox == null ? null : pathSandbox.config();
    }

    /** 用容器默认沙箱装配一个探针 Agent。 */
    public Agent probe(String name, ApprovalPolicy policy, ApprovalHandler handler) {
        return probe(name, containerSandbox, policy, handler);
    }

    /** 完整装配：沙箱、审批策略、审批处理器全部由调用方指定。 */
    public Agent probe(String name, ToolSandbox sandbox, ApprovalPolicy policy, ApprovalHandler handler) {
        return Agent.builder(name)
                .spec(AgentSpec.builder()
                        .name(name)
                        .maxSteps(4)
                        // 关掉记忆：这些探针只跑一次，不需要跨用例的会话历史
                        .memory(false)
                        .build())
                .modelRegistry(modelRegistry)
                .loopRegistry(loopRegistry)
                // 全局工具表 = 内置工具 + 自写工具；沙箱演示要看的正是"同一张表、不同的守门人"
                .toolRegistry(toolCatalog.global())
                .sandbox(sandbox)
                .approvalPolicy(policy)
                .approvalHandler(handler)
                .listener(listener)
                .build();
    }

    /**
     * 一个"假的宽松沙箱"：只放行、不看路径。
     *
     * <p>用它替换容器里的 {@link ToolSandbox} 之后再去越界读文件 —— 内置工具<b>仍然</b>会拒绝，
     * 这就是纵深防御（每个内置工具内部还会再自查一遍 {@code PathSandbox}）。</p>
     */
    public static ToolSandbox permissive() {
        return ToolSandbox.permissive();
    }

    /** 把手写沙箱与真正的 {@link PathSandbox} 组合起来（取最严结论）。 */
    public static ToolSandbox combineWithPathSandbox(ToolSandbox custom, SandboxConfig config) {
        return CompositeSandbox.of(custom, new PathSandbox(config));
    }
}
