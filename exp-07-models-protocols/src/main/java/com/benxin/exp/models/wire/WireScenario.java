package com.benxin.exp.models.wire;

import com.benxin.exp.common.wire.AnthropicWire;
import com.benxin.exp.common.wire.GeminiWire;
import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.ResponsesWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.exp.common.wire.StubHttpServer.Recorded;
import com.benxin.exp.common.wire.StubHttpServer.Reply;
import com.benxin.exp.models.custom.DemoWire;
import com.benxin.exp.models.custom.OverridingOpenAiCodec;
import com.benxin.exp.models.decorator.CachingLlmModel;
import com.benxin.exp.models.support.PrettyJson;
import com.benxin.exp.models.support.StubRegistry;
import com.benxin.exp.models.transport.FakeTransport;
import com.benxin.exp.models.transport.ScriptedStreamTransport;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.StreamCollector;
import com.benxin.llm.core.model.impl.AnthropicModel;
import com.benxin.llm.core.model.impl.GeminiModel;
import com.benxin.llm.core.model.impl.OpenAiModel;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本模块的"引擎"：<b>所有线级演示的桩服务编排与请求发起点</b>。
 *
 * <p>把编排收在一个类里，是为了让演示（{@code ProtocolWireDemo}）与测试
 * （{@code ProtocolWireTest}）跑的是<b>同一段代码</b>：测试断言的就是演示打印出来的那些东西，
 * 于是"演示里看到的现象"与"回归用例守住的行为"永远不会分叉。</p>
 *
 * <h2>为什么每个场景都要 reset</h2>
 *
 * <p>桩服务是共享的，请求是累计录制的。每个场景开头 {@code reset()} 一次，
 * 于是"这一节抓到的请求"就是确定的第 1 笔 —— 测试因此可以断言
 * {@code requestCount() == 1} 这种强条件，而不是"找一找有没有"。</p>
 */
public class WireScenario {

    private final StubHttpServer stub;
    private final ModelRegistry models;
    private final HttpTransport transport;

    public WireScenario(StubHttpServer stub, ModelRegistry models, HttpTransport transport) {
        this.stub = stub;
        this.models = models;
        this.transport = transport;
    }

    /** 默认用共享桩服务与容器里的注册表/传输层。 */
    public WireScenario(ModelRegistry models, HttpTransport transport) {
        this(StubRegistry.stub(), models, transport);
    }

    public StubHttpServer stub() {
        return stub;
    }

    /**
     * 进入一个新场景：清掉录制、默认队列<b>与路由</b>。
     *
     * <p>为什么要连路由一起清：桩服务的路由是<b>按注册顺序</b>匹配的，一条宽泛的路由
     * （例如"任何路径都回 503"）会一直留在表里把之后所有场景的请求截胡。示例里这是真实发生过的
     * 故障 —— "错误路径"那一节跑完之后，Anthropic / Gemini / Responses 三节全部收到 503，
     * 而它们自己的路由其实还在表里、只是排在后面；演示里表现为"流式增量全是空的"，
     * 排查成本很高。场景之间本该彼此独立，因此每个场景开头连路由一起清。</p>
     */
    public WireScenario resetStub() {
        stub.reset();
        stub.clearRoutes();
        return this;
    }

    public ModelRegistry models() {
        return models;
    }

    public HttpTransport transport() {
        return transport;
    }

    /** 按注册名取模型。 */
    public LlmModel model(String name) {
        return models.get(name);
    }

    // ==================================================================
    // 一、四种协议的非流式"线级"演示
    // ==================================================================

    /** OpenAI Chat Completions：{@code POST /v1/chat/completions}。 */
    public WireResult openAiText() {
        resetStub();
        String body = OpenAiWire.textResponse(OpenAiWire.MODEL, "今天风大，建议改坐地铁。", "stop", 42, 7);
        stub.onPath("/v1/chat/completions", request -> Reply.json(body));
        LlmModel model = model(WireConstants.OPENAI_KEY);
        return result("OpenAI Chat Completions · 文本", "/v1/chat/completions", model,
                model.chat(WireConstants.request()), body, mark());
    }

    /** Anthropic Messages：{@code POST /v1/messages}。 */
    public WireResult anthropicText() {
        resetStub();
        String body = AnthropicWire.textResponse(AnthropicWire.MODEL, "今天风大，建议改坐地铁。",
                "end_turn", 37, 11, 5);
        stub.onPath("/v1/messages", request -> Reply.json(body));
        LlmModel model = model(WireConstants.ANTHROPIC_KEY);
        return result("Anthropic Messages · 文本", "/v1/messages", model,
                model.chat(WireConstants.request()), body, mark());
    }

    /** Gemini：{@code POST /v1beta/models/{model}:generateContent}。 */
    public WireResult geminiText() {
        resetStub();
        String body = GeminiWire.textResponse("今天风大，建议改坐地铁。", 29, 13, 4, 0, "STOP");
        stub.onSuffix(":generateContent", request -> Reply.json(body));
        LlmModel model = model(WireConstants.GEMINI_KEY);
        return result("Gemini generateContent · 文本", ":generateContent", model,
                model.chat(WireConstants.request()), body, mark());
    }

    /** OpenAI Responses API：{@code POST /v1/responses}。 */
    public WireResult responsesText() {
        resetStub();
        String body = ResponsesWire.textResponse("今天风大，建议改坐地铁。", 21, 9);
        stub.onPath("/v1/responses", request -> Reply.json(body));
        LlmModel model = model(WireConstants.RESPONSES_KEY);
        return result("OpenAI Responses API · 文本", "/v1/responses", model,
                model.chat(WireConstants.request()), body, mark());
    }

    // ==================================================================
    // 二、工具调用（一次完整的：请求侧声明 + 响应侧调用）
    // ==================================================================

