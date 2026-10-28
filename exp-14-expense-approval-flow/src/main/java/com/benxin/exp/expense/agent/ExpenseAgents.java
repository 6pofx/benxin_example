package com.benxin.exp.expense.agent;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 报销审批的声明式入口。
 *
 * <p>注意这里<b>看不到任何图</b>：没有节点、没有边、没有条件。它只知道
 * "我跑的是 {@code flow} 这个 Loop"，图的形状完全在 {@code flow/ExpenseApprovalFlow} 里。
 * 这正是声明式调用与编排解耦的样子 —— 同一张图换个 Agent 名字、换个模型就能复用。</p>
 *
 * <p>返回 {@link AgentResult} 而不是字符串：审批跑完之后要读轨迹（{@code visited}）
 * 与黑板快照（业务字段），只看最终文案是不够的。</p>
 */
public final class ExpenseAgents {

    private ExpenseAgents() {
    }

    @LlmAgent(
            name = "expenseAgent",
            model = "local-lmstudio",
            loop = "flow",
            systemPrompt = "你是企业财务共享中心的报销审批助手。回答简短，不要输出思考过程。",
            // 最长路径：类别识别 + 风险评估 + 总监审批 = 3 次模型往返，给 8 步留余量
            maxSteps = 8,
            memory = false,
            temperature = 0)
    public interface ExpenseAgent {

        /** 一段报销说明进，一条审批结果（凭证/驳回/退回）出。 */
        AgentResult approve(@User String claim);
    }
}
