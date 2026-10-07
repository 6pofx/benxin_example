package com.benxin.exp.observability.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.agent.FailAssistant;
import com.benxin.exp.observability.agent.GuardedAssistant;
import com.benxin.exp.observability.agent.ObservabilityAssistant;
import com.benxin.exp.observability.agent.RewriteAssistant;
import com.benxin.exp.observability.agent.TaskAssistant;
import com.benxin.exp.observability.agent.ThinkingAssistant;
import com.benxin.exp.observability.agent.ToolErrorProbe;
import com.benxin.exp.observability.agent.ToolGuardAssistant;
import com.benxin.exp.observability.interceptor.GuardInterceptor;
import com.benxin.exp.observability.interceptor.OrderProbeInterceptor;
import com.benxin.exp.observability.interceptor.RewriteInterceptor;
import com.benxin.exp.observability.interceptor.ShortCircuitInterceptor;
import com.benxin.exp.observability.interceptor.ToolAuditInterceptor;
import com.benxin.exp.observability.listener.MonitorListener;
import com.benxin.exp.observability.listener.ToolActivityListener;
import com.benxin.exp.observability.stream.StreamingService;
import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.exp.observability.web.SessionIds;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.CompositeListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.SelectiveInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 启动即跑的演示：把"可观测性 + HTTP 端点"这条路径上的每个能力都打一遍。
 *
 * <p>输出刻意保持"看得见结论"：不只打印回答，还打印<b>监听器收到的事件序列</b>、
 * <b>拦截器的调用顺序</b>与<b>自己的 HTTP 端点的真实响应体</b> ——
 * 因为这一章讲的正是"跑起来之后你能看见什么"，看不见就等于没演示。</p>
 *
 * <p>端口不写死：{@code server.port=0} 时真实端口由操作系统分配，
 * 这里从 {@link ApplicationContext} 的 {@code local.server.port} 读回来，
 * 再用 {@link RestClient} 打自己 —— 与外部调用者走的是同一条 HTTP 路径。</p>
 */