    public WireResult openAiTool() {
        resetStub();
        String body = OpenAiWire.toolCallsResponse(OpenAiWire.MODEL,
                new OpenAiWire.ToolCall("call_w1", "get_weather", "{\"city\":\"北京\"}"));
        stub.onPath("/v1/chat/completions", request -> Reply.json(body));
        LlmModel model = model(WireConstants.OPENAI_KEY);
        return result("OpenAI · 工具调用", "/v1/chat/completions", model,
                model.chat(WireConstants.toolRequest()), body, mark());
    }

    public WireResult anthropicTool() {
        resetStub();
        String body = AnthropicWire.toolUseResponse(AnthropicWire.MODEL,
                List.of(new AnthropicWire.ToolUse("toolu_w1", "get_weather", "{\"city\":\"北京\"}")), 33, 15);
        stub.onPath("/v1/messages", request -> Reply.json(body));
        LlmModel model = model(WireConstants.ANTHROPIC_KEY);
        return result("Anthropic · 工具调用", "/v1/messages", model,
                model.chat(WireConstants.toolRequest()), body, mark());
    }

    public WireResult geminiTool() {
        resetStub();
        String body = GeminiWire.functionCallResponse("get_weather", "{\"city\":\"北京\"}", 27, 10);
        stub.onSuffix(":generateContent", request -> Reply.json(body));
        LlmModel model = model(WireConstants.GEMINI_KEY);
        return result("Gemini · 函数调用", ":generateContent", model,
                model.chat(WireConstants.toolRequest()), body, mark());
    }

    public WireResult responsesTool() {
        resetStub();
        String body = ResponsesWire.functionCallResponse("call_w1", "get_weather", "{\"city\":\"北京\"}", 19, 8);
        stub.onPath("/v1/responses", request -> Reply.json(body));
        LlmModel model = model(WireConstants.RESPONSES_KEY);
        return result("Responses · 函数调用", "/v1/responses", model,
                model.chat(WireConstants.toolRequest()), body, mark());
    }

    // ==================================================================
    // 三、工具结果回灌（四个协议把"结果"放在四个不同的位置）
    // ==================================================================

    public WireResult toolResultRoundTrip(String protocol) {
        resetStub();
        String responseBody = routeTextFor(protocol, "已经查过天气了。");
        LlmModel model = model(keyOf(protocol));
        return result(protocol + " · 工具结果回灌", pathOf(protocol), model,
                model.chat(WireConstants.toolResultRequest()), responseBody, mark());
    }

    // ==================================================================
    // 四、多模态（同一张图，四种编码）
    // ==================================================================

    public WireResult imageRoundTrip(String protocol) {
        return imageRoundTrip(protocol, true);
    }

    /**
     * 带图请求。
     *
     * @param inline true = 内联 base64（四个协议都能表达）；false = 外链 URL
     */
    public WireResult imageRoundTrip(String protocol, boolean inline) {
        resetStub();
        String responseBody = routeTextFor(protocol, "图里是一个透明像素。");
        LlmModel model = model(keyOf(protocol));
        ChatRequest request = inline
                ? WireConstants.request(true, false, true, false, false)
                : WireConstants.request(true, false, false, true, false);
        return result(protocol + " · 图片编码（" + (inline ? "内联 data URI" : "外链 URL") + "）",
                pathOf(protocol), model, model.chat(request), responseBody, mark());
    }

    // ==================================================================
    // 五、流式（SSE）：结束语义 + 分片拼装
    // ==================================================================

    /** OpenAI 流：正文增量 + {@code [DONE]} + {@code stream_options.include_usage} 的用量帧。 */
    public StreamResult openAiStream() {
        resetStub();
        StubHttpServer.Reply reply = Reply.sseFrames(OpenAiWire.streamingText(
                OpenAiWire.MODEL, 42, 7, "今天", "风大", "，建议改坐地铁。"));
        stub.onPath("/v1/chat/completions", request -> reply);
        return stream("OpenAI · 文本流（[DONE] 收尾）", WireConstants.OPENAI_KEY, WireConstants.request(), reply);
    }

    /** OpenAI 的 {@code reasoning_content}（DeepSeek-R1 形态）：思维链与正文分开累积。 */
    public StreamResult openAiReasoningStream() {
        resetStub();
        List<String> frames = new ArrayList<>();
        frames.add(OpenAiWire.reasoningChunk(OpenAiWire.MODEL, "先看风速……"));
        frames.add(OpenAiWire.reasoningChunk(OpenAiWire.MODEL, "再看温度。"));
        frames.add(OpenAiWire.textChunk(OpenAiWire.MODEL, "结论：不适合骑车。"));
        frames.add(OpenAiWire.finishChunk(OpenAiWire.MODEL, "stop"));
        frames.add(OpenAiWire.usageChunk(OpenAiWire.MODEL, 42, 7));
        frames.add(OpenAiWire.doneFrame());
        StubHttpServer.Reply reply = Reply.sseFrames(frames);
        stub.onPath("/v1/chat/completions", request -> reply);
        return stream("OpenAI · reasoning_content + include_usage", WireConstants.OPENAI_KEY,
                WireConstants.request(), reply);
    }

    /** OpenAI 工具参数按 {@code index} 分片：{@code arguments} 是拼出来的。 */
    public StreamResult openAiToolArgumentsStream() {
        resetStub();
        String args = "{\"city\":\"北京\",\"days\":3}";
        List<String> fragments = OpenAiWire.splitInto(args, 5);
        StubHttpServer.Reply reply = Reply.sseFrames(
                OpenAiWire.streamingToolCall(OpenAiWire.MODEL, 0, "call_w1", "get_weather", fragments));
        stub.onPath("/v1/chat/completions", request -> reply);
        return stream("OpenAI · 工具参数按 index 分片", WireConstants.OPENAI_KEY,
                WireConstants.toolRequest(), reply);
    }

