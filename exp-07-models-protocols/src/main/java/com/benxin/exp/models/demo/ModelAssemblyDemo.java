package com.benxin.exp.models.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.models.config.BaseUrlSupplier;
import com.benxin.exp.models.config.ModelSettings;
import com.benxin.exp.models.support.WireDump;
import com.benxin.exp.models.wire.WireConstants;
import com.benxin.exp.models.wire.WireScenario;
import com.benxin.exp.models.wire.WireScenario.RetryResult;
import com.benxin.exp.models.wire.WireScenario.WireResult;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型装配演示：{@code ModelConfig} 全字段 / {@code ModelFactory} / {@code ModelRegistry} /
 * 模型来源三选一 / 重试装饰器。
 *
 * <p>这一节回答的是"配置是怎么变成一个能用的模型对象的"：yml 一条路、{@code LlmModel} bean
 * 一条路、{@code new OpenAiModel(...)} 一条路，三条路最后都落到同一个
 * {@link ModelConfig} + {@code ProtocolCodec} + {@code HttpTransport} 的三件套上。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "assembly", havingValue = "true", matchIfMissing = true)
public class ModelAssemblyDemo implements ApplicationRunner {

    private final ModelRegistry models;
    private final HttpTransport transport;
    private final BaseUrlSupplier baseUrlSupplier;
    private final ModelSettings settings;

    public ModelAssemblyDemo(ModelRegistry models, HttpTransport transport,
                             BaseUrlSupplier baseUrlSupplier, ModelSettings settings) {
        this.models = models;
        this.transport = transport;
        this.baseUrlSupplier = baseUrlSupplier;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        WireScenario scenario = new WireScenario(models, transport);

        Demo demo = Demo.begin("示例 07 · 模型装配 —— 配置、工厂、注册表、装饰器");
        demo.banner("默认模型: %s（配置里原样写的名字: %s）",
                models.defaultName(), models.configuredDefaultName());
        demo.banner("传输层 bean: %s → 全应用的模型都走它", transport.getClass().getSimpleName());

        demo.guard("① ModelConfig 全字段", () -> modelConfigFields(demo, scenario));
        demo.guard("② 模型来源三选一", () -> threeSources(demo));
        demo.guard("③ ModelFactory / ModelRegistry", () -> factoryAndRegistry(demo));
        demo.guard("④ 重试装饰器", () -> retryDecorator(demo, scenario));
        demo.guard("⑤ ModelCapabilities", () -> capabilities(demo));

        demo.done();
    }

    // ------------------------------------------------------------------ ① ModelConfig

    private void modelConfigFields(Demo demo, WireScenario scenario) {
        demo.section("ModelConfig 全字段 —— yml / java 两条通道落到同一个对象");

        demo.info("yml 里的占位符 ${exp.stub.base-url} 解析成: %s",
                baseUrlSupplier.resolvedFromYml("exp.stub.base-url"));

        ModelConfig fromYml = ymlConfig("openai-demo");
        ModelConfig fromSettings = baseUrlSupplier.toModelConfig(settings);

        printConfig(demo, "llm.models.openai-demo（yml 绑定）", fromYml);
        printConfig(demo, "exp.model.*（独立样本）", fromSettings);

        demo.info("── 用这份独立样本真打一次桩服务，逐条看字段去了哪里：");
        WireResult result = scenario.settingsRoundTrip(fromSettings);
        WireDump.request(demo, result.recorded());
        demo.ok("api-key 写成 \"Bearer sk-settings\" → Authorization = %s（不会变成 Bearer Bearer）",
                result.header("Authorization"));
        demo.ok("自定义头覆盖了内置认证头 → Authorization = %s", result.header("Authorization"));
        demo.ok("extra 里的 vendor_flag 出现在请求体顶层 = %s",
                result.requestBody() != null && result.requestBody().contains("\"vendor_flag\""));
        demo.ok("temperature / max_tokens 来自配置（请求里没写就下发配置值）");
        WireDump.response(demo, result.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(result.response()));

        demo.info("── base-url 末尾斜杠：插件在 ModelConfig 构造时就裁掉了（否则会拼出 //v1/chat/completions）");
        demo.ok("ModelConfig.builder(\"x\").baseUrl(\"http://h/v1///\") → baseUrl() = %s",
                ModelConfig.builder("x").baseUrl("http://h/v1///").build().baseUrl());

        demo.info("── resolveModel(ChatRequest)：请求里显式指定则优先，否则用配置里的");
        demo.ok("默认 → %s；请求写 deepseek-chat → %s",
                fromYml.resolveModel(WireConstants.request()),
                fromYml.resolveModel(com.benxin.llm.core.chat.ChatRequest.builder()
                        .model("deepseek-chat").build()));
        demo.ok("hasApiKey()：配了 → %s；没配 → %s",
                fromYml.hasApiKey(),
                ModelConfig.builder("no-key").baseUrl("http://h").build().hasApiKey());
    }

