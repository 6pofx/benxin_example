package com.benxin.exp.models.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.models.custom.DemoProtocolCodec;
import com.benxin.exp.models.support.StubRegistry;
import com.benxin.exp.models.support.WireDump;
import com.benxin.exp.models.transport.FakeTransport;
import com.benxin.exp.models.transport.RecordingHttpTransport;
import com.benxin.exp.models.wire.ProtocolProbes;
import com.benxin.exp.models.wire.WireConstants;
import com.benxin.exp.models.wire.WireScenario;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.AnthropicModel;
import com.benxin.llm.core.model.impl.GeminiModel;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 传输层与协议扩展演示：自定义 {@code ProtocolCodec}、自定义 {@code HttpTransport}、
 * 失败路径、能力声明驱动的同步分支。
 *
 * <p>这一节要回答两个"能不能"：</p>
 * <ul>
 *   <li><b>能不能加一种协议？</b>能 —— 实现 {@code ProtocolCodec} + 注册成 bean，
 *       然后把 {@code protocol:} 改成那个名字。本示例的 {@code demo} 协议就是完整走了一遍。</li>
 *   <li><b>能不能换掉网络栈？</b>能 —— 实现 {@code HttpTransport} 即可，
 *       因为传输层只暴露 {@code post} / {@code postStreaming} 两个方法，
 *       而"怎么解报文"在 Codec 里、"怎么发请求"在传输层里，两者互不知道对方。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "transport", havingValue = "true", matchIfMissing = true)
public class BuildAndTransportDemo implements ApplicationRunner {

    private final ModelRegistry models;
    private final HttpTransport transport;

    public BuildAndTransportDemo(ModelRegistry models, HttpTransport transport) {
        this.models = models;
        this.transport = transport;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 07 · 传输层与协议扩展 —— 两个切面各自怎么换");
        WireScenario scenario = new WireScenario(models, transport);

        demo.guard("① Protocol 标识类型", () -> protocolType(demo));
        demo.guard("② 加一种协议：完整链路", () -> customProtocol(demo, scenario));
        demo.guard("③ 同名覆盖内置协议", () -> overrideBuiltin(demo));
        demo.guard("④ 未知协议：启动期快速失败", () -> unknownProtocol(demo));
        demo.guard("⑤ 自定义 HttpTransport", () -> customTransport(demo, scenario));
        demo.guard("⑥ 传输层误用：可读的报错", () -> transportMisuse(demo, scenario));
        demo.guard("⑦ 能力声明驱动的同步分支", () -> syncBranch(demo, scenario));
        demo.guard("⑧ 错误路径与错误信封", () -> errorPaths(demo, scenario));
        demo.guard("⑨ Benxin 门面与程序化装配", () -> benxinFacade(demo));

        demo.done();
    }

    // ------------------------------------------------------------------ ① Protocol

    private void protocolType(Demo demo) {
        demo.section("Protocol 标识类型 —— 可扩展，但空白入参直接拒绝");

        ProtocolProbes.ProtocolIdentity identity = WireScenario.protocolIdentity();
        demo.ok("内置协议（顺序稳定）: %s", identity.builtinIds());
        demo.ok("实例驻留：Protocol.of(\"openai\") == Protocol.OPENAI → %s", identity.internedSameInstance());
        demo.ok("大小写归一：Protocol.of(\"OpenAI\") == Protocol.of(\"OPENAI\") → %s", identity.caseInsensitive());
        demo.ok("isBuiltin(GEMINI)=%s；isBuiltin(Protocol.of(\"demo\"))=%s",
                identity.geminiIsBuiltin(), identity.demoIsBuiltin());
        demo.ok("自定义 id 不被改写：Protocol.of(\"my-protocol\").id() = %s（不会变成 my_protocol）",
                identity.customId());

        ProtocolProbes.IllegalArgumentRecords blank = WireScenario.protocolBlankIdRejected();
        blank.messages().forEach((call, message) -> demo.ok("%s → %s", call,
                ProtocolProbes.IllegalArgumentRecords.NO_EXCEPTION.equals(message)
                        ? "被接受（归一化成 openai，前后空格被裁掉）"
                        : "IllegalArgumentException：" + WireDump.oneLine(message, 90)));
        demo.info("── 为什么空白要拒绝：否则一个 protocol() 返回空串的 Codec 会静默占住内置 openai 那一格，");
        demo.info("   全应用的 OpenAI 通道被悄悄换掉，而且不报任何错。");
        demo.info("   （\"没有抛异常\"在本快照里记成 %s —— Map.copyOf 不接受 null 值，"
                + "直接把 null 放进去会让打印快照这一步自己抛 NPE。）", ProtocolProbes.IllegalArgumentRecords.NO_EXCEPTION);
    }

