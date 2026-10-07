package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.tool.ToolCallRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loop 演示的公共输出工具：所有示例都按同一套口径打印 {@link AgentResult}。
 *
 * <p>为什么值得单独抽一个类：本模块要打 7 个内置 Loop + 2 个自定义 Loop 的结果，
 * 如果每处各写一遍"打哪些字段、怎么截断"，输出很快就会各说各话 ——
 * 而这份输出正是示例的产物，它必须能横向对照着看。</p>
 */
final class LoopReport {

    /** 打印时优先出现的属性键（高信号）：先按这份清单的顺序，再按字母序补齐其余。 */
    private static final List<String> PRIORITY_KEYS = List.of(
            "benxin.plan",
            "benxin.staged.stage",
            "benxin.staged.plan",
            "benxin.staged.verdict",
            "benxin.staged.repair-rounds",
            "benxin.reflexion.attempts",
            "benxin.reflexion.score",
            "benxin.reflexion.exhausted",
            "benxin.codex.plan",
            "benxin.codex.completedSteps",
            "benxin.codex.verified",
            "benxin.codex.verificationNote",
            "benxin.codex.sandboxWriteEnabled",
            "benxin.codex.execEnabled",
            "benxin.codex.approvalPolicy",
            "benxin.claude-code.initialized",
            "benxin.claude-code.tools",
            "benxin.claude-code.subAgents",
            "benxin.claude-code.steps",
            "benxin.claude-code.compactions",
            "benxin.claude-code.toolErrors",
            "benxin.claude-code.toolErrorStreak",
            "benxin.todos",
            "exp.decorate.restated",
            "exp.decorate.directToolResult",
            "exp.decorate.execution",
            "exp.decorate.budgetExhausted",
            "exp.decorate.subAgentEvents",
            "lastSubAgent");

    /** 单条属性值打印时的字符上限。 */
    private static final int VALUE_LIMIT = 150;

    /** 最多打印多少条属性。 */
    private static final int MAX_ATTRIBUTES = 22;

    private LoopReport() {
    }

    /** 打印一次运行的"结果全字段"：loopName / steps / finishReason / text 摘要 / attributes。 */
    static void result(Demo demo, AgentResult result) {
        demo.ok("loopName = %s，model = %s，agent = %s，session = %s",
                result.loopName(), result.modelName(), result.agentName(), result.sessionId());
        demo.ok("steps = %d，finishReason = %s，maxStepsReached = %s，duration = %d ms",
                result.steps(), result.finishReason(), result.maxStepsReached(), result.durationMillis());
        Usage usage = result.usage();
        demo.ok("usage: 输入 %d / 输出 %d / 合计 %d tokens",
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
        demo.info("text 摘要: %s", oneLine(result.text(), 220));
        demo.info("toolCalls: %s", describeToolCalls(result.toolCalls()));
        attributes(demo, result.attributes());
    }

    /** 打印属性的关键项（优先级键在前，其余按字母序，值截断）。 */
    static void attributes(Demo demo, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            demo.info("attributes: （空）");
            return;
        }
        Map<String, Object> sorted = new TreeMap<>();
        Set<String> printed = new LinkedHashSet<>();
        for (String key : PRIORITY_KEYS) {
            if (attributes.containsKey(key)) {
                sorted.put(key, attributes.get(key));
                printed.add(key);
            }
        }
        attributes.forEach((key, value) -> {
            if (!printed.contains(key) && sorted.size() < MAX_ATTRIBUTES) {
                sorted.put(key, value);
            }
        });
        demo.info("attributes (%d 项，显示 %d 项):", attributes.size(), sorted.size());
        sorted.forEach((key, value) -> demo.info("   %-38s = %s", key, oneLine(shortValue(value), VALUE_LIMIT)));
        int hidden = attributes.size() - sorted.size();
        if (hidden > 0) {
            demo.info("   …另有 %d 项未显示", hidden);
        }
    }

    /** 把工具调用轨迹压成一行便于扫读。 */
    static String describeToolCalls(List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) {
            return "（无）";
        }
        List<String> parts = new ArrayList<>();
        for (ToolCallRecord record : records) {
            parts.add(record.name() + (record.error() ? "[失败]" : "[成功]"));
        }
        return parts.size() + " 次 → " + String.join(", ", parts);
    }

    /** 把一行文本压平并截断，方便在控制台横向对照。 */
    static String oneLine(String text, int limit) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /** 属性值的可读化：集合只报条数与前几项，Map 报键值。 */
    static String shortValue(Object value) {
        if (value == null) {
            return "(null)";
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            int index = 0;
            for (Object element : collection) {
                if (index++ >= 4) {
                    items.add("…共 " + collection.size() + " 项");
                    break;
                }
                items.add(String.valueOf(element));
            }
            return "[" + String.join(" | ", items) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> items = new ArrayList<>();
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ >= 6) {
                    items.add("…共 " + map.size() + " 项");
                    break;
                }
                items.add(entry.getKey() + "=" + entry.getValue());
            }
            return "{" + String.join(", ", items) + "}";
        }
        return String.valueOf(value);
    }

    /** 打印某次运行里"模型看到了什么"的最后一条 user 消息（用于证明提示词注入真的发生了）。 */
    static void lastUserMessage(Demo demo, AgentResult result, String what) {
        result.messages().stream()
                .filter(m -> m.role() == com.benxin.llm.core.message.Role.USER)
                .reduce((first, second) -> second)
                .ifPresent(m -> demo.info("%s：%s", what, oneLine(m.text(), 240)));
    }

    /** 历史里是否出现过给定文本（演示与测试共用）。 */
    static boolean historyContains(AgentResult result, String needle) {
        return result.messages().stream().anyMatch(m -> m.text() != null && m.text().contains(needle));
    }
}