    private void printConfig(Demo demo, String label, ModelConfig config) {
        demo.info("── %s", label);
        demo.info("   name=%s protocol=%s model=%s", config.name(), config.protocol(), config.model());
        demo.info("   baseUrl=%s", config.baseUrl());
        demo.info("   apiKey=%s（hasApiKey=%s）", mask(config.apiKey()), config.hasApiKey());
        demo.info("   headers=%s", config.headers());
        demo.info("   connectTimeout=%s readTimeout=%s streamReadTimeout=%s",
                config.connectTimeout(), config.readTimeout(), config.streamReadTimeout());
        demo.info("   maxRetries=%d temperature=%s maxTokens=%s",
                config.maxRetries(), config.temperature(), config.maxTokens());
        demo.info("   extra=%s", config.extra());
    }

    // ------------------------------------------------------------------ ② 三种来源

    private void threeSources(Demo demo) {
        demo.section("模型来源三选一 —— yml / LlmModel bean / 程序化");

        demo.info("① yml：llm.models.<key>.* → %s", ymlOnlyKeys());
        demo.info("② LlmModel bean：%s（{@code @LlmModelDef} 或 name()）", beanModelKeys());
        demo.info("③ 程序化 new OpenAiModel(config, transport) / Benxin.openAi(...)");

        demo.info("── bean 覆盖同名 yml 配置：名为 openai-demo 的 bean 赢了 yml 里同名那一条");
        LlmModel winner = models.get(WireConstants.OPENAI_KEY);
        ModelConfig config = WireScenario.configOf(winner);
        demo.ok("   最终生效：model=%s（yml 写的是 stub-gpt，bean 写的是 stub-gpt-bean）", config.model());
        demo.ok("   extra=%s（yml 里没有这一项）", config.extra());
        demo.ok("   capabilities().thinking()=%s（yml 表达不出来，只能靠代码）",
                winner.capabilities().thinking());

        demo.info("── 默认模型的决定方式：primary + order，或 llm.default-model");
        demo.info("   本例 llm.default-model=openai-demo；若把 default-model 删掉，primary=true 且 order 最小的胜出");
        demo.info("   defaultName()=%s（实际生效） / configuredDefaultName()=%s（配置里原样写的）",
                models.defaultName(), models.configuredDefaultName());
    }

    // ------------------------------------------------------------------ ③ 工厂与注册表

