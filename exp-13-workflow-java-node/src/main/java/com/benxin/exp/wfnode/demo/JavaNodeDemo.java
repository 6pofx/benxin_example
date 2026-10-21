package com.benxin.exp.wfnode.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.wfnode.agent.ReleaseAgents;
import com.benxin.exp.wfnode.config.LocalEndpoint;
import com.benxin.exp.wfnode.node.FlakyAuditHandler;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 演示 13 的主线：工作流的 <b>Java 节点</b>。
 *
 * <p>顺序刻意这样安排：先说清"图里能点名哪些处理器"，再跑一张<b>真实模型</b>参与的图，
 * 最后单独跑一张<b>没有模型</b>的图看失败语义 —— 后者即使 LM Studio 没开也能跑完，
 * 因为 Java 节点的容错跟模型没有关系。</p>
 */
@Component
@ConditionalOnProperty(name = "exp.demo.enabled", havingValue = "true", matchIfMissing = true)
public class JavaNodeDemo implements ApplicationRunner {

    private final LocalEndpoint endpoint;
    private final WorkflowHandlerRegistry handlers;
    private final AgentRegistry agentRegistry;
    private final FlakyAuditHandler flakyAudit;

    public JavaNodeDemo(LocalEndpoint endpoint,
                        WorkflowHandlerRegistry handlers,
                        AgentRegistry agentRegistry,
                        FlakyAuditHandler flakyAudit) {
        this.endpoint = endpoint;
        this.handlers = handlers;
        this.agentRegistry = agentRegistry;
        this.flakyAudit = flakyAudit;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 13 · 工作流的 Java 节点（type: java + @LlmNode）");
        demo.banner("模型：%s（本模块没有离线 mock，连不上就只跑不需要模型的那张图）", endpoint.model());
        demo.info("端点：%s", endpoint.describe());

        showRegistry(demo);
        runReleasePipeline(demo);
        runFaultWorkflow(demo);
        summarize(demo);
        demo.done();
    }

    // ------------------------------------------------------------------
    // ① 注册表就是信任边界
    // ------------------------------------------------------------------

    private void showRegistry(Demo demo) {
        demo.section("注册表：图里能点名的处理器，只能是宿主装进来的这些");
        demo.ok("已注册的处理器：%s", handlers.names());
        demo.info("  名字来自 @LlmNode(\"...\")，**不是类名** —— 定义里写的是字符串，");
        demo.info("  类名会随重构变化，注解上的名字才是稳定契约。");
        demo.info("  未注册的名字会在**启动期**失败，并列出这份清单（本模块的测试钉住了这条）。");
        demo.info("  处理器不经过 ToolSandbox 与审批：它等同于应用自己的 service，");
        demo.info("  安全性来自注册制本身 —— 定义里写不出「类名/方法名」，就没有任意代码执行的入口。");
    }

    // ------------------------------------------------------------------
    // ② 真实模型 + 三个 Java 节点
    // ------------------------------------------------------------------

