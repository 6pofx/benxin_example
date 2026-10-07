package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.AgentNames;
import com.benxin.exp.loops.agent.CodexAgent;
import com.benxin.exp.loops.agent.CodexPatchAgent;
import com.benxin.exp.loops.agent.CodexUnrestrictedAgent;
import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.exp.loops.tool.CodexPatchTools;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.patch.ApplyPatchParser;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.spring.AgentRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置 Loop ④：{@code codex} —— turn 制「计划 → 补丁 → 验证」。
 *
 * <p>三段式里最值得单独演示的是两件：</p>
 * <ol>
 *   <li><b>{@code apply_patch} 专用格式</b>：本心内置工具集里没有这个工具，
 *       但补丁解析器是公共 API（{@link ApplyPatchParser}）。本模块把
 *       {@link CodexPatchTools#describeFormat()} 打印出来，再用一个脚本模型真的吐一段合法补丁，
 *       观察它被解析、落盘、并把结果回灌给模型；</li>
 *   <li><b>沙箱权限边界如实告知</b>：{@link CodexLoop} 的写/执行/审批三项配置会写进
 *       兜底系统提示词与 {@code benxin.codex.*} 属性。而兜底提示词只在
 *       {@code ctx.systemPrompt()} 为空时注入，所以这里用两个 Agent 做对照：
 *       一个配了提示词（观察属性里的事实），一个没配（把提示词本身打出来）。</li>
 * </ol>
 */
@Component
public class CodexLoopDemo {

    private final CodexAgent codexAgent;
    private final CodexPatchAgent codexPatchAgent;
    private final CodexUnrestrictedAgent codexUnrestrictedAgent;
    private final AgentRegistry agentRegistry;
    private final LoopRegistry loopRegistry;

    public CodexLoopDemo(CodexAgent codexAgent,
                         CodexPatchAgent codexPatchAgent,
                         CodexUnrestrictedAgent codexUnrestrictedAgent,
                         AgentRegistry agentRegistry,
                         LoopRegistry loopRegistry) {
        this.codexAgent = codexAgent;
        this.codexPatchAgent = codexPatchAgent;
        this.codexUnrestrictedAgent = codexUnrestrictedAgent;
        this.agentRegistry = agentRegistry;
        this.loopRegistry = loopRegistry;
    }

    void run(Demo demo) {
        demo.section("codex ① turn 制：计划 → 执行 → 验证，以及 apply_patch 专用格式");
        patchTurn(demo);
        demo.section("codex ② 如实告知沙箱权限边界（构造参数不同，提示词就不同）");
        permissionBoundary(demo);
    }

    /** ① 计划 → 补丁 → 验证。 */
    private void patchTurn(Demo demo) {
        demo.info("apply_patch 的格式说明（来自 ApplyPatchParser.describeFormat()，只列前 12 行）：");
        String format = CodexPatchTools.describeFormat();
        format.lines().limit(12).forEach(line -> demo.info("   %s", line));

        Path target = OfflineModelConfiguration.patchDemoTarget();
        deleteQuietly(target);

        AgentResult result = codexPatchAgent.ask(
                "请在工作目录下新建一个演示文件，用它说明 codex 的补丁式改动是怎么落地的。");

        LoopReport.result(demo, result);
        demo.ok("文件真的被创建了：%s（存在 = %s）", target, Files.exists(target));
        if (Files.exists(target)) {
            demo.info("文件内容：%s", LoopReport.oneLine(readQuietly(target), 200));
        }
        demo.ok("模型输出的补丁以 **apply_patch 工具调用**的形式被执行并落盘，而不是当成普通文本回灌");
        demo.info("这条口径值得记：CodexLoop 只有 runToolCallingLoop → executeTools 一条执行路径，"
                + "**不会**从回复正文里解析补丁。只吐文本的话文件不会创建，"
                + "而模型输出看起来完全正常 —— 是一种很难发现的假成功。");

        // 补丁原文同时也留在历史里（模型"边说边动手"），从结果里读比从模型对象读更可靠
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().contains("*** Begin Patch"))
                .findFirst()
                .ifPresent(m -> demo.info("回灌给模型的补丁原文（节选）：%s",
                        LoopReport.oneLine(m.text(), 200)));
        demo.info("计划的解析支持「1. 动作 ｜ 验证：方法」这种带验证方式的写法"
                + "（benxin.codex.plan 里每项含 step / verify 两个字段）。");
        demo.info("本次没有打开 bash，因此 验证阶段 不会逼模型跑命令，而是在 "
                + "benxin.codex.verificationNote 里如实写明「工具集中没有命令类工具」。");
    }

    /** ② 权限边界：构造参数 → 提示词与属性。 */
    private void permissionBoundary(Demo demo) {
        // 注意：**不能**把 exp-codex-unrestricted 强转成 CodexLoop。
        // LoopVariants 用"包一层"的方式做变体（否则注册名会退化成内置名并顶掉内置版），
        // 所以它在类型上是 AbstractAgentLoop，不是 CodexLoop —— 它内部持有的那个
        // CodexLoop 才是真正的委托对象。描述与行为都从外层取就够了。
        AgentLoop defaultLoop = loopRegistry.get("codex");
        AgentLoop unrestricted = loopRegistry.get("exp-codex-unrestricted");
        demo.info("内置 codex：%s", defaultLoop.description());
        demo.info("放开变体：%s", unrestricted.description());
        demo.info("口径提醒：变体 Loop 与内置 Loop **没有**继承关系（都是 AbstractAgentLoop 的实现类），"
                + "按具体类强转会在运行期抛 ClassCastException —— 这也是「一切可插拔」的代价与证据。");

        // 未配 systemPrompt 的 Agent 才会看到兜底提示词 —— 这本身就是一条口径。
        AgentResult result = codexUnrestrictedAgent.ask("读取当前目录，说明你有哪些权限。");
        demo.ok("没有配 systemPrompt 的 Agent，兜底提示词按 user 消息注入（首条 user 消息即证据）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 200)));
        LoopReport.attributes(demo, result.attributes());
        demo.info("attributes 里 benxin.codex.sandboxWriteEnabled / execEnabled / approvalPolicy "
                + "就是**构造参数的真实值**，不是从工具清单猜出来的。");

        demo.info("配了 systemPrompt 的 Agent（内置 codex 那个）不会走兜底：");
        AgentResult configured = codexAgent.ask("读取当前目录，说明你有哪些权限。");
        LoopReport.attributes(demo, configured.attributes());
        demo.info("对照结论：想拿到 codex 的权限提示词，就别给它配 systemPrompt；"
                + "配了以后提示词由你负责，它绝不覆盖 —— 这就是「不抢用户提示词」的代价与收益。");
        demo.info("Agent 侧的工具集：%s", agentRegistry.get(AgentNames.CODEX).tools().names());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 清理失败不该让演示中断：补丁工具自己会给出"文件已存在"的诊断
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }
}
