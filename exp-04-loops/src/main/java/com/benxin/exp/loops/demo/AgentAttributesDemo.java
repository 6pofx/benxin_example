package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.ClaudeCodeAgent;
import com.benxin.exp.loops.agent.GenericAgent;
import com.benxin.exp.loops.agent.ParentTaskAgent;
import com.benxin.exp.loops.agent.PlanExecuteAgent;
import com.benxin.llm.core.agent.AgentAttributes;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.PlanExecuteLoop;
import com.benxin.llm.core.loop.StagedLoop;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code AgentAttributes}：运行属性的键从哪来、又往哪去。
 *
 * <p>{@code AgentResult.attributes()} 就是本次运行的 {@code ctx.attributes()} 快照
 * （{@code DefaultLoopContext.toResult} 里 {@code new LinkedHashMap<>(attributes)}），
 * 因此它同时装着三类东西：</p>
 * <ol>
 *   <li>{@link AgentAttributes} 里定义的**框架级**键（系统提示词覆盖、计划、待办、会话…）；</li>
 *   <li>各 Loop 自己写的**Loop 级**键（{@code benxin.staged.*} / {@code benxin.codex.*} /
 *       {@code benxin.claude-code.*} / {@code benxin.reflexion.*}）；</li>
 *   <li>使用者自己塞进去的键（{@code @Ctx} 变量、工具写入的键）。</li>
 * </ol>
 *
 * <p><b>本演示会如实打出哪些键"在、但值为 null"</b>：{@code AgentAttributes} 里的常量
 * 只是"键名定义"，不等于"这个键一定会被写"。把这件事打出来，比列一张"六大属性表"有用得多。</p>
 */
@Component
public class AgentAttributesDemo {

    private static final List<String> KEYS = List.of(
            AgentAttributes.SYSTEM_PROMPT,
            AgentAttributes.TODOS,
            AgentAttributes.PLAN,
            AgentAttributes.DEFAULT_SUB_AGENT,
            AgentAttributes.SESSION_ID,
            AgentAttributes.AGENT_NAME);

    private final GenericAgent genericAgent;
    private final ClaudeCodeAgent claudeCodeAgent;
    private final PlanExecuteAgent planExecuteAgent;
    private final ParentTaskAgent parentTaskAgent;
    private final AgentRegistry agentRegistry;

    public AgentAttributesDemo(GenericAgent genericAgent,
                               ClaudeCodeAgent claudeCodeAgent,
                               PlanExecuteAgent planExecuteAgent,
                               ParentTaskAgent parentTaskAgent,
                               AgentRegistry agentRegistry) {
        this.genericAgent = genericAgent;
        this.claudeCodeAgent = claudeCodeAgent;
        this.planExecuteAgent = planExecuteAgent;
        this.parentTaskAgent = parentTaskAgent;
        this.agentRegistry = agentRegistry;
    }

    void run(Demo demo) {
        demo.section("AgentAttributes：六个框架级键在真实运行里的取值");

        demo.info("声明式 Agent 的每个方法都会把解析好的系统提示词写进 %s：",
                AgentAttributes.SYSTEM_PROMPT);
        AgentResult basic = genericAgent.ask("你好");
        showKeys(demo, basic.attributes(), AgentNames.GENERIC);

        demo.info("待办（%s）由 todo_write 工具写入 —— 调过它才会有这个键：",
                AgentAttributes.TODOS);
        AgentResult todos = claudeCodeAgent.ask(
                "#tool:todo_write {\"todos\":[{\"content\":\"演示 benxin.todos\",\"status\":\"in_progress\"}]}");
        showKeys(demo, todos.attributes(), AgentNames.CLAUDE_CODE);

        demo.info("计划（%s）由 plan-execute / codex 写入：", AgentAttributes.PLAN);
        AgentResult planned = planExecuteAgent.ask("请演示计划属性是怎么写进去的。");
        showKeys(demo, planned.attributes(), AgentNames.PLAN_EXECUTE);

        demo.info("默认子代理（%s）由 @Ctx(\"%s\") 参数写进去：",
                AgentAttributes.DEFAULT_SUB_AGENT, AgentAttributes.DEFAULT_SUB_AGENT);
        AgentResult sub = parentTaskAgent.ask(
                "#tool:task {\"description\":\"读一次天气\",\"prompt\":\"请告诉我北京今天的天气。\"}",
                AgentNames.CHILD);
        showKeys(demo, sub.attributes(), AgentNames.PARENT_TASK);
        demo.ok("task 工具的参数里**没有** subagent_type，它从 %s 回退取到了默认子代理",
                AgentAttributes.DEFAULT_SUB_AGENT);

        demo.info("Loop 级属性同样是「通过 attributes 往外说话」的通道：");
        demo.info("   plan-execute → %s", PlanExecuteLoop.PLAN_ATTRIBUTE);
        demo.info("   staged       → %s / %s / %s / %s",
                StagedLoop.STAGE_ATTRIBUTE, StagedLoop.PLAN_ATTRIBUTE,
                StagedLoop.VERDICT_ATTRIBUTE, StagedLoop.REPAIR_ROUNDS_ATTRIBUTE);
        demo.info("   codex        → benxin.codex.plan / .verified / .verificationNote / .sandboxWriteEnabled …");
        demo.info("   claude-code  → benxin.claude-code.initialized / .tools / .subAgents / .toolErrors …");
        demo.info("   reflexion    → benxin.reflexion.attempts / .score / .exhausted");
        demo.info("   exp-decorate → exp.decorate.* （自定义 Loop 同样可以写自己的键）");

        demo.warn("口径提醒：AgentAttributes 里的常量只是键名定义。%s 与 %s 在框架内部"
                + "**没有任何写入点** —— 所以在 AgentResult.attributes() 里查不到它们（见上面的输出）。",
                AgentAttributes.SESSION_ID, AgentAttributes.AGENT_NAME);
        demo.info("想看这两个值请直接用 AgentResult.sessionId() / .agentName() 或 Agent.name()。");
    }

    /** 把六个键的存在性与取值逐条打出来 —— 包括"不存在"。 */
    private void showKeys(Demo demo, Map<String, Object> attributes, String agentName) {
        demo.info("Agent [%s] 的属性共 %d 项；六个框架级键：", agentName, attributes.size());
        for (String key : KEYS) {
            if (attributes.containsKey(key)) {
                demo.info("   ✓ %-28s = %s", key, LoopReport.oneLine(
                        LoopReport.shortValue(attributes.get(key)), 90));
            } else {
                demo.info("   ✗ %-28s （本次运行没有写入）", key);
            }
        }
        demo.info("   其余键：%s", attributes.keySet().stream()
                .filter(key -> !KEYS.contains(key))
                .map(key -> key + "=" + LoopReport.oneLine(LoopReport.shortValue(attributes.get(key)), 40))
                .toList());
    }

    /** 供测试复用：某个键在某次运行里是否真的被写入了。 */
    public static boolean hasKey(AgentResult result, String key) {
        return result.attributes().containsKey(key);
    }

    /** 供测试复用：当前注册表里的 Agent 名（清历史时要用）。 */
    public java.util.Set<String> agentNames() {
        return agentRegistry.names();
    }
}
