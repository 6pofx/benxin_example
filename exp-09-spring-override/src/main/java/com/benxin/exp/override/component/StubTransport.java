package com.benxin.exp.override.component;

import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 切面 1 的自定义实现：{@code HttpTransport} 的"记账 + 转发"装饰器。
 *
 * <p>它不是假实现 —— 真正的 HTTP 还是由 {@link JdkHttpTransport} 发出去的，
 * 所以 {@code StubHttpServer} 那侧能看到真实的 TCP 请求。它只是多记了两件事：
 * 请求 URL 与请求体。示例与测试靠这两样证明"模型确实走了我换的这个传输层"。</p>
 *
 * <p>{@link #postStreaming} 必须返回 {@code TransportResponse.streaming(...)}：
 * 声明式路径恒为流式，返回非流式响应会被 {@code HttpLlmModel} 明确报错
 * （见 {@code HttpLlmModel#stream} 里那段针对自定义传输层的提示）。这里直接委托给
 * JDK 实现，所以天然满足这个约定。</p>
 */
public class StubTransport implements HttpTransport {

    private final HttpTransport delegate = new JdkHttpTransport();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        return delegate.post(record("POST", url, body), headers, body, timeout);
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        return delegate.postStreaming(record("POST(stream)", url, body), headers, body, timeout);
    }

    private String record(String method, String url, String body) {
        calls.add(method + " " + url);
        bodies.add(body == null ? "" : body);
        return url;
    }

    /** 依次记录每次请求的 {@code 方法 URL}。 */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    /** 依次记录每次请求的请求体原文。 */
    public List<String> bodies() {
        return List.copyOf(bodies);
    }

    public int count() {
        return calls.size();
    }

    public String lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    public String lastBody() {
        return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
    }

    public void reset() {
        calls.clear();
        bodies.clear();
    }

    @Override
    public String toString() {
        return "StubTransport(记账型 HttpTransport，请求数=" + calls.size() + ")";
    }
}
