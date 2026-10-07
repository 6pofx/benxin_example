package com.benxin.exp.override;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.llm.core.Benxin;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.protocol.openai.OpenAiCodec;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.transport.TransportResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>裸 core</b>：完全不依赖 Spring 的 {@link Benxin} 门面。
 *
 * <p>这个类里没有一个 Spring 注解、没有 {@code @SpringBootTest}、没有 {@code ApplicationContext} ——
 * 它就是一段普通的 JUnit 代码，跑在一个普通的 JVM 里。
 * 这就是「benxin-core 零 Spring 依赖」这个主张最直接的证据：
 * 如果 core 里漏进了一个 {@code @Component} 或一次 {@code ApplicationContext} 调用，
 * 这个类根本编译不过、也跑不起来。</p>
 *
 * <p>它同时把"一条真实的模型链路"跑通了 —— 不是打桩到接口层，而是：</p>
 * <pre>
 *   Benxin.openAi(config, transport)
 *        ↓ 编解码
 *   HttpLlmModel → HttpTransport(JdkHttpTransport) → 真实 TCP
 *        ↓
 *   StubHttpServer（回环地址上的真实 HTTP 端点）
 *        ↓ 按 OpenAI 协议回帧
 *   ChatResponse → AgentLoop → AgentResult
 * </pre>
 *
 * <p>离线、零 token，但走的是真的 socket、真的协议报文。</p>
 */
@DisplayName("示例 09 · 裸 core 门面（完全不依赖 Spring）")
class CoreFacadeTest {

    private static ModelConfig config(String baseUrl) {
        return ModelConfig.builder("stub-endpoint")
                .protocol("openai")
                .baseUrl(baseUrl)
                .apiKey("sk-exp09")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .temperature(0.3)
                .maxTokens(64)
                .build();
    }

    // ==================================================================
    // 装配入口：Benxin.*
    // ==================================================================

    @Test
    @DisplayName("Benxin.transport() / loops() / emptyLoops() / models() / tools(...)")
    void facadeAssemblyEntryPoints() {
        // 默认传输层：JDK HttpClient，零第三方依赖。
        assertThat(Benxin.transport()).isInstanceOf(JdkHttpTransport.class);

        // 装好内置 Loop 的注册表。
        LoopRegistry loops = Benxin.loops();
        assertThat(loops.names()).contains("dsh-minimal", "react", "claude-code", "codex",
                "plan-execute", "reflexion", "workflow", "staged");
        assertThat(loops.defaultName()).isEqualTo("dsh-minimal");

        // 全新的空注册表：一个都没有 —— "不想要内置的那一套"时从它开始。
        LoopRegistry empty = Benxin.emptyLoops();
        assertThat(empty.isEmpty()).isTrue();

        // 空模型表：等着你往里 register。
        ModelRegistry models = Benxin.models();
        assertThat(models.isEmpty()).isTrue();
        models.register(Benxin.openAi(config("http://127.0.0.1:1")));
        assertThat(models.names()).containsExactly("stub-endpoint");

        // 扫描普通对象上的 @LlmTool 方法，组成工具集。
        ToolRegistry tools = Benxin.tools(new CommonTools());
        assertThat(tools.names())
                .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate", "describe_context");
        // 与 ToolScanner.scanAll(...) 是同一件事（Benxin.tools 就是它的薄封装）。
        assertThat(ToolScanner.scanAll(new CommonTools())).hasSize(4);
    }

    @Test
    @DisplayName("Benxin.agent(name) 已经预装好 LoopRegistry 与 ModelRegistry —— 只要再给模型就能跑")
    void agentFactoryIsPreconfigured() {
        // Benxin.agent(String) 内部就是 Agent.builder(name).loopRegistry(loops()).modelRegistry(models())
        Agent agent = Benxin.agent("bare-agent")
                .model(Benxin.openAi(config("http://127.0.0.1:1")))
                .tools(new CommonTools())
                .build();

        assertThat(agent.name()).isEqualTo("bare-agent");
        assertThat(agent.loop().name()).isEqualTo("dsh-minimal");
        assertThat(agent.model().name()).isEqualTo("stub-endpoint");
        assertThat(agent.tools().names()).contains("get_weather", "calculate");
        assertThat(agent.spec().maxSteps()).isEqualTo(24);   // AgentSpec.Builder 的默认值
    }