    /** Anthropic 流：强类型事件 + {@code message_stop} 收尾。 */
    public StreamResult anthropicStream() {
        resetStub();
        StubHttpServer.Reply reply = Reply.sseFrames(AnthropicWire.streamingText(
                AnthropicWire.MODEL, 37, 11, "今天", "风大", "，建议改坐地铁。"));
        stub.onPath("/v1/messages", request -> reply);
        return stream("Anthropic · 文本流（message_stop 收尾）", WireConstants.ANTHROPIC_KEY,
                WireConstants.request(), reply);
    }

    /** Anthropic：{@code thinking} 块 + {@code signature} 分片 + {@code message_delta} 的累计用量换算。 */
    public StreamResult anthropicThinkingStream() {
        resetStub();
        List<String> frames = new ArrayList<>();
        // output_tokens 首帧给 1，message_delta 给累计 23 → 上层应当看到增量 22，合计 23
        frames.add(AnthropicWire.messageStart(AnthropicWire.MODEL, 41, 1, 9));
        frames.add(AnthropicWire.thinkingBlockStart(0));
        frames.add(AnthropicWire.thinkingDelta(0, "先判断风向"));
        frames.add(AnthropicWire.thinkingDelta(0, "，再看气温。"));
        frames.add(AnthropicWire.signatureDelta(0, "sig-part-1"));
        frames.add(AnthropicWire.signatureDelta(0, "-sig-part-2"));
        frames.add(AnthropicWire.blockStop(0));
        frames.add(AnthropicWire.textBlockStart(1));
        frames.add(AnthropicWire.textDelta(1, "建议改坐地铁。"));
        frames.add(AnthropicWire.blockStop(1));
        frames.add(AnthropicWire.messageDelta("end_turn", 23));
        frames.add(AnthropicWire.messageStop());
        StubHttpServer.Reply reply = Reply.sseFrames(frames);
        stub.onPath("/v1/messages", request -> reply);
        return stream("Anthropic · thinking + signature + 用量增量换算", WireConstants.ANTHROPIC_KEY,
                WireConstants.request(), reply);
    }

    /** Anthropic 工具参数按 {@code block index} 的 {@code input_json_delta.partial_json} 分片。 */
    public StreamResult anthropicToolArgumentsStream() {
        resetStub();
        String args = "{\"city\":\"北京\",\"days\":3}";
        StubHttpServer.Reply reply = Reply.sseFrames(AnthropicWire.streamingToolUse(
                AnthropicWire.MODEL, 33, 15, "toolu_w1", "get_weather", AnthropicWire.splitInto(args, 7)));
        stub.onPath("/v1/messages", request -> reply);
        return stream("Anthropic · input_json_delta 分片", WireConstants.ANTHROPIC_KEY,
                WireConstants.toolRequest(), reply);
    }

    /** Gemini 流：没有 {@code [DONE]}，靠连接关闭收尾。 */
    public StreamResult geminiStream() {
        resetStub();
        StubHttpServer.Reply reply = Reply.sseFrames(GeminiWire.streamingText(
                29, 13, "今天", "风大", "，建议改坐地铁。"));
        stub.onSuffix(":streamGenerateContent", request -> reply);
        return stream("Gemini · 文本流（无 [DONE]，靠流关闭）", WireConstants.GEMINI_KEY,
                WireConstants.request(), reply);
    }

    /** Gemini：{@code functionCall} 一次性给全（不存在参数分片）。 */
    public StreamResult geminiFunctionCallStream() {
        resetStub();
        StubHttpServer.Reply reply = Reply.sseFrames(
                GeminiWire.streamingFunctionCall("get_weather", "{\"city\":\"北京\",\"days\":3}", 27, 10));
        stub.onSuffix(":streamGenerateContent", request -> reply);
        return stream("Gemini · functionCall 一次性给全", WireConstants.GEMINI_KEY,
                WireConstants.toolRequest(), reply);
    }

    /** Responses 流：命名事件，没有 {@code [DONE]}，靠 {@code response.completed} 收尾。 */
    public StreamResult responsesStream() {
        resetStub();
        List<String> frames = new ArrayList<>();
        frames.add(ResponsesWire.createdFrame());
        frames.add(ResponsesWire.reasoningDeltaFrame("先看风速……"));
        frames.add(ResponsesWire.textDeltaFrame("今天"));
        frames.add(ResponsesWire.textDeltaFrame("风大。"));
        frames.add(ResponsesWire.completedFrame(21, 9));
        StubHttpServer.Reply reply = Reply.sseFrames(frames);
        stub.onPath("/v1/responses", request -> reply);
        return stream("Responses · 命名事件（无 [DONE]）", WireConstants.RESPONSES_KEY,
                WireConstants.request(), reply);
    }

    /** Responses 工具参数按 {@code output_index} 分片 + {@code output_item.done} 的权威完整值。 */
    public StreamResult responsesToolArgumentsStream() {
        resetStub();
        String args = "{\"city\":\"北京\",\"days\":3}";
        List<String> frames = new ArrayList<>();
        frames.add(ResponsesWire.createdFrame());
        frames.add(ResponsesWire.functionCallAddedFrame(0, "call_w1", "get_weather"));
        for (String fragment : split(args, 4)) {
            frames.add(ResponsesWire.functionArgsDeltaFrame(0, fragment));
        }
        frames.add(ResponsesWire.functionCallDoneFrame(0, "call_w1", "get_weather", args));
        frames.add(ResponsesWire.completedFrame(19, 8));
        StubHttpServer.Reply reply = Reply.sseFrames(frames);
        stub.onPath("/v1/responses", request -> reply);
        return stream("Responses · output_index 分片 + item.done", WireConstants.RESPONSES_KEY,
                WireConstants.toolRequest(), reply);
    }