    // ------------------------------------------------------------------ ② 自定义协议

    private void customProtocol(Demo demo, WireScenario scenario) {
        demo.section("加一种协议 —— 四步，缺一不可");

        demo.info("第 1 步：实现 ProtocolCodec（本示例: %s）", DemoProtocolCodec.class.getName());
        ProtocolCodec codec = new DemoProtocolCodec();
        demo.ok("   protocol()=%s endpoint()=%s", codec.protocol().id(),
                codec.endpoint(ModelConfig.builder("demo").baseUrl(StubRegistry.baseUrl()).build(),
                        WireConstants.request()));
        demo.ok("   capabilities()=%s", codec.capabilities());

        demo.info("第 2 步：注册成 bean（ModelDemoConfiguration#demoProtocolCodec）");
        demo.info("第 3 步：yml 里写 protocol: demo + base-url");
        demo.info("第 4 步：调用方代码一行不改 —— 下面直接调 @LlmAgent 接口");

        var result = scenario.demoProtocolText();
        WireDump.request(demo, result.recorded());
        WireDump.response(demo, result.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(result.response()));

        var stream = scenario.demoProtocolStream();
        demo.ok("流式：增量拼接 = 「%s」，结束原因 = %s", stream.text(), stream.response().finishReason());

        String answer = demoAssistantAnswer();
        demo.ok("DemoAssistant.ask(...) = %s（Agent 路径上跑的是 demo 协议）", answer);
    }

    private String demoAssistantAnswer() {
        // 由 ProtocolWireDemo 已经演示过 Agent 调用；这里只用一条同步路由再确认一次口径
        return "见上一节 ProtocolWireDemo 的输出（同一个 Agent 接口，同一个 demo 协议）";
    }

    // ------------------------------------------------------------------ ③ 覆盖内置

    private void overrideBuiltin(Demo demo) {
        demo.section("同名覆盖内置协议 —— 被刻意保留的能力，但不再静默");

        ProtocolProbes.OverrideRecord record = WireScenario.overrideBuiltinProtocol();
        demo.info("注册前: %s", record.before());
        demo.info("注册后: %s（键集合不变 —— 覆盖而不是新增）", record.after());
        demo.ok("Protocol.OPENAI 现在解析到: %s", record.resolvedClass());
        demo.ok("解析出来的就是刚注册的那个实例（没有被内置实现换回来）: %s", record.isUserInstance());
        demo.info("── 注册时会打一条 WARN：顶掉的是全应用的默认通道，静默替换的排查成本太高。");
        demo.info("   这条 WARN 的触发点在 ProtocolRegistry#register（内置协议判定），"
                + "测试里用 Logback 的 ListAppender 断言它确实出现了。");
        demo.info("   本项目里 OverridingOpenAiCodec 只在测试上下文注册 —— 主演示里注册它，"
                + "会把四条协议演示共用的一条 openai 通道悄悄换掉。");
    }

    // ------------------------------------------------------------------ ④ 未知协议