    // ==================================================================
    // 模型装配：三种协议 + withCodec + ModelFactory
    // ==================================================================

    @Test
    @DisplayName("Benxin.openAi / anthropic / gemini：三个协议各自一个入口，且都能带自定义 transport")
    void protocolSpecificFactories() {
        HttpTransport transport = Benxin.transport();
        ModelConfig openAi = config("https://api.example.invalid/v1");

        assertThat(Benxin.openAi(openAi)).isInstanceOf(LlmModel.class);
        assertThat(Benxin.openAi(openAi, transport)).isInstanceOf(LlmModel.class);
        assertThat(Benxin.anthropic(openAi)).isInstanceOf(LlmModel.class);
        assertThat(Benxin.anthropic(openAi, transport)).isInstanceOf(LlmModel.class);
        assertThat(Benxin.gemini(openAi)).isInstanceOf(LlmModel.class);
        assertThat(Benxin.gemini(openAi, transport)).isInstanceOf(LlmModel.class);

        // 三种协议之间真正的差别在 Codec 上，而不在模型类上 ——
        // 它们的实现都是同一个 HttpLlmModel。
        assertThat(((HttpLlmModel) Benxin.openAi(openAi, transport)).codec().protocol().id())
                .isEqualTo("openai");
        assertThat(((HttpLlmModel) Benxin.anthropic(openAi, transport)).codec().protocol().id())
                .isEqualTo("anthropic");
        assertThat(((HttpLlmModel) Benxin.gemini(openAi, transport)).codec().protocol().id())
                .isEqualTo("gemini");
    }