@Component
@ConditionalOnProperty(prefix = "demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityDemo implements ApplicationRunner {

    private final ChatAssistant chat;
    private final ThinkingAssistant thinking;
    private final ToolGuardAssistant toolGuard;
    private final GuardedAssistant guarded;
    private final RewriteAssistant rewrite;
    private final ObservabilityAssistant observability;
    private final ToolErrorProbe toolErrorProbe;
    private final FailAssistant fail;
    private final MonitorListener monitor;
    private final ToolActivityListener activity;
    private final RecordingModel model;
    private final CommonTools commonTools;
    private final FailingProbeTool failingProbeTool;
    private final AgentRegistry agentRegistry;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmInterceptorChain interceptorChain;
    private final ObjectProvider<AgentInterceptor> rawInterceptors;
    private final ShortCircuitInterceptor shortCircuit;
    private final ToolAuditInterceptor toolAudit;
    private final SessionIds sessionIds;
    private final ApplicationContext context;

    private RestClient http;

    public ObservabilityDemo(ChatAssistant chat,
                             ThinkingAssistant thinking,
                             ToolGuardAssistant toolGuard,
                             GuardedAssistant guarded,
                             RewriteAssistant rewrite,
                             ObservabilityAssistant observability,
                             ToolErrorProbe toolErrorProbe,
                             FailAssistant fail,
                             TaskAssistant task,
                             RecordingModel mockLlmModel,
                             CommonTools commonTools,
                             FailingProbeTool failingProbeTool,
                             AgentRegistry agentRegistry,
                             ModelRegistry modelRegistry,
                             LoopRegistry loopRegistry,
                             LlmInterceptorChain interceptorChain,
                             ObjectProvider<AgentInterceptor> rawInterceptors,
                             ShortCircuitInterceptor shortCircuitInterceptor,
                             ToolAuditInterceptor toolAuditInterceptor,
                             SessionIds sessionIds,
                             ApplicationContext context) {
        this.chat = chat;
        this.thinking = thinking;
        this.toolGuard = toolGuard;
        this.guarded = guarded;
        this.rewrite = rewrite;
        this.observability = observability;
        this.toolErrorProbe = toolErrorProbe;
        this.fail = fail;
        // 监听器按名字取而不是按类型注入：ObservabilityConfiguration 里两个 @Bean 的返回类型
        // 刻意声明为 AgentListener —— 内置 llmLoggingListener 的 @ConditionalOnMissingBean
        // 是按类型判断的，容器里必须真的存在"类型为 AgentListener 的 bean"它才会让位。
        // 按名字 + 具体类型取回实例，既保住那条装配语义，又能读到录制下来的事件。
        this.monitor = context.getBean("monitorListener", MonitorListener.class);
        this.activity = context.getBean("toolActivityListener", ToolActivityListener.class);
        this.model = mockLlmModel;
        this.commonTools = commonTools;
        this.failingProbeTool = failingProbeTool;
        this.agentRegistry = agentRegistry;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.interceptorChain = interceptorChain;
        this.rawInterceptors = rawInterceptors;
        this.shortCircuit = shortCircuitInterceptor;
        this.toolAudit = toolAuditInterceptor;
        this.sessionIds = sessionIds;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 08 · 可观测性与 HTTP 端点");
        demo.banner("模型: %s（默认 %s）", modelRegistry.names(), modelRegistry.defaultName());
        demo.banner("Loop: %s", loopRegistry.names());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("监听器: %s + %s（内置 llmLoggingListener 已让位）", monitor, activity);
        demo.banner("拦截器链（%d 个，已按 order 升序、并完成 @LlmGuard 范围包装）:",
                interceptorChain.interceptors().size());
        interceptorChain.interceptors().forEach(i -> demo.banner("   order=%4d  %s", i.order(), i));

        prepareHttp(demo);

        demo.guard("监听器 12 个回调", () -> listenerCallbacks(demo));
        demo.guard("监听器装配语义", () -> listenerWiring(demo));
        demo.guard("拦截器 9 个钩子与顺序", () -> interceptorHooks(demo));
        demo.guard("@LlmGuard 作用范围", () -> guardScope(demo));
        demo.guard("流式三件套", () -> streaming(demo));
        demo.guard("HTTP 端点全量", () -> httpEndpoints(demo));
        demo.guard("端点契约里最容易猜错的几条", () -> httpContracts(demo));

        demo.done();

        if (context.getEnvironment().getProperty("exp.demo.exit-after-run", Boolean.class, false)) {
            System.out.println("  [exp.demo.exit-after-run=true] 演示结束，主动退出 JVM。");
            System.exit(org.springframework.boot.SpringApplication.exit(context));
        }
    }

    // ------------------------------------------------------------------
    // HTTP 客户端准备
    // ------------------------------------------------------------------

    private void prepareHttp(Demo demo) {
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null && context instanceof WebServerApplicationContext web) {
            port = web.getWebServer().getPort();
        }
        String basePath = context.getEnvironment().getProperty("llm.web.base-path", "/llm");
        if (port == null) {
            demo.warn("没有拿到 WebServer 端口，HTTP 演示将跳过（是否在非 Web 环境里跑？）");
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 演示里所有调用都打本机离线模型，3s/10s 足够；设超时是为了不让演示挂死。
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port + basePath)
                .requestFactory(factory)
                .build();
        demo.banner("HTTP 端点: http://localhost:%d%s（base-path 来自 llm.web.base-path）", port, basePath);
    }

    // ------------------------------------------------------------------
    // ① 监听器 12 个回调
    // ------------------------------------------------------------------

    private void listenerCallbacks(Demo demo) {
        demo.section("AgentListener 的 12 个回调 —— 全部触发并留档");
        reset();

        AgentResult result = observability.run("北京天气怎么样？");
        printEvents(demo, 24);

        demo.ok("onAgentStart=%d onStepStart=%d onStepEnd=%d onToolCall=%d onToolResult=%d",
                monitor.count("onAgentStart"), monitor.count("onStepStart"),
                monitor.count("onStepEnd"), monitor.count("onToolCall"), monitor.count("onToolResult"));
        demo.ok("onSubAgentStart=%d onSubAgentEnd=%d onCustomEvent=%d onAgentEnd=%d onError=%d",
                monitor.count("onSubAgentStart"), monitor.count("onSubAgentEnd"),
                monitor.count("onCustomEvent"), monitor.count("onAgentEnd"), monitor.count("onError"));

        demo.info("onAgentEnd 拿到的 AgentResult 全字段:");
        demo.info("   steps=%d  finishReason=%s  maxStepsReached=%s  durationMillis=%dms",
                result.steps(), result.finishReason(), result.maxStepsReached(), result.durationMillis());
        demo.info("   agent=%s session=%s loop=%s model=%s",
                result.agentName(), result.sessionId(), result.loopName(), result.modelName());
        demo.info("   usage: 输入 %d / 输出 %d / 合计 %d",
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().totalTokens());
        demo.info("   toolCalls=%s", result.toolCalls().stream()
                .map(t -> t.name() + "(error=" + t.error() + ", " + t.durationMillis() + "ms)").toList());
        demo.info("   attributes=%s", result.attributes());
        demo.info("   文本=%s", oneLine(result.text()));

        // 思考增量
        reset();
        thinking.ask("介绍一下你自己");
        demo.info("思考型 Agent（thinking(true)）：onThinkingDelta=%d 片，onTextDelta=%d 片",
                monitor.count("onThinkingDelta"), monitor.count("onTextDelta"));
        demo.info("   拼回的文本=%s", oneLine(monitor.joinRaw("onTextDelta")));

        // 工具失败结果的 onToolResult。
        // 用 probe_fail（唯一一个真的会抛异常的工具）：CommonTools.calculate 的"失败"
        // 只是内容里的一段错误文本，ToolResult.error() 仍然是 false —— 那条路走不出 error=true。
        reset();
        failingProbeTool.reset();
        AgentResult failed = demo.guard("工具失败路径",
                () -> toolErrorProbe.ask(FailingProbeTool.TRIGGER), null);
        if (failed != null) {
            demo.ok("工具失败：onToolResult error=%s（工具被真实调用 %d 次，运行照常收尾）",
                    failed.toolCalls().get(0).error(), failingProbeTool.callCount());
            demo.info("   失败文本=%s", oneLine(failed.toolCalls().get(0).result()));
            monitor.details("onToolResult").forEach(d -> demo.info("   onToolResult 收到: %s", d));
        }

        // 对照：内容层面的"失败"不会让 error 变成 true
        reset();
        AgentResult contentOnly = observability.run("#tool:calculate {\"expression\":\"1+1+?\"}");
        demo.warn("对照：calculate 的失败只是内容里的错误（error=%s）：%s",
                contentOnly.toolCalls().get(0).error(), oneLine(contentOnly.toolCalls().get(0).result()));
        demo.info("   顺带一条口径：`#tool:<名字>` 每一步都会重新命中 → 本次运行调了 %d 次工具（结果被截断收尾=%s）",
                contentOnly.toolCalls().size(), contentOnly.maxStepsReached());

        // onError
        reset();
        try {
            fail.ask("随便问点什么");
        } catch (RuntimeException expected) {
            demo.info("模型抛异常（预期行为）: %s", expected.getClass().getSimpleName());
        }
        demo.warn("失败运行只广播 onError、不产生 onAgentEnd：onError=%d 次，onAgentEnd=%d 次",
                monitor.count("onError"), monitor.count("onAgentEnd"));
        monitor.details("onError").forEach(d -> demo.info("   onError 收到: %s", d));
    }

    // ------------------------------------------------------------------
    // ② 监听器装配语义
    // ------------------------------------------------------------------

    private void listenerWiring(Demo demo) {
        demo.section("监听器装配语义 —— 内置让位 + 自定义叠加");
        reset();

        chat.ask("自我介绍");
        demo.ok("两个自定义监听器同时收到事件：monitor.onAgentEnd=%d，activity.agentEnds=%d",
                monitor.count("onAgentEnd"), activity.agentEnds());
        demo.ok("容器里 'llmLoggingListener' bean 存在吗？%s（@ConditionalOnMissingBean 按类型判断 → 让位）",
                context.containsBean("llmLoggingListener"));
        demo.ok("容器里类型为 AgentListener 的 bean: %s",
                String.join(", ", context.getBeanNamesForType(AgentListener.class)));

        MonitorListener left = new MonitorListener("composite-left");
        MonitorListener right = new MonitorListener("composite-right");
        AgentListener composite = CompositeListener.of(left, AgentListener.noop(), right);
        demo.info("手动构造的 CompositeListener = %s", composite);

        // 手动构造的链必须现场装到 Agent 上才会收到事件：容器里那些 Agent 的监听器是启动期
        // 定好的，而 toBuilder() 刻意不复制监听器 —— 正好用来把整条链替换成这一份。
        agentRegistry.get("chatAssistant").toBuilder()
                .listener(composite)
                .build()
                .call("自我介绍", "composite-demo-session");
        demo.ok("CompositeListener.of(left, noop, right) 装到 Agent 副本上：left=%d 条，right=%d 条（noop 一列不发事件）",
                left.events().size(), right.events().size());
        demo.info("of(...) 会拍平嵌套的 CompositeListener、过滤 null（但不会过滤 noop —— 它只是一个普通匿名实现）；"
                + "单个监听器抛异常会被吞掉，不影响其它监听器");
    }

    // ------------------------------------------------------------------
    // ③ 拦截器 9 个钩子与顺序
    // ------------------------------------------------------------------

    private void interceptorHooks(Demo demo) {
        demo.section("AgentInterceptor 的 9 个钩子 —— 顺序、短路、改写、记账");
        reset();
        OrderProbeInterceptor.resetTrace();
        GuardInterceptor.resetHooks();
        shortCircuit.reset();
        toolAudit.reset();

        guarded.ask("自我介绍");
        demo.ok("order() 升序执行；afterAgent 也是升序（不是洋葱式逆序）:");
        demo.info("   %s", OrderProbeInterceptor.trace());
        demo.ok("@LlmGuard 目标 Agent 上被触发的钩子种类: %s",
                GuardInterceptor.names().stream().distinct().toList());
        demo.info("   9 个钩子里 order() 只在装配期被询问（本次运行期 0 次），supports() 在每次调用前被问 %d 次",
                GuardInterceptor.supportsAsked());

        // 短路 + 记账
        reset();
        shortCircuit.reset();
        toolAudit.reset();
        commonTools.reset();
        String shortCircuited = toolGuard.ask("北京天气怎么样？").text();
        demo.ok("beforeTool 返回非 null → 工具被短路：CommonTools 实际执行 %d 次，短路 %d 次",
                commonTools.callCount(), shortCircuit.shortCircuits());
        demo.info("   模型拿到的是拦截器给的结果: %s", oneLine(shortCircuited));
        demo.ok("afterTool 对短路结果同样记账: %s", toolAudit.entries());

        // 改写
        reset();
        String rewritten = rewrite.ask("自我介绍");
        ChatRequest captured = model.lastRequest();
        demo.ok("beforeModel 塞进请求 extra 的 %s = %s", RewriteInterceptor.TRACE_KEY,
                captured == null ? "(未捕获)" : captured.extra().get(RewriteInterceptor.TRACE_KEY));
        demo.ok("afterModel 的改写进入了最终结果（含标记 %s）= %s",
                RewriteInterceptor.SUFFIX_MARKER, rewritten.contains(RewriteInterceptor.SUFFIX_MARKER));
        demo.info("   最终文本: %s", oneLine(rewritten));

        // onError
        GuardInterceptor.resetHooks();
        try {
            fail.ask("失败一次");
        } catch (RuntimeException expected) {
            demo.info("拦截器 onError 路径（预期抛异常）: %s", expected.getClass().getSimpleName());
        }
        demo.info("注意：failAssistant 不在 @LlmGuard 的名单里，所以 GuardInterceptor 的 onError 计数为 %d",
                GuardInterceptor.names().stream().filter(n -> n.equals("onError")).count());
    }

    // ------------------------------------------------------------------
    // ④ @LlmGuard 作用范围
    // ------------------------------------------------------------------

    private void guardScope(Demo demo) {
        demo.section("@LlmGuard 作用范围与 LlmInterceptorChain");
        reset();

        GuardInterceptor.resetHooks();
        chat.ask("自我介绍");
        demo.ok("未被点名的 Agent [chatAssistant]：钩子记录 %d 条（范围声明生效）", GuardInterceptor.hooks().size());
        demo.info("   supports() 被询问 %d 次 —— 名单不匹配时 SelectiveInterceptor 的 && 直接短路，"
                + "连被包装者都不会被问到", GuardInterceptor.supportsAsked());

        GuardInterceptor.resetHooks();
        guarded.ask("自我介绍");
        demo.ok("被点名的 Agent [guardedAssistant]：钩子记录 %d 条", GuardInterceptor.hooks().size());

        long wrapped = interceptorChain.interceptors().stream()
                .filter(i -> i instanceof SelectiveInterceptor).count();
        demo.ok("LlmInterceptorChain 里有 %d 个 SelectiveInterceptor（@LlmGuard 的包装层）", wrapped);
        long raw = rawInterceptors.orderedStream().filter(i -> i instanceof SelectiveInterceptor).count();
        demo.warn("ObjectProvider<AgentInterceptor> 里被包装的数量 = %d —— 它取到未包装的原始 bean，"
                + "消费端依赖它会让 @LlmGuard(agents={...}) 的范围声明失效", raw);
    }

    // ------------------------------------------------------------------
    // ⑤ 流式三件套
    // ------------------------------------------------------------------

    private void streaming(Demo demo) {
        demo.section("流式三件套 —— LlmStreamHandler / StreamCollector / ofText");
        StreamingService streaming = new StreamingService(model);

        StreamingService.SevenHookRecorder hooks =
                streaming.observeHooks(StreamingService.textRequest(model, "自我介绍"));
        demo.ok("7 个回调：onStart=%d（恰好一次）、文本 %d 字符、onUsage=%d、onComplete=%d、onError=%d",
                hooks.starts(), hooks.text().length(), hooks.usages(), hooks.completions(), hooks.errors());
        demo.info("   onThinkingDelta 收到 %d 字符思维链，onToolCall %d 次",
                hooks.thinking().length(), hooks.toolCalls().size());

        StreamingService.CollectorSummary tools = streaming.collect(
                StreamingService.toolRequest(model, "北京天气", "get_weather", "{\"city\":\"北京\"}"));
        demo.ok("StreamCollector：isStarted=%s hasContent=%s text=%d 字符 toolUses=%s",
                tools.started(), tools.hasContent(), tools.text().length(), tools.toolNames());
        demo.info("   usage=%s，error=%s", StreamingService.usageLine(tools.usage()),
                StreamingService.describe(tools.error()));

        StringBuilder sink = new StringBuilder();
        streaming.collectTextOnly(StreamingService.textRequest(model, "自我介绍"), sink);
        demo.ok("LlmStreamHandler.ofText(consumer)：收到 %d 字符", sink.length());

        reset();
        chat.ask("自我介绍");
        demo.ok("不传 handler 的调用：监听器 onTextDelta=%d 片（模型支持流式 → 一定触发）",
                monitor.count("onTextDelta"));

        reset();
        StringBuilder external = new StringBuilder();
        String text = chat.askWithHandler("介绍一下你自己", LlmStreamHandler.ofText(external::append));
        demo.ok("传了 handler 的调用：监听器 onTextDelta=%d 片，handler 收到 %d 字符，与返回值一致=%s",
                monitor.count("onTextDelta"), external.length(), text.equals(external.toString()));
    }

    // ------------------------------------------------------------------
    // ⑥ HTTP 端点全量
    // ------------------------------------------------------------------

    private void httpEndpoints(Demo demo) {
        demo.section("HTTP 端点全量 —— /agents | /loops | /models | /chat | /stream");
        if (http == null) {
            demo.warn("没有 HTTP 客户端，跳过");
            return;
        }

        List<?> agents = http.get().uri("/agents").retrieve().body(List.class);
        demo.ok("GET /llm/agents 返回 %d 个 Agent；chatAssistant 那一条:", agents.size());
        demo.info("   %s", agents.stream()
                .filter(a -> "chatAssistant".equals(String.valueOf(((Map<?, ?>) a).get("name"))))
                .findFirst().orElse(null));

        List<?> loops = http.get().uri("/loops").retrieve().body(List.class);
        demo.ok("GET /llm/loops 返回 %d 条，含自定义 Loop exp-observe=%s，含 workflow:<key>=%s",
                loops.size(),
                loops.stream().anyMatch(l -> String.valueOf(l).startsWith("exp-observe")),
                loops.stream().anyMatch(l -> String.valueOf(l).startsWith("workflow:")));

        Map<?, ?> models = http.get().uri("/models").retrieve().body(Map.class);
        demo.ok("GET /llm/models = %s", models);

        Map<?, ?> chatResponse = http.post().uri("/agents/chatAssistant/chat")
                .body(Map.of("message", "北京天气怎么样？"))
                .retrieve().body(Map.class);
        demo.ok("POST /llm/agents/chatAssistant/chat 的真实响应体:");
        demo.info("   %s", compact(chatResponse));

        String sse = sse("/agents/chatAssistant/stream", "北京天气怎么样？");
        demo.ok("GET /llm/agents/chatAssistant/stream 的 SSE 原始报文（前 10 行，已按 UTF-8 解码）:");
        sse.lines().filter(l -> !l.isBlank()).limit(10).forEach(line -> demo.info("   %s", oneLine(line)));
        demo.info("   出现的事件类型: %s", sseEventTypes(sse));
        demo.warn("这个端点的 Content-Type 是 text/event-stream（不带 charset）：Spring 客户端"
                + " body(String.class) 会按 ISO-8859-1 解出乱码，请按 SSE 规范显式用 UTF-8 解字节");
    }

    // ------------------------------------------------------------------
    // ⑦ 最容易猜错的契约
    // ------------------------------------------------------------------

    private void httpContracts(Demo demo) {
        demo.section("端点契约 —— 按直觉猜会猜错的那几条");
        if (http == null) {
            demo.warn("没有 HTTP 客户端，跳过");
            return;
        }

        var missing = http.post().uri("/agents/doesNotExist/chat")
                .body(Map.of("message", "你好"))
                .exchange((request, response) -> Map.entry(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        demo.ok("① 未知 Agent 名 → HTTP %d，响应体 = %s", missing.getKey(), oneLine(missing.getValue()));

        Map<?, ?> first = chatJson("不传 sessionId 的第一次");
        Map<?, ?> second = chatJson("不传 sessionId 的第二次");
        demo.ok("② 不传 sessionId → 每次独立：%s 与 %s（新生成的 http-<uuid>）",
                first.get("sessionId"), second.get("sessionId"));

        demo.ok("③ 不声明 tools / toolNames 的 Agent 继承全局工具表: %s",
                agentRegistry.get("chatAssistant").tools().names());

        model.reset();
        http.post().uri("/agents/chatAssistant/chat")
                .body(Map.of("message", "介绍一下你自己"))
                .retrieve().body(Map.class);
        ChatRequest request = model.lastRequest();
        String system = request == null ? "(未捕获)" : request.systemText();
        demo.ok("④ 方法级 @SystemPrompt 在 HTTP 端点上不生效：端点看到的系统提示词 = %s", oneLine(system));
        demo.info("   含【方法级提示词】吗？%s（false = 契约成立）", system.contains("【方法级提示词】"));

        reset();
        try {
            fail.ask("失败一次");
        } catch (RuntimeException expected) {
            demo.info("失败运行（预期抛异常）: %s", expected.getClass().getSimpleName());
        }
        demo.ok("⑤ 失败只广播 onError、不产生 agent_run：onError=%d，onAgentEnd=%d",
                monitor.count("onError"), monitor.count("onAgentEnd"));

        String sse = sse("/agents/chatAssistant/stream", "北京天气怎么样？");
        demo.ok("⑥ SSE 里没有 tool_result 事件，实际事件类型 = %s", sseEventTypes(sse));
        demo.info("   工具结果只出现在 done 事件的 toolCalls 里");
    }

    private Map<?, ?> chatJson(String message) {
        return http.post().uri("/agents/chatAssistant/chat")
                .body(Map.of("message", message))
                .retrieve().body(Map.class);
    }

    private String sse(String path, String message) {
        // 刻意按 byte[] 取回、再用 UTF-8 显式解码：SSE 规范规定报文就是 UTF-8，
        // 但本端点声明的 Content-Type 是 text/event-stream（@RequestMapping 上没带 charset），
        // Spring 的 StringHttpMessageConverter 对 text/* 默认按 ISO-8859-1 解 ——
        // 直接 body(String.class) 拿到的是乱码。这是客户端侧的解码选择，不是服务端发错了编码。
        byte[] raw = http.get().uri(uri -> uri.path(path).queryParam("message", message).build())
                .retrieve().body(byte[].class);
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void reset() {
        model.reset();
        commonTools.reset();
        failingProbeTool.reset();
        monitor.reset();
        activity.reset();
        sessionIds.clearAll(agentRegistry);
    }

    private void printEvents(Demo demo, int limit) {
        List<MonitorListener.Event> events = monitor.events();
        demo.info("事件序列（共 %d 条，显示前 %d 条）:", events.size(), Math.min(limit, events.size()));
        events.stream().limit(limit).forEach(e -> demo.info("   %s", e));
    }

    private static String sseEventTypes(String sse) {
        if (sse == null) {
            return "[]";
        }
        return sse.lines()
                .filter(l -> l.startsWith("event:"))
                .map(l -> l.substring("event:".length()).trim())
                .distinct()
                .toList()
                .toString();
    }

    private static String compact(Map<?, ?> body) {
        if (body == null) {
            return "(空响应体)";
        }
        StringBuilder sb = new StringBuilder("{");
        body.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(k).append('=').append(oneLine(String.valueOf(v)));
        });
        return sb.append('}').toString();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }
}