    /** 自定义 demo 协议：端到端跑通一次模型调用。 */
    public WireResult demoProtocolText() {
        resetStub();
        String body = DemoWire.textResponse("demo 协议答：可以骑车。", 17, 6);
        stub.onPath("/demo/v2/invoke", request -> Reply.json(body));
        LlmModel model = model(WireConstants.DEMO_KEY);
        return result("自定义 demo 协议 · 文本", "/demo/v2/invoke", model,
                model.chat(WireConstants.request()), body, mark());
    }

    /** 自定义 demo 协议的流式。 */
    public StreamResult demoProtocolStream() {
        resetStub();
        StubHttpServer.Reply reply = Reply.sseFrames(
                DemoWire.streamingText(17, 6, "demo ", "协议 ", "流式。"));
        stub.onPath("/demo/v2/invoke", request -> reply);
        return stream("自定义 demo 协议 · 流式", WireConstants.DEMO_KEY, WireConstants.request(), reply);
    }

    // ==================================================================
    // 六、错误路径
    // ==================================================================

    /**
     * HTTP 503：可重试的瞬时错误。
     *
     * <p>路由<b>只挂在当前协议自己的端点上</b>，而不是 {@code on(request -> true, …)} 这种
     * "全匹配"。原因是一次全匹配路由会留在桩服务的路由表里，把后面所有场景的请求都截胡 ——
     * 演示里这是真实发生过的故障：错误路径那一节跑完之后，Anthropic / Gemini / Responses
     * 三节全部收到 503，而它们自己的路由其实还在表里、只是排在后面。</p>
     */
    public ModelException statusError(String protocolKey, int status, String body) {
        resetStub();
        onProtocolPath(protocolKey, request -> Reply.json(status, body));
        return expectFailure(protocolKey);
    }

    /** 网关返回 HTTP 200，但 body 是错误信封 —— 四个协议都必须识别出来。 */
    public ModelException errorEnvelope(String protocolKey, String envelope) {
        resetStub();
        onProtocolPath(protocolKey, request -> Reply.json(envelope));
        return expectFailure(protocolKey);
    }

    /** 发起一次调用并取回它抛出的 {@link ModelException}；调用成功本身就是失败。 */
    private ModelException expectFailure(String protocolKey) {
        int before = stub.requestCount();
        try {
            model(protocolKey).chat(WireConstants.request());
        } catch (ModelException e) {
            return e;
        }
        throw new AssertionError("期望抛出 ModelException，但调用成功了（" + protocolKey
                + "，桩服务收到 " + (stub.requestCount() - before) + " 次请求）");
    }

    // ==================================================================
    // 七、重试
    // ==================================================================

    /**
     * 桩服务先回 503、再回 200，证明"重试确实发生了"。
     *
     * <p>用桩服务的<b>默认队列</b>（{@code enqueue}）而不是路由：同一路径的两次请求
     * 需要拿到两个不同的回复，FIFO 队列正是为此准备的。</p>
     *
     * @return 重试装饰器本身（可以读 {@code attempts()}）与最终响应
     */
    public RetryResult retryAfterTransientFailure() {
        resetStub();
        stub.enqueue(Reply.json(503, "{\"error\":{\"message\":\"上游繁忙\"}}"));
        stub.enqueue(Reply.json(OpenAiWire.textResponse(OpenAiWire.MODEL, "重试之后成功了。", "stop", 12, 4)));
        RetryingLlmModel retrying = new RetryingLlmModel(model(WireConstants.OPENAI_KEY), 2, 1L, 2.0d);
        ChatResponse response = retrying.chat(WireConstants.request());
        return new RetryResult(retrying, response, stub.requestCount());
    }

    /** 400 属于确定性失败：不该重试。 */
    public RetryResult noRetryOnDeterministicFailure() {
        resetStub();
        stub.enqueue(Reply.json(400, "{\"error\":{\"message\":\"参数不合法\"}}"));
        RetryingLlmModel retrying = new RetryingLlmModel(model(WireConstants.OPENAI_KEY), 3, 1L, 2.0d);
        ModelException failure = null;
        try {
            retrying.chat(WireConstants.request());
        } catch (ModelException e) {
            failure = e;
        }
        return new RetryResult(retrying, failure, stub.requestCount());
    }

    /** 重试用尽：503 一直回，最终把最后一次异常抛出去。 */
    public RetryResult retriesExhausted() {
        resetStub();
        // 同样只挂在 openai 自己的端点上：这一节与"错误路径"节共用桩服务，
        // 一条全匹配路由会污染之后的所有场景。
        stub.onPath(stubPathOf(WireConstants.OPENAI_KEY),
                request -> Reply.json(503, "{\"error\":{\"message\":\"一直繁忙\"}}"));
        RetryingLlmModel retrying = new RetryingLlmModel(model(WireConstants.OPENAI_KEY), 1, 1L, 2.0d);
        ModelException failure = null;
        try {
            retrying.chat(WireConstants.request());
        } catch (ModelException e) {
            failure = e;
        }
        return new RetryResult(retrying, failure, stub.requestCount());
    }

    /** 流式：尚未吐出任何内容就失败 → 允许重试。 */
    public StreamResult streamingRetryBeforeAnyContent() {
        resetStub();
        stub.enqueue(Reply.json(503, "{\"error\":{\"message\":\"上游繁忙\"}}"));
        stub.enqueue(Reply.sseFrames(OpenAiWire.streamingText(OpenAiWire.MODEL, 12, 4, "重试后", "的流。")));
        RetryingLlmModel retrying = new RetryingLlmModel(model(WireConstants.OPENAI_KEY), 2, 1L, 2.0d);
        StreamCollector collector = new StreamCollector();
        retrying.stream(WireConstants.request(), collector);
        return new StreamResult("重试：内容尚未下发", null, collector, stub.requestCount(), retrying, null, null);
    }