    @Test
    @DisplayName("Benxin.model(config, transport) 按 config.protocol() 自动选协议 —— 内部没有任何 switch")
    void modelByProtocol() {
        // 注意 maxRetries 的默认值是 2（ModelConfig.Builder 的默认值），而 ModelFactory.create
        // 在 maxRetries > 0 时会自动套一层 RetryingLlmModel。所以"拿到的到底是什么类型"
        // 取决于配置里那个数字 —— 想直接看到 HttpLlmModel，就得把重试关掉。
        ModelConfig noRetry = ModelConfig.builder("stub-endpoint")
                .protocol("openai")
                .baseUrl("https://x.invalid")
                .apiKey("sk-exp09")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .build();
        assertThat(Benxin.model(noRetry, Benxin.transport())).isInstanceOf(HttpLlmModel.class);
        assertThat(((HttpLlmModel) Benxin.model(noRetry, Benxin.transport()))
                .codec().protocol().id()).isEqualTo("openai");

        // 默认配置（maxRetries = 2）拿到的其实是重试装饰器 —— 装饰器照样能被当模型用，
        // 想看里面的编解码器要先 unwrap()，别直接强转。
        LlmModel retried = Benxin.model(ModelConfig.builder("a")
                .protocol("anthropic").baseUrl("https://x.invalid").build(), Benxin.transport());
        assertThat(retried).isNotInstanceOf(HttpLlmModel.class);
        assertThat(((HttpLlmModel) retried.unwrap()).codec().protocol().id()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("Benxin.withCodec(config, codec, transport)：自定义协议时的通用入口")
    void withCustomCodec() {
        ProtocolCodec codec = new OpenAiCodec();
        LlmModel model = Benxin.withCodec(config("https://x.invalid"), codec, Benxin.transport());

        assertThat(model).isInstanceOf(HttpLlmModel.class);
        assertThat(((HttpLlmModel) model).codec()).isSameAs(codec);
    }

    @Test
    @DisplayName("ModelFactory：create / createAll / registry 三个静态入口")
    void modelFactoryStatics() {
        HttpTransport transport = Benxin.transport();

        // create：单个装配，maxRetries > 0 时自动套一层重试装饰器。
        LlmModel withRetry = ModelFactory.create(ModelConfig.builder("r")
                .baseUrl("https://x.invalid").model("m").maxRetries(2).build(), transport);
        assertThat(withRetry).isNotInstanceOf(HttpLlmModel.class);
        assertThat(withRetry.unwrap()).isInstanceOf(HttpLlmModel.class);

        LlmModel noRetry = ModelFactory.create(ModelConfig.builder("n")
                .baseUrl("https://x.invalid").model("m").maxRetries(0).build(), transport);
        assertThat(noRetry).isInstanceOf(HttpLlmModel.class);

        // createAll：批量装配，保留插入顺序（"默认模型取第一个"的语义依赖它）。
        Map<String, LlmModel> all = ModelFactory.createAll(Map.of(
                "one", ModelConfig.builder("one").baseUrl("https://x.invalid").model("m").maxRetries(0).build(),
                "two", ModelConfig.builder("two").baseUrl("https://x.invalid").model("m").maxRetries(0).build()),
                transport);
        assertThat(all.keySet()).containsExactlyInAnyOrder("one", "two");

        // registry：装配 + 注册 + 定默认，一步到位（Spring 侧 llmModelRegistry 用的就是它）。
        // 这里必须用 LinkedHashMap 而不是 Map.of(...)：ModelRegistry 的"注册顺序即回退顺序"
        // 依赖插入顺序，而 Map.of 的迭代顺序是未指定的（同一份代码在不同 JVM 上可能给出不同顺序），
        // 用它去断言 names() 的顺序等于给自己埋一颗随机失败的雷。
        Map<String, ModelConfig> ordered = new java.util.LinkedHashMap<>();
        ordered.put("first", ModelConfig.builder("first").baseUrl("https://x.invalid").model("m").maxRetries(0).build());
        ordered.put("second", ModelConfig.builder("second").baseUrl("https://x.invalid").model("m").maxRetries(0).build());
        ModelRegistry registry = ModelFactory.registry(ordered, transport, "second");
        assertThat(registry.names()).containsExactly("first", "second");
        assertThat(registry.defaultName()).isEqualTo("second");

        // 默认注册表 = 内置四协议，且不可被外部修改。
        ProtocolRegistry protocols = ModelFactory.defaultRegistry();
        assertThat(protocols.ids()).contains("openai", "anthropic", "gemini", "responses");
        assertThat(Protocol.builtinIds()).containsExactlyInAnyOrderElementsOf(protocols.ids());
    }

    // ==================================================================
    // 真 · 一次调用：走真实 HTTP
    // ==================================================================

    @Test
    @DisplayName("一次真正的 Agent.call(...)：真 socket、真协议报文、离线零 token")
    void realCallThroughRealHttp() throws Exception {
        try (StubHttpServer server = StubHttpServer.start()) {
            // ⚠️ 这里必须回<b>流式帧</b>，不能回一个普通 JSON 响应。
            // 原因：OpenAiCodec 声明的能力里 streaming = true，而 DefaultLoopContext.callModel
            // 是<b>看着 capabilities().streaming() 决定走哪条通道</b>的
            // （DefaultLoopContext.java:262-268），跟 AgentSpec.stream() 无关。
            // 回一个 application/json 的正文，SSE 解析器只会看到一条非 data 帧并逐帧跳过
            // （这是它刻意的容错设计），结果是"HTTP 200 但回答为空"。
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.sseFrames(
                    OpenAiWire.streamingText(OpenAiWire.MODEL, 42, 7,
                            "（桩服务）离线回答：我是一次", "真实 HTTP 往返", "的结果。")));

            LlmModel model = Benxin.openAi(config(server.baseUrl()), Benxin.transport());
            Agent agent = Benxin.agent("bare-core-agent")
                    .model(model)
                    .systemPrompt("你是裸 core 演示。")
                    .build();

            AgentResult result = agent.call("你好");

            assertThat(result.text()).contains("真实 HTTP 往返");
            assertThat(result.modelName()).isEqualTo("stub-endpoint");
            assertThat(result.loopName()).isEqualTo("dsh-minimal");
            assertThat(result.steps()).isEqualTo(1);
            assertThat(result.usage().totalTokens()).isEqualTo(49);   // OpenAiWire 默认 42 + 7

            // 服务端侧的证据：请求真的到了，URL / 认证头 / 报文形状都对。
            assertThat(server.requestCount()).isEqualTo(1);
            StubHttpServer.Recorded recorded = server.lastRequest().orElseThrow();
            assertThat(recorded.method()).isEqualTo("POST");
            assertThat(recorded.path()).isEqualTo("/v1/chat/completions");
            assertThat(recorded.header("Authorization")).isEqualTo("Bearer sk-exp09");
            assertThat(recorded.json().path("model").asText()).isEqualTo(OpenAiWire.MODEL);
            assertThat(recorded.json().path("temperature").asDouble()).isEqualTo(0.3);
            assertThat(recorded.json().path("max_tokens").asInt()).isEqualTo(64);
            // 系统提示词按 OpenAI 协议放在 messages 的第一条（而不是顶层字段）。
            assertThat(recorded.json().path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(recorded.json().path("messages").get(0).path("content").asText())
                    .contains("裸 core 演示");
            // 走的是流式通道，所以网关侧看到的是 stream=true + Accept: text/event-stream。
            assertThat(recorded.json().path("stream").asBoolean()).isTrue();
            assertThat(recorded.header("Accept")).isEqualTo("text/event-stream");
        }
    }

    @Test
    @DisplayName("流式也是一样的：真 SSE 分帧 → StreamCollector → 拼回完整文本")
    void realStreaming() {
        try (StubHttpServer server = StubHttpServer.start()) {
            // 必须用 sseFrames 而不是 sseData：
            // OpenAiWire.streamingText(...) 返回的<b>已经是</b>一条条完整的 SSE 帧（自带 "data: " 与结尾空行），
            // 而 sseData 会再给每个元素套一层 "data: " 前缀 —— 于是整条流被塞进<b>一个</b> SSE 事件里，
            // 解码器解析那坨拼接文本失败后逐帧跳过（这是它的容错设计），最后一个增量都收不到。
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.sseFrames(
                    OpenAiWire.streamingText(OpenAiWire.MODEL, 12, 5, "流式", "也是", "真的")));

            LlmModel model = Benxin.openAi(config(server.baseUrl()), Benxin.transport());
            ChatRequest request = ChatRequest.builder()
                    .message(com.benxin.llm.core.message.ChatMessage.user("你好"))
                    .stream(true)
                    .build();

            // StreamCollector 既是 LlmStreamHandler（把增量透传给下游），
            // 又能把整条流重新聚合成一个 ChatResponse —— Agent Loop 用的就是它。
            StringBuilder deltas = new StringBuilder();
            StreamCollector collector = new StreamCollector(
                    com.benxin.llm.core.model.LlmStreamHandler.ofText(deltas::append));
            model.stream(request, collector);

            assertThat(collector.error()).isNull();
            assertThat(collector.isStarted()).isTrue();
            assertThat(deltas.toString()).isEqualTo("流式也是真的");
            assertThat(collector.text()).isEqualTo("流式也是真的");
            assertThat(collector.response().usage().outputTokens()).isEqualTo(5);
            assertThat(collector.response().usage().inputTokens()).isEqualTo(12);
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.lastRequest().orElseThrow().header("Accept")).isEqualTo("text/event-stream");
        }
    }

