package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.ClaudeCodeAgent;
import com.benxin.exp.loops.agent.ClaudeCodeFlakyAgent;
import com.benxin.exp.loops.agent.ParentTaskAgent;
import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.config.SubAgentAuditListener;
import com.benxin.exp.loops.tool.FlakyTools;
import com.benxin.llm.core.agent.AgentAttributes;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ③：{@code claude-code} —— 单一主循环 + 待办 + 压缩 + 子代理 + 错误熔断。
 *
 * <p>它和 {@code dsh-minimal} 的主线完全一样（都是 {@code runToolCallingLoop}），
 * 全部差别都在"每一轮调用前注入什么提醒"。本演示挑两件最容易讲不清的事各跑一遍：</p>
 * <ol>
 *   <li><b>子代理派生</b>：{@code @LlmAgent(subAgents = {...})} + 内置 {@code task} 工具
 *       + {@code #tool:task {...}} 强制调用。能看到父侧 {@code toolCalls()} 里有 {@code task}、
 *       子代理的 token 被补记进父的 {@code usage()}、以及两处观察点各自被回调；</li>
 *   <li><b>连续工具错误熔断</b>：让一个工具连续失败，观察历史里被注入
 *       "停下来重新评估"的提醒，而不是停机 —— 这是本 Loop 与"自动重试"的分水岭。</li>
 * </ol>
 *
 * <p>待办管理（{@code todo_write}）与上下文压缩这里只做定点说明、不强行制造场景：
 * 压缩的触发条件是"估算 token 超过模型窗口的 70%"，用离线脚本模型和几十条短消息
 * 是撞不到那条线的 —— 硬造一个假的长上下文只会演示到假的东西。</p>
 */
@Component
public class ClaudeCodeLoopDemo {

    /** 与 {@code ClaudeCodeLoop.REFLECTION...} 无关：这是熔断提醒里的特征串。 */
    static final String BREAKER_MARKER = "停下来重新评估";

    private final ClaudeCodeAgent claudeCodeAgent;
    private final ClaudeCodeFlakyAgent flakyAgent;
    private final ParentTaskAgent parentTaskAgent;
    private final SubAgentAuditListener auditListener;
    private final FlakyTools flakyTools;
    private final AgentRegistry agentRegistry;

    public ClaudeCodeLoopDemo(ClaudeCodeAgent claudeCodeAgent,
                              ClaudeCodeFlakyAgent flakyAgent,
                              ParentTaskAgent parentTaskAgent,
                              SubAgentAuditListener auditListener,
                              FlakyTools flakyTools,
                              AgentRegistry agentRegistry) {
        this.claudeCodeAgent = claudeCodeAgent;
        this.flakyAgent = flakyAgent;
        this.parentTaskAgent = parentTaskAgent;
        this.auditListener = auditListener;
        this.flakyTools = flakyTools;
        this.agentRegistry = agentRegistry;
    }

    void run(Demo demo) {
        demo.section("claude-code ① 环境注入与待办工具");
        environmentAndTodos(demo);
        demo.section("claude-code ② 子代理派生（task 工具，真实派生路径）");
        subAgentDerivation(demo);
        demo.section("claude-code ③ 连续工具错误后的提醒（熔断，而不是停机）");
        errorCircuitBreaker(demo);
    }

    /** ① 首轮环境注入 + todo_write。 */
    private void environmentAndTodos(Demo demo) {
        var agent = agentRegistry.get(AgentNames.CLAUDE_CODE);
        demo.info("Agent 的工具集 = %s", agent.tools().names());
        demo.info("构造参数缺省：new ClaudeCodeLoop() = (subAgentName=task, compactThresholdSteps=12,"
                + " maxConsecutiveToolErrors=3)；本模块按需求演示的是第 2、3 个参数的含义。");
        demo.info("首轮会注入环境（benxin.claude-code.initialized / .tools / .subAgents / .cwd）；"
                + "系统提示词非空时**不会**再注入兜底提示词（本 Agent 配了 systemPrompt）。");
        demo.info("模型 = %s（= %s + 一个只补 todo_write 参数的装饰器）。原因：离线脚本模型的 "
                + "#tool: 语法用惰性正则抠参数，碰到「对象数组」这种嵌套 JSON 会在第一个 } 就截断，"
                + "不补的话 TodoWriteTool 永远只回「参数 todos 必填」—— benxin.todos 也永远写不进去。",
                OfflineModelConfiguration.TODO_AWARE_BEAN, OfflineModelConfiguration.GENERIC);

        AgentResult result = claudeCodeAgent.ask(OfflineModelConfiguration.TODO_WRITE_INPUT);
        LoopReport.result(demo, result);
        demo.ok("待办工具被真实执行，清单写进了 attributes[benxin.todos]（= AgentAttributes.TODOS）：%s",
                result.attributes().get(AgentAttributes.TODOS));
        demo.ok("todo_write 的调用结果是成功的 = %s",
                result.toolCalls().stream().anyMatch(r -> "todo_write".equals(r.name()) && !r.error()));
        demo.info("spec.maxSteps = %d（来自 llm.agent.hard-max-steps 的夹取上限）", agent.spec().maxSteps());
        demo.info("压缩参数 compactThresholdSteps 控制「每多少步检查一次 token 占用」，"
                + "触发线是模型窗口的 70%%；本演示的上下文远小于它，因此 benxin.claude-code.compactions 恒为 0 —— "
                + "这是**没触发**，不是没实现。");
    }

    /** ② 子代理：注解声明 + task 工具 + 强制调用。 */
    private void subAgentDerivation(Demo demo) {
        auditListener.reset();
        String input = "#tool:task {\"description\":\"查询北京天气\","
                + "\"prompt\":\"请告诉我北京今天的天气与气温。\",\"subagent_type\":\""
                + AgentNames.CHILD + "\"}";
        AgentResult result = parentTaskAgent.ask(input, AgentNames.CHILD);

        LoopReport.result(demo, result);
        demo.ok("父 Agent 的工具调用轨迹里有 task = %s",
                result.toolCalls().stream().anyMatch(r -> "task".equals(r.name())));
        demo.ok("父 Agent 的 usage 含子代理消耗：合计 %d tokens（steps = %d）",
                result.usage().totalTokens(), result.steps());
        demo.ok("AgentListener.onSubAgentStart / onSubAgentEnd 被触发，共 %d 条事件：",
                auditListener.events().size());
        auditListener.events().forEach(e -> demo.info("   %s", LoopReport.shortValue(e)));
        demo.info("口径提醒：task 只回传子代理的结论摘要，子代理的完整对话不会进父上下文 —— "
                + "这正是「子代理把脏活留在自己上下文里」的实现方式。");
    }

    /** ③ 连续错误熔断：注入提醒而不是停机。 */
    private void errorCircuitBreaker(Demo demo) {
        flakyTools.reset();
        String input = "#tool:flaky_query {\"keyword\":\"连续失败演示\"}";
        AgentResult result = flakyAgent.ask(input);

        LoopReport.result(demo, result);
        demo.ok("工具确实连续失败 %d 次（flaky_query 每次都抛异常）", flakyTools.failures());
        demo.ok("历史里出现了「停下来重新评估」的提醒 = %s",
                LoopReport.historyContains(result, BREAKER_MARKER));
        demo.ok("它是**提醒**而不是停机：运行没有被中断，仍以正常结果收尾（finishReason = %s）",
                result.finishReason());
        demo.info("触发条件：连续 3 次工具结果的 error 标志为 true（maxConsecutiveToolErrors 默认 3）；"
                + "触发后计数清零，再连续失败还会被再次提醒。");
        demo.info("这条路径需要真实的 error 结果 —— CommonTools 返回的「错误：xxx」是普通字符串，"
                + "error 标志为 false，触发不了熔断。");
        demo.info("观测点：attributes 里的 benxin.claude-code.toolErrors 与 toolErrorStreak。");
    }
}