    /**
     * 流式：<b>已经吐出内容之后</b>才失败 → 不再重试，错误原样转发。
     *
     * <p>用 {@link ScriptedStreamTransport} 精确控制"交付完第 1 帧之后断开"，
     * 因为真实服务端"写一半就断连"的时点不可控。</p>
     */
    public StreamResult streamingNoRetryAfterContent() {
        resetStub();
        List<String> frames = List.of(OpenAiWire.textChunk(OpenAiWire.MODEL, "已经出字了"));
        ScriptedStreamTransport scripted = ScriptedStreamTransport.streamThenFail(frames);
        ModelConfig config = ModelConfig.builder("openai-demo")
                .protocol(Protocol.OPENAI)
                .baseUrl("http://127.0.0.1:1/unused")
                .model(OpenAiWire.MODEL)
                .build();
        LlmModel inner = new OpenAiModel(config, scripted);
        RetryingLlmModel retrying = new RetryingLlmModel(inner, 3, 1L, 2.0d);
        StreamCollector collector = new StreamCollector();
        retrying.stream(WireConstants.request(), collector);
        return new StreamResult("重试：内容已下发后失败", null, collector, 0, retrying, scripted, null);
    }

    // ==================================================================
    // 八、ModelCapabilities 与"同步分支"
    // ==================================================================

    /**
     * 用<b>真实 Agent 路径</b>（{@code DefaultLoopContext#callModel}）证明
     * {@code ModelCapabilities.streaming=false} 时确实改走 {@code chat()} 同步分支。
     *
     * <h2>为什么必须是 Agent 路径，而不是直接调 model.stream()</h2>
     *
     * <p>分支决策发生在 {@code DefaultLoopContext#callModel} 里：它读
     * {@code model().capabilities().streaming()} 决定调 {@code stream()} 还是 {@code chat()}。
     * 直接调 {@code model.stream(...)} 会把这一步绕过去 —— {@code HttpLlmModel.stream()}
     * 恒走 {@code postStreaming()}，与能力声明毫无关系。</p>
     *
     * <p>本示例最初就是这么写的：演示里打印"传输层上发生的调用应是 post"，实测却是
     * {@code postStreaming}，"只实现 post() 也能在 Agent 路径上跑通"这句结论并没有被验证。</p>
     *
     * @return 传输层记录下来的调用（断言里面只应有 {@code post}，没有 {@code postStreaming}）
     */
    public SyncBranchResult syncBranchReachableThroughAgentLoop() {
        String body = OpenAiWire.textResponse(OpenAiWire.MODEL, "同步分支回答。", "stop", 9, 3);
        FakeTransport fake = new FakeTransport()
                .post(call -> TransportResponse.of(200, Map.of("Content-Type", List.of("application/json")), body))
                // 故意把这个"不支持流式"的传输层的 postStreaming 也实现成非流式：
                // 若分支判断被改错、误走了流式，插件会报出可读的 ModelException，
                // 于是"分支没走到"这件事同样会被立刻发现，而不是悄悄给出空回答。
                .streamingReturnsNonStreaming(body);
        ModelConfig config = ModelConfig.builder("sync-branch")
                .protocol(Protocol.OPENAI)
                .baseUrl("http://fake.invalid")
                .model(OpenAiWire.MODEL)
                .maxRetries(0)
                .build();
        // 三参构造器：显式声明"这条线不支持流式"，LoopContext 据此改走同步分支
        LlmModel model = new OpenAiModel(config, fake, ModelCapabilities.builder()
                .streaming(false)
                .toolCalling(false)
                .maxContextTokens(32_000)
                .build());

        Agent agent = Agent.builder("sync-branch-agent", model, BuiltinLoops.defaultRegistry().get("dsh-minimal"))
                .systemPrompt("你是同步分支的测试助手。")
                .memory(false)
                .listener(AgentListener.noop())
                .build();
        AgentResult result = agent.call("走同步分支");
        return new SyncBranchResult(fake, model, lastAssistant(result), result.text());
    }

    /** Agent 结果里最后一条 assistant 消息；没有则返回 null。 */
    private static ChatMessage lastAssistant(AgentResult result) {
        ChatMessage last = null;
        for (ChatMessage message : result.messages()) {
            if (message.role() == Role.ASSISTANT) {
                last = message;
            }
        }
        return last;
    }
    /** 传输层在 {@code postStreaming()} 里返回非流式响应 → 必须是可读的 ModelException。 */
    public TransportMisuseResult postStreamingReturningNonStreaming() {
        FakeTransport fake = new FakeTransport()
                .streamingReturnsNonStreaming(OpenAiWire.textResponse(OpenAiWire.MODEL, "我不该出现在这里", "stop", 1, 1));
        ModelConfig config = ModelConfig.builder("openai-demo")
                .protocol(Protocol.OPENAI)
                .baseUrl("http://fake.invalid")
                .model(OpenAiWire.MODEL)
                .build();
        LlmModel model = new OpenAiModel(config, fake);
        AtomicReference<Throwable> error = new AtomicReference<>();
        model.stream(WireConstants.request(), new LlmStreamHandler() {
            @Override
            public void onError(Throwable cause) {
                error.set(cause);
            }
        });
        return new TransportMisuseResult(fake, error.get());
    }

    /**
     * {@code Protocol.of(null / "")} 直接失败，而不是悄悄回落到 openai。
     *
     * <p>这条口径值得单独演示：如果一个 {@code ProtocolCodec#protocol()} 返回空串就被当成
     * {@code openai}，那它会把<b>全应用的默认通道</b>悄悄换掉，而且不报任何错。</p>
     */
    public static ProtocolProbes.IllegalArgumentRecords protocolBlankIdRejected() {
        ProtocolProbes.IllegalArgumentRecords records = new ProtocolProbes.IllegalArgumentRecords();
        records.add("Protocol.of(null)", ofOrNull(null));
        records.add("Protocol.of(\"\")", ofOrNull(""));
        records.add("Protocol.of(\"   \")", ofOrNull("   "));
        records.add("Protocol.of(\" OpenAI \")", ofOrNull(" OpenAI "));
        return records;
    }

