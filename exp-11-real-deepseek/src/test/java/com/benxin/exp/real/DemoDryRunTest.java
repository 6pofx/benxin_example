package com.benxin.exp.real;

import com.benxin.exp.common.wire.OpenAiWire;
import com.benxin.exp.common.wire.ResponsesWire;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpLlmModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预演：让<b>整个真实演示</b>在一个本地打桩端点上跑一遍，零联网、零 token。
 *
 * <p>为什么值得写这个用例？因为真实用的那 9 个小节里，只有"离线兜底"一节能被普通的
 * 离线用例覆盖 —— 剩下 8 节全部依赖真实端点，一旦某节里有个 NPE，
 * 你会在"已经花掉 token 之后"才发现。这个预演把那份风险提前到零成本的地方：
 * 打桩端点会像真模型一样回 SSE、回 tool_calls、回 401，
 * 于是从 Agent 装配到报文留档的每一行代码都被走了一遍。</p>
 *
 * <p><b>安全设计（很重要）</b>：本用例分两阶段，且第一阶段是"安全证明"。</p>
 * <ol>
 *   <li>先用系统属性把三个端点的 {@code base-url} 改写到打桩服务上（系统属性的优先级
 *       高于 application-real.yml），然后<b>不开演示</b>地启动一次上下文，
 *       断言 {@code HttpLlmModel.config().baseUrl()} 确实指向回环地址。
 *       如果改写没生效，这里就会失败，第二阶段（会真的跑演示）根本不会执行 ——
 *       因此本用例不存在"配置没覆盖成功、于是打到真实端点"的可能。</li>
 *   <li>确认安全后再启动第二次，捕获 stdout，断言演示本身没有失败项。</li>
 * </ol>
 */
@DisplayName("示例 11 · 整个真实演示的离线预演（打桩端点，零 token）")
class DemoDryRunTest {

    private static StubHttpServer stub;

    @BeforeAll
    static void startStub() {
        stub = StubHttpServer.start();
        stub.onPath("/v1/chat/completions", request -> {
            String auth = request.header("Authorization");
            // 假 key 走 401：与真实端点上的行为保持一致，让"错误路径"那一节也能被预演
            if (auth != null && auth.contains("sk-invalid")) {
                return StubHttpServer.Reply.json(401,
                        "{\"error\":{\"message\":\"Authentication Fails, Your api key is invalid\","
                                + "\"type\":\"authentication_error\"}}");
            }
            String body = request.body();
            boolean streaming = body.contains("\"stream\":true");
            if (!streaming) {
                // 协议对照那一节走的是非流式 chat()
                return StubHttpServer.Reply.json(OpenAiWire.textResponse(OpenAiWire.MODEL, "2", "stop", 40, 3));
            }
            // 只有"系统提示里明确要求先调工具"的请求才回 tool_calls —— 与真实模型的行为对齐
            boolean needsTool = body.contains("必须先调用");
            boolean hasToolResult = body.contains("\"role\":\"tool\"");
            if (needsTool && !hasToolResult) {
                return StubHttpServer.Reply.sseFrames(
                        OpenAiWire.streamingToolCall(OpenAiWire.MODEL, 0, "call_stub_1", "now", List.of("{}")));
            }
            return StubHttpServer.Reply.sseFrames(OpenAiWire.streamingText(OpenAiWire.MODEL, 40, 3, "好"));
        });
        // Responses 协议：base-url 已含 /responses，编解码器会原样使用
        stub.onPath("/responses", request -> StubHttpServer.Reply.json(ResponsesWire.textResponse("2", 40, 3)));
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    @DisplayName("演示 9 个小节全部跑完且没有失败项（含 401 与报文留档）")
    void wholeDemoRunsAgainstStub() {
        // ---------- 阶段 1：证明端点改写确实生效（不生效就在这里失败，绝不进阶段 2） ----------
        try (ConfigurableApplicationContext probe = boot(properties(Map.of("exp.demo.enabled", "false")))) {
            ModelRegistry models = probe.getBean(ModelRegistry.class);
            assertThat(baseUrlOf(models, "deepseek")).isEqualTo(stub.baseUrl());
            assertThat(baseUrlOf(models, "deepseek-responses")).isEqualTo(stub.baseUrl() + "/responses");
            assertThat(baseUrlOf(models, "deepseek-badkey")).isEqualTo(stub.baseUrl());
            assertThat(stub.requestCount()).isZero();
        }

        // ---------- 阶段 2：放开演示，捕获 stdout ----------
        String output;
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            try (ConfigurableApplicationContext ignored = boot(properties(Map.of()))) {
                // 演示由 ApplicationRunner 驱动，上下文 refresh 完成即已跑完
            }
        } finally {
            System.setOut(original);
        }
        output = buffer.toString(StandardCharsets.UTF_8);

        // 失败时把真实输出打出来，便于定位；
        // 想主动看一遍预演输出（对齐格式、检查措辞）时加 -Dexp.demo.show-output=true 即可。
        if (Boolean.getBoolean("exp.demo.show-output") || !output.contains("项正常完成")) {
            original.println(output);
        }

        assertThat(output).contains("项正常完成");
        assertThat(output).doesNotContain("✗ 演示失败");
        assertThat(output).contains("Authorization 已脱敏成 \"Bearer sk-****\"：true");
        assertThat(output).contains("statusCode() = 401");
        assertThat(output).contains("本次共");
        assertThat(output).contains("项正常完成");
        // 打桩端点上真的收到了请求（说明整条链路是走网络出去的，而不是被短路了）
        assertThat(stub.requestCount()).isGreaterThanOrEqualTo(9);
        assertThat(stub.requestsMatching("/responses")).hasSize(1);
    }

    // ------------------------------------------------------------------

    private static Map<String, String> properties(Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>();
        // 注意用系统属性而不是 SpringApplication.setDefaultProperties：
        // 默认属性的优先级**低于** application-real.yml，用它改写端点是无效的（会打到真实端点）。
        values.put("llm.models.deepseek.base-url", stub.baseUrl());
        values.put("llm.models.deepseek.api-key", "sk-dry-run");
        values.put("llm.models.deepseek-responses.base-url", stub.baseUrl() + "/responses");
        values.put("llm.models.deepseek-responses.api-key", "sk-dry-run");
        values.put("llm.models.deepseek-badkey.base-url", stub.baseUrl());
        values.putAll(extra);
        return values;
    }

    private static ConfigurableApplicationContext boot(Map<String, String> properties) {
        properties.forEach(System::setProperty);
        try {
            SpringApplication application = new SpringApplication(RealDeepSeekApplication.class);
            application.setAdditionalProfiles("real");
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            return application.run();
        } finally {
            // 必须清干净：surefire 里所有测试类共用同一个 JVM，
            // 残留的系统属性会让 OfflinePathTest 看到一个"默认 profile 下也有 deepseek"的世界。
            properties.keySet().forEach(System::clearProperty);
        }
    }

    private static String baseUrlOf(ModelRegistry models, String name) {
        LlmModel model = models.get(name).unwrap();
        assertThat(model).isInstanceOf(HttpLlmModel.class);
        return ((HttpLlmModel) model).config().baseUrl();
    }
}
