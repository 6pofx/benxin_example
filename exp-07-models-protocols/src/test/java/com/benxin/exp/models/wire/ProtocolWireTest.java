package com.benxin.exp.models.wire;

import com.benxin.exp.common.wire.AnthropicWire;
import com.benxin.exp.common.wire.GeminiWire;
import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.ResponsesWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.exp.models.support.StubRegistry;
import com.benxin.exp.models.transport.FakeTransport;
import com.benxin.exp.models.transport.RecordingHttpTransport;
import com.benxin.exp.models.transport.ScriptedStreamTransport;
import com.benxin.exp.models.wire.WireScenario.RetryResult;
import com.benxin.exp.models.wire.WireScenario.StreamResult;
import com.benxin.exp.models.wire.WireScenario.WireResult;
import com.benxin.exp.models.wire.WireScenario.SyncBranchResult;
import com.benxin.exp.models.wire.WireScenario.TransportMisuseResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.HttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线级协议测试：把 {@code ProtocolWireDemo} 打印的每一条结论钉成可回归的用例。
 *
 * <p>与演示共用 {@link WireScenario}，因此"演示里看到的"与"这里断言住的"是同一段代码、
 * 同一份桩服务的报文。测试全程离线：所有端点都指向只监听回环地址的随机端口。</p>
 *
 * <h2>为什么每个用例开头都 stub.reset()</h2>
 *
 * <p>桩服务是共享的（一个端口同时服务四个协议），请求是累计录制的。每个用例开头清一次，
 * 于是"本次抓到的请求"就是确定的第 1 笔 —— 断言可以写成 {@code path().isEqualTo(...)}
 * 这种强条件，而不是"找一找有没有"。</p>
 *
 * <h2>为什么用例要排序</h2>
 *
 * <p>协议注册表与桩服务的路由都是<b>进程级共享状态</b>（一个 JVM 里只有一个注册表、
 * 一个桩服务），而 {@link Order} 显式固定了"谁先污染、谁后验证"的顺序：
 * 覆盖内置协议、错误路由这些会改状态的用例放在最后，前面的用例因此可以在干净状态上断言。</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("示例 07 · 四种协议的线级报文")
class ProtocolWireTest {

    /**
     * 桩服务必须在 Spring 绑定配置之前就起来：yml 里的 {@code ${exp.stub.base-url}}
     * 在上下文 refresh 时解析，而端口只有桩服务起来才知道。
     */
    static {
        StubRegistry.stub();
    }

    @Autowired
    private ModelRegistry models;

    @Autowired
    private HttpTransport transport;

    private WireScenario scenario;

    @BeforeEach
    void setUp() {
        scenario = new WireScenario(models, transport);
        // 连路由一起清：桩服务是共享的，上一个用例留下的宽泛路由会把本用例的请求截胡
        scenario.resetStub();
    }

