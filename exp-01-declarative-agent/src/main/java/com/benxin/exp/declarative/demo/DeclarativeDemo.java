package com.benxin.exp.declarative.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.declarative.agent.BasicAssistant;
import com.benxin.exp.declarative.agent.PlainAssistant;
import com.benxin.exp.declarative.agent.TimeAssistant;
import com.benxin.exp.declarative.agent.WeatherAssistant;
import com.benxin.exp.declarative.dto.ReviewReport;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 启动即跑的演示：把"声明式 Agent"这条路径上的每个能力都打一遍。
 *
 * <p>输出刻意保持"看得见结论"：不只打印回答，还打印框架内部的装配结果
 * （AgentSpec、真正发出去的消息、运行时属性），因为声明式的价值恰恰在于
 * "你写一个接口，剩下的都被框架做掉了" —— 那些被做掉的部分必须能被观察。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeclarativeDemo implements ApplicationRunner {

    private final BasicAssistant basic;
    private final WeatherAssistant weather;
    private final TimeAssistant time;
    private final RecordingModel model;
    private final AgentRegistry agentRegistry;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmAgentFactory agentFactory;
    private final CommonTools commonTools;

    public DeclarativeDemo(BasicAssistant basic,
                           WeatherAssistant weather,
                           TimeAssistant time,
                           RecordingModel mockLlmModel,
                           AgentRegistry agentRegistry,
                           ModelRegistry modelRegistry,
                           LoopRegistry loopRegistry,
                           LlmAgentFactory agentFactory,
                           CommonTools commonTools) {
        this.basic = basic;
        this.weather = weather;
        this.time = time;
        this.model = mockLlmModel;
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.agentFactory = agentFactory;
        this.commonTools = commonTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 01 · 声明式 Agent —— 一个接口 + 一个注解，注入即用");
        demo.banner("模型: %s（默认 %s）", modelRegistry.names(), modelRegistry.defaultName());
        demo.banner("Loop: %s", loopRegistry.names());
        demo.banner("Agent: %s", agentRegistry.names());

        demo.guard("最小声明式调用", () -> minimalCall(demo));
        demo.guard("参数注解语义", () -> parameterSemantics(demo));
        demo.guard("方法级系统提示词", () -> methodLevelPrompt(demo));
        demo.guard("强类型 DTO 返回", () -> typedReturn(demo));
        demo.guard("返回值类型全谱", () -> returnTypes(demo));
        demo.guard("流式增量", () -> streaming(demo));
        demo.guard("tools 与 toolNames", () -> toolSelection(demo));
        demo.guard("会话与历史", () -> sessionAndHistory(demo));
        demo.guard("装配视图", () -> assemblyView(demo));

        demo.done();
    }

    /** ① 最核心的一条：方法调用就是一次 Agent 运行，调用方看不到任何 LLM API。 */
    private void minimalCall(Demo demo) {
        demo.section("最小声明式调用 —— 接口 + 注解，注入即用");
        reset();
        String answer = basic.ask("你好");
        demo.ok("回答: %s", answer);
        demo.info("底层脚本模型被调用了 %d 次（一次问答 = 一次模型调用）", scripted().calls());
    }

    /** ② 参数注解：@User 前缀后缀、@Assistant 预填充、@Ctx 不进消息体、@Memory 会话续接。 */
    private void parameterSemantics(Demo demo) {
        demo.section("参数注解语义 —— 哪些进消息体，哪些不进");

        reset();
        basic.askWithPrefix("我在准备出差", "请把这句话改写得更礼貌");
        demo.info("@User(prefix/suffix) 之后真正发出去的消息：");
        printMessages(demo, model.lastRequest());

        reset();
        basic.guided("好的，我先用一句话概括。", "帮我总结这次改动");
        demo.info("@Assistant 会作为 assistant 角色进入历史（用于少样本引导）：");
        printMessages(demo, model.lastRequest());

        reset();
        basic.explain("public class A { void f() {} }", "严谨", "初学者");
        ChatRequest request = model.lastRequest();
        demo.info("@Ctx 渲染进了系统提示词：");
        demo.info("   %s", oneLine(request.systemText()));
        demo.info("@Ctx 没有泄漏进消息体（除系统提示词外，消息里只应有 user 那一条）：");
        printMessages(demo, request);
    }

    /** ③ 方法级 @SystemPrompt：inherit 与 append 的差别一眼可见。 */
    private void methodLevelPrompt(Demo demo) {
        demo.section("方法级系统提示词 —— value / append / inherit");

        reset();
        basic.reviewStrict("public class A { void f() {} }");
        demo.info("inherit = false（只用方法级提示词）：");
        demo.info("   %s", oneLine(model.lastRequest().systemText()));

        reset();
        basic.reviewStrictWithAppend("public class A { void f() {} }");
        demo.info("inherit = true + append（方法级 + append + Agent 级提示词拼接）：");
        demo.info("   %s", oneLine(model.lastRequest().systemText()));

        reset();
        basic.tone("讲讲这个插件", "轻松");
        demo.info("@Ctx(attribute = false) 只渲染模板、不写运行时属性：");
        demo.info("   %s", oneLine(model.lastRequest().systemText()));
    }

    /** ④ DTO 返回：模型输出的 JSON 直接变成 Java 对象。 */
    private void typedReturn(Demo demo) {
        demo.section("强类型返回 —— 模型输出的 JSON 自动反序列化成 record");
        reset();
        ReviewReport report = basic.review("public class A { void f() { } }");
        demo.ok("summary = %s", report.summary());
        demo.ok("issues  = %s", report.issues());
        demo.ok("score   = %d", report.score());
    }

    /** ⑤ 同一个接口，声明什么返回类型就得到什么。 */
    private void returnTypes(Demo demo) {
        demo.section("返回值类型全谱 —— String / AgentResult / Optional / List / int / long / boolean / void");

        reset();
        AgentResult result = basic.result("介绍一下你自己");
        demo.info("AgentResult: loop=%s model=%s steps=%d finishReason=%s",
                result.loopName(), result.modelName(), result.steps(), result.finishReason());
        demo.info("  用量: 输入 %d / 输出 %d / 合计 %d",
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().totalTokens());
        demo.info("  文本: %s", oneLine(result.text()));

        reset();
        int steps = basic.steps("介绍一下你自己");
        long elapsed = basic.elapsedMillis("介绍一下你自己");
        boolean answered = basic.answered("介绍一下你自己");
        demo.ok("int 返回步数 = %d，long 返回耗时 = %d ms，boolean = %s", steps, elapsed, answered);

        reset();
        List<ChatMessage> transcript = basic.transcript("介绍一下你自己");
        demo.ok("List<ChatMessage> 返回 %d 条消息，最后一条角色 = %s",
                transcript.size(), transcript.get(transcript.size() - 1).role());

        reset();
        Optional<String> silent = basic.maybe("保持沉默");
        demo.ok("Optional<String> 在模型输出空白时落到 empty：isPresent=%s，orElse 兜底 = %s",
                silent.isPresent(), silent.orElse("（兜底值）"));

        reset();
        basic.fireAndForget("介绍一下你自己");
        demo.ok("void 方法也不影响运行，历史仍然被写回：%s",
                oneLine(basic.result("介绍一下你自己").text()));
    }

    /** ⑥ 流式：多一个 LlmStreamHandler 参数即可。 */
    private void streaming(Demo demo) {
        demo.section("流式输出 —— 增量实时到达（不需要另一套 API）");
        reset();
        StringBuilder collected = new StringBuilder();
        String finalText = basic.stream("介绍一下你自己", new LlmStreamHandler() {
            @Override
            public void onStart() {
                demo.info("onStart：可以开始渲染了");
            }

            @Override
            public void onTextDelta(String delta) {
                collected.append(delta);
            }

            @Override
            public void onUsage(com.benxin.llm.core.chat.Usage usage) {
                demo.info("onUsage：输入 %d / 输出 %d", usage.inputTokens(), usage.outputTokens());
            }
        });
        demo.ok("共收到 %d 个字符的增量，方法返回值与拼接结果一致 = %s",
                collected.length(), finalText.equals(collected.toString()));
        demo.info("   增量内容: %s", oneLine(collected.toString()));
    }

    /** ⑦ tools / toolNames：两种挑选工具的方式。 */
    private void toolSelection(Demo demo) {
        demo.section("tools 与 toolNames —— 两种挑选工具的方式");
        demo.info("容器全局工具表: %s", agentRegistry.get("weatherAssistant").tools().names());

        reset();
        commonTools.reset();
        String answer = weather.ask("北京天气怎么样？");
        demo.ok("tools = {CommonTools.class}：工具被调用 %d 次 → %s", commonTools.callCount(), oneLine(answer));
        demo.info("   模型请求的工具调用: %s", scripted().toolCallLog());

        reset();
        String now = time.ask("现在几点了？");
        demo.ok("toolNames = {get_current_time, calculate}：%s", oneLine(now));
        demo.info("   该 Agent 实际拿到的工具: %s", agentRegistry.get("timeAssistant").tools().names());
    }

    /** ⑧ 会话与历史：sessionId 决定"和谁续聊"。 */
    private void sessionAndHistory(Demo demo) {
        demo.section("会话与记忆 —— sessionId 决定续接哪一份历史");
        reset();
        basic.chatWithMemory("demo-session", "你好");
        basic.chatWithMemory("demo-session", "介绍一下你自己");
        basic.chatWithMemory("other-session", "你好");

        Agent agent = agentRegistry.get("basicAssistant");
        demo.ok("会话 demo-session 的历史条数 = %d", agent.history("demo-session").size());
        demo.ok("会话 other-session 的历史条数 = %d（互不干扰）", agent.history("other-session").size());

        agent.clearHistory("other-session");
        demo.ok("clearHistory 之后 other-session 的历史条数 = %d", agent.history("other-session").size());

        var session = agent.session("session-object");
        session.chat("你好");
        demo.ok("AgentSession 视图：id=%s，历史 %d 条，属性 %s",
                session.id(), session.history().size(), session.attributes().keySet());
    }

    /** ⑨ 装配视图：注解 → AgentSpec 的翻译结果，以及 Agent 最终拿到了什么。 */
    private void assemblyView(Demo demo) {
        demo.section("装配视图 —— 注解是怎么变成 AgentSpec 的");
        var spec = agentFactory.specOf(BasicAssistant.class);
        demo.info("BasicAssistant 的 AgentSpec:");
        demo.info("   name=%s model=%s loop=%s maxSteps=%d temperature=%.2f maxTokens=%d memory=%s",
                spec.name(), spec.model(), spec.loop(), spec.maxSteps(),
                spec.temperature(), spec.maxTokens(), spec.memory());
        demo.info("   systemPrompt=%s", oneLine(spec.systemPrompt()));
        demo.info("   metadata=%s", spec.metadata());

        var plainSpec = agentFactory.specOf(PlainAssistant.class);
        demo.info("PlainAssistant（注解上一个属性都没写）的 AgentSpec:");
        demo.info("   name=%s（从接口名推断） loop=%s maxSteps=%d（回落 llm.agent.*）memory=%s",
                plainSpec.name(), plainSpec.loop(), plainSpec.maxSteps(), plainSpec.memory());

        Agent weatherAgent = agentRegistry.get("weatherAssistant");
        demo.info("weatherAssistant 实际装配结果:");
        demo.info("   loop=%s model=%s tools=%s maxSteps=%d",
                weatherAgent.loop().name(), weatherAgent.model().name(),
                weatherAgent.tools().names(), weatherAgent.spec().maxSteps());
        demo.info("AgentRegistry 里的名字（含尚未被调用过的）: %s", agentRegistry.names());
        demo.info("可用 Loop（前 4 个）: %s", loopRegistry.names().stream().limit(4).toList());
    }

    // ------------------------------------------------------------------

    private void printMessages(Demo demo, ChatRequest request) {
        if (request == null) {
            demo.warn("没有录制到请求");
            return;
        }
        request.messages().forEach(m -> demo.info("   [%s] %s", m.role(), oneLine(m.text())));
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    private void reset() {
        model.reset();
        // 声明式 Agent 默认开着记忆，不传 sessionId 时都落在 "<agentName>-default" 上。
        // 演示里每一节都清空一次历史，否则上一节的对话会进入下一节的消息体，
        // 打印出来的"这一轮到底发了什么"就不好读了。
        //
        // 注意这里走的是 names() + find()：@LlmAgent 接口在注册表里是"惰性条目"
        // （registerLazy），resolved() 只会列出已经被解析过的那些 —— 直接用它遍历，
        // 一个都清不掉。find() 会顺带把惰性条目解析成真身。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    private ScriptedLlmModel scripted() {
        return (ScriptedLlmModel) model.unwrap();
    }
}