    @Test
    @DisplayName("自己写一个 HttpTransport 也能接进来 —— 这就是「换网络栈」的接口面")
    void customTransportWithoutSpring() {
        HttpTransport recording = new HttpTransport() {
            @Override
            public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
                return TransportResponse.of(200, Map.of(),
                        OpenAiWire.textResponse("（本地假传输层）没走网络。"));
            }

            @Override
            public TransportResponse postStreaming(String url, Map<String, String> headers, String body,
                                                   Duration timeout) {
                return TransportResponse.streaming(200, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
        };

        LlmModel model = Benxin.openAi(config("http://127.0.0.1:1"), recording);
        ChatResponse response = model.chat(ChatRequest.builder()
                .message(com.benxin.llm.core.message.ChatMessage.user("你好")).build());

        assertThat(response.text()).contains("没走网络");
    }

    @Test
    @DisplayName("ToolScanner：scan / scanAll / hasTools 三个入口")
    void toolScannerEntryPoints() {
        CommonTools tools = new CommonTools();

        assertThat(ToolScanner.hasTools(CommonTools.class)).isTrue();
        assertThat(ToolScanner.hasTools(String.class)).isFalse();
        assertThat(ToolScanner.scan(tools)).hasSize(4);
        assertThat(ToolScanner.scanAll(tools, "一个没有工具的普通对象")).hasSize(4);
        assertThat(ToolScanner.scan(null)).isEmpty();
    }

    @Test
    @DisplayName("模型解析失败在 build() 就报错，而不是等到第一次调用 —— 报错里带「怎么办」")
    void emptyModelRegistryFailsReadably() {
        // DefaultAgent 的构造器里就做模型解析：先按名字取，取不到再退到"默认模型"，
        // 两条都不通时立刻抛 ModelException（DefaultAgent.java:68-74），
        // 所以这个错误在 build() 那一刻就冒出来了 —— 不会留下一个"一调用就炸"的 Agent。
        assertThatThrownBy(() -> Benxin.agent("no-model-agent").model("不存在的模型").build())
                .isInstanceOf(com.benxin.llm.core.model.ModelException.class)
                .hasMessageContaining("不存在的模型")
                // 报错里带上"已注册了什么"，便于一眼看出是配置没生效还是名字写错了。
                .hasMessageContaining("已注册");

        // 空注册表 + 不给名字：这时连"默认模型"也解析不出来，报的是另一句话 ——
        // 它直接告诉你下一步该做什么（配 llm.models.* 或给一个 LlmModel bean）。
        assertThatThrownBy(() -> Benxin.agent("bare-agent").build())
                .isInstanceOf(com.benxin.llm.core.model.ModelException.class)
                .hasMessageContaining("尚未注册任何模型")
                .hasMessageContaining("llm.models.*");
    }

    @Test
    @DisplayName("Benxin.VERSION 是一个可读的版本号（与 Maven 版本号同源，别让示例里那句悄悄过期）")
    void versionConstant() {
        assertThat(Benxin.VERSION).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("一个细节：Benxin.loops() 每次都给新注册表 —— 你不会污染别人的内置 Loop 表")
    void loopsAreNotASharedSingleton() {
        LoopRegistry first = Benxin.loops();
        LoopRegistry second = Benxin.loops();
        assertThat(first).isNotSameAs(second);

        first.register("only-in-first", first.get("dsh-minimal"));
        assertThat(first.contains("only-in-first")).isTrue();
        assertThat(second.contains("only-in-first")).isFalse();
    }

    @Test
    @DisplayName("AgentLoop 是接口：工具循环在 core 里是可替换的，不是写死的")
    void loopIsReplaceable() {
        AgentLoop minimal = Benxin.loops().get("dsh-minimal");
        assertThat(minimal.name()).isEqualTo("dsh-minimal");
        assertThat(minimal.description()).isNotBlank();
        assertThat(Benxin.loops().find("不存在的 Loop")).isEmpty();

        List<String> names = List.copyOf(Benxin.loops().names());
        assertThat(names).hasSize(8);
    }
}