    private void factoryAndRegistry(Demo demo) {
        demo.section("ModelFactory / ModelRegistry");

        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        configs.put("first", ModelConfig.builder("first")
                .protocol("openai").baseUrl(scenarioBaseUrl()).model("stub-gpt").maxRetries(0).build());
        configs.put("second", ModelConfig.builder("second")
                .protocol("gemini").baseUrl(scenarioBaseUrl()).model("gemini-stub-001").maxRetries(2).build());

        Map<String, LlmModel> created = ModelFactory.createAll(configs, transport);
        demo.ok("ModelFactory.createAll 保留配置顺序 → %s", created.keySet());
        demo.ok("maxRetries=0 → %s；maxRetries=2 → %s",
                created.get("first").getClass().getSimpleName(),
                created.get("second").getClass().getSimpleName());
        demo.info("   也就是说 max-retries 这个配置项的兑现方式是"
                + "「ModelFactory 自动包一层 RetryingLlmModel」，而不是每个调用方自己包");

        ModelRegistry registry = ModelFactory.registry(configs, transport, "不存在的名字");
        demo.ok("registry(names)=%s", registry.names());
        demo.ok("配置的默认名不存在 → defaultName() 回退到注册顺序的第一个 = %s，"
                        + "而 configuredDefaultName() 保留原样写的名字 = %s",
                registry.defaultName(), registry.configuredDefaultName());
        demo.ok("defaultModel().name() = %s（与 defaultName() 口径一致）", registry.defaultModel().name());
        demo.ok("find/get：find(\"first\").isPresent()=%s；get(\"nope\") 抛异常=%s",
                registry.find("first").isPresent(), throwsModelException(() -> registry.get("nope")));

        ModelRegistry scratch = new ModelRegistry();
        scratch.register(created.get("first"));
        scratch.registerAll(Map.of("third", created.get("second")));
        scratch.setDefault("third");
        demo.ok("register/registerAll/setDefault 之后：%s，默认 %s，all().size()=%d",
                scratch.names(), scratch.defaultName(), scratch.all().size());
        scratch.clear();
        demo.ok("clear() 之后 names()=%s，defaultModel() 抛异常=%s",
                scratch.names(), throwsModelException(() -> {
                    scratch.defaultModel();
                }));

        demo.info("── ModelFactory.defaultRegistry()：内置四协议（裸 core 场景的默认注册表）");
        demo.ok("   %s", ModelFactory.defaultRegistry().describe());
    }

    // ------------------------------------------------------------------ ④ 重试

    private void retryDecorator(Demo demo, WireScenario scenario) {
        demo.section("RetryingLlmModel —— 瞬时错误退避重试，但不是无脑重试");

        RetryResult retried = scenario.retryAfterTransientFailure();
        demo.info("── 桩服务第 1 次回 503、第 2 次回 200：");
        demo.ok("   发起重试 %d 次，桩服务共收到 %d 次请求，最终回答 = 「%s」",
                retried.attempts(), retried.requestCount(), retried.response().text());

        RetryResult deterministic = scenario.noRetryOnDeterministicFailure();
        demo.info("── 桩服务回 400（确定性失败）：");
        demo.ok("   重试次数 = %d，请求数 = %d —— 重试判定完全交给 ModelException.isRetryable()，"
                        + "400 立刻上抛，不浪费用户等待",
                deterministic.attempts(), deterministic.requestCount());

        RetryResult exhausted = scenario.retriesExhausted();
        demo.info("── maxRetries=1 而桩服务一直回 503：");
        demo.ok("   重试 %d 次后抛出最后一次异常（HTTP %d，isRetryable=%s）",
                exhausted.attempts(), exhausted.failure().statusCode(), exhausted.failure().isRetryable());

        var streamed = scenario.streamingRetryBeforeAnyContent();
        demo.ok("── 流式：内容尚未下发就失败 → 允许重试。重试 %d 次，请求数 %d，最终增量 = 「%s」",
                streamed.model() instanceof RetryingLlmModel retrying ? retrying.attempts() : -1,
                streamed.requestCount(), streamed.text());

        var afterContent = scenario.streamingNoRetryAfterContent();
        demo.ok("── 流式：已经吐出内容之后才失败 → 不再重试。重试 %d 次，错误 = %s",
                afterContent.model() instanceof RetryingLlmModel retrying ? retrying.attempts() : -1,
                afterContent.error() == null ? "无" : afterContent.error().getMessage());
        demo.info("   理由：内容一旦交付给调用方就无法撤回，重试只会造成重复投递；"
                + "这一条在 README 里被明确承诺，因此测试里用「交付完第 1 帧后断开」精确复现。");
    }

