package com.benxin.exp.database.agent;

import com.benxin.exp.database.tool.OrderQueryTools;
import com.benxin.exp.database.tool.OrderWriteTools;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.loop.WorkflowLoop;

/**
 * 合规审批助手 —— 唯一一个用<b>声明式工作流</b>当 Loop 的 Agent。
 *
 * <p>它的 {@code loop = "workflow:order-compliance"} 指的是
 * {@code application.yml} 里这一段注册出来的 Loop：</p>
 * <pre>
 * llm:
 *   workflows:
 *     order-compliance:
 *       location: classpath:workflows/order-compliance.yml
 * </pre>
 * <p>于是 Agent 本身不需要知道图长什么样，YAML 改一行就换一套流程。</p>
 *
 * <h2>两条容易猜错的口径</h2>
 * <ol>
 *   <li><b>工作流节点用的工具，来自它挂载的那个 Agent 的工具表，不是全局工具表。</b>
 *       YAML 里的 {@code tool: query_orders} 之所以能跑通，是因为本接口的
 *       {@code tools} 里带了 {@link OrderQueryTools}。
 *       把 {@code tools} 去掉，tool 节点会直接报"未知工具"。</li>
 *   <li><b>本次运行的轨迹不在返回值里。</b> {@link WorkflowRun} 被写进了运行属性
 *       （{@code WorkflowLoop.RUN_ATTRIBUTE}），因此想断言"走了哪条分支"，
 *       要么取 {@code AgentResult.attributes()}，要么直接用
 *       {@code agentRegistry.get("complianceAssistant").call(...)}。
 *       本接口额外暴露一个 {@code run} 方法，就是为了让调用方少绕一步。</li>
 * </ol>
 */
@LlmAgent(
        name = "complianceAssistant",
        model = "mock",
        // 指向 LLM 配置里注册出来的 Loop；名字不对时框架会 WARN 并回退到默认 Loop
        loop = "workflow:order-compliance",
        // 只读工具按来源类取（YAML 里的 tool 节点用的就是这几个）；
        // 写工具走 toolNames —— 理由见 OrderAssistant 的类注释
        tools = {OrderQueryTools.class},
        toolNames = {OrderWriteTools.CREATE_ORDER, OrderWriteTools.REFUND_ORDER},
        maxSteps = 12,
        // 工作流自己有黑板，会话记忆对它没有意义；关掉可以让"输入"保持干净
        memory = false,
        systemPrompt = "你是订单合规审批助手，按预定义的流程图推进，不要跳步。"
)
public interface ComplianceAssistant {

    /** 跑一次合规审批，返回工作流的最终答复文本。 */
    String check(@User String question);

    /**
     * 跑一次并取回工作流轨迹。
     *
     * <p>返回值类型写成 {@code AgentResult}，是因为轨迹在
     * {@code AgentResult.attributes().get(WorkflowLoop.RUN_ATTRIBUTE)} 上，
     * 而不是在文本里 —— <b>工作流把它最有用的一份产物（访问过的节点序列）放在运行属性里</b>。
     * 想断言"走了哪条分支"，只能从这里拿。</p>
     */
    @SystemPrompt("请按流程图执行，不要额外发挥。")
    AgentResult checkWithTrace(@User String question);

    /** 便于演示与测试直接拿到运行属性的键名。 */
    static String runAttribute() {
        return WorkflowLoop.RUN_ATTRIBUTE;
    }
}
