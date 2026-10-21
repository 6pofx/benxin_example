package com.benxin.exp.wfnode.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 本模块的两个工作流入口。
 *
 * <p>它们都<b>只换 Loop</b>，写法和 exp-01 的普通 Agent 没有任何区别 ——
 * 这正是工作流的价值：控制流写在 YAML 里，Java 侧只留一个接口。</p>
 *
 * <p>{@code model = "local-lmstudio"} 是显式写的，而 {@code llm.default-model} 也是它：
 * 本模块刻意不提供离线 mock 模型，所以"忘了切模型"这件事不可能悄悄发生。</p>
 */
public final class ReleaseAgents {

    private ReleaseAgents() {
    }

    /**
     * 主演示：内容发布流水线（{@code workflows/content-release.yml}）。
     *
     * <p>返回 {@link AgentResult} 而不是 {@code String}，因为工作流的价值有一半在可观测性上：
     * 跑完之后的轨迹（{@code benxin.workflow.run}）与黑板快照（{@code benxin.workflow.variables}）
     * 都在 {@code attributes()} 里，演示与测试都要读它们。</p>
     *
     * <p>{@code maxSteps = 4}：这张图最多两次模型往返（摘要 / 压缩），给 4 步留一倍余量。</p>
     */
    @LlmAgent(
            name = "releaseAgent",
            model = "local-lmstudio",
            loop = "workflow:content-release",
            systemPrompt = "你是发布流水线里的编辑。回答简短，不要输出思考过程。",
            maxSteps = 4,
            memory = false,
            temperature = 0)
    public interface ReleaseAgent {

        /** 一段草稿进，一条发布包出。 */
        AgentResult publish(@User String draft);
    }

    /**
     * 失败语义演示（{@code workflows/java-fault.yml}）。
     *
     * <p>这张图里一个 agent 节点都没有，因此<b>一次模型调用都不会发生</b> ——
     * 即使 LM Studio 没开也能跑完。绑定模型只是为了满足"Agent 必须有一个模型"这一装配要求。</p>
     */
    @LlmAgent(
            name = "faultAgent",
            model = "local-lmstudio",
            loop = "workflow:java-fault",
            maxSteps = 4,
            memory = false)
    public interface FaultAgent {

        /** 跑一遍容错图：retry 重试成功 + continueOnError 走补偿。 */
        AgentResult run(@User String task);
    }

    /**
     * 合规闸门（{@code workflows/compliance-gate.yml}）：同样一个 agent 节点都没有。
     *
     * <p>它证明"同一段 Java 逻辑可以被多张图复用"：{@code compliance} 处理器既挂在
     * 主演示图的 {@code screen} 节点上，也挂在这张只有三个节点的闸门图上。
     * 因为图里没有概率成分，两条分支（放行 / 拦截）都能被确定性地验证。</p>
     */
    @LlmAgent(
            name = "gateAgent",
            model = "local-lmstudio",
            loop = "workflow:compliance-gate",
            maxSteps = 2,
            memory = false)
    public interface GateAgent {

        /** 一段待发布的文案进，放行/拦截的结论出。 */
        AgentResult screen(@User String text);
    }
}
