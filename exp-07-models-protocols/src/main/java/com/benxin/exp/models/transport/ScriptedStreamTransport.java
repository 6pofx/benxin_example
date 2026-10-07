package com.benxin.exp.models.transport;

import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "自己造一条会在中途断掉的 SSE 流"的传输层。
 *
 * <p>用途是复现一个真 socket 很难稳定复现的时点：<b>流已经吐出一部分内容之后才失败</b>。
 * 用真实服务端"写一半就断连"依赖 TCP 缓冲与操作系统的行为，时点不可控；
 * 这里用输入流精确控制"交付完第 N 帧之后抛 IOException"，于是
 * {@code RetryingLlmModel} 的"已经出字就不再重试"这条口径可以被确定性地断言。</p>
 *
 * <p>{@link #post} 也一并给出（返回一个完整的非流式响应），
 * 因为"只实现了 post() 的传输层在 Agent 路径上可达"同样需要证据。</p>
 */
public class ScriptedStreamTransport implements HttpTransport {

    /** 已经数到几个完整帧（一帧以空行结束，即连续两个换行）。 */
    private final String sseBody;
    private final int failAfterFrames;
    private final List<String> seenUrls = new ArrayList<>();

    private ScriptedStreamTransport(String sseBody, int failAfterFrames) {
        this.sseBody = sseBody;
        this.failAfterFrames = failAfterFrames;
    }

    /**
     * 先完整交付 {@code frames}，再让下一次读取抛 {@link IOException}。
     *
     * @param frames 已带好 {@code data: } 前缀与结尾空行的原始 SSE 帧
     */
    public static ScriptedStreamTransport streamThenFail(List<String> frames) {
        return new ScriptedStreamTransport(String.join("", frames), frames.size());
    }

    /** 只吐帧、不失败（用于对照，证明"失败"确实来自我们制造的断流）。 */
    public static ScriptedStreamTransport ofFrames(List<String> frames) {
        return new ScriptedStreamTransport(String.join("", frames), Integer.MAX_VALUE);
    }

    /** 这个传输层一次网络也不走，"失败"完全由我们自己制造。 */
    @Override
    public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
        seenUrls.add(url);
        return TransportResponse.of(200, Map.of("Content-Type", List.of("application/json")),
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"同步分支\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
    }

    @Override
    public TransportResponse postStreaming(String url, Map<String, String> headers, String body, Duration timeout) {
        seenUrls.add(url);
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Content-Type", List.of("text/event-stream; charset=utf-8"));
        return TransportResponse.streaming(200, responseHeaders, failingStream());
    }

    /** 请求过的 URL，按顺序。 */
    public List<String> seenUrls() {
        return List.copyOf(seenUrls);
    }

    /**
     * 交付完 {@code failAfterFrames} 个 SSE 帧之后抛 IOException。
     *
     * <p>按帧计数而不是按字节计数，是因为 SSE 的分帧语义就是"空行结束一帧"：
     * 数到第 N 个空行时，前 N 帧已经完整进入了解码器，这个时点与真实断流的效果一致。</p>
     *
     * <p><b>为什么"下一个字节"才抛，而不是在空行那个字节上抛</b>：
     * 空行由两个 {@code \n} 组成，第二个 {@code \n} 才是这一帧的最后一个字节。
     * 如果在读到它的时候直接抛，这一帧的空行就永远送不到 {@code SseParser} 手里 ——
     * 而 SSE 解析器是靠空行<b>冲刷</b>事件的，于是最后一帧的内容会被整帧丢掉。
     * 那样一来"交付完 N 帧之后才断"这句话就是假的：实际只交付了 N-1 帧。
     * 本示例的测试正是在这一点上抓到过：桩传输层说"已交付 1 帧后中断"，
     * 而收集器里一个字都没有。</p>
     */
    private InputStream failingStream() {
        InputStream delegate = new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8));
        int[] remaining = {failAfterFrames};
        boolean[] previousWasNewline = {false};
        // 已经数满 failAfterFrames 帧：把这个字节交出去，下一次读取再抛
        boolean[] failPending = {false};
        return new FilterInputStream(delegate) {

            @Override
            public int read() throws IOException {
                if (failPending[0]) {
                    throw new IOException("桩传输层：已交付 " + failAfterFrames + " 帧后连接中断");
                }
                int value = super.read();
                if (value < 0) {
                    // 流尾：桩服务从未"正常关闭"，因此这里也报错 —— 真实断连同样不会有正常 EOF
                    throw new IOException("桩传输层：连接在服务器正常关闭之前被中断");
                }
                if (value == '\n') {
                    if (previousWasNewline[0] && remaining[0] != Integer.MAX_VALUE) {
                        if (--remaining[0] <= 0) {
                            failPending[0] = true;
                        }
                    }
                    previousWasNewline[0] = true;
                } else {
                    previousWasNewline[0] = false;
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                // BufferedReader 走的是批量读；这里退化成逐字节，保证"第 N 帧之后抛"的时点精确。
                // 对演示与测试来说吞吐量无关紧要，时点的确定性才是重点。
                int first = read();
                if (first < 0) {
                    return -1;
                }
                buffer[offset] = (byte) first;
                return 1;
            }
        };
    }
}
