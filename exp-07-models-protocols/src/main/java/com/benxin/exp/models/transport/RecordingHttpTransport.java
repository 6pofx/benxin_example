package com.benxin.exp.models.transport;

import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 录制型传输装饰器：套在任意 {@link HttpTransport} 外面，把每一笔往来的
 * URL / 请求头 / 请求体 / 响应状态与响应体写进内存<b>并落盘</b>。
 *
 * <p>它证明的是"传输层是可以被装饰的"：被包装的实现可以是
 * {@code JdkHttpTransport}（真的走 socket），也可以是
 * {@link FakeTransport}（完全不碰网络），而模型与 Agent 的代码一行都不用改。</p>
 *
 * <p>落到 {@code target/wire-log/} 而不是临时目录，是因为"把抓到的报文留下来"
 * 本身就是排障手段：CI 上跑失败时，日志文件是唯一能复现现场的东西。</p>
 */
public class RecordingHttpTransport implements HttpTransport {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss.SSS");

    private final HttpTransport delegate;
    private final Path logDir;
    private final AtomicInteger seq = new AtomicInteger();
    private final List<Exchange> exchanges = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();

    public RecordingHttpTransport(HttpTransport delegate, Path logDir) {
        this.delegate = delegate;
        this.logDir = logDir;
    }

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body,
                                  java.time.Duration timeout) {
        TransportResponse response = delegate.post(url, headers, body, timeout);
        record("post", url, headers, body, response, response == null ? null : response.body());
        return response;
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body,
                                           java.time.Duration timeout) {
        TransportResponse response = delegate.postStreaming(url, headers, body, timeout);
        // 流式响应的 body 还没被消费（真正的 SSE 帧在 stream() 里），这里只能录状态与响应头；
        // 故意不为了"录全"而把流读干 —— 那会把真正的解码器饿死。
        record("postStreaming", url, headers, body, response,
                response != null && response.isStreaming() ? "(SSE 流，正文由解码器实时消费)" : null);
        return response;
    }

    /** 按到达顺序返回全部录到的往来。 */
    public synchronized List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    /** 落盘的日志文件（与 {@link #exchanges()} 同序）。 */
    public synchronized List<Path> files() {
        return List.copyOf(files);
    }

    public Path logDir() {
        return logDir;
    }

    public int count() {
        return exchanges.size();
    }

    private synchronized void record(String method, String url, Map<String, String> headers, String body,
                                     TransportResponse response, String responseBody) {
        int index = seq.incrementAndGet();
        Map<String, List<String>> headerView = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> headerView.put(k, List.of(v)));
        }
        StubHttpServer.Recorded request =
                StubHttpServer.Recorded.of(method, pathOf(url), headerView, body, index);
        Exchange exchange = new Exchange(request, url,
                response == null ? -1 : response.status(),
                response != null && response.isStreaming(),
                responseBody);
        exchanges.add(exchange);
        files.add(write(index, exchange));
    }

    private Path write(int index, Exchange exchange) {
        try {
            Files.createDirectories(logDir);
            Path file = logDir.resolve(String.format("%03d-%s.log", index, LocalTime.now().format(STAMP)));
            Files.writeString(file, render(exchange), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            // 落盘失败不该让一次模型调用失败：录制是观测手段，不是业务前提
            throw new ModelException("录制传输层写日志失败: " + logDir, e);
        }
    }

    private static String render(Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        sb.append(exchange.method()).append(' ').append(exchange.url()).append('\n');
        exchange.request().headers().forEach((k, v) -> sb.append(k).append(": ")
                .append(v.isEmpty() ? "" : v.get(0)).append('\n'));
        sb.append('\n').append(exchange.request().body()).append('\n');
        sb.append("\n--- response ").append(exchange.status())
                .append(exchange.streaming() ? " (stream)" : "").append(" ---\n");
        sb.append(exchange.responseBody() == null ? "" : exchange.responseBody()).append('\n');
        return sb.toString();
    }

    /** 只取 path 部分，便于与 HTTP 桩服务的 {@code Recorded.path()} 对齐。 */
    private static String pathOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** 一笔录制下来的往来。 */
    public record Exchange(StubHttpServer.Recorded request,
                           String url,
                           int status,
                           boolean streaming,
                           String responseBody) {

        public String method() {
            return request.method();
        }

        public String headers() {
            return String.valueOf(request.headers());
        }
    }

    /** 把一段字节流读成文本的便捷方法（给"假传输层"构造流式响应时用）。 */
    public static InputStream utf8(String text) {
        return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
