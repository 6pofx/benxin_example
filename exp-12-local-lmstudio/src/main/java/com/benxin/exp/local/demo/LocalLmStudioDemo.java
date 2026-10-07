package com.benxin.exp.local.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.local.agent.LocalAgents;
import com.benxin.exp.local.config.LocalCallMetrics;
import com.benxin.exp.local.config.LocalModelGate;
import com.benxin.exp.local.tool.LocalTools;
import com.benxin.exp.local.vision.TestImageFactory;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即跑的演示：用本机 LM Studio 上的 Qwen3-VL-8B 把「本心」的真实链路跑一遍。
 *
 * <p>编排上有一条刻意的设计：<b>离线那条路永远先跑</b>。
 * 它证明了"同一批 Agent 代码，换模型不改一个字"，也让 LM Studio 没开的人
 * 仍能看到一个完整可用的演示，而不是一句"连不上，退出"。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocalLmStudioDemo implements ApplicationRunner {

    /** 本机端点在模型注册表里的名字（对应 yml 的 {@code llm.models.local-lmstudio}）。 */
    private static final String LOCAL_MODEL = "local-lmstudio";

    private final LocalAgents.LocalAssistant assistant;
    private final LocalAgents.ToolAssistant toolAssistant;
    private final LocalAgents.VisionAssistant visionAssistant;
    private final LocalAgents.MemoryAssistant memoryAssistant;
    private final AgentRegistry agentRegistry;
    private final ModelRegistry modelRegistry;
    private final LlmAgentFactory agentFactory;
    private final LocalModelGate gate;
    private final LocalCallMetrics metrics;
    private final LocalTools localTools;

    public LocalLmStudioDemo(LocalAgents.LocalAssistant assistant,
                            LocalAgents.ToolAssistant toolAssistant,
                            LocalAgents.VisionAssistant visionAssistant,
                            LocalAgents.MemoryAssistant memoryAssistant,
                            AgentRegistry agentRegistry,
                            ModelRegistry modelRegistry,
                            LlmAgentFactory agentFactory,
                            LocalModelGate gate,
                            LocalCallMetrics metrics,
                            LocalTools localTools) {
        this.assistant = assistant;
        this.toolAssistant = toolAssistant;
        this.visionAssistant = visionAssistant;
        this.memoryAssistant = memoryAssistant;
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.agentFactory = agentFactory;
        this.gate = gate;
        this.metrics = metrics;
        this.localTools = localTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        metrics.reset();
        Demo demo = Demo.begin("示例 12 · 本机小模型（LM Studio / Qwen3-VL-8B）");
        demo.banner("本次判定: %s", gate.describe());
        demo.banner("已注册模型: %s（默认 %s）", modelRegistry.names(), modelRegistry.defaultName());
        demo.banner("Agent: %s", agentRegistry.names());

        demo.guard("离线兜底（同一批接口，用脚本模型）", () -> offlineFallback(demo));
        if (gate.enabled()) {
            if (!gate.modelLooksLoaded()) {
                demo.warn("端点已加载的模型里没有 [%s]，LM Studio 可能会现场换载（首次调用会慢）",
                        gate.model());
            }
            demo.guard("真实对话", () -> realChat(demo));
            demo.guard("真实工具调用（function calling）", () -> realToolCall(demo));
            demo.guard("真实流式（SSE）", () -> realStreaming(demo));
            demo.guard("真实视觉（多模态 ImagePart）", () -> realVision(demo));
            demo.guard("真实多轮记忆", () -> realMemory(demo));
            demo.guard("真实 Loop 对照", () -> realLoopComparison(demo));
        } else {
            demo.section("真实调用 —— 本次跳过");
            demo.warn("%s", gate.describe());
            demo.info("想强制跑真实演示：把 exp.local.mode 设为 on（或确认 LM Studio 已在 %s 上服务）",
                    gate.baseUrl());
        }
        demo.guard("装配视图", () -> assemblyView(demo));

        summary(demo);
        demo.done();
    }

    /** ① 永远先跑：同一批 Agent 接口 + 离线脚本模型，证明"换模型不改业务代码"。 */
    private void offlineFallback(Demo demo) {
        demo.section("离线兜底 —— 同一批接口，换模型不改一个字");
        String answer = assistant.ask("自我介绍");
        demo.ok("离线回答: %s", oneLine(answer));
        LlmModel mock = modelRegistry.get("mock");
        demo.info("模型实现 = %s（默认模型是它，所以 LM Studio 没开也能跑完整演示）", mock);
        demo.info("四个 Agent 的接口上都**没有**写 model 属性：specOf(LocalAssistant).model() = %s",
                agentFactory.specOf(LocalAgents.LocalAssistant.class).model());
    }

    /** ② 真实对话：AgentResult 的每个字段。 */
    private void realChat(Demo demo) {
        demo.section("真实对话 —— 本机 8B 模型的回答与用量");
        Agent agent = localCopy("localAssistant");
        demo.info("派生副本: %s", agent);

        AgentResult result = agent.call("用一句话解释什么是「声明式 Agent」。", "local-chat", null);
        demo.ok("回答: %s", oneLine(result.text()));
        demo.info("model=%s loop=%s steps=%d finishReason=%s duration=%d ms",
                result.modelName(), result.loopName(), result.steps(),
                result.finishReason(), result.durationMillis());
        demo.info("用量: 输入 %d + 输出 %d = %d tokens（本地推理，不花钱）",
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().totalTokens());
    }

    /** ③ 真实 function calling：协议层 tools 声明 → tool_calls 解析 → 结果回灌 → 收尾。 */
    private void realToolCall(Demo demo) {
        demo.section("真实工具调用 —— 模型自己决定调哪个工具");
        localTools.reset();
        Agent agent = localCopy("toolAssistant");

        AgentResult result = agent.call("现在几点了？另外帮我算一下 17 + 25。", "local-tools", null);
        demo.ok("回答: %s", oneLine(result.text()));
        demo.info("steps=%d（>1 说明确实走了一轮工具）工具被真实执行 %d 次",
                result.steps(), localTools.callCount());
        result.toolCalls().forEach(call -> demo.info("   toolCall: %s%s → %s",
                call.name(), call.arguments(), oneLine(call.result())));
    }

    /** ④ 真实流式：首 token 延迟与增量拼接。 */
    private void realStreaming(Demo demo) {
        demo.section("真实流式 —— 增量实时到达");
        Agent agent = localCopy("localAssistant");
        StringBuilder collected = new StringBuilder();
        long[] started = {0};
        long begin = System.currentTimeMillis();

        AgentResult streamed = agent.call("数一下从 1 到 5，用空格隔开。", "local-stream", new LlmStreamHandler() {
            @Override
            public void onStart() {
                started[0] = System.currentTimeMillis();
            }

            @Override
            public void onTextDelta(String delta) {
                collected.append(delta);
            }

            @Override
            public void onUsage(Usage usage) {
                demo.info("onUsage: 输入 %d + 输出 %d", usage.inputTokens(), usage.outputTokens());
            }
        });
        String text = streamed.text();

        demo.ok("增量拼接与返回值一致 = %s", text.equals(collected.toString()));
        demo.info("首 token 延迟 ≈ %d ms，整段 ≈ %d ms",
                started[0] == 0 ? -1 : started[0] - begin, System.currentTimeMillis() - begin);
        demo.info("增量内容: %s", oneLine(collected.toString()));
        demo.info("提示：声明式路径本来就是流式的（模型支持 streaming 就调 stream()），"
                + "这里的 handler 只是把增量接在手上");
    }

    /** ⑤ 真实视觉：这是本地多模态模型最值得演示的一条。 */
    private void realVision(Demo demo) {
        demo.section("真实视觉 —— 运行时生成的图，让模型描述出来");
        demo.info("测试图: %s", TestImageFactory.describe());

        Agent agent = localCopy("visionAssistant");
        ChatMessage message = ChatMessage.user(
                new TextPart(TestImageFactory.question()),
                ImagePart.ofBase64("image/png", TestImageFactory.base64()));

        AgentResult result = agent.call(List.of(message));
        String answer = result.text();
        demo.ok("模型回答: %s", oneLine(answer));
        demo.ok("颜色「%s」= %s；数字 %s = %s",
                TestImageFactory.COLOR, answer.contains(TestImageFactory.COLOR),
                TestImageFactory.NUMBER, answer.contains(TestImageFactory.NUMBER));
        demo.info("用量: 输入 %d + 输出 %d（图片会被切成视觉 token，所以输入明显更大）",
                result.usage().inputTokens(), result.usage().outputTokens());
        demo.info("多模态走的是 Agent API：声明式接口的参数只支持文本/上下文变量，"
                + "发图要用 ChatMessage.user(TextPart, ImagePart) 组装消息");
        demo.info("实测观察：图小/字小时（320×160、64px）8B 模型会偶发把 42 读成 12；"
                + "放大到 640×320、150px 后连测多次全对 —— 换模型时先怀疑分辨率，再怀疑插件");
    }

    /** ⑥ 真实多轮记忆：第二问不再复述第一问的实体。 */
    private void realMemory(Demo demo) {
        demo.section("真实多轮记忆 —— 同一个 sessionId 续接");
        // 注意：不能用声明式接口直接调 —— 那个 Agent 的模型是默认的 mock。
        // 要"用本地模型跑多轮"，同样得先派生一个指向本机端点的副本。
        Agent local = localCopy("memoryAssistant");
        String first = local.call("记住一个代号：青竹。只回复「好」。", "local-memory", null).text();
        String second = local.call("刚才让你记的代号是什么？只回复代号。", "local-memory", null).text();
        demo.ok("第一问: %s", oneLine(first));
        demo.ok("第二问: %s（第二问没有重复提供代号，靠的是会话历史）", oneLine(second));

        demo.info("会话 local-memory 的历史条数 = %d", local.history("local-memory").size());
    }

    /** ⑦ 真实 Loop 对照：同一个任务，两个 Loop 的步数与用量。 */
    private void realLoopComparison(Demo demo) {
        demo.section("真实 Loop 对照 —— dsh-minimal vs react");
        // 只换 loop，其余原样继承（监听器也在其中，所以统计不会漏也不会重复）
        Agent minimal = localCopy("localAssistant").toBuilder().loop("dsh-minimal").build();
        Agent react = localCopy("localAssistant").toBuilder().loop("react").build();
        demo.info("两个副本只差 loop：%s / %s", minimal.loop().name(), react.loop().name());

        AgentResult a = minimal.call("回答一个字：好", "loop-minimal", null);
        AgentResult b = react.call("回答一个字：好", "loop-react", null);
        demo.info("[dsh-minimal] steps=%d usage=%d+%d 回答=%s",
                a.steps(), a.usage().inputTokens(), a.usage().outputTokens(), oneLine(a.text()));
        demo.info("[react]       steps=%d usage=%d+%d 回答=%s",
                b.steps(), b.usage().inputTokens(), b.usage().outputTokens(), oneLine(b.text()));
        demo.info("两者骨架相同：模型支持原生 function calling 时，react 走的也是结构化工具调用；"
                + "它的文本协议（Thought/Action/Observation）只在模型不支持工具调用时才启用");
    }

    /** ⑧ 装配视图：谁挂了哪些工具、端点是怎么配的。 */
    private void assemblyView(Demo demo) {
        demo.section("装配视图 —— 端点是配置，模型是 bean，业务代码零改动");
        ToolRegistry tools = agentRegistry.get("toolAssistant").tools();
        demo.info("toolAssistant 的工具: %s", tools.names());
        demo.info("localAssistant 的工具: %s（没写 tools/toolNames → 继承容器全局工具表）",
                agentRegistry.get("localAssistant").tools().names());
        demo.info("本机端点: %s → POST %s", LOCAL_MODEL, gate.chatCompletionsUrl());
        demo.info("下发给上游的模型 id: %s（yml 里可用 $${LOCAL_MODEL_ID:...} 覆盖）", gate.model());
        demo.info("当前可用 Loop: %s", agentFactory.loopRegistry().names().stream().limit(4).toList());
    }

    private void summary(Demo demo) {
        demo.section("本次运行统计");
        if (metrics.runCount() == 0) {
            demo.info("没有发生真实调用（本次只有离线兜底那次）");
            return;
        }
        metrics.runs().forEach(line -> demo.info("   %s", line));
        demo.ok("真实运行 %d 次 / 工具调用 %d 次（其中失败 %d 次）/ 合计 %d 步 / %d tokens",
                metrics.runCount(), metrics.toolCalls(), metrics.toolErrors(),
                metrics.steps(), metrics.totalTokens());
        demo.info("这些 token 全部由本机推理产生：**不花钱、不出网**；"
                + "想对照云端用量可以看 exp-11 的实测表");
    }

    // ------------------------------------------------------------------

    /**
     * 拿一个"只换了模型"的 Agent 副本。
     *
     * <p>{@code toBuilder()} 会把 Loop / 工具 / 记忆 / 沙箱 / 审批 / 拦截器 / 监听器
     * 全部原样继承（注册表与监听器都在里面），所以这里只写一行 {@code model(...)} 就够了 ——
     * 这正是"换模型不改业务代码"的字面意思。</p>
     *
     * <p>这里按**名字**换模型。1.1.0 时这一步是不生效的（{@code toBuilder()} 已带模型实例，
     * 而解析顺序"实例优先"，名字被静默忽略 —— FINDINGS 第 30 条），当时必须传实例；
     * 1.1.1 修好之后两种写法等价。</p>
     *
     * <p>同理，1.1.0 的 {@code toBuilder()} 还会**丢掉监听器**（FINDINGS 第 31 条），
     * 那时这里要额外补一句 {@code .listener(metrics)}，而现在不用 —— 多补一次反而会让
     * 同一次运行被统计两遍。</p>
     */
    private Agent localCopy(String agentName) {
        return agentRegistry.get(agentName).toBuilder().model(LOCAL_MODEL).build();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }
}
