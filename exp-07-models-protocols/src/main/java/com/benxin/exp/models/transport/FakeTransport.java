package com.benxin.exp.models.transport;

import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 完全离线的传输实现：一次网络调用都不发，直接把预置好的响应交出去。
 *
 * <p>它要证明的不是"测试可以用假实现"（那是常识），而是
 * <b>换掉网络栈时业务代码零感知</b>：同一个 {@code HttpLlmModel}、
 * 同一份 {@code ModelConfig}、同一段调用代码，把 {@code JdkHttpTransport}
 * 换成它，响应照样能从协议解码器里解出来 —— 因为"发请求"与"解报文"本来就分属两个切面。</p>
 *
 * <p>三个可编程的行为位，正好覆盖本模块要演示的三类场景：</p>
 * <ul>
 *   <li>{@link #post} —— 非流式调用（也是"模型声明 streaming=false 后走 chat() 分支"的落点）；</li>
 *   <li>{@link #postStreaming} —— 流式调用，可以返回 {@code TransportResponse.streaming(...)}
 *       让解码器真的去解析 SSE；</li>
 *   <li>{@link #streamingReturnsNonStreaming()} —— 故意在 {@code postStreaming} 里返回
 *       非流式响应，用来验证插件给出的是可读的 {@code ModelException} 而不是 NPE。</li>
 * </ul>
 */
public class FakeTransport implements HttpTransport {

    /** 收到一次请求就记一笔：URL / 头 / 体都留下来，供"零感知"这条结论取证。 */
    public record Call(String method, String url, Map<String, String> headers, String body) {
    }

    private final List<Call> calls = new ArrayList<>();
    private Function<Call, TransportResponse> postHandler = call -> TransportResponse.of(200,
            Map.of("Content-Type", List.of("application/json")), "{}");
    private Function<Call, TransportResponse> streamingHandler;
    private boolean streamingReturnsNonStreaming;

    /** 非流式响应的处理器：拿到调用信息，返回 {@code TransportResponse.of(...)}。 */
    public FakeTransport post(Function<Call, TransportResponse> handler) {
        this.postHandler = handler;
        return this;
    }

    /** 流式响应的处理器：通常返回 {@code TransportResponse.streaming(...)}。 */
    public FakeTransport postStreaming(Function<Call, TransportResponse> handler) {
        this.streamingHandler = handler;
        return this;
    }

    /**
     * 让 {@code postStreaming} 返回<b>非流式</b>响应。
     *
     * <p>这是把自己写成"有缺陷的传输层"，用来看插件怎么报错：声明式路径恒为流式，
     * 因此这条错误必须是可读的 {@code ModelException}，而不是让调用方去猜 NPE 从哪来。</p>
     */
    public FakeTransport streamingReturnsNonStreaming(String body) {
        this.streamingReturnsNonStreaming = true;
        this.streamingHandler = call -> TransportResponse.of(200,
                Map.of("Content-Type", List.of("application/json")), body);
        return this;
    }

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        Call call = new Call("post", url, copy(headers), body);
        calls.add(call);
        return postHandler.apply(call);
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        Call call = new Call("postStreaming", url, copy(headers), body);
        calls.add(call);
        if (streamingHandler != null) {
            return streamingHandler.apply(call);
        }
        if (streamingReturnsNonStreaming) {
            throw new IllegalStateException("streamingReturnsNonStreaming 必须同时给出 body");
        }
        // 默认行为：把请求体里的 model 拼成一条最小 OpenAI 文本流，方便"随手就能跑通"
        return streaming(call, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: [DONE]\n\n");
    }

    public boolean streamingReturnsNonStreaming() {
        return streamingReturnsNonStreaming;
    }

    /** 按调用顺序返回全部记录。 */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    public void reset() {
        calls.clear();
    }

    // ------------------------------------------------------------------ 构造响应的便捷方法

    /** 200 + JSON 文本，内容类型按 {@code application/json} 给全。 */
    public static TransportResponse json(String body) {
        return TransportResponse.of(200, Map.of("Content-Type", List.of("application/json")), body);
    }

    /** 指定状态码 + JSON 文本。 */
    public static TransportResponse json(int status, String body) {
        return TransportResponse.of(status,
                Map.of("Content-Type", List.of("application/json")), body);
    }

    /** 200 + SSE 输入流；{@code sse} 参数是<b>原始帧</b>（要自己带 {@code data: } 前缀与空行）。 */
    public static TransportResponse streaming(Call call, String sse) {
        return TransportResponse.streaming(200,
                Map.of("Content-Type", List.of("text/event-stream; charset=utf-8")),
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
    }

    /** 把若干 payload 自动包成 {@code data: <payload>\n\n}。 */
    public static TransportResponse streamingData(Call call, List<String> payloads) {
        StringBuilder sb = new StringBuilder();
        payloads.forEach(p -> sb.append("data: ").append(p).append("\n\n"));
        return streaming(call, sb.toString());
    }

    private static Map<String, String> copy(Map<String, String> headers) {
        return headers == null ? Map.of() : new LinkedHashMap<>(headers);
    }
}
