package com.benxin.exp.models;

import com.benxin.exp.common.wire.AnthropicWire;
import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.exp.models.agent.DemoAssistant;
import com.benxin.exp.models.config.BaseUrlSupplier;
import com.benxin.exp.models.config.ModelSettings;
import com.benxin.exp.models.custom.DemoProtocolCodec;
import com.benxin.exp.models.custom.DemoWire;
import com.benxin.exp.models.custom.OverridingOpenAiCodec;
import com.benxin.exp.models.support.StubRegistry;
import com.benxin.exp.models.wire.WireConstants;
import com.benxin.exp.models.wire.WireScenario;
import com.benxin.llm.core.Benxin;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.spring.LlmAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型装配测试：{@code ModelConfig} 全字段、{@code ModelFactory}、{@code ModelRegistry}、
 * 模型来源三选一、协议扩展的 Spring 接线。
 *
 * <p>与 {@code ModelAssemblyDemo} 共用同一份配置与同一段编排，因此演示里打印的每条结论
 * 都能在这里被断言住。全部离线：所有 base-url 都指向只监听回环地址的随机端口。</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("示例 07 · 模型装配与注册表")
class ModelAssemblyTest {

    /**
     * 把桩服务的地址写进系统属性 —— 必须发生在 Spring 绑定配置<b>之前</b>。
     *
     * <p>时序与 {@code ModelsProtocolsApplication#main} 完全一致：yml 里的
     * {@code ${exp.stub.base-url}} 是在上下文 refresh 时解析的，而端口只有桩服务起来才知道。
     * 放成静态初始化块（而不是 {@code @BeforeAll}）是刻意的：类初始化一定早于
     * Spring TestContext 创建上下文，否则占位符会以原文 {@code ${exp.stub.base-url}}
     * 落进配置里，URL 拼出来直接报 "Illegal character in path"。</p>
     */
    static {
        StubRegistry.stub();
    }

    @Autowired
    private ModelRegistry models;

    @Autowired
    private HttpTransport transport;

    @Autowired
    private BaseUrlSupplier baseUrlSupplier;

    @Autowired
    private ModelSettings settings;

    @Autowired
    private ProtocolRegistry protocolRegistry;

    @Autowired
    private DemoAssistant demoAssistant;

