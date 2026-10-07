package com.benxin.exp.real;

import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.exp.real.wire.WireLog;
import com.benxin.exp.real.wire.WireLogTransport;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.transport.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 报文留档装饰器的离线契约测试：用 {@code exp-common} 的嵌入式 HTTP 桩服务
 * 让<b>真实传输层</b>连上一个真实 TCP 端点，从而验证三件在真实调用时看不见的事：
 *
 * <ol>
 *   <li>认证头确实由协议编解码器带上（{@code Authorization: Bearer <key>}）；</li>
 *   <li>留档文件里的 {@code Authorization} 一定被脱敏成 {@code Bearer sk-****}，
 *       且文件内容里不含真实 key —— 这条断言是"repo 里不会出现 key"的自动化保障；</li>
 *   <li>流式（SSE）与非流式两种响应形态都会被正确留档（{@code responseRaw} vs {@code response}）。</li>
 * </ol>
 *
 * <p>全程零 token、零联网：打桩服务监听在回环地址上，{@link WireLog#realRequests()}
 * 会把它排除在"真实请求"之外 —— 这个区分本身也是示例的一部分。</p>
 */
@DisplayName("示例 11 · 报文留档装饰器（离线，走真实 socket）")
class WireLogTransportTest {

    private static final String FAKE_KEY = "sk-unit-test-not-a-real-key";

    @Test
    @DisplayName("非流式：请求/响应落盘，Authorization 脱敏成 Bearer sk-****")
    void postIsArchivedAndMasked() throws IOException {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.json(OpenAiWire.textResponse("2")));

            WireLog log = newWireLog("target/wire-log-test-post");
            LlmModel model = model(server, log);

            ChatResponse response = model.chat(ChatRequest.builder()
                    .message(ChatMessage.user("1+1=?"))
                    .maxTokens(48)
                    .build());

            assertThat(response.text()).isEqualTo("2");
            // 真实传输层确实把 key 带出去了（否则真实端点会 401）
            assertThat(server.lastRequest().orElseThrow().header("Authorization"))
                    .isEqualTo("Bearer " + FAKE_KEY);

            WireLog.Entry entry = log.last();
            assertThat(entry).isNotNull();
            assertThat(entry.url()).endsWith("/v1/chat/completions");
            assertThat(entry.status()).isEqualTo(200);
            // 留档里是脱敏后的值
            assertThat(entry.requestHeaders().get("Authorization")).isEqualTo("Bearer sk-****");

            String content = Files.readString(entry.file(), StandardCharsets.UTF_8);
            assertThat(content).contains("Bearer sk-****");
            assertThat(content).doesNotContain(FAKE_KEY);
            // 抠 token 的两个关键字段确实下发了
            assertThat(content).contains("max_tokens").contains("48");
            assertThat(content).contains("\"usage\"");
            assertThat(entry.requestBody()).contains("\"max_tokens\":48");
        }
    }

    @Test
    @DisplayName("流式：SSE 原文被完整留档（responseRaw），并统计帧数")
    void streamingIsArchivedAsRawSse() throws IOException {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.sseFrames(
                    OpenAiWire.streamingText(OpenAiWire.MODEL, 42, 7, "2")));

            WireLog log = newWireLog("target/wire-log-test-stream");
            LlmModel model = model(server, log);

            StringBuilder collected = new StringBuilder();
            model.stream(ChatRequest.builder().message(ChatMessage.user("1+1=?")).build(),
                    com.benxin.llm.core.model.LlmStreamHandler.ofText(collected::append));

            assertThat(collected.toString()).isEqualTo("2");

            WireLog.Entry entry = log.last();
            assertThat(entry).isNotNull();
            assertThat(entry.streaming()).isTrue();
            assertThat(entry.responseRaw()).contains("data: [DONE]").contains("chat.completion.chunk");
            assertThat(entry.summary()).contains("SSE");

            String content = Files.readString(entry.file(), StandardCharsets.UTF_8);
            assertThat(content).contains("responseRaw").doesNotContain(FAKE_KEY);
        }
    }

    @Test
    @DisplayName("错误响应也会留档：401 的原始报文是排障的第一手资料")
    void errorResponseIsArchived() {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.json(401,
                    "{\"error\":{\"message\":\"Authentication Fails, Your api key is invalid\"}}"));

            WireLog log = newWireLog("target/wire-log-test-401");
            LlmModel model = model(server, log);

            assertThatThrownBy(() -> model.chat(ChatRequest.builder().message(ChatMessage.user("hi")).build()))
                    .isInstanceOf(ModelException.class)
                    .hasMessageContaining("401");

            assertThat(log.last().status()).isEqualTo(401);
            assertThat(log.last().responseBody()).contains("Authentication Fails");
        }
    }

    @Test
    @DisplayName("脱敏规则：Authorization / 任何名字带 key 或 token 的头都不落原文")
    void maskingRules() {
        assertThat(WireLog.sanitize("Authorization", "Bearer sk-abcdef123456")).isEqualTo("Bearer sk-****");
        assertThat(WireLog.sanitize("authorization", "sk-abcdef123456")).isEqualTo("****");
        assertThat(WireLog.sanitize("X-Api-Key", "sk-abcdef123456")).isEqualTo("****");
        assertThat(WireLog.sanitize("X-Auth-Token", "abcdef")).isEqualTo("****");
        assertThat(WireLog.sanitize("Content-Type", "application/json")).isEqualTo("application/json");

        Map<String, String> sanitized = WireLog.sanitize(Map.of(
                "Authorization", "Bearer sk-real-key",
                "Accept", "application/json"));
        assertThat(sanitized.get("Authorization")).isEqualTo("Bearer sk-****");
        assertThat(sanitized.values()).noneMatch(value -> value.contains("sk-real-key"));
    }

    @Test
    @DisplayName("回环地址不算真实请求：打桩测试不会污染用量账本")
    void loopbackIsNotCountedAsRealTraffic() {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.onPath("/v1/chat/completions", request -> StubHttpServer.Reply.json(OpenAiWire.textResponse("2")));
            WireLog log = newWireLog("target/wire-log-test-loopback");
            model(server, log).chat(ChatRequest.builder().message(ChatMessage.user("hi")).build());

            assertThat(log.requests()).isEqualTo(1);
            assertThat(log.realRequests()).isZero();
            assertThat(WireLog.isRealEndpoint(server.baseUrl() + "/v1/chat/completions")).isFalse();
            assertThat(WireLog.isRealEndpoint("https://api.deepseek.com/v1/chat/completions")).isTrue();
        }
    }

    // ------------------------------------------------------------------

    private static WireLog newWireLog(String dir) {
        MockEnvironment environment = new MockEnvironment().withProperty("exp.wire-log.dir", dir);
        return new WireLog(environment);
    }

    private static LlmModel model(StubHttpServer server, WireLog log) {
        ModelConfig config = ModelConfig.builder("stub")
                .protocol("openai")
                .baseUrl(server.baseUrl())
                .apiKey(FAKE_KEY)
                .model(OpenAiWire.MODEL)
                // max-retries = 0：一次调用 = 一笔请求，断言里的计数才成立
                .maxRetries(0)
                .maxTokens(48)
                .build();
        return ModelFactory.create(config, new WireLogTransport(log, new JdkHttpTransport()),
                ProtocolRegistry.withBuiltins());
    }
}