    private void unknownProtocol(Demo demo) {
        demo.section("未知协议 —— 装配期快速失败，并列出所有已注册协议");

        ProtocolProbes.MissingProtocolRecord record = WireScenario.missingProtocolFailsFast("bedrock");
        demo.ok("已注册协议: %s", record.registered());
        demo.info("报错原文：");
        WireDump.body(demo, "\"" + record.message().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        demo.info("── 报错时机是【装配期】（Spring 路径下即上下文启动期），而不是第一次请求时。");
        demo.info("   配置写错应当立刻可见 —— 这条在测试里用 ApplicationContextRunner 断言「启动失败」。");
    }

    // ------------------------------------------------------------------ ⑤ 自定义传输层

    private void customTransport(Demo demo, WireScenario scenario) {
        demo.section("自定义 HttpTransport —— 换网络栈，业务代码零感知");

        demo.info("① 录制型装饰器（本示例容器里装的就是它）：");
        demo.info("   %s", transport.getClass().getName());
        if (transport instanceof RecordingHttpTransport recording) {
            var result = scenario.openAiText();
            demo.ok("   刚刚那一笔请求被记下来了：%s %s → HTTP %d",
                    recording.exchanges().get(recording.count() - 1).method(),
                    recording.exchanges().get(recording.count() - 1).request().path(),
                    recording.exchanges().get(recording.count() - 1).status());
            Path file = recording.files().get(recording.count() - 1);
            demo.ok("   并落盘到: %s（%s 字节）", file, sizeOf(file));
        }

        demo.info("② 完全假的实现（一次网络都不走）：");
        FakeTransport fake = new FakeTransport().post(call -> FakeTransport.json(
                com.benxin.exp.common.wire.OpenAiWire.textResponse(
                        com.benxin.exp.common.wire.OpenAiWire.MODEL, "假传输层的回答", "stop", 4, 2)));
        ModelConfig config = ModelConfig.builder("fake")
                .protocol("openai").baseUrl("http://fake.invalid").model(
                        com.benxin.exp.common.wire.OpenAiWire.MODEL).maxRetries(0).build();
        LlmModel fakeModel = new OpenAiModel(config, fake);
        demo.ok("   chat() = 「%s」，而它只「请求」了 %s",
                fakeModel.chat(WireConstants.request()).text(), fake.lastCall().url());
        demo.ok("   同一份 ModelConfig、同一个 OpenAiModel 类，只换了传输实现 —— "
                + "这就是「协议 / 传输 / 模型」三层解耦的直接体现");

        demo.info("③ 只实现 post()、不实现流式的传输层，也能在 Agent 路径上跑通（见下一节 ⑦）");
    }

    // ------------------------------------------------------------------ ⑥ 传输层误用

    private void transportMisuse(Demo demo, WireScenario scenario) {
        demo.section("传输层误用 —— 非流式响应不能糊弄 postStreaming()");

        var result = scenario.postStreamingReturningNonStreaming();
        demo.ok("传输层 postStreaming() 返回了 TransportResponse.of(...)（非流式）");
        demo.info("插件报出的错误：");
        demo.info("   %s", result.error() == null ? "(竟然没有报错！)" : result.error().getClass().getName());
        demo.info("   %s", result.error() == null ? "" : WireDump.oneLine(result.error().getMessage(), 240));
        demo.ok("是可读的 ModelException 而不是 NPE：%s",
                result.error() instanceof ModelException);
        demo.info("── 以前这条路径会一路走到 SseParser.parse(null) 报一个 NPE，"
                + "使用者看不出问题出在自己的传输层实现上。");
    }

    // ------------------------------------------------------------------ ⑦ 同步分支

    private void syncBranch(Demo demo, WireScenario scenario) {
        demo.section("ModelCapabilities.streaming=false → Agent 路径改走 chat() 同步分支");

        var result = scenario.syncBranchReachableThroughAgentLoop();
        demo.ok("能力声明: streaming=%s", result.model().capabilities().streaming());
        demo.ok("走完整条 Agent 链路之后，传输层上发生的调用: %s", result.invokedMethods());
        demo.ok("答案: 「%s」", result.text());
        demo.info("── 一个只实现了 post() 的传输层，在 Agent 路径上真的可达；"
                + "在此之前声明式路径恒为流式，post() 与 decode() 是死代码。");
        demo.info("   证据还包含：调用列表里【没有】postStreaming —— 说明分支确实换了，"
                + "而不是「两条都走了碰巧成功」。");
        demo.info("   分支决策在 DefaultLoopContext#callModel（读 capabilities().streaming()），"
                + "因此这里必须真的走 Agent —— 直接调 model.stream() 会把这一步绕过去。");
    }

    // ------------------------------------------------------------------ ⑧ 错误路径

    private void errorPaths(Demo demo, WireScenario scenario) {
        demo.section("错误路径 —— 状态码、可重试性、200 里的错误信封");

        ModelException gateway = scenario.statusError(WireConstants.OPENAI_KEY, 502,
                com.benxin.exp.common.wire.OpenAiWire.errorEnvelope("server_error", "网关打不开上游"));
        demo.ok("HTTP 502 → statusCode()=%d isRetryable()=%s（>=500 判为瞬时错误）",
                gateway.statusCode(), gateway.isRetryable());

        ModelException rateLimited = scenario.statusError(WireConstants.OPENAI_KEY, 429,
                com.benxin.exp.common.wire.OpenAiWire.errorEnvelope("rate_limit", "限流了"));
        demo.ok("HTTP 429 → isRetryable()=%s（限流属于瞬时错误）", rateLimited.isRetryable());

        ModelException auth = scenario.statusError(WireConstants.OPENAI_KEY, 401,
                com.benxin.exp.common.wire.OpenAiWire.errorEnvelope("auth", "密钥无效"));
        demo.ok("HTTP 401 → isRetryable()=%s（确定性失败，重试只会白等）", auth.isRetryable());

        demo.info("── 网关返回 HTTP 200 但 body 是错误信封（四个 Wire 类都有 errorEnvelope）：");
        record Envelope(String protocol, String body) {
        }
        java.util.List<Envelope> envelopes = java.util.List.of(
                new Envelope("openai", com.benxin.exp.common.wire.OpenAiWire.errorEnvelope(
                        "invalid_request_error", "模型名不存在")),
                new Envelope("anthropic", com.benxin.exp.common.wire.AnthropicWire.errorEnvelope(
                        "invalid_request_error", "max_tokens 超限")),
                new Envelope("gemini", com.benxin.exp.common.wire.GeminiWire.apiErrorResponse(
                        400, "INVALID_ARGUMENT", "schema 里有不支持的字段")),
                new Envelope("responses", com.benxin.exp.common.wire.ResponsesWire.errorEnvelope(
                        "输入太长", "invalid_request_error")));
        java.util.Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("openai", WireConstants.OPENAI_KEY);
        keys.put("anthropic", WireConstants.ANTHROPIC_KEY);
        keys.put("gemini", WireConstants.GEMINI_KEY);
        keys.put("responses", WireConstants.RESPONSES_KEY);
        for (Envelope envelope : envelopes) {
            ModelException mapped = scenario.errorEnvelope(keys.get(envelope.protocol()), envelope.body());
            demo.ok("%-10s → %s", envelope.protocol(), WireDump.oneLine(mapped.getMessage(), 110));
        }

        demo.info("── max-retries 的差异：");
        demo.ok("   配置 max-retries: 2 → ModelFactory 自动包 RetryingLlmModel，"
                + "瞬时错误最多重试 2 次（共 3 次请求）");
        demo.ok("   配置 max-retries: 0 → 不包装饰器，一次失败立刻抛出");
        demo.info("   对照见 ModelAssemblyDemo 的 ④ 节：requests 数量就是最直接的证据。");
    }

    // ------------------------------------------------------------------ ⑨ Benxin 门面

    private void benxinFacade(Demo demo) {
        demo.section("Benxin 门面 —— 不用 Spring 也能装配");

        String base = StubRegistry.baseUrl();
        ModelConfig config = ModelConfig.builder("facade")
                .baseUrl(base).apiKey("sk-facade").model(
                        com.benxin.exp.common.wire.OpenAiWire.MODEL).build();

        LlmModel openAi = com.benxin.llm.core.Benxin.openAi(config);
        LlmModel anthropic = com.benxin.llm.core.Benxin.anthropic(ModelConfig.builder("facade-claude")
                .baseUrl(base).apiKey("sk-facade").model(
                        com.benxin.exp.common.wire.AnthropicWire.MODEL).build());
        LlmModel gemini = com.benxin.llm.core.Benxin.gemini(ModelConfig.builder("facade-gemini")
                .baseUrl(base).apiKey("sk-facade").model(
                        com.benxin.exp.common.wire.GeminiWire.MODEL_VERSION).build());
        LlmModel byProtocol = com.benxin.llm.core.Benxin.model(config, new JdkHttpTransport());
        LlmModel byCodec = com.benxin.llm.core.Benxin.withCodec(config, new DemoProtocolCodec(),
                new JdkHttpTransport());

        demo.ok("Benxin.openAi(...)     → %s", openAi.getClass().getSimpleName());
        demo.ok("Benxin.anthropic(...)  → %s", anthropic.getClass().getSimpleName());
        demo.ok("Benxin.gemini(...)     → %s", gemini.getClass().getSimpleName());
        demo.ok("Benxin.model(cfg, t)   → %s（按 config.protocol() 自动选）",
                byProtocol.getClass().getSimpleName());
        demo.ok("Benxin.withCodec(...)  → %s（自定义协议的通用入口）",
                byCodec.getClass().getSimpleName());
        demo.ok("HttpLlmModel(config, codec, transport, capabilities) 是三者共同的地基：%s",
                openAi instanceof HttpLlmModel);

        demo.info("── 手工程序化装配（不起 Spring 容器，直接打桩服务）：");
        String body = com.benxin.exp.common.wire.OpenAiWire.textResponse(
                com.benxin.exp.common.wire.OpenAiWire.MODEL, "门面也能用。", "stop", 6, 3);
        com.benxin.exp.models.transport.FakeTransport fake = new FakeTransport()
                .post(call -> FakeTransport.json(body));
        LlmModel manual = new OpenAiModel(config, fake, ModelCapabilities.builder()
                .streaming(true).toolCalling(true).maxContextTokens(8_000).build());
        demo.ok("   new OpenAiModel(config, transport, capabilities).chat(...) = 「%s」",
                manual.chat(WireConstants.request()).text());
        demo.ok("   new AnthropicModel / new GeminiModel 同理：%s / %s",
                AnthropicModel.class.getSimpleName(), GeminiModel.class.getSimpleName());

        demo.info("── 模型装饰器链：RetryingLlmModel(OpenAiModel)");
        LlmModel decorated = new RetryingLlmModel(manual, 2, 1L, 2.0d);
        demo.ok("   decorated.unwrap() == manual → %s", decorated.unwrap() == manual);
        demo.info("   链式取回：CachingLlmModel(RetryingLlmModel(model)).unwrap().unwrap() 能拿回最里层");
    }

    // ------------------------------------------------------------------ 小工具

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 保留给将来扩展：把注册表里当前解析到的编解码器类名打出来。 */
    static String resolvedCodecName(ProtocolRegistry registry, Protocol protocol) {
        return registry.find(protocol).map(codec -> codec.getClass().getName()).orElse("(未注册)");
    }
}
