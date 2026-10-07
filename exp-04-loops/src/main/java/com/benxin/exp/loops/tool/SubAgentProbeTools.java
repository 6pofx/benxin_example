package com.benxin.exp.loops.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理观察工具集（普通类，由 {@code @Bean} 交给容器，风格同 {@code CommonTools}）。
 *
 * <p>它演示的是 {@code ToolContext} 的 {@code spawn(...)} 方法 —— 也就是
 * "工具自己派生子代理"这条路径。与内置 {@code task} 工具的区别只有一个：
 * <b>能额外带一份运行属性给子代理</b>。</p>
 */
public class SubAgentProbeTools {

    /** 父侧观测：每次派生时记下"我要求带哪些属性"。 */
    private final List<Map<String, Object>> spawnLog = new ArrayList<>();

    /**
     * 把一个独立任务甩给子代理。
     *
     * <p>刻意不把当前对话交给子代理：本心反复强调"子代理看不到父上下文"，
     * 因此这里只传一句自洽的指令。</p>
     */
    @LlmTool(name = "delegate_task",
            description = "派生子代理执行一个独立任务；可附带一份运行属性供子代理读取")
    public ToolResult delegateTask(
            @LlmToolParam(value = "instruction",
                    description = "交给子代理的完整指令（子代理看不到当前对话）") String instruction,
            @LlmToolParam(value = "subagent_type", required = false,
                    description = "子代理名；留空则用 benxin.default-subagent 属性") String subagentType,
            @LlmToolParam(value = "trace", required = false,
                    description = "要透传给子代理的追踪标记") String trace,
            ToolContext ctx) {
        if (instruction == null || instruction.isBlank()) {
            return ToolResult.error("参数 instruction 必填：请给出交给子代理的完整任务说明。");
        }
        if (!ctx.canSpawnSubAgent()) {
            return ToolResult.error("当前 Agent 未配置子代理能力，无法派生 [" + subagentType + "]。");
        }
        String name = subagentType == null || subagentType.isBlank()
                ? String.valueOf(ctx.attributes().get("benxin.default-subagent"))
                : subagentType;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("benxin.parent.agent", ctx.agentName());
        extra.put("probe.trace", trace == null ? "未提供" : trace);
        spawnLog.add(Map.copyOf(extra));

        return ctx.spawn(name, instruction, extra)
                .map(result -> ToolResult.ok(
                        "[子代理 " + name + " 的结论] " + instruction + "\n" + result.text(),
                        Map.of("subAgent", name, "steps", result.steps(),
                                "inputTokens", result.usage().inputTokens(),
                                "outputTokens", result.usage().outputTokens())))
                .orElseGet(() -> ToolResult.error("派生 [" + name + "] 失败：上下文没有返回结果。"));
    }

    /** 父侧观测：每次派生时"我送出去了什么"。 */
    public List<Map<String, Object>> spawnLog() {
        return List.copyOf(spawnLog);
    }

    public void reset() {
        spawnLog.clear();
    }
}