    /**
     * 容器里的 {@code LlmModel} bean。
     *
     * <p>必须按<b>bean 名字</b>注入：容器里的 {@code LlmModel} 类型有 8 个候选
     * （7 条 yml 配置 + 1 个 bean），按类型注入会直接 {@code NoUniqueBeanDefinitionException}。
     * 这也是 {@code ModelRegistry} 存在的理由 —— 上层要的是"按名字取模型"。</p>
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("openaiDemoModel")
    private LlmModel openaiDemoModelBean;

    // ==================================================================
    // 一、ModelConfig 全字段
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("ModelConfig：base-url 末尾斜杠在构造时就被裁掉")
    void baseUrlTrailingSlashesAreTrimmed() {
        ModelConfig config = ModelConfig.builder("x").baseUrl("http://h/v1///").build();

        // 不裁的话会拼出 http://h/v1///v1/chat/completions 这种路径
        assertThat(config.baseUrl()).isEqualTo("http://h/v1");
        assertThat(ModelConfig.builder("y").baseUrl("  http://h/  ").build().baseUrl())
                .isEqualTo("http://h");
        assertThat(ModelConfig.builder("z").baseUrl(null).build().baseUrl()).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("ModelConfig：max-retries 默认是 2（不是 0），负数按 0 处理")
    void maxRetriesDefaultsToTwo() {
        // 这条口径最容易猜错：想"只发一笔报文、数请求数"必须显式写 max-retries: 0，
        // 否则一次瞬时错误会变成 3 次请求。
        assertThat(ModelConfig.builder("a").build().maxRetries()).isEqualTo(2);
        assertThat(ModelConfig.builder("a").maxRetries(0).build().maxRetries()).isZero();
        assertThat(ModelConfig.builder("a").maxRetries(-5).build().maxRetries()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("ModelConfig：hasApiKey / resolveModel(ChatRequest) 的口径")
    void hasApiKeyAndResolveModel() {
        ModelConfig config = ModelConfig.builder("cfg")
                .model("stub-gpt")
                .apiKey("sk-x")
                .build();

        assertThat(config.hasApiKey()).isTrue();
        assertThat(ModelConfig.builder("no-key").baseUrl("http://h").build().hasApiKey()).isFalse();
        assertThat(ModelConfig.builder("blank").apiKey("   ").build().hasApiKey()).isFalse();

        // 请求里显式指定则优先，否则用配置里的
        assertThat(config.resolveModel(WireConstants.request())).isEqualTo("stub-gpt");
        assertThat(config.resolveModel(com.benxin.llm.core.chat.ChatRequest.builder()
                .model("deepseek-chat").build())).isEqualTo("deepseek-chat");
        assertThat(config.resolveModel(null)).isEqualTo("stub-gpt");
        assertThat(config.resolveModel(com.benxin.llm.core.chat.ChatRequest.builder().build()))
                .isEqualTo("stub-gpt");
    }

    @Test
    @Order(4)
    @DisplayName("ModelConfig：超时字段缺省有兜底值，不过期地返回 null")
    void timeoutsHaveDefaults() {
        ModelConfig config = ModelConfig.builder("t").baseUrl("http://h").build();

        assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.readTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.streamReadTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.protocol()).isEqualTo(Protocol.OPENAI);
    }

    @Test
    @Order(5)
    @DisplayName("exp.model.* 全字段绑定：yml 与 java 两条通道落到同一个 ModelConfig")
    void settingsSampleIsBoundCompletely() {
        ModelConfig fromSettings = baseUrlSupplier.toModelConfig(settings);

        assertThat(fromSettings.name()).isEqualTo("settings-sample");
        assertThat(fromSettings.protocol()).isEqualTo(Protocol.OPENAI);
        // base-url 用的是 ${exp.stub.base-url}，解析结果必须就是桩服务地址
        assertThat(fromSettings.baseUrl()).isEqualTo(StubRegistry.baseUrl());
        // api-key 写成 "Bearer sk-settings"：原样保留，由 Codec 决定怎么用
        assertThat(fromSettings.apiKey()).isEqualTo("Bearer sk-settings");
        assertThat(fromSettings.model()).isEqualTo("stub-gpt");
        assertThat(fromSettings.headers())
                .containsEntry("X-Trace-Id", "exp-07")
                .containsEntry("Authorization", "Bearer sk-overridden");
        assertThat(fromSettings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(fromSettings.readTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(fromSettings.streamReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(fromSettings.maxRetries()).isZero();
        assertThat(fromSettings.temperature()).isEqualTo(0.15d);
        assertThat(fromSettings.maxTokens()).isEqualTo(128);
        assertThat(fromSettings.extra())
                .containsEntry("top_k", 5)
                .containsEntry("vendor_flag", "exp-07");
    }

    @Test
    @Order(6)
    @DisplayName("api-key 容忍 \"Bearer xxx\" 写法：不会拼成 Bearer Bearer；自定义头可覆盖认证头")
    void apiKeyAndHeaderHandlingOnTheWire() {
        WireScenario scenario = new WireScenario(models, transport);
        WireScenario.WireResult wire = roundTrip(scenario, baseUrlSupplier.toModelConfig(settings));

        // 配置里 api-key 已经是 "Bearer sk-settings"，自定义头 Authorization 又覆盖了它
        assertThat(wire.header("Authorization")).isEqualTo("Bearer sk-overridden");
        assertThat(wire.header("X-Trace-Id")).isEqualTo("exp-07");
        // extra 合并进请求体顶层
        assertThat(wire.requestBody()).contains("\"top_k\":5");
        assertThat(wire.requestBody()).contains("\"vendor_flag\":\"exp-07\"");
        // 请求里的 temperature / max_tokens 优先于配置（请求 0.3/256，配置 0.15/128）
        assertThat(wire.recorded().json().path("temperature").asDouble()).isEqualTo(0.3d);
        assertThat(wire.recorded().json().path("max_tokens").asInt()).isEqualTo(256);
        // model 取自配置（请求里没写 model）
        assertThat(wire.recorded().json().path("model").asText()).isEqualTo("stub-gpt");
        // 桩服务确实只收到这一笔请求
        assertThat(wire.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("请求里没写 temperature/maxTokens 时，配置值被合并进请求")
    void configDefaultsAreMergedIntoRequest() {
        WireScenario scenario = new WireScenario(models, transport);
        scenario.stub().reset();
        String body = OpenAiWire.textResponse("stub-gpt", "配置兜底生效。", "stop", 2, 1);
        scenario.stub().onPath("/v1/chat/completions", request -> com.benxin.exp.common.wire.StubHttpServer.Reply.json(body));

        ModelConfig config = ModelConfig.builder("merged")
                .protocol(Protocol.OPENAI)
                .baseUrl(StubRegistry.baseUrl())
                .apiKey("sk-merged")
                .model("stub-gpt")
                .temperature(0.75d)
                .maxTokens(64)
                .extra(Map.of("from_config", true))
                .maxRetries(0)
                .build();
        LlmModel model = ModelFactory.create(config, transport);
        // 请求里什么都不给：temperature / maxTokens 都应为 null
        model.chat(com.benxin.llm.core.chat.ChatRequest.builder()
                .message(com.benxin.llm.core.message.ChatMessage.user("hi"))
                .build());

        var sent = scenario.stub().lastRequest().orElseThrow().json();
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.75d);
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(64);
        // 配置级 extra 作为底、请求级 extra 覆盖之
        assertThat(sent.path("from_config").asBoolean()).isTrue();
    }

    // ==================================================================
    // 二、ModelFactory
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("ModelFactory.create：max-retries=0 给裸模型，>0 自动包一层 RetryingLlmModel")
    void factoryWrapsRetryingDecoratorOnlyWhenMaxRetriesPositive() {
        ModelConfig plain = ModelConfig.builder("plain")
                .protocol(Protocol.OPENAI).baseUrl(StubRegistry.baseUrl()).model("stub-gpt")
                .maxRetries(0).build();
        ModelConfig retrying = plain.toBuilder().maxRetries(2).build();

        assertThat(ModelFactory.create(plain, transport)).isInstanceOf(HttpLlmModel.class);
        assertThat(ModelFactory.create(retrying, transport)).isInstanceOf(RetryingLlmModel.class);
        // 装饰器透传名字与能力声明 —— 重试不会让模型多出视觉能力
        LlmModel wrapped = ModelFactory.create(retrying, transport);
        assertThat(wrapped.name()).isEqualTo("plain");
        assertThat(wrapped.capabilities()).isEqualTo(ModelFactory.create(plain, transport).capabilities());
    }

    @Test
    @Order(11)
    @DisplayName("ModelFactory.createAll：保留配置顺序，键取配置里的名字")
    void factoryCreateAllKeepsOrder() {
        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        configs.put("first", ModelConfig.builder("first").protocol(Protocol.OPENAI)
                .baseUrl(StubRegistry.baseUrl()).model("stub-gpt").maxRetries(0).build());
        configs.put("second", ModelConfig.builder("second").protocol(Protocol.GEMINI)
                .baseUrl(StubRegistry.baseUrl()).model("gemini-stub-001").maxRetries(2).build());

        Map<String, LlmModel> created = ModelFactory.createAll(configs, transport);

        assertThat(created.keySet()).containsExactly("first", "second");
        assertThat(created.get("first")).isInstanceOf(HttpLlmModel.class);
        assertThat(created.get("second")).isInstanceOf(RetryingLlmModel.class);
        // 顺序即"回退顺序"，不能被 HashMap 打乱
        assertThat(created.keySet().iterator().next()).isEqualTo("first");
        assertThat(ModelFactory.createAll(null, transport)).isEmpty();
        assertThat(ModelFactory.createAll(Map.of(), transport)).isEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("ModelFactory.createAll：某个配置为 null 时直接失败，而不是静默跳过")
    void factoryCreateAllRejectsNullConfig() {
        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        configs.put("good", ModelConfig.builder("good").protocol(Protocol.OPENAI)
                .baseUrl(StubRegistry.baseUrl()).model("stub-gpt").maxRetries(0).build());
        configs.put("broken", null);

        assertThatThrownBy(() -> ModelFactory.createAll(configs, transport))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("broken");
    }

    @Test
    @Order(13)
    @DisplayName("ModelFactory.registry：配置的默认名取不到时回退到注册顺序第一个")
    void factoryRegistryFallsBackToFirstRegisteredModel() {
        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        configs.put("first", ModelConfig.builder("first").protocol(Protocol.OPENAI)
                .baseUrl(StubRegistry.baseUrl()).model("stub-gpt").maxRetries(0).build());
        configs.put("second", ModelConfig.builder("second").protocol(Protocol.OPENAI)
                .baseUrl(StubRegistry.baseUrl()).model("stub-gpt").maxRetries(0).build());

        ModelRegistry registry = ModelFactory.registry(configs, transport, "不存在的名字");

        assertThat(registry.names()).containsExactly("first", "second");
        // 两条口径必须分开：一个回退、一个保留原样
        assertThat(registry.defaultName()).isEqualTo("first");
        assertThat(registry.configuredDefaultName()).isEqualTo("不存在的名字");
        assertThat(registry.defaultModel().name()).isEqualTo("first");

        // 默认名为空 / 空白时同样取第一个
        assertThat(ModelFactory.registry(configs, transport, null).defaultName()).isEqualTo("first");
        assertThat(ModelFactory.registry(configs, transport, "  ").defaultName()).isEqualTo("first");
        assertThat(ModelFactory.registry(configs, transport, "second").defaultName()).isEqualTo("second");
    }

    @Test
    @Order(14)
    @DisplayName("ModelFactory.defaultRegistry()：裸 core 场景只有内置四协议，且不改全局")
    void factoryDefaultRegistryHasBuiltinsOnly() {
        ProtocolRegistry registry = ModelFactory.defaultRegistry();

        assertThat(registry.ids()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(registry.find(Protocol.of("demo"))).isEmpty();
        // 再取一次仍是同一份，说明它没有被上一步的"自定义协议演示"污染
        assertThat(ModelFactory.defaultRegistry().ids()).containsExactly(
                "openai", "anthropic", "gemini", "responses");
    }

    // ==================================================================
    // 三、ModelRegistry
    // ==================================================================

    @Test
    @Order(20)
    @DisplayName("ModelRegistry：register / registerAll / find / get / names / all")
    void registryBasics() {
        ModelRegistry registry = new ModelRegistry();
        LlmModel first = fakeModel("first");
        LlmModel second = fakeModel("second");

        registry.register(first);
        registry.registerAll(Map.of("third", second));

        assertThat(registry.names()).containsExactly("first", "third");
        assertThat(registry.all()).containsExactly(first, second);
        assertThat(registry.find("first")).contains(first);
        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.get("third")).isSameAs(second);
        assertThat(registry.isEmpty()).isFalse();
        // 空白名字与 null 模型被忽略，而不是注册出一个取不到的名字
        registry.register("  ", fakeModel("blank"));
        registry.register("null-model", null);
        assertThat(registry.names()).containsExactly("first", "third");
    }

    @Test
    @Order(21)
    @DisplayName("ModelRegistry：setDefault / defaultName / configuredDefaultName")
    void registryDefaultNameSemantics() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(fakeModel("first"));
        registry.register(fakeModel("second"));

        assertThat(registry.configuredDefaultName()).isNull();
        // 没有显式默认时取注册顺序第一个
        assertThat(registry.defaultName()).isEqualTo("first");
        assertThat(registry.defaultModel().name()).isEqualTo("first");

        registry.setDefault("second");
        assertThat(registry.defaultName()).isEqualTo("second");
        assertThat(registry.configuredDefaultName()).isEqualTo("second");

        // 配了一个取不到的名字：一个回退、一个保留原样
        registry.setDefault("ghost");
        assertThat(registry.defaultName()).isEqualTo("first");
        assertThat(registry.configuredDefaultName()).isEqualTo("ghost");
        assertThat(registry.defaultModel().name()).isEqualTo("first");
    }

    @Test
    @Order(22)
    @DisplayName("ModelRegistry：get 未知名字抛 ModelException；空注册表取默认模型也抛")
    void registryFailureModes() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(fakeModel("only"));

        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("only");

        registry.clear();
        assertThat(registry.names()).isEmpty();
        assertThat(registry.all()).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
        assertThatThrownBy(registry::defaultModel)
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("尚未注册任何模型");
        // clear 不会把 configuredDefaultName 也抹掉：它记录的是"配置里原样写了什么"
        assertThat(registry.configuredDefaultName()).isNull();
    }

    // ==================================================================
    // 四、Spring 接线：模型来源三选一 + 自定义协议 + 自定义传输层
    // ==================================================================

    @Test
    @Order(30)
    @DisplayName("yml 里的七条模型都装配出来了，默认模型是 llm.default-model 指定的那条")
    void containerRegistryContainsAllYmlModels() {
        assertThat(models.names()).containsExactly(
                "openai-demo", "anthropic-demo", "gemini-demo", "responses-demo", "demo",
                "retry-demo", "plain-demo");
        assertThat(models.configuredDefaultName()).isEqualTo("openai-demo");
        assertThat(models.defaultName()).isEqualTo("openai-demo");
        assertThat(models.defaultModel().name()).isEqualTo("openai-demo");
    }

    @Test
    @Order(31)
    @DisplayName("LlmModel bean 覆盖同名的 yml 配置：bean 后注册、因此 bean 胜出")
    void modelBeanOverridesSameNamedYmlEntry() {
        assertThat(openaiDemoModelBean).isSameAs(models.get("openai-demo"));

        ModelConfig config = WireScenario.configOf(models.get("openai-demo"));
        // yml 里写的是 stub-gpt，bean 里写的是 stub-gpt-bean
        assertThat(config.model()).isEqualTo("stub-gpt-bean");
        assertThat(config.extra()).containsEntry("bean_marker", true);
        assertThat(config.headers()).containsEntry("X-From", "bean");
        // 能力声明是 yml 表达不出来的那一项：thinking 被代码打开了
        assertThat(models.get("openai-demo").capabilities().thinking()).isTrue();
        // 而内置 openai 预设默认是关闭的
        assertThat(com.benxin.llm.core.protocol.openai.OpenAiCodec.RECOMMENDED_CAPABILITIES.thinking())
                .isFalse();
    }

    @Test
    @Order(32)
    @DisplayName("max-retries 的兑现方式：0 → 裸模型，2 → RetryingLlmModel")
    void maxRetriesDecidesWhetherDecoratorIsApplied() {
        assertThat(models.get("plain-demo")).isInstanceOf(HttpLlmModel.class);
        assertThat(models.get("plain-demo")).isNotInstanceOf(RetryingLlmModel.class);
        assertThat(models.get("retry-demo")).isInstanceOf(RetryingLlmModel.class);
        assertThat(((RetryingLlmModel) models.get("retry-demo")).maxRetries()).isEqualTo(2);
        // 装饰器的退避参数沿用默认值：配置里只暴露"重试几次"
        assertThat(((RetryingLlmModel) models.get("retry-demo")).initialBackoffMillis())
                .isEqualTo(RetryingLlmModel.DEFAULT_INITIAL_BACKOFF_MILLIS);
    }

    @Test
    @Order(33)
    @DisplayName("ProtocolCodec bean 被收进协议注册表：protocol: demo 因此可用")
    void protocolCodecBeanIsRegistered() {
        assertThat(protocolRegistry.ids()).containsExactly(
                "openai", "anthropic", "gemini", "responses", "demo");
        assertThat(protocolRegistry.find(Protocol.of("demo")))
                .isPresent()
                .get()
                .isInstanceOf(DemoProtocolCodec.class);
        // 内置四协议仍然在
        assertThat(protocolRegistry.find(Protocol.OPENAI)).isPresent();
        assertThat(protocolRegistry.describe()).containsExactly(
                "openai", "anthropic", "gemini", "responses", "demo");
    }

    @Test
    @Order(34)
    @DisplayName("HttpTransport bean 接管全应用：容器里的模型都走 RecordingHttpTransport")
    void customTransportBeanTakesOver() {
        assertThat(transport).isInstanceOf(com.benxin.exp.models.transport.RecordingHttpTransport.class);
        // 容器里那条 openai 模型用的就是同一个传输实例
        HttpLlmModel model = (HttpLlmModel) models.get(WireConstants.OPENAI_KEY);
        WireScenario scenario = new WireScenario(models, transport);
        scenario.openAiText();
        com.benxin.exp.models.transport.RecordingHttpTransport recording =
                (com.benxin.exp.models.transport.RecordingHttpTransport) transport;
        assertThat(recording.count()).isPositive();
        assertThat(model.config().baseUrl()).isEqualTo(StubRegistry.baseUrl());
    }

    @Test
    @Order(35)
    @DisplayName("BaseUrlSupplier：${exp.stub.base-url} 解析成本地桩服务，且绝不指向公网")
    void baseUrlPlaceholderResolvesToLocalStub() {
        assertThat(baseUrlSupplier.resolvedFromYml(StubRegistry.BASE_URL_PROPERTY))
                .isEqualTo(StubRegistry.baseUrl());
        assertThat(baseUrlSupplier.stubBaseUrl()).isEqualTo(StubRegistry.baseUrl());
        // 硬性约定：本模块里不存在任何指向真实厂商地址的 base-url
        assertThat(models.all()).allSatisfy(model -> {
            // configOf 会先剥掉重试装饰器：max-retries>0 的模型外面包着 RetryingLlmModel
            String baseUrl = WireScenario.configOf(model).baseUrl();
            assertThat(baseUrl).startsWith("http://127.0.0.1:");
        });
    }

    @Test
    @Order(36)
    @DisplayName("声明式 Agent 走自定义协议：@LlmAgent(model = \"demo\") 端到端跑通，调用方零改动")
    void declarativeAgentRunsOnCustomProtocol() {
        WireScenario scenario = new WireScenario(models, transport);
        scenario.resetStub();
        // 声明式路径恒为流式（LoopContext 读 capabilities().streaming()，demo 协议声明为 true），
        // 因此桩服务必须回 SSE 帧。回一整包 JSON 会被 SseParser 当成"一条事件都没有的流"，
        // 结果是静默的空回答 —— 这条真实行为由 ProtocolWireTest#nonSseBodyOnStreamingPath 断言。
        scenario.stub().onPath("/demo/v2/invoke", request -> StubHttpServer.Reply.sseFrames(
                DemoWire.streamingText(5, 2, "可以", "骑车。")));

        String answer = demoAssistant.ask("demo 协议能用吗？");

        assertThat(answer).isEqualTo("可以骑车。");
        // 整条链路打的是自定义协议的端点与认证头
        var recorded = scenario.stub().lastRequest().orElseThrow();
        assertThat(recorded.path()).isEqualTo("/demo/v2/invoke");
        assertThat(recorded.header("X-Demo-Key")).isEqualTo("sk-demo");
        // 系统提示进了 directive.system —— 与四种内置协议的落点都不一样
        assertThat(recorded.json().path("directive").path("system").asText()).contains("demo 协议演示用的助手");
    }

    // ==================================================================
    // 五、Benxin 门面与程序化装配
    // ==================================================================

    @Test
    @Order(40)
    @DisplayName("Benxin 门面：openAi/anthropic/gemini/model/withCodec 都落到 HttpLlmModel 上")
    void benxinFacadeBuildsHttpLlmModels() {
        String base = StubRegistry.baseUrl();
        // 显式 maxRetries(0)：否则门面会用 ModelConfig 的默认值 2，把模型包成 RetryingLlmModel，
        // 于是"门面返回的是不是 HttpLlmModel"这条断言就看不出来了。
        ModelConfig config = ModelConfig.builder("facade").baseUrl(base).apiKey("sk-facade")
                .model(OpenAiWire.MODEL).maxRetries(0).build();

        LlmModel openAi = Benxin.openAi(config);
        LlmModel anthropic = Benxin.anthropic(ModelConfig.builder("facade-claude").baseUrl(base)
                .apiKey("sk-facade").model(AnthropicWire.MODEL).maxRetries(0).build());
        LlmModel gemini = Benxin.gemini(ModelConfig.builder("facade-gemini").baseUrl(base)
                .apiKey("sk-facade").model(com.benxin.exp.common.wire.GeminiWire.MODEL_VERSION)
                .maxRetries(0).build());
        LlmModel byProtocol = Benxin.model(config, new JdkHttpTransport());
        LlmModel byCodec = Benxin.withCodec(config, new DemoProtocolCodec(), new JdkHttpTransport());

        // 三者共同的地基就是 HttpLlmModel：协议、传输、配置三件套
        assertThat(openAi).isInstanceOf(HttpLlmModel.class);
        assertThat(anthropic).isInstanceOf(HttpLlmModel.class);
        assertThat(gemini).isInstanceOf(HttpLlmModel.class);
        assertThat(byProtocol).isInstanceOf(HttpLlmModel.class);
        assertThat(byCodec).isInstanceOf(HttpLlmModel.class);
        // 按 protocol 自动选 Codec
        assertThat(((HttpLlmModel) byProtocol).codec().protocol()).isEqualTo(Protocol.OPENAI);
        // 换协议就是换 Codec：同一个配置类，不同的编解码器
        assertThat(((HttpLlmModel) byCodec).codec()).isInstanceOf(DemoProtocolCodec.class);
        assertThat(((HttpLlmModel) anthropic).codec().protocol()).isEqualTo(Protocol.ANTHROPIC);
        assertThat(((HttpLlmModel) gemini).codec().protocol()).isEqualTo(Protocol.GEMINI);
    }

    // ==================================================================
    // 六、启动期失败（Spring 路径）
    // ==================================================================

    /**
     * 只装配"配置 → 注册表"这一小段的最小上下文。
     *
     * <p>刻意<b>不</b>扫本模块的 {@code @SpringBootApplication}：这一节要断言的是
     * <b>自动配置本身</b>在遇到未注册协议时的行为，与业务代码无关。
     * {@code ApplicationContextRunner} 默认不加载任何自动配置，因此这里显式点名要哪几个 ——
     * 也正是这份 import 清单（{@code AutoConfiguration.imports}）决定了真实应用里会装配什么。</p>
     */
    private ApplicationContextRunner minimalContext() {
        return new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        LlmAutoConfiguration.class,
                        com.benxin.llm.spring.LlmAgentAutoConfiguration.class,
                        com.benxin.llm.spring.LlmWebAutoConfiguration.class))
                .withPropertyValues(
                        "llm.tools.builtin-enabled=false",
                        "llm.web.enabled=false");
    }

    @Test
    @Order(50)
    @DisplayName("配置里写了没有 Codec 的协议 → 上下文启动期直接失败，并列出已注册协议")
    void unknownProtocolFailsAtContextStartup() {
        minimalContext()
                .withPropertyValues(
                        "llm.models.ghost.protocol=bedrock",
                        "llm.models.ghost.base-url=http://127.0.0.1:1/unused",
                        "llm.models.ghost.model=ghost-model")
                .run(context -> {
                    // 报错发生在 llmModelRegistry 这个 bean 的创建阶段 —— 也就是"启动期"，
                    // 而不是第一次请求时。配置写错必须立刻可见。
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(rootCauseMessages(failure))
                            .contains("未知协议")
                            .contains("bedrock")
                            .contains("openai");
                });
    }

    @Test
    @Order(51)
    @DisplayName("同一个最小上下文里，合法协议照常装配成功")
    void knownProtocolStartsFineInMinimalContext() {
        minimalContext()
                .withPropertyValues(
                        "llm.models.good.protocol=openai",
                        "llm.models.good.base-url=http://127.0.0.1:1/unused",
                        "llm.models.good.model=stub-gpt",
                        "llm.models.good.max-retries=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 未显式指定默认模型时取注册顺序第一个
                    assertThat(context.getBean(ModelRegistry.class).defaultName()).isEqualTo("good");
                    assertThat(context.getBean(ProtocolRegistry.class).ids())
                            .containsExactly("openai", "anthropic", "gemini", "responses");
                });
    }

    @Test
    @Order(52)
    @DisplayName("同名覆盖内置协议时会打一条 WARN（覆盖是允许的，但不该静默）")
    void overridingBuiltinProtocolLogsWarning() {
        ch.qos.logback.classic.Logger registryLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProtocolRegistry.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
        try {
            ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
            registry.register(new OverridingOpenAiCodec());

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("顶掉了同名的内置协议实现")
                                .contains("openai")
                                .contains(OverridingOpenAiCodec.class.getName());
                    });
        } finally {
            registryLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private WireScenario.WireResult roundTrip(WireScenario scenario, ModelConfig config) {
        return scenario.settingsRoundTrip(config);
    }

    /**
     * 一个不出网的假模型，只用来填注册表的格子。
     *
     * <p>用 {@code FakeTransport} 而不是真传输层：注册表本身与网络无关，
     * 把网络依赖引进来只会让失败原因变得难以定位。</p>
     */
    private LlmModel fakeModel(String name) {
        ModelConfig config = ModelConfig.builder(name)
                .protocol(Protocol.OPENAI)
                .baseUrl("http://127.0.0.1:1/unused")
                .model("stub-gpt")
                .maxRetries(0)
                .build();
        return new com.benxin.llm.core.model.impl.OpenAiModel(config,
                new com.benxin.exp.models.transport.FakeTransport());
    }

    private static String rootCauseMessages(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}
