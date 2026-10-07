package com.benxin.exp.real.wire;

import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 报文留档用的 {@link HttpTransport} 装饰器：委托 {@link JdkHttpTransport}，
 * 顺手把 URL / 请求头（脱敏）/ 请求体 / 响应体写进 {@link WireLog}。
 *
 * <p>为什么用装饰器而不是改模型或协议编解码器？因为"我要看真实报文"是一个
 * <b>横切关注点</b>：它不该出现在业务代码里，也不该要求换协议实现。本心的
 * {@code HttpTransport} 是一个 bean 且带 {@code @ConditionalOnMissingBean}，
 * 于是"把传输层包一层"就是全部成本 —— 19 行装配 + 一个类。</p>
 *
 * <p>流式的处理稍微绕一点：{@code postStreaming} 返回的是<b>未消费的输入流</b>，
 * 报文此刻还没到。这里的做法是把流包一层 tee，边读边攒，
 * 在 {@code close()} 时落盘 —— {@code HttpLlmModel.stream()} 用 try-with-resources
 * 读完就关，因此文件一定会在这次调用结束前出现。</p>
 */
public class WireLogTransport implements HttpTransport {

    private final HttpTransport delegate;
    private final WireLog log;

    public WireLogTransport(WireLog log) {
        this(log, new JdkHttpTransport());
    }

    public WireLogTransport(WireLog log, HttpTransport delegate) {
        this.log = log;
        this.delegate = delegate == null ? new JdkHttpTransport() : delegate;
    }

    /** 被装饰的传输层（排障用）。 */
    public HttpTransport delegate() {
        return delegate;
    }

    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        TransportResponse response = delegate.post(url, headers, body, timeout);
        log.recordPost(url, headers, body, response.status(), response.body());
        return response;
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        TransportResponse response = delegate.postStreaming(url, headers, body, timeout);
        if (!response.isStreaming()) {
            // 上游在流式通道上回了普通 JSON（例如 4xx 错误体），同样留档
            log.recordStream(url, headers, body, response.status(), response.body());
            return response;
        }
        InputStream tee = new CapturingStream(response.stream(), captured ->
                log.recordStream(url, headers, body, response.status(), captured));
        return TransportResponse.streaming(response.status(), response.headers(), tee);
    }

    /**
     * 边读边攒的输入流：读完（关闭）时把攒到的 SSE 原文交给回调落盘。
     *
     * <p>{@code close()} 里的落盘只做一次 —— {@code try-with-resources} 与
     * 上游解码器都可能触发关闭，重复写会把同一笔往来记成两笔。</p>
     */
    private static final class CapturingStream extends FilterInputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Consumer<String> onClose;
        private boolean flushed;

        private CapturingStream(InputStream in, Consumer<String> onClose) {
            super(in);
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                buffer.write(value);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                buffer.write(bytes, offset, read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            flush();
            super.close();
        }

        private void flush() {
            if (flushed) {
                return;
            }
            flushed = true;
            onClose.accept(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