    // ------------------------------------------------------------------ ⑤ 能力声明

    private void capabilities(Demo demo) {
        demo.section("ModelCapabilities —— 六个字段都有行为含义，不是装饰品");

        demo.info("── 四个内置协议的推荐声明：");
        print("openai", com.benxin.llm.core.protocol.openai.OpenAiCodec.RECOMMENDED_CAPABILITIES, demo);
        print("anthropic", com.benxin.llm.core.protocol.anthropic.AnthropicCodec.RECOMMENDED_CAPABILITIES, demo);
        print("gemini", com.benxin.llm.core.protocol.gemini.GeminiCodec.RECOMMENDED_CAPABILITIES, demo);
        print("responses", com.benxin.llm.core.protocol.responses.ResponsesApiCodec.RECOMMENDED_CAPABILITIES, demo);
        print("demo（自定义）", com.benxin.exp.models.custom.DemoProtocolCodec.CAPABILITIES, demo);

        demo.info("── 语义：");
        demo.info("   streaming=false        → DefaultLoopContext#callModel 改走 chat() 同步分支（见 BuildAndTransportDemo ⑦）");
        demo.info("   toolCalling=false      → react Loop 会降级到文本协议（Action:/Action Input:）");
        demo.info("   parallelToolCalls      → 多个工具调用能否并发执行");
        demo.info("   vision / thinking      → 该通道是否接受图片、是否解析思维链");
        demo.info("   maxContextTokens       → 上下文压缩的阈值依据");

        demo.info("── 能力声明取自 Codec（而不是模型预设）：ModelFactory 装配时用的是 codec.capabilities()");
        LlmModel gemini = models.get(WireConstants.GEMINI_KEY);
        demo.ok("   gemini-demo 的 capabilities = %s", gemini.capabilities());
        demo.ok("   装饰器会透传能力：RetryingLlmModel(new OpenAiModel(...)).capabilities().thinking() = %s",
                new RetryingLlmModel(models.get(WireConstants.OPENAI_KEY)).capabilities().thinking());
        demo.info("   透传是必须的：重试不会让模型多出视觉能力，若装饰器返回默认值，"
                + "Loop 的降级决策会被悄悄改掉。");
    }

    private void print(String name, ModelCapabilities capabilities, Demo demo) {
        demo.info("   %-12s streaming=%-5s toolCalling=%-5s parallel=%-5s vision=%-5s thinking=%-5s maxContext=%d",
                name, capabilities.streaming(), capabilities.toolCalling(), capabilities.parallelToolCalls(),
                capabilities.vision(), capabilities.thinking(), capabilities.maxContextTokens());
    }

    // ------------------------------------------------------------------ 小工具

    private ModelConfig ymlConfig(String name) {
        return WireScenario.configOf(models.get(name));
    }

    private java.util.Set<String> ymlOnlyKeys() {
        // yml 里的键取自 application.yml 的 llm.models.*；bean 覆盖后名字不变，因此这里列出全部
        return models.names();
    }

    private java.util.Set<String> beanModelKeys() {
        return java.util.Set.of(WireConstants.OPENAI_KEY);
    }

    private String scenarioBaseUrl() {
        String url = com.benxin.exp.models.support.StubRegistry.baseUrl();
        return url == null ? "" : url;
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "(空)";
        }
        return apiKey.length() <= 8 ? apiKey : apiKey.substring(0, 8) + "…";
    }

    private static boolean throwsModelException(Runnable body) {
        try {
            body.run();
            return false;
        } catch (com.benxin.llm.core.model.ModelException e) {
            return true;
        }
    }
}