    /** {@code Protocol} 标识类型自身的语义：驻留、大小写归一、内置判定。 */
    public static ProtocolProbes.ProtocolIdentity protocolIdentity() {
        return new ProtocolProbes.ProtocolIdentity(
                Protocol.OPENAI == Protocol.of("openai"),
                Protocol.of("OpenAI") == Protocol.of("OPENAI"),
                Protocol.builtinIds(),
                Protocol.isBuiltin(Protocol.GEMINI),
                Protocol.isBuiltin(Protocol.of("demo")),
                Protocol.of("my-protocol").id());
    }

    /** 调用并取回异常消息；没有抛异常时返回 {@code null}（由探测快照换成可读的占位文本）。 */
    private static String ofOrNull(String value) {
        try {
            Protocol.of(value);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * 配置里写了一个没有 Codec 的协议名 → <b>装配期就失败</b>，并列出所有已注册协议。
     *
     * <p>刻意不用 Spring：这条报错来自 {@code ModelFactory#createByProtocol}，
     * 与容器无关；把 Spring 的那一份留给 {@code ApplicationContextRunner} 测试去覆盖，
     * 这里只验证报错本身是否可读。</p>
     */
    public static ProtocolProbes.MissingProtocolRecord missingProtocolFailsFast(String missing) {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
        ModelConfig config = ModelConfig.builder("ghost")
                .protocol(missing)
                .baseUrl("http://127.0.0.1:1/unused")
                .model("ghost-model")
                .build();
        try {
            ModelFactory.create(config, null, registry);
            throw new AssertionError("未知协议 [" + missing + "] 本应让装配失败，但它成功了");
        } catch (ModelException e) {
            return new ProtocolProbes.MissingProtocolRecord(registry.describe(), e.getMessage());
        }
    }

    /**
     * 同名覆盖内置协议：注册一个 {@code protocol()} 返回 {@link Protocol#OPENAI} 的 Codec。
     *
     * <p>覆盖是被<b>刻意保留</b>的能力，因此不禁止，但会打 WARN。
     * 这里同时验证"覆盖之后内置实现没有被换回来" —— 以前有过一个缺陷：
     * starter 在用户 bean 之后又注册了一遍内置 Codec，于是覆盖被静默撤销。</p>
     */
    public static ProtocolProbes.OverrideRecord overrideBuiltinProtocol() {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
        List<String> before = registry.describe();

        OverridingOpenAiCodec mine = new OverridingOpenAiCodec();
        registry.register(mine);

        var resolved = registry.find(Protocol.OPENAI).orElseThrow();
        return new ProtocolProbes.OverrideRecord(before, registry.describe(), resolved.getClass().getName(),
                resolved == mine, resolved instanceof OverridingOpenAiCodec);
    }

    // ==================================================================
    // 九、装饰器
    // ==================================================================

    /** 自带重试装饰器 + 自写缓存装饰器叠加，并演示 {@code unwrap()} 链式取回。 */
    public DecorationResult stackedDecorators() {
        resetStub();
        stub.onPath("/v1/chat/completions", request ->
                Reply.json(OpenAiWire.textResponse(OpenAiWire.MODEL, "被缓存的回答。", "stop", 5, 2)));
        CachingLlmModel caching = new CachingLlmModel(model(WireConstants.OPENAI_KEY));
        RetryingLlmModel outer = new RetryingLlmModel(caching, 2, 1L, 2.0d);
        ChatResponse first = outer.chat(WireConstants.request());
        ChatResponse second = outer.chat(WireConstants.request());
        return new DecorationResult(outer, caching, first, second, stub.requestCount());
    }

    // ==================================================================
    // 十、ModelConfig 全字段
    // ==================================================================

    /** 用 {@code exp.model.*} 配置出来的 ModelConfig 打一次桩服务，返回抓到的请求。 */
    public WireResult settingsRoundTrip(ModelConfig config) {
        resetStub();
        String body = OpenAiWire.textResponse(config.model(), "设置已生效。", "stop", 3, 1);
        stub.onPath("/v1/chat/completions", request -> Reply.json(body));
        LlmModel model = ModelFactory.create(config, transport);
        return result("ModelConfig 全字段 · " + config.name(), "/v1/chat/completions", model,
                model.chat(WireConstants.request()), body, mark());
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 用当前桩服务的路由直接发起一次流式调用，<b>不注册任何路由、也不清桩服务</b>。
     *
     * <p>给"我要自己摆一个畸形/异常的上游响应"这类测试用：上面那些 {@code openAiStream()} 之类的
     * 场景方法都是自包含的（开头就 reset 并注册自己的路由），因此没法拿来验证"上游回了个奇怪的
     * 东西"这种情况。</p>
     */
    public StreamResult streamDirect(String protocolKey, ChatRequest request) {
        LlmModel model = model(protocolKey);
        int before = stub.requestCount();
        StreamCollector collector = new StreamCollector();
        try {
            model.stream(request, collector);
        } catch (RuntimeException e) {
            collector.onError(e);
        }
        return new StreamResult(protocolKey + " · 直接流式调用", requestAt(before), collector,
                stub.requestCount() - before, model, null, null);
    }

    private StreamResult stream(String label, String protocolKey, ChatRequest request, Reply reply) {
        LlmModel model = model(protocolKey);
        int before = stub.requestCount();
        StreamCollector collector = new StreamCollector();
        try {
            model.stream(request, collector);
        } catch (RuntimeException e) {
            collector.onError(e);
        }
        // 取"发起前那一次计数"对应的那一笔，而不是 lastRequest()：
        // 共享桩服务上可能有重试或其它线程的请求，lastRequest() 会拿到别人的报文。
        Recorded recorded = requestAt(before);
        return new StreamResult(label, recorded, collector, stub.requestCount() - before, model, null, reply);
    }

    /**
     * 一次非流式场景的结果。
     *
     * <p>{@code recorded} 取的是<b>发起调用之前记下的那一笔序号</b>，而不是 {@code lastRequest()}：
     * 桩服务是共享的，重试装饰器或其它演示节的请求会让"最后一笔"变成别人的报文 ——
     * 演示里真的出现过这种错位（打印出来的请求体与响应体对不上号）。</p>
     */
    private WireResult result(String label, String pathFragment, LlmModel model, ChatResponse response,
                              String responseBody, int requestIndex) {
        return new WireResult(label, pathFragment, model, response, requestAt(requestIndex),
                responseBody, Math.max(1, stub.requestCount() - requestIndex));
    }

    /**
     * 把一条"只回这个协议的请求"的错误路由挂到桩服务上。
     *
     * <p>用匹配器而不是 {@code on(request -> true, …)}：一条全匹配路由会留在桩服务的路由表里
     * 把之后所有场景的请求都截胡（示例里真实踩过，见 {@link #resetStub()}）。</p>
     *
     * <p>Gemini 的端点路径里带模型名（{@code /v1beta/models/<model>:generateContent}），
     * 因此它按后缀匹配，其余协议按精确路径匹配。</p>
     */
    private void onProtocolPath(String protocolKey, StubHttpServer.Route route) {
        if (WireConstants.GEMINI_KEY.equals(protocolKey)) {
            stub.onSuffix(":generateContent", route);
        } else {
            stub.onPath(stubPathOf(protocolKey), route);
        }
    }

    /**
     * 第 {@code index} 笔（0 基）录制下来的请求；不存在时返回 {@code null}。
     *
     * <p>用它而不是 {@code lastRequest()}：桩服务是共享的，而重试装饰器一次调用会发多笔，
     * 若拿"最后一笔"回来，打印出来的可能是重试之后那一笔，与被断言的那次调用对不上。</p>
     */
    private Recorded requestAt(int index) {
        List<Recorded> all = stub.requests();
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /**
     * 记下"下一次请求将会是第几笔"。
     *
     * <p>调用方必须把它作为<b>最后一个实参</b>传进 {@code result(...)}：Java 的实参求值顺序
     * 是从左到右，因此 {@code mark()} 会在 {@code model.chat(...)} 之后才被求值 ——
     * 于是它拿到的正是"这一笔请求"的下标。写法看着有点绕，但比让每个场景自己维护计数器更难写错。</p>
     */
    private int mark() {
        return stub.requestCount() - 1;
    }

    /**
     * 某个协议键在桩服务上的端点路径。
     *
     * <p>Gemini 的路径里带模型名（{@code /v1beta/models/<model>:generateContent}），
     * 因此这里按"后缀"匹配，其余协议按精确路径匹配。</p>
     */
    private String stubPathOf(String protocolKey) {
        return switch (protocolKey) {
            case WireConstants.OPENAI_KEY -> "/v1/chat/completions";
            case WireConstants.ANTHROPIC_KEY -> "/v1/messages";
            case WireConstants.GEMINI_KEY -> ":generateContent";
            case WireConstants.RESPONSES_KEY -> "/v1/responses";
            case WireConstants.DEMO_KEY -> "/demo/v2/invoke";
            default -> throw new IllegalArgumentException("未知模型注册名: " + protocolKey);
        };
    }

    /** 给某个协议注册一条"回固定文本"的路由，并返回它回放的协议原文。 */
    private String routeTextFor(String protocol, String text) {
        switch (protocol) {
            case "openai" -> {
                String body = OpenAiWire.textResponse(OpenAiWire.MODEL, text, "stop", 5, 2);
                stub.onPath("/v1/chat/completions", request -> Reply.json(body));
                return body;
            }
            case "anthropic" -> {
                String body = AnthropicWire.textResponse(AnthropicWire.MODEL, text, "end_turn", 5, 2, 0);
                stub.onPath("/v1/messages", request -> Reply.json(body));
                return body;
            }
            case "gemini" -> {
                String body = GeminiWire.textResponse(text, 5, 2);
                stub.onSuffix(":generateContent", request -> Reply.json(body));
                return body;
            }
            case "responses" -> {
                String body = ResponsesWire.textResponse(text, 5, 2);
                stub.onPath("/v1/responses", request -> Reply.json(body));
                return body;
            }
            default -> throw new IllegalArgumentException("未知协议: " + protocol);
        }
    }

    private static String keyOf(String protocol) {
        return switch (protocol) {
            case "openai" -> WireConstants.OPENAI_KEY;
            case "anthropic" -> WireConstants.ANTHROPIC_KEY;
            case "gemini" -> WireConstants.GEMINI_KEY;
            case "responses" -> WireConstants.RESPONSES_KEY;
            default -> throw new IllegalArgumentException("未知协议: " + protocol);
        };
    }

    private static String pathOf(String protocol) {
        return switch (protocol) {
            case "openai" -> "/v1/chat/completions";
            case "anthropic" -> "/v1/messages";
            case "gemini" -> ":generateContent";
            case "responses" -> "/v1/responses";
            default -> throw new IllegalArgumentException("未知协议: " + protocol);
        };
    }

    /** 任意切分一段文本（模拟 {@code output_index} 分片）。 */
    private static List<String> split(String text, int pieces) {
        List<String> parts = new ArrayList<>();
        int size = Math.max(1, (int) Math.ceil((double) text.length() / pieces));
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    // ==================================================================
    // 结果载体
    // ==================================================================

    /** 一次非流式场景的结果：模型 / 响应 / 抓到的请求 / 桩服务回放的协议原文。 */
    public record WireResult(String label,
                             String pathFragment,
                             LlmModel model,
                             ChatResponse response,
                             Recorded recorded,
                             String responseBody,
                             int requestCount) {

        /** 抓到的请求体（协议原文）。 */
        public String requestBody() {
            return recorded == null ? null : recorded.body();
        }

        /** 认证头（忽略大小写）。 */
        public String header(String name) {
            return recorded == null ? null : recorded.header(name);
        }

        public String path() {
            return recorded == null ? null : recorded.path();
        }

        /** 请求体的美化形式，便于直接塞进断言消息。 */
        public String prettyRequest() {
            return PrettyJson.format(requestBody());
        }

        /** 响应体的美化形式（桩服务回放的协议原文）。 */
        public String prettyResponse() {
            return PrettyJson.format(responseBody);
        }

        public Usage usage() {
            return response.usage();
        }

        /** 响应里的工具调用。 */
        public List<ToolUsePart> toolUses() {
            return response.message() == null ? List.of() : response.message().toolUses();
        }
    }

    /** 一次流式场景的结果。 */
    public record StreamResult(String label,
                               Recorded recorded,
                               StreamCollector collector,
                               int requestCount,
                               LlmModel model,
                               ScriptedStreamTransport scripted,
                               Reply reply) {

        /** 桩服务回放的 SSE 帧（原始文本，含 {@code data: } 前缀与空行）。 */
        public String sseFrames() {
            return reply == null ? "" : String.join("", reply.frames());
        }

        public String text() {
            return collector.text();
        }

        public String thinking() {
            return collector.thinkingText();
        }

        public Usage usage() {
            return collector.usage();
        }

        public List<ToolUsePart> toolUses() {
            return collector.toolUses();
        }

        public Throwable error() {
            return collector.error();
        }

        /**
         * 这次流式调用是否拿到了一个"成功"的响应。
         *
         * <p>刻意不直接转发 {@code collector.response()}：那条路在出错时会把已收到的增量
         * 合成一个响应交出来，让半截结果看起来像成功。口径统一在
         * {@link WireScenario#successfulResponse(StreamCollector)}。</p>
         */
        public ChatResponse response() {
            return successfulResponse(collector);
        }

        public String requestBody() {
            return recorded == null ? null : recorded.body();
        }

        public String header(String name) {
            return recorded == null ? null : recorded.header(name);
        }

        public String path() {
            return recorded == null ? null : recorded.path();
        }
    }

    /** 重试场景的结果。 */
    public record RetryResult(RetryingLlmModel model, Object outcome, int requestCount) {

        public int attempts() {
            return model.attempts();
        }

        public ChatResponse response() {
            return outcome instanceof ChatResponse response ? response : null;
        }

        public ModelException failure() {
            return outcome instanceof ModelException failure ? failure : null;
        }
    }

    /** 同步分支场景的结果：Agent 的最终 assistant 消息 + 它给出的文本 + 传输层记录。 */
    public record SyncBranchResult(FakeTransport transport, LlmModel model, ChatMessage response,
                                   String text) {

        /** 传输层上发生过的调用方法名（{@code post} / {@code postStreaming}）。 */
        public List<String> invokedMethods() {
            return transport.calls().stream().map(FakeTransport.Call::method).toList();
        }
    }

    /** 传输层误用场景的结果。 */
    public record TransportMisuseResult(FakeTransport transport, Throwable error) {
    }

    /** 装饰器叠加场景的结果。 */
    public record DecorationResult(RetryingLlmModel outer, CachingLlmModel caching,
                                   ChatResponse first, ChatResponse second, int requestCount) {
    }

    /**
     * 流式场景里"这次到底算不算成功"的唯一口径。
     *
     * <p>{@link StreamCollector} 有两个都可能被填上的字段：{@code completed}（解码器正常收尾）
     * 与 {@code error}（失败）。它自带的 {@code response()} 在 {@code completed == null} 时
     * 会把已收到的增量<b>合成</b>一个响应交出来 —— 对"流正常结束但解码器没回调 onComplete"
     * 是有用的兜底，但在<b>已经出错</b>的情况下，这个合成结果会让"半截响应"看起来像成功结果
     * （示例里的重试演示正是如此：收集器既报错又给出响应，读的人会以为那条半截内容"成功了"）。</p>
     *
     * <p>因此演示与测试统一走这个方法：<b>有错就是错，没有响应</b>。</p>
     */
    public static ChatResponse successfulResponse(StreamCollector collector) {
        return collector == null || collector.error() != null ? null : collector.response();
    }

    /** 便捷重载。 */
    public static ChatResponse successfulResponse(StreamResult result) {
        return result == null ? null : successfulResponse(result.collector());
    }

    /** 便捷方法：把四个协议名按内置顺序列出来，供演示循环使用。 */
    public static List<String> builtinProtocolNames() {
        return Protocol.builtinIds();
    }

    /**
     * {@link HttpLlmModel} 的配置视图。
     *
     * <p>用于断言"装配出来的到底是什么" —— 例如 {@code openai-demo} 的 {@code model}
     * 到底是 yml 里那个还是 bean 里那个。</p>
     *
     * <p>会先沿 {@code unwrap()} 链剥掉装饰器：{@code max-retries > 0} 的模型外面包着
     * {@code RetryingLlmModel}，直接强转会 ClassCastException。</p>
     */
    public static ModelConfig configOf(LlmModel model) {
        return httpModelOf(model).config();
    }

    /** 剥掉装饰器之后的 {@link HttpLlmModel}。 */
    public static HttpLlmModel httpModelOf(LlmModel model) {
        LlmModel current = model;
        // 装饰器链不会成环，但加个上限免得将来有人写出环来把测试挂死
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof HttpLlmModel http) {
                return http;
            }
            LlmModel next = current.unwrap();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        throw new IllegalArgumentException("模型 [" + (model == null ? "null" : model.name())
                + "] 不是 HttpLlmModel，也没有能剥到 HttpLlmModel 的装饰器链: "
                + (model == null ? "null" : model.getClass().getName()));
    }
}