    private void runReleasePipeline(Demo demo) {
        demo.section("主演示：真实模型负责「写」，Java 节点负责「算」与「判」");
        demo.info("  图：begin → summarize(agent) → stats(java) → route → [condense(agent)]");
        demo.info("      → screen(java) → decide → [pack(java)] → done");

        if (!endpoint.enabled()) {
            demo.warn("跳过这张图：LM Studio 不可用（%s）", endpoint.describe());
            demo.info("  本模块不提供离线 mock，所以这里**不伪造**一次模型调用的结果。");
            demo.info("  想强制跑：-Dexp.local.mode=on（连不上会明确报错，而不是静默跳过）。");
            return;
        }

        demo.guard("内容发布流水线（真实模型）", () -> {
            AgentResult result = agentRegistry.get("releaseAgent")
                    .call("本心（benxin）是一个 Spring Boot 插件，它用注解声明式地调用大模型，"
                            + "模型、协议、循环、工具、上下文、记忆、提示词全都可以被替换，"
                            + "而调用方的写法保持不变。新增的工作流 Java 节点允许把一段本地逻辑"
                            + "注册进图里，用来做计数、校验、格式转换这类不该交给模型的事。",
                            "demo-release", null);

            WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
            @SuppressWarnings("unchecked")
            Map<String, Object> variables =
                    (Map<String, Object>) result.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE);

            demo.info("  实际使用的模型：%s（Loop=%s）", result.modelName(), result.loopName());
            demo.info("  走过的节点：%s", run == null ? "（无轨迹）" : run.visited());

            boolean condensed = run != null && run.visited().contains("condense");
            demo.ok("  模型写的第一版摘要：%s", variables == null ? "?" : variables.get("summarize"));
            if (condensed) {
                demo.ok("  超过 40 字 → 又调了一次模型压缩：%s", variables == null ? "?" : variables.get("text"));
            } else {
                demo.info("  篇幅没超限，没有触发压缩那次模型调用");
            }
            demo.ok("  Java 节点数出的字数：%s（篇幅判定：%s）—— 数的是最终要发布的那一版",
                    variables == null ? "?" : variables.get("chars"),
                    variables == null ? "?" : variables.get("level"));
            demo.ok("  Java 节点的合规结论：%s%s",
                    variables == null ? "?" : variables.get("verdict"),
                    blank(variables, "violations") ? "" : "（" + variables.get("violations") + "）");
            demo.info("  这一步很关键：字数与合规都是**本地算的**，模型说什么都不影响它们。");
            demo.info("  用量：%d tokens（in %d / out %d），耗时 %d ms，%d 步",
                    result.usage().totalTokens(), result.usage().inputTokens(),
                    result.usage().outputTokens(), result.durationMillis(), result.steps());
            demo.ok("  最终产出：%s", oneLine(result.text()));
        });
    }

    // ------------------------------------------------------------------
    // ③ 失败语义：这张图里没有 agent 节点
    // ------------------------------------------------------------------

    private void runFaultWorkflow(Demo demo) {
        demo.section("失败语义：retry 与 continueOnError 对 Java 节点同样生效");
        demo.info("  图：begin → audit(java, retry: 3) → charge(java, retry: 1, continueOnError: true)");
        demo.info("      → [${error} 非空 ? compensate : ok] → done");
        demo.info("  这张图一个 agent 节点都没有，所以**不花任何模型调用**，LM Studio 关着也能跑。");

        demo.guard("容错演示（无需模型）", () -> {
            // 清掉上一次的尝试计数，让这次从"前两次必失败"开始
            flakyAudit.reset();

            AgentResult result = agentRegistry.get("faultAgent")
                    .call("交付前的审计与扣费", "demo-fault", null);

            WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
            demo.info("  走过的节点（含失败的尝试）：%s", run == null ? "（无轨迹）" : run.visited());
            demo.info("  audit 被尝试了 %d 次（retry: 3 ⇒ 最多 1+3 次）", flakyAudit.attempts("audit"));
            demo.ok("  整张图仍然走完了：%s", oneLine(result.text()));
            demo.info("  注意 charge 失败后**没有中断**整张图：continueOnError 把它交给了补偿分支，");
            demo.info("  而「走哪条边」仍由 ${error} is not empty 这个条件决定 —— Java 节点自己没有路由权。");
        });
    }

    private void summarize(Demo demo) {
        demo.section("小结：Java 节点补的是哪个洞");
        demo.info("  set    只能写静态模板；branch 只能按表达式选边；tool 是给**模型**用的能力。");
        demo.info("  java   是给**图**用的逻辑：读一批值 → 按业务规则算 → 吐出结构化结果。");
        demo.info("  三条边界：不调模型、不调工具、不决定下一条边。");
        demo.info("  两条输出通道：文本进 ${节点 id}，变量给下游与条件表达式按名字读。");
    }

    private static boolean blank(Map<String, Object> variables, String key) {
        if (variables == null) {
            return true;
        }
        Object value = variables.get(key);
        return value == null || value.toString().isBlank();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