    // ==================================================================
    // 一、请求侧：端点 / 认证头 / 请求体结构
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("OpenAI 请求侧：/v1/chat/completions + Authorization: Bearer + 系统提示在 messages[] 里")
    void openAiRequestSide() {
        WireResult result = scenario.openAiText();

        assertThat(result.path()).isEqualTo("/v1/chat/completions");
        // 注：openai-demo 这条配置被代码接管了（ModelDemoConfiguration#openaiDemoModel），
        // 因此 model 是 bean 里写的 stub-gpt-bean、认证头是 bean 的 key
        assertThat(result.header("Authorization")).isEqualTo("Bearer sk-bean-override");
        assertThat(result.model()).isInstanceOf(OpenAiModel.class);

        var body = result.recorded().json();
        assertThat(body.path("model").asText()).isEqualTo("stub-gpt-bean");
        // 系统提示：一条 role=system 的消息，位置就在 messages[] 的第一个
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText())
                .isEqualTo(WireConstants.SYSTEM_PROMPT);
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        // 输出上限字段名是 max_tokens（配置里的 321 大于请求里的 256，请求优先）
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.3d);
        // 请求级 extra 透传到顶层
        assertThat(body.path("vendor_flag").asText()).isEqualTo("exp-07");
        // 配置级 extra 也在（bean 的 bean_marker）
        assertThat(body.path("bean_marker").asBoolean()).isTrue();
        // 非流式请求里不应出现 stream 字段
        assertThat(body.has("stream")).isFalse();
        assertThat(result.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("OpenAI 工具声明：tools[].type=function + function.parameters")
    void openAiToolDeclaration() {
        WireResult result = scenario.openAiTool();

        var tool = result.recorded().json().path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("get_weather");
        // 字段名是 parameters（Anthropic 才是 input_schema）
        assertThat(tool.path("function").path("parameters").path("required").get(0).asText())
                .isEqualTo("city");
        // 响应侧解出一次工具调用
        assertThat(result.toolUses()).hasSize(1);
        ToolUsePart use = result.toolUses().get(0);
        assertThat(use.id()).isEqualTo("call_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @Order(3)
    @DisplayName("Anthropic 请求侧：/v1/messages + x-api-key/anthropic-version + system 是顶层字段")
    void anthropicRequestSide() {
        WireResult result = scenario.anthropicText();

        assertThat(result.path()).isEqualTo("/v1/messages");
        assertThat(result.header("x-api-key")).isEqualTo("sk-anthropic");
        // Anthropic 要求显式声明版本，缺了直接 400
        assertThat(result.header("anthropic-version")).isEqualTo("2023-06-01");

        var body = result.recorded().json();
        assertThat(body.path("model").asText()).isEqualTo("claude-stub");
        // 关键差异：system 是顶层字符串，而不是 messages 里的一条
        assertThat(body.path("system").asText()).isEqualTo(WireConstants.SYSTEM_PROMPT);
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        // Anthropic 的 messages[].content 恒为内容块数组
        assertThat(body.path("messages").get(0).path("content").get(0).path("type").asText())
                .isEqualTo("text");
        // max_tokens 必填：请求里给了 256
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        // 不应出现 OpenAI 那些字段
        assertThat(body.has("max_output_tokens")).isFalse();
        assertThat(body.has("instructions")).isFalse();
        assertThat(result.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Anthropic 工具声明用 input_schema；tool_use 的 input 是 JSON 对象")
    void anthropicToolDeclaration() {
        WireResult result = scenario.anthropicTool();

        var tool = result.recorded().json().path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("get_weather");
        // 字段名是 input_schema；OpenAI 那层 function 包装在 Anthropic 里不存在
        assertThat(tool.has("input_schema")).isTrue();
        assertThat(tool.has("function")).isFalse();

        ToolUsePart use = result.toolUses().get(0);
        assertThat(use.id()).isEqualTo("toolu_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        // input 在协议里是对象，插件把它序列化成统一的 argumentsJson 字符串
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(result.usage().inputTokens()).isEqualTo(33);
        assertThat(result.usage().outputTokens()).isEqualTo(15);
    }

    @Test
    @Order(4)
    @DisplayName("Anthropic 回灌工具结果：tool_use 与 tool_result 两个内容块必须成对出现")
    void anthropicToolResultEncodesBothBlocks() {
        // wireScenario.anthropicTool() 用的是 toolRequest()：只声明工具、不带历史工具结果。
        // 要看到 tool_use / tool_result 的内容块编码，必须用一个"上一轮调过工具"的请求。
        WireResult result = scenario.toolResultRoundTrip("anthropic");

        assertThat(result.requestBody()).contains("\"type\":\"tool_use\"");
        assertThat(result.requestBody()).contains("\"type\":\"tool_result\"");
        // Anthropic 靠 tool_use_id 把结果对回去，且 id 必须与 assistant 侧下发的一致
        assertThat(result.requestBody()).contains("\"tool_use_id\":\"call_1\"");
        assertThat(result.requestBody()).contains("\"id\":\"call_1\"");
        assertThat(result.requestBody()).contains("晴，18℃，微风");
    }

    @Test
    @Order(5)
    @DisplayName("Gemini 请求侧：模型名拼进 URL + x-goog-api-key，且 URL 里没有 ?key=")
    void geminiRequestSide() {
        WireResult result = scenario.geminiText();

        // 端点把模型名拼在路径上，这是 Gemini 与另外三家的结构性差异
        assertThat(result.path()).isEqualTo("/v1beta/models/gemini-stub-001:generateContent");
        assertThat(result.header("x-goog-api-key")).isEqualTo("sk-gemini");
        // 密钥走请求头而不是查询串 —— 查询串会进网关日志与代理缓存
        assertThat(result.recorded().query()).isEmpty();
        assertThat(result.recorded().queryParam("key")).isNull();
        assertThat(result.path()).doesNotContain("key=");

        var body = result.recorded().json();
        // Gemini 的请求体里【没有】model 字段：它在 URL 上
        assertThat(body.has("model")).isFalse();
        // 系统提示走顶层 systemInstruction.parts[].text
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo(WireConstants.SYSTEM_PROMPT);
        assertThat(body.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText())
                .isEqualTo(WireConstants.USER_TEXT);
        // 输出上限叫 generationConfig.maxOutputTokens
        assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(256);
        assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.3d);
        assertThat(result.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("Gemini 函数声明：tools[].functionDeclarations[]；id 由插件合成 call_<名字>_<序号>")
    void geminiToolDeclaration() {
        WireResult result = scenario.geminiTool();

        var declaration = result.recorded().json().path("tools").get(0).path("functionDeclarations").get(0);
        assertThat(declaration.path("name").asText()).isEqualTo("get_weather");
        // toolConfig.functionCallingConfig 是 Gemini 的 tool_choice 落点
        assertThat(result.requestBody()).contains("functionCallingConfig");

        ToolUsePart use = result.toolUses().get(0);
        // 协议里 functionCall 没有 id，插件按 call_<名字>_<序号> 合成
        assertThat(use.id()).isEqualTo("call_get_weather_1");
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\"}");
        // 口径提醒：Gemini 的结束原因直接来自响应里的 finishReason。
        // GeminiWire.functionCallResponse 给的是 "STOP"，所以这里就是 STOP ——
        // 插件不会因为"看到了 functionCall"就把它改写成 TOOL_CALLS（OpenAI 那条路才有这个兜底，
        // 因为部分兼容网关压根不回 finish_reason）。上层判断"要不要执行工具"应当看 toolUses 是否为空。
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.toolUses()).isNotEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("Responses 请求侧：/v1/responses + 顶层 instructions + input[] + max_output_tokens")
    void responsesRequestSide() {
        WireResult result = scenario.responsesText();

        assertThat(result.path()).isEqualTo("/v1/responses");
        // 认证头与 Chat Completions 相同
        assertThat(result.header("Authorization")).isEqualTo("Bearer sk-responses");

        var body = result.recorded().json();
        assertThat(body.path("model").asText()).isEqualTo("gpt-stub-001");
        // 三处结构性差异：instructions 顶层、input item 列表、max_output_tokens
        assertThat(body.path("instructions").asText()).isEqualTo(WireConstants.SYSTEM_PROMPT);
        assertThat(body.has("messages")).isFalse();
        assertThat(body.path("input").get(0).path("type").asText()).isEqualTo("message");
        assertThat(body.path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("input").get(0).path("content").asText()).isEqualTo(WireConstants.USER_TEXT);
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(256);
        // 下发 max_tokens 会被判为未知参数，因此绝不能同时出现
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(result.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("Responses 工具声明是扁平的：{type,name,description,parameters}，没有 function 那层包装")
    void responsesToolDeclaration() {
        WireResult result = scenario.responsesTool();

        var tool = result.recorded().json().path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("name").asText()).isEqualTo("get_weather");
        assertThat(tool.has("parameters")).isTrue();
        assertThat(tool.has("function")).isFalse();

        ToolUsePart use = result.toolUses().get(0);
        // 注意是 call_id 而不是 OpenAI 的 id —— 它才是把 function_call_output 对回去的键
        assertThat(use.id()).isEqualTo("call_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @Order(9)
    @DisplayName("工具结果回灌：四个协议把「结果」放在四个不同的位置")
    void toolResultsLandInFourDifferentPlaces() {
        WireResult openai = scenario.toolResultRoundTrip("openai");
        assertThat(openai.requestBody()).contains("\"role\":\"tool\"");
        assertThat(openai.requestBody()).contains("\"tool_call_id\":\"call_1\"");

        WireResult anthropic = scenario.toolResultRoundTrip("anthropic");
        assertThat(anthropic.requestBody()).contains("\"type\":\"tool_result\"");
        assertThat(anthropic.requestBody()).contains("\"tool_use_id\":\"call_1\"");

        WireResult gemini = scenario.toolResultRoundTrip("gemini");
        assertThat(gemini.requestBody()).contains("functionResponse");
        assertThat(gemini.requestBody()).contains("\"role\":\"user\"");

        WireResult responses = scenario.toolResultRoundTrip("responses");
        assertThat(responses.requestBody()).contains("function_call_output");
        assertThat(responses.requestBody()).contains("\"call_id\":\"call_1\"");

        // 四个协议都成功解出了一段文本
        for (WireResult each : List.of(openai, anthropic, gemini, responses)) {
            assertThat(each.response().text()).isEqualTo("已经查过天气了。");
        }
    }

    @Test
    @Order(10)
    @DisplayName("多模态：同一张图在四种协议里有四种编码；Gemini 不吃外链图片")
    void imageEncodingDiffersPerProtocol() {
        WireResult openai = scenario.imageRoundTrip("openai", true);
        assertThat(openai.requestBody()).contains("data:image/png;base64,");

        WireResult anthropic = scenario.imageRoundTrip("anthropic", true);
        assertThat(anthropic.requestBody()).contains("\"type\":\"image\"");
        assertThat(anthropic.requestBody()).contains("\"media_type\":\"image/png\"");

        WireResult gemini = scenario.imageRoundTrip("gemini", true);
        assertThat(gemini.requestBody()).contains("inlineData");
        assertThat(gemini.requestBody()).contains("\"mimeType\":\"image/png\"");

        WireResult responses = scenario.imageRoundTrip("responses", true);
        assertThat(responses.requestBody()).contains("input_image");

        // 外链 URL：Gemini 无法直接下发（inlineData 只收 base64），会降级成一句文字说明
        WireResult geminiUrl = scenario.imageRoundTrip("gemini", false);
        assertThat(geminiUrl.requestBody()).doesNotContain("inlineData");
        // 降级文本的格式是 "[图片: <url>]"（冒号后有空格）—— 这是插件 GeminiCodec 里的字面量，
        // 断言必须与它逐字符一致，否则"降级发生了"这件事会因为一个空格而判错。
        assertThat(geminiUrl.requestBody()).contains("[图片: " + WireConstants.IMAGE_URL + "]");
    }

    // ==================================================================
    // 二、响应侧
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("OpenAI 响应侧：text / usage / finish_reason 全部对上")
    void openAiResponseSide() {
        WireResult result = scenario.openAiText();

        assertThat(result.response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.response().id()).isEqualTo(OpenAiWire.RESPONSE_ID);
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(42);
        assertThat(result.usage().outputTokens()).isEqualTo(7);
        assertThat(result.usage().totalTokens()).isEqualTo(49);
    }

    @Test
    @Order(12)
    @DisplayName("OpenAI 的 reasoning_content 会被解成 ThinkingPart，同时留在 raw 里")
    void openAiReasoningContentBecomesThinking() {
        scenario.resetStub();
        String body = OpenAiWire.reasoningResponse(OpenAiWire.MODEL, "先看风速，再看温度。",
                "结论：不适合骑车。", 11, 5);
        scenario.stub().onPath("/v1/chat/completions", request -> StubHttpServer.Reply.json(body));

        ChatResponse response = scenario.model(WireConstants.OPENAI_KEY).chat(WireConstants.request());

        assertThat(response.text()).isEqualTo("结论：不适合骑车。");
        assertThat(response.message().thinking()).isEqualTo("先看风速，再看温度。");
        assertThat(response.raw()).containsEntry("reasoning_content", "先看风速，再看温度。");
    }

    @Test
    @Order(13)
    @DisplayName("Anthropic 响应侧：stop_reason=end_turn 解成 STOP，cache_read 计入缓存命中")
    void anthropicResponseSide() {
        WireResult result = scenario.anthropicText();

        assertThat(result.response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.response().id()).isEqualTo(AnthropicWire.MESSAGE_ID);
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(37);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(5);
    }

    @Test
    @Order(14)
    @DisplayName("Gemini 响应侧：finishReason=STOP，用量取自 usageMetadata")
    void geminiResponseSide() {
        WireResult result = scenario.geminiText();

        assertThat(result.response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(29);
        assertThat(result.usage().outputTokens()).isEqualTo(13);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(4);
    }

    @Test
    @Order(15)
    @DisplayName("Responses 响应侧：结束原因来自 status=completed，而不是 finish_reason")
    void responsesResponseSide() {
        WireResult result = scenario.responsesText();

        assertThat(result.response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(21);
        assertThat(result.usage().outputTokens()).isEqualTo(9);
    }

    @Test
    @Order(16)
    @DisplayName("Gemini 安全拦截：没有 candidates 但有 promptFeedback.blockReason → 空文本 + CONTENT_FILTER")
    void geminiSafetyBlockDegradesGracefully() {
        scenario.resetStub();
        String body = GeminiWire.safetyBlockResponse("SAFETY");
        scenario.stub().onSuffix(":generateContent", request -> StubHttpServer.Reply.json(body));

        ChatResponse response = scenario.model(WireConstants.GEMINI_KEY).chat(WireConstants.request());

        // 这一条与另外三个协议的 errorEnvelope 不同：Gemini 的"被拦截"不是 error 信封，
        // 因此插件不抛异常，而是把 promptFeedback.blockReason 兜底成 CONTENT_FILTER ——
        // 上层据此能区分"模型没说话"与"内容被安全策略拦了"。
        assertThat(response.text()).isEmpty();
        assertThat(response.finishReason()).isEqualTo(FinishReason.CONTENT_FILTER);
        assertThat(response.raw()).containsKey("blockReason");
        assertThat(response.raw()).containsKey("promptFeedback");
    }

    // ==================================================================
    // 三、流式：增量拼接、结束语义、分片拼装
    // ==================================================================

    @Test
    @Order(20)
    @DisplayName("OpenAI 流：增量拼接 + usage 帧 + [DONE] 立即收尾")
    void openAiStreamEndsWithDone() {
        StreamResult result = scenario.openAiStream();

        assertThat(result.path()).isEqualTo("/v1/chat/completions");
        assertThat(result.text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.error()).isNull();
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        // usage 来自 stream_options.include_usage 的末帧（该帧 choices 是空数组）
        assertThat(result.usage().inputTokens()).isEqualTo(42);
        assertThat(result.usage().outputTokens()).isEqualTo(7);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(8);
        // 请求体里确实打开了流式与用量帧
        assertThat(result.requestBody()).contains("\"stream\":true");
        assertThat(result.requestBody()).contains("\"include_usage\":true");
        // 桩服务回放的帧里最后一帧就是 [DONE]
        assertThat(result.sseFrames()).endsWith("data: [DONE]\n\n");
    }

    @Test
    @Order(21)
    @DisplayName("OpenAI 流：reasoning_content 与正文分开累积，用量帧照常生效")
    void openAiReasoningStreamKeepsThinkingSeparate() {
        StreamResult result = scenario.openAiReasoningStream();

        assertThat(result.thinking()).isEqualTo("先看风速……再看温度。");
        assertThat(result.text()).isEqualTo("结论：不适合骑车。");
        assertThat(result.usage().inputTokens()).isEqualTo(42);
        assertThat(result.usage().outputTokens()).isEqualTo(7);
    }

    @Test
    @Order(22)
    @DisplayName("Anthropic 流：强类型事件 + message_stop 收尾（没有 [DONE]）")
    void anthropicStreamEndsWithMessageStop() {
        StreamResult result = scenario.anthropicStream();

        assertThat(result.path()).isEqualTo("/v1/messages");
        assertThat(result.text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.error()).isNull();
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        // 帧里带 event: 名，且结束帧是 message_stop —— 协议里并不存在 [DONE]
        assertThat(result.sseFrames()).contains("event: message_start");
        assertThat(result.sseFrames()).contains("event: message_stop");
        assertThat(result.sseFrames()).doesNotContain("[DONE]");
        // 用量：message_start 报 output=1，message_delta 报累计 11 → 合计 11
        assertThat(result.usage().inputTokens()).isEqualTo(37);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }

    @Test
    @Order(23)
    @DisplayName("Anthropic thinking 块：思维链与 signature 都累积；累计 output_tokens 被换算成增量")
    void anthropicThinkingBlockAndUsageDelta() {
        StreamResult result = scenario.anthropicThinkingStream();

        assertThat(result.thinking()).isEqualTo("先判断风向，再看气温。");
        assertThat(result.text()).isEqualTo("建议改坐地铁。");
        // signature 被切成两段下发，解码器必须累积
        var thinking = (com.benxin.llm.core.message.ThinkingPart) result.response().message()
                .parts().stream()
                .filter(com.benxin.llm.core.message.ThinkingPart.class::isInstance)
                .findFirst().orElseThrow();
        assertThat(thinking.signature()).isEqualTo("sig-part-1-sig-part-2");
        // message_start 给 output=1，message_delta 给【累计】23 → 换算成增量 22 后回调，
        // 于是 StreamCollector 累加得到的正好是累计值 23（如果不换算就会变成 24）
        assertThat(result.usage().outputTokens()).isEqualTo(23);
        assertThat(result.usage().inputTokens()).isEqualTo(41);
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    @Order(24)
    @DisplayName("Gemini 流：没有 [DONE]，靠连接关闭由 finish() 兜底收尾；累计用量换算成增量")
    void geminiStreamHasNoDoneMarker() {
        StreamResult result = scenario.geminiStream();

        assertThat(result.path()).isEqualTo("/v1beta/models/gemini-stub-001:streamGenerateContent");
        // 流式端点带 alt=sse
        assertThat(result.recorded().query()).isEqualTo("alt=sse");
        assertThat(result.text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(result.error()).isNull();
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.sseFrames()).doesNotContain("[DONE]");
        assertThat(result.usage().inputTokens()).isEqualTo(29);
        assertThat(result.usage().outputTokens()).isEqualTo(13);
    }

    @Test
    @Order(25)
    @DisplayName("Responses 流：命名事件 + response.completed 收尾，同样没有 [DONE]")
    void responsesStreamUsesNamedEvents() {
        StreamResult result = scenario.responsesStream();

        assertThat(result.path()).isEqualTo("/v1/responses");
        assertThat(result.text()).isEqualTo("今天风大。");
        assertThat(result.thinking()).isEqualTo("先看风速……");
        assertThat(result.error()).isNull();
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.sseFrames()).contains("event: response.output_text.delta");
        assertThat(result.sseFrames()).contains("event: response.completed");
        assertThat(result.sseFrames()).doesNotContain("[DONE]");
        assertThat(result.usage().inputTokens()).isEqualTo(21);
        assertThat(result.usage().outputTokens()).isEqualTo(9);
    }

    @Test
    @Order(26)
    @DisplayName("OpenAI 工具参数按 index 分片：拼出来的 arguments 必须是完整 JSON")
    void openAiToolArgumentsAreAssembledByIndex() {
        StreamResult result = scenario.openAiToolArgumentsStream();

        assertThat(result.error()).isNull();
        assertThat(result.toolUses()).hasSize(1);
        ToolUsePart use = result.toolUses().get(0);
        assertThat(use.id()).isEqualTo("call_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        // 参数被切成 5 段下发（id/name 只在第一段），拼出来必须与原值一字不差
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\",\"days\":3}");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @Order(27)
    @DisplayName("Anthropic 工具参数按 block index 的 input_json_delta 分片拼装")
    void anthropicToolArgumentsAreAssembledFromPartialJson() {
        StreamResult result = scenario.anthropicToolArgumentsStream();

        assertThat(result.error()).isNull();
        assertThat(result.toolUses()).hasSize(1);
        ToolUsePart use = result.toolUses().get(0);
        // id / name 来自 content_block_start，参数来自多段 partial_json
        assertThat(use.id()).isEqualTo("toolu_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\",\"days\":3}");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @Order(28)
    @DisplayName("Responses 工具参数按 output_index 分片，最终以 output_item.done 的完整值为准")
    void responsesToolArgumentsUseOutputIndex() {
        StreamResult result = scenario.responsesToolArgumentsStream();

        assertThat(result.error()).isNull();
        assertThat(result.toolUses()).hasSize(1);
        ToolUsePart use = result.toolUses().get(0);
        assertThat(use.id()).isEqualTo("call_w1");
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\",\"days\":3}");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @Order(29)
    @DisplayName("Gemini 流式下 functionCall 一次性给全，不存在参数分片")
    void geminiFunctionCallArrivesInOneFrame() {
        StreamResult result = scenario.geminiFunctionCallStream();

        assertThat(result.error()).isNull();
        assertThat(result.toolUses()).hasSize(1);
        ToolUsePart use = result.toolUses().get(0);
        assertThat(use.name()).isEqualTo("get_weather");
        assertThat(use.argumentsJson()).isEqualTo("{\"city\":\"北京\",\"days\":3}");
        // 合成 id 的序号规则与非流式一致
        assertThat(use.id()).isEqualTo("call_get_weather_1");
    }

    // ==================================================================
    // 四、自定义协议（demo）
    // ==================================================================

    @Test
    @Order(30)
    @DisplayName("自定义 demo 协议：端点、认证头、请求体、响应体整套换掉")
    void demoProtocolEndToEnd() {
        WireResult result = scenario.demoProtocolText();

        // 端点与认证头都是自定义的：X-Demo-Key / X-Demo-Api，不是 Bearer
        assertThat(result.path()).isEqualTo("/demo/v2/invoke");
        assertThat(result.header("X-Demo-Key")).isEqualTo("sk-demo");
        assertThat(result.header("X-Demo-Api")).isEqualTo("2026-01-01");
        // yml 里的自定义头也下发（X-Demo-Tenant: exp-07）
        assertThat(result.header("X-Demo-Tenant")).isEqualTo("exp-07");
        assertThat(result.header("Authorization")).isNull();

        var body = result.recorded().json();
        // 系统提示在 directive.system，对话在 turns[].who/says
        assertThat(body.path("directive").path("system").asText()).isEqualTo(WireConstants.SYSTEM_PROMPT);
        assertThat(body.path("directive").path("maxTokens").asInt()).isEqualTo(256);
        assertThat(body.path("turns").get(0).path("who").asText()).isEqualTo("user");
        assertThat(body.path("turns").get(0).path("says").asText()).isEqualTo(WireConstants.USER_TEXT);
        // extra 一样覆盖式写入顶层
        assertThat(body.path("vendor_flag").asText()).isEqualTo("exp-07");

        // 响应：out[].value + spend.in/out + outcome
        assertThat(result.response().text()).isEqualTo("demo 协议答：可以骑车。");
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(17);
        assertThat(result.usage().outputTokens()).isEqualTo(6);
        assertThat(result.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(31)
    @DisplayName("自定义 demo 协议：流式用 {\"event\":\"done\"} 收尾，增量照常拼接")
    void demoProtocolStream() {
        StreamResult result = scenario.demoProtocolStream();

        // DemoWire.streamingText(17, 6, "demo ", "协议 ", "流式。") —— 三段的拼接结果
        assertThat(result.text()).isEqualTo("demo 协议 流式。");
        assertThat(result.error()).isNull();
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(17);
        assertThat(result.usage().outputTokens()).isEqualTo(6);
        assertThat(result.sseFrames()).contains("\"event\":\"done\"");
    }

    @Test
    @Order(32)
    @DisplayName("真实行为档案：流式路径收到「非 SSE 的整包响应」时是静默的空回答，不报错")
    void nonSseBodyOnStreamingPathIsSilentlyEmpty() {
        scenario.resetStub();
        // 网关（或写错的桩）对流式请求回了一整包普通 JSON。
        // JdkHttpTransport 只看状态码，于是它被当成"一条事件都没有的 SSE 流"：
        // SseParser 一个事件都解析不出来 → 解码器的 finish() 兜底收尾 →
        // StreamCollector 拿到一个空响应，而 error 是 null。
        scenario.stub().onPath("/v1/chat/completions", request -> StubHttpServer.Reply.json(
                OpenAiWire.textResponse(OpenAiWire.MODEL, "这段正文不会出现在任何地方", "stop", 9, 3)));

        StreamResult result = new WireScenario(models, transport)
                .streamDirect(WireConstants.OPENAI_KEY, WireConstants.request());

        assertThat(result.error()).isNull();
        assertThat(result.text()).isEmpty();
        assertThat(result.usage()).isEqualTo(com.benxin.llm.core.chat.Usage.ZERO);
        // 解码器仍然收尾了（finish() 兜底），所以不是"卡住"，而是"悄悄给了空"
        assertThat(result.response()).isNotNull();
        // 连一个分片都没收到，因此结束原因只能落到 UNKNOWN —— 这也是"上游没按协议回"的信号之一
        assertThat(result.response().finishReason()).isEqualTo(FinishReason.UNKNOWN);
    }

    // ==================================================================
    // 五、装饰器与重试
    // ==================================================================

    @Test
    @Order(40)
    @DisplayName("桩服务先回 503 再回 200：重试确实发生了（1 次重试、2 次请求）")
    void transientFailureIsRetried() {
        RetryResult result = scenario.retryAfterTransientFailure();

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.requestCount()).isEqualTo(2);
        assertThat(result.response()).isNotNull();
        assertThat(result.response().text()).isEqualTo("重试之后成功了。");
    }

    @Test
    @Order(41)
    @DisplayName("400 是确定性失败：一次都不重试")
    void deterministicFailureIsNotRetried() {
        RetryResult result = scenario.noRetryOnDeterministicFailure();

        assertThat(result.attempts()).isZero();
        assertThat(result.requestCount()).isEqualTo(1);
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().statusCode()).isEqualTo(400);
        assertThat(result.failure().isRetryable()).isFalse();
    }

    @Test
    @Order(42)
    @DisplayName("重试用尽：maxRetries=1 时共 2 次请求，最后一次异常被抛出")
    void retriesExhaustedThrowsLastFailure() {
        RetryResult result = scenario.retriesExhausted();

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.requestCount()).isEqualTo(2);
        assertThat(result.response()).isNull();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().statusCode()).isEqualTo(503);
        assertThat(result.failure().isRetryable()).isTrue();
    }

    @Test
    @Order(43)
    @DisplayName("流式：尚未吐出任何内容就失败 → 允许重试")
    void streamingRetryBeforeAnyContent() {
        StreamResult result = scenario.streamingRetryBeforeAnyContent();

        assertThat(result.text()).isEqualTo("重试后的流。");
        assertThat(result.error()).isNull();
        assertThat(result.model()).isInstanceOf(RetryingLlmModel.class);
        assertThat(((RetryingLlmModel) result.model()).attempts()).isEqualTo(1);
        assertThat(result.requestCount()).isEqualTo(2);
    }

    @Test
    @Order(44)
    @DisplayName("流式：已经吐出内容之后才失败 → 不再重试，错误原样转发")
    void streamingDoesNotRetryAfterContentWasEmitted() {
        StreamResult result = scenario.streamingNoRetryAfterContent();

        // 已经交付给调用方的增量无法撤回，重试只会造成重复投递
        assertThat(result.text()).isEqualTo("已经出字了");
        // 错误确实是"断流"这一类，而不是别的失败
        assertThat(result.error()).isInstanceOf(java.io.IOException.class);
        assertThat(result.error().getMessage()).contains("连接");
        // 3 次重试的额度一次都没用掉
        assertThat(((RetryingLlmModel) result.model()).attempts()).isZero();
        // 断流由 ScriptedStreamTransport 精确制造，一次网络都没走
        assertThat(result.scripted()).isNotNull();
        assertThat(result.scripted().seenUrls()).hasSize(1);
    }

    @Test
    @Order(45)
    @DisplayName("CachingLlmModel + RetryingLlmModel 叠加：同请求只打一次上游，unwarp() 能链式取回")
    void decoratorsCanBeStacked() {
        var result = scenario.stackedDecorators();

        assertThat(result.first().text()).isEqualTo("被缓存的回答。");
        assertThat(result.second().text()).isEqualTo("被缓存的回答。");
        // 两次 chat() 只有一次真的打到了桩服务
        assertThat(result.requestCount()).isEqualTo(1);
        assertThat(result.caching().hits()).isEqualTo(1);
        assertThat(result.caching().misses()).isEqualTo(1);
        // 链式取回：外层 → 缓存 → 真正的模型
        assertThat(result.outer().unwrap()).isSameAs(result.caching());
        assertThat(result.caching().unwrap()).isInstanceOf(OpenAiModel.class);
    }

    // ==================================================================
    // 六、传输层
    // ==================================================================

    @Test
    @Order(50)
    @DisplayName("RecordingHttpTransport 记录每一笔往来并落盘")
    void recordingTransportKeepsExchangesAndFiles() {
        assertThat(transport).isInstanceOf(RecordingHttpTransport.class);
        RecordingHttpTransport recording = (RecordingHttpTransport) transport;

        scenario.openAiText();

        assertThat(recording.count()).isPositive();
        RecordingHttpTransport.Exchange last = recording.exchanges().get(recording.count() - 1);
        assertThat(last.method()).isEqualTo("post");
        assertThat(last.status()).isEqualTo(200);
        assertThat(last.request().path()).isEqualTo("/v1/chat/completions");
        assertThat(last.responseBody()).contains("今天风大");
        // 落盘是排障手段：日志文件必须真的存在
        assertThat(recording.files()).hasSize(recording.count());
        assertThat(recording.files().get(recording.count() - 1)).exists();
    }

    @Test
    @Order(51)
    @DisplayName("postStreaming() 返回非流式响应 → 可读的 ModelException，而不是 NPE")
    void postStreamingReturningNonStreamingIsReadable() {
        TransportMisuseResult result = scenario.postStreamingReturningNonStreaming();

        assertThat(result.error()).isInstanceOf(ModelException.class);
        assertThat(result.error().getMessage())
                .contains("postStreaming")
                .contains("非流式响应")
                .contains("TransportResponse.streaming");
        // 报错必须点名是调用方自己的传输层实现有问题
        assertThat(result.transport().streamingReturnsNonStreaming()).isTrue();
    }

    @Test
    @Order(52)
    @DisplayName("ModelCapabilities.streaming=false → Agent 路径走 chat() 同步分支（传输层只被调用 post）")
    void syncBranchIsReachableWhenStreamingIsFalse() {
        SyncBranchResult result = scenario.syncBranchReachableThroughAgentLoop();

        assertThat(result.model().capabilities().streaming()).isFalse();
        // 关键证据：走完整条 Agent 链路之后，传输层上只发生了 post，没有 postStreaming。
        // 分支决策在 DefaultLoopContext#callModel，直接调 model.stream() 是测不到它的。
        assertThat(result.invokedMethods()).containsExactly("post");
        assertThat(result.response()).isNotNull();
        assertThat(result.response().text()).isEqualTo("同步分支回答。");
        assertThat(result.text()).isEqualTo("同步分支回答。");
    }

    @Test
    @Order(53)
    @DisplayName("FakeTransport：一次网络都不走，同一份 ModelConfig 照样能解出响应")
    void fakeTransportNeedsNoNetwork() {
        String body = OpenAiWire.textResponse(OpenAiWire.MODEL, "假传输层的回答", "stop", 4, 2);
        FakeTransport fake = new FakeTransport().post(call -> FakeTransport.json(body));
        ModelConfig config = ModelConfig.builder("fake")
                .protocol(Protocol.OPENAI)
                .baseUrl("http://fake.invalid")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .build();

        OpenAiModel model = new OpenAiModel(config, fake);
        ChatResponse response = model.chat(WireConstants.request());

        assertThat(response.text()).isEqualTo("假传输层的回答");
        assertThat(fake.lastCall().method()).isEqualTo("post");
        // URL 仍然由 Codec 拼好，只是没人真的去连它
        assertThat(fake.lastCall().url()).isEqualTo("http://fake.invalid/v1/chat/completions");
        // 请求体也是 Codec 编码出来的完整报文
        assertThat(fake.lastCall().body()).contains("\"model\":\"stub-gpt\"");
        // 没配 api-key 时 Authorization 头不下发（而不是下发一个空的 Bearer）
        assertThat(fake.lastCall().headers()).doesNotContainKey("Authorization");
    }

    @Test
    @Order(54)
    @DisplayName("ScriptedStreamTransport：帧照常送达；但流尾一定报 IOException（它模拟的是「不会正常关闭的连接」）")
    void scriptedTransportAlwaysFailsAtStreamEnd() {
        ScriptedStreamTransport scripted = ScriptedStreamTransport.ofFrames(
                List.of(OpenAiWire.textChunk(OpenAiWire.MODEL, "甲"),
                        OpenAiWire.textChunk(OpenAiWire.MODEL, "乙")));
        ModelConfig config = ModelConfig.builder("scripted")
                .protocol(Protocol.OPENAI)
                .baseUrl("http://127.0.0.1:1/unused")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .build();
        OpenAiModel model = new OpenAiModel(config, scripted);

        var collector = new com.benxin.llm.core.model.StreamCollector();
        model.stream(WireConstants.request(), collector);

        // 两帧照常送达并被解码器累积
        assertThat(collector.text()).isEqualTo("甲乙");
        // 注意 ofFrames 这个名字容易让人以为"不会失败"：它只是不按帧数设限，流尾照样抛 IOException
        // —— 因为它模拟的是"真实断连"，而断连不会有正常 EOF。
        assertThat(collector.error()).isInstanceOf(java.io.IOException.class);
        // 这里两帧之后已经没有更多分片，解码器的 finish() 在断流之前就跑完了，
        // 所以 onComplete 已经发出 —— 与"帧还没吐完就断"（streamThenFail）是两种不同的结局。
        assertThat(collector.response()).isNotNull();
        assertThat(collector.response().text()).isEqualTo("甲乙");
        assertThat(scripted.seenUrls()).hasSize(1);
    }

    // ==================================================================
    // 七、错误路径
    // ==================================================================

    @Test
    @Order(60)
    @DisplayName("HTTP 状态码 → ModelException 的 statusCode / isRetryable")
    void httpStatusCodesMapToRetryableModelException() {
        ModelException gateway = scenario.statusError(WireConstants.OPENAI_KEY, 502, "{}");
        assertThat(gateway.statusCode()).isEqualTo(502);
        assertThat(gateway.isRetryable()).isTrue();

        ModelException limited = scenario.statusError(WireConstants.OPENAI_KEY, 429, "{}");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.isRetryable()).isTrue();

        ModelException unauthorized = scenario.statusError(WireConstants.OPENAI_KEY, 401, "{}");
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.isRetryable()).isFalse();
    }

    @Test
    @Order(61)
    @DisplayName("HTTP 200 + 错误信封：四个协议都要识别出来（否则会被当成功响应）")
    void errorEnvelopesAreDetectedForAllProtocols() {
        ModelException openai = scenario.errorEnvelope(WireConstants.OPENAI_KEY,
                OpenAiWire.errorEnvelope("invalid_request_error", "模型名不存在"));
        assertThat(openai.getMessage()).contains("模型名不存在");
        // 错误信封走的是 HTTP 200，因此 statusCode 是 0，也就不会被当成可重试
        assertThat(openai.statusCode()).isZero();
        assertThat(openai.isRetryable()).isFalse();

        ModelException anthropic = scenario.errorEnvelope(WireConstants.ANTHROPIC_KEY,
                AnthropicWire.errorEnvelope("invalid_request_error", "max_tokens 超限"));
        assertThat(anthropic.getMessage()).contains("max_tokens 超限");

        ModelException gemini = scenario.errorEnvelope(WireConstants.GEMINI_KEY,
                GeminiWire.apiErrorResponse(400, "INVALID_ARGUMENT", "schema 里有不支持的字段"));
        // Gemini 的错误信封里 status/message 都有，插件把两者一起带上，排障时不必再去翻报文
        assertThat(gemini.getMessage())
                .contains("schema 里有不支持的字段")
                .contains("INVALID_ARGUMENT");

        ModelException responses = scenario.errorEnvelope(WireConstants.RESPONSES_KEY,
                ResponsesWire.errorEnvelope("输入太长", "invalid_request_error"));
        assertThat(responses.getMessage()).contains("输入太长");
    }

    @Test
    @Order(62)
    @DisplayName("错误路径的路由只挂在对应协议的端点上：不会污染之后的场景")
    void errorRoutesDoNotPoisonOtherScenarios() {
        // 先制造一次「桩服务一直回 503」，这正是以前把后续所有场景打成 503 的那个坑
        RetryResult exhausted = scenario.retriesExhausted();
        assertThat(exhausted.failure()).isNotNull();

        // 紧接着跑 Anthropic / Gemini / Responses：它们自己的路由必须还在生效
        assertThat(scenario.anthropicText().response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(scenario.geminiText().response().text()).isEqualTo("今天风大，建议改坐地铁。");
        assertThat(scenario.responsesText().response().text()).isEqualTo("今天风大，建议改坐地铁。");
    }

    // ==================================================================
    // 八、协议标识类型
    // ==================================================================

    @Test
    @Order(70)
    @DisplayName("Protocol.of(null/\"\"/空白) 直接抛异常，不回落到 openai")
    void protocolOfBlankIdRejected() {
        assertThatThrownBy(() -> Protocol.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Protocol.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Protocol.of("   ")).isInstanceOf(IllegalArgumentException.class);
        // 合法 id（含前后空格）被归一化后正常接受
        assertThat(Protocol.of(" OpenAI ")).isEqualTo(Protocol.OPENAI);

        var records = WireScenario.protocolBlankIdRejected();
        assertThat(records.messages()).containsOnlyKeys(
                "Protocol.of(null)", "Protocol.of(\"\")", "Protocol.of(\"   \")", "Protocol.of(\" OpenAI \")");
        assertThat(records.allBlankRejected()).isTrue();
        assertThat(records.nonBlankAccepted()).isTrue();
        // 空白入参的报错消息是可读的（含"协议 id 不能为空"），而不是空指针或一句 "null"
        assertThat(records.messages().get("Protocol.of(\"   \")")).contains("协议 id 不能为空");
        assertThat(records.messages().get("Protocol.of(null)")).contains("协议 id 不能为空");
        // 合法的那个记成"没有抛异常"的占位文本（Map.copyOf 不接受 null 值）
        assertThat(records.messages().get("Protocol.of(\" OpenAI \")"))
                .isEqualTo(ProtocolProbes.IllegalArgumentRecords.NO_EXCEPTION);
    }

    @Test
    @Order(71)
    @DisplayName("Protocol 标识类型：驻留、大小写归一、内置判定、自定义 id 不被改写")
    void protocolIdentitySemantics() {
        ProtocolProbes.ProtocolIdentity identity = WireScenario.protocolIdentity();

        assertThat(identity.internedSameInstance()).isTrue();
        assertThat(identity.caseInsensitive()).isTrue();
        assertThat(identity.builtinIds()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(identity.geminiIsBuiltin()).isTrue();
        // demo 是自定义协议：Protocol.of 接受任意非空 id，是否可用由注册表决定
        assertThat(identity.demoIsBuiltin()).isFalse();
        assertThat(identity.customId()).isEqualTo("my-protocol");
        // 分隔符不会被替换掉
        assertThat(Protocol.of("my-protocol").id()).isEqualTo("my-protocol");
        assertThat(Protocol.builtins()).hasSize(4);
        assertThat(Protocol.isBuiltin(null)).isFalse();
    }

    @Test
    @Order(72)
    @DisplayName("未知协议在装配期失败，并列出所有已注册协议")
    void unknownProtocolFailsFastAtAssemblyTime() {
        ProtocolProbes.MissingProtocolRecord record = WireScenario.missingProtocolFailsFast("bedrock");

        assertThat(record.registered()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(record.message())
                .contains("bedrock")
                .contains("未知协议")
                .contains("ProtocolCodec")
                .contains("openai");
    }

    @Test
    @Order(73)
    @DisplayName("同名覆盖内置协议：后注册的生效，且内置实现没有被换回来")
    void overridingBuiltinProtocolKeepsUserImplementation() {
        ProtocolProbes.OverrideRecord record = WireScenario.overrideBuiltinProtocol();

        // 覆盖是新增键集合不变的"替换"，不是新增协议
        assertThat(record.before()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(record.after()).containsExactly("openai", "anthropic", "gemini", "responses");
        assertThat(record.resolvedClass())
                .isEqualTo("com.benxin.exp.models.custom.OverridingOpenAiCodec");
        assertThat(record.isUserInstance()).isTrue();
        assertThat(record.isOverrideImplementation()).isTrue();
    }

    @Test
    @Order(74)
    @DisplayName("覆盖版 openai 生效：端点与认证头整套换成自定义实现")
    void overridingCodecActuallyTakesEffect() {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
        registry.register(new com.benxin.exp.models.custom.OverridingOpenAiCodec());

        StubHttpServer stub = scenario.stub();
        stub.reset();
        stub.onPath("/override/v9/talk", request -> StubHttpServer.Reply.json("{\"reply\":\"覆盖版回答\"}"));

        ModelConfig config = ModelConfig.builder("override")
                .protocol(Protocol.OPENAI)
                .baseUrl(stub.baseUrl())
                .apiKey("sk-override")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .build();
        var model = com.benxin.llm.core.model.ModelFactory.create(config, transport, registry);
        ChatResponse response = model.chat(WireConstants.request());

        assertThat(response.text()).isEqualTo("覆盖版回答");
        // 路径是覆盖实现自己的（内置 OpenAI 走 /v1/chat/completions）
        assertThat(stub.lastRequest().orElseThrow().path()).isEqualTo("/override/v9/talk");
        assertThat(stub.lastRequest().orElseThrow().header("X-Override-Key")).isEqualTo("sk-override");
        assertThat(stub.lastRequest().orElseThrow().header("Authorization")).isNull();
    }

    @Test
    @Order(75)
    @DisplayName("自定义协议 Codec 的 protocol() 返回 null / 空串时，注册直接失败并点名实现类")
    void codecWithBlankProtocolIdIsRejected() {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();

        assertThatThrownBy(() -> registry.register(new ProtocolCodec() {
            @Override
            public Protocol protocol() {
                return Protocol.of("");
            }

            @Override
            public String endpoint(ModelConfig config, com.benxin.llm.core.chat.ChatRequest request) {
                return "";
            }

            @Override
            public java.util.Map<String, String> headers(ModelConfig config) {
                return java.util.Map.of();
            }

            @Override
            public String encode(com.benxin.llm.core.chat.ChatRequest request, ModelConfig config) {
                return "{}";
            }

            @Override
            public ChatResponse decode(String responseBody, ModelConfig config) {
                return ChatResponse.builder().build();
            }

            @Override
            public com.benxin.llm.core.protocol.StreamDecoder newStreamDecoder(
                    ModelConfig config, com.benxin.llm.core.model.LlmStreamHandler handler) {
                throw new UnsupportedOperationException();
            }
        }))
                .isInstanceOf(IllegalArgumentException.class)
                // 消息里必须点名"是哪个 Codec 干的好事"，否则使用者还得自己翻
                .hasMessageContaining("protocol() 非法");

        assertThatThrownBy(() -> registry.register(new ProtocolCodec() {
            @Override
            public Protocol protocol() {
                return null;
            }

            @Override
            public String endpoint(ModelConfig config, com.benxin.llm.core.chat.ChatRequest request) {
                return "";
            }

            @Override
            public java.util.Map<String, String> headers(ModelConfig config) {
                return java.util.Map.of();
            }

            @Override
            public String encode(com.benxin.llm.core.chat.ChatRequest request, ModelConfig config) {
                return "{}";
            }

            @Override
            public ChatResponse decode(String responseBody, ModelConfig config) {
                return ChatResponse.builder().build();
            }

            @Override
            public com.benxin.llm.core.protocol.StreamDecoder newStreamDecoder(
                    ModelConfig config, com.benxin.llm.core.model.LlmStreamHandler handler) {
                throw new UnsupportedOperationException();
            }
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("返回 null");
    }

    @Test
    @Order(76)
    @DisplayName("能力声明取自 Codec：ModelFactory 装配出的模型带着 Codec 声明的那一份")
    void capabilitiesComeFromCodec() {
        var model = scenario.model(WireConstants.GEMINI_KEY);
        ModelCapabilities capabilities = model.capabilities();

        assertThat(capabilities).isEqualTo(
                com.benxin.llm.core.protocol.gemini.GeminiCodec.RECOMMENDED_CAPABILITIES);
        assertThat(capabilities.streaming()).isTrue();
        assertThat(capabilities.vision()).isTrue();
        assertThat(capabilities.maxContextTokens()).isEqualTo(1_000_000);

        // demo 协议声明 vision=false / parallelToolCalls=false，与内置的取值刻意不同
        ModelCapabilities demo = scenario.model(WireConstants.DEMO_KEY).capabilities();
        assertThat(demo.vision()).isFalse();
        assertThat(demo.parallelToolCalls()).isFalse();
        assertThat(demo.thinking()).isTrue();
    }
}
