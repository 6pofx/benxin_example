package com.benxin.exp.observability;

import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.interceptor.RewriteInterceptor;
import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /llm/**} 端点的全量验证，重点在 README 里那张<b>"按直觉猜会猜错"的契约表</b>。
 *
 * <p>用 {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} + {@link TestRestTemplate}：
 * SSE 必须走真实 HTTP 才能验证"分帧到达 + 事件名"，MockMvc 会把流式响应拍平成一个字符串，
 * 事件边界就看不见了。</p>
 *
 * <p>本类的上下文配置（{@code llm.web.base-path} 保持默认 {@code /llm}）与
 * {@link LlmWebBasePathTest} 不同，两个类各自缓存一个上下文，互不干扰。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "llm.web.enabled=true")
@DisplayName("示例 08 · HTTP 端点契约")
class LlmWebEndpointTest extends ObservabilityTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ChatAssistant chatAssistant;

    private String url(String path) {
        return "http://localhost:" + port + "/llm" + path;
    }

    /** 读 SSE：超时必须设为 0（无限），否则会在服务端 complete 之前被掐断连接。 */
    private ResponseEntity<String> sse(String path, String message) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(0);
        TestRestTemplate local = new TestRestTemplate();
        local.getRestTemplate().setRequestFactory(factory);
        return local.getForEntity(url(path) + "?message={m}", String.class, message);
    }

    /** 取 SSE 报文的原始字节：用于验证"字节是 UTF-8，但 Content-Type 没声明 charset"。 */
    private ResponseEntity<byte[]> sseBytes(String path, String message) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(0);
        TestRestTemplate local = new TestRestTemplate();
        local.getRestTemplate().setRequestFactory(factory);
        return local.getForEntity(url(path) + "?message={m}", byte[].class, message);
    }

    /** 取 SSE 报文里出现过的事件名（去重、保序）。 */
    private static List<String> eventTypes(String body) {
        return body.lines()
                .filter(line -> line.startsWith("event:"))
                .map(line -> line.substring("event:".length()).trim())
                .distinct()
                .toList();
    }

    /** 取某一类事件的数据行（JSON 文本）。 */
    private static List<String> eventData(String body, String event) {
        List<String> data = new ArrayList<>();
        String current = null;
        for (String line : body.split("\r?\n")) {
            if (line.startsWith("event:")) {
                current = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && event.equals(current)) {
                data.add(line.substring("data:".length()).trim());
            }
        }
        return data;
    }

    private Map<String, Object> postChat(String agent, String message) {
        ResponseEntity<Map> response = rest.exchange(url("/agents/" + agent + "/chat"),
                HttpMethod.POST, jsonEntity(Map.of("message", message)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static List<ChatMessage> nonSystemMessages(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() != Role.SYSTEM).toList();
    }

    // ------------------------------------------------------------------
    // ① GET /llm/agents
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /llm/agents 列出 name/loop/model/tools/maxSteps/systemPrompt")
    void agentsEndpoint() {
        ResponseEntity<List> response = rest.getForEntity(url("/agents"), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> agents = response.getBody();
        assertThat(agents).isNotEmpty();
        assertThat(agents.stream().map(a -> a.get("name")).toList())
                .contains("chatAssistant", "observability", "guardedAssistant", "failAssistant");

        Map<String, Object> chatEntry = agents.stream()
                .filter(a -> "chatAssistant".equals(a.get("name")))
                .findFirst().orElseThrow();
        assertThat(chatEntry).containsKeys("name", "loop", "model", "tools", "maxSteps", "systemPrompt");
        assertThat(chatEntry.get("loop")).isEqualTo("dsh-minimal");
        assertThat(chatEntry.get("model")).isEqualTo("mock");
        assertThat(chatEntry.get("maxSteps")).isEqualTo(4);
        assertThat(String.valueOf(chatEntry.get("systemPrompt"))).contains("示例 08");
        assertThat(((List<?>) chatEntry.get("tools")).stream().map(String::valueOf).toList())
                .contains("get_weather", "calculate");
    }

    // ------------------------------------------------------------------
    // ② GET /llm/loops
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /llm/loops 列出内置 Loop、自定义 Loop 与 workflow:<key>")
    void loopsEndpoint() {
        ResponseEntity<List> response = rest.getForEntity(url("/loops"), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> loops = (List<String>) response.getBody();
        assertThat(loops).isNotEmpty();
        assertThat(loops).anyMatch(l -> l.startsWith("dsh-minimal"));
        assertThat(loops).anyMatch(l -> l.startsWith("exp-observe"));      // 自定义 @LlmLoop
        assertThat(loops).anyMatch(l -> l.startsWith("workflow:greet"));   // llm.workflows.greet
    }

    // ------------------------------------------------------------------
    // ③ GET /llm/models
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /llm/models 返回 default 与 names")
    void modelsEndpoint() {
        ResponseEntity<Map> response = rest.getForEntity(url("/models"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("default", "names");
        assertThat(body.get("default")).isEqualTo("mock");
        assertThat(((List<?>) body.get("names")).stream().map(String::valueOf).toList())
                .contains("mock", "failing");
    }
    // ------------------------------------------------------------------
    // ④ POST /llm/agents/{name}/chat
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /chat 返回 agent/sessionId/text/steps/loop/model/durationMillis/finishReason/maxStepsReached/usage/toolCalls")
    void chatEndpoint() {
        Map<String, Object> body = postChat("chatAssistant", "北京天气怎么样？");

        assertThat(body).containsKeys("agent", "sessionId", "text", "steps", "loop", "model",
                "durationMillis", "finishReason", "maxStepsReached", "usage", "toolCalls");
        assertThat(body.get("agent")).isEqualTo("chatAssistant");
        assertThat(body.get("loop")).isEqualTo("dsh-minimal");
        assertThat(body.get("model")).isEqualTo("mock");
        assertThat(body.get("steps")).isEqualTo(2);
        assertThat(body.get("maxStepsReached")).isEqualTo(false);
        assertThat(String.valueOf(body.get("text"))).contains("根据工具返回的结果");
        assertThat(String.valueOf(body.get("sessionId"))).startsWith("http-");
        assertThat((List<?>) body.get("toolCalls")).hasSize(1);

        Map<String, Object> usage = (Map<String, Object>) body.get("usage");
        assertThat(usage).containsKeys("inputTokens", "outputTokens", "totalTokens");
        assertThat(((Number) usage.get("totalTokens")).intValue()).isPositive();

        // 工具真的执行了（端点调用走的是同一条工具链）
        assertThat(commonTools.callCount()).isPositive();
    }

    @Test
    @DisplayName("POST /chat 到 @LlmAgent(interceptors={...}) 的 Agent：注解级拦截器同样生效")
    void chatEndpointAppliesAnnotationInterceptors() {
        Map<String, Object> body = postChat("rewrite", "自我介绍");

        assertThat(String.valueOf(body.get("text"))).contains(RewriteInterceptor.SUFFIX_MARKER);
        assertThat(mockLlmModel.lastRequest().extra()).containsKey(RewriteInterceptor.TRACE_KEY);
    }

    // ------------------------------------------------------------------
    // ⑤ GET /llm/agents/{name}/stream（SSE）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /stream 返回 SSE，事件类型为 delta/thinking/tool_call/done")
    void streamEndpoint() {
        ResponseEntity<String> response = sse("/agents/chatAssistant/stream", "北京天气怎么样？");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(String.valueOf(response.getHeaders().getContentType()))
                .containsIgnoringCase(MediaType.TEXT_EVENT_STREAM_VALUE);

        String body = response.getBody();
        assertThat(body).isNotNull();
        List<String> types = eventTypes(body);
        assertThat(types).contains("delta", "tool_call", "done");
        assertThat(types).doesNotContain("tool_result", "error");

        // done 事件带着完整结果（JSON）
        List<String> done = eventData(body, "done");
        assertThat(done).hasSize(1);
        assertThat(done.get(0)).contains("\"agent\":\"chatAssistant\"");
        assertThat(done.get(0)).contains("\"toolCalls\"");
        // 增量拼起来就是最终文本的一部分
        assertThat(String.join("", eventData(body, "delta"))).isNotBlank();
    }

    @Test
    @DisplayName("SSE 里有 tool_call 事件；工具结果只在 done 的 toolCalls 里，没有独立的 tool_result 事件")
    void streamEndpointHasNoToolResultEvent() {
        String body = sse("/agents/chatAssistant/stream", "北京天气怎么样？").getBody();

        assertThat(eventTypes(body)).contains("tool_call");
        assertThat(eventTypes(body)).doesNotContain("tool_result");
        assertThat(eventData(body, "tool_call").get(0)).contains("get_weather");
        assertThat(eventData(body, "done").get(0)).contains("get_weather");
    }

    @Test
    @DisplayName("思考型 Agent 的 SSE 里会多出 thinking 事件")
    void streamEndpointEmitsThinking() {
        String body = sse("/agents/thinkingAssistant/stream", "自我介绍").getBody();

        assertThat(eventTypes(body)).contains("thinking");
    }

    @Test
    @DisplayName("SSE 的 done 事件携带 sessionId，且不传 sessionId 时每次都是新的 http-<uuid>")
    void streamSessionIdIsFresh() {
        String first = sse("/agents/chatAssistant/stream", "自我介绍").getBody();
        String second = sse("/agents/chatAssistant/stream", "自我介绍").getBody();

        assertThat(eventData(first, "done").get(0)).contains("http-");
        assertThat(eventData(second, "done").get(0)).contains("http-");
        assertThat(eventData(first, "done").get(0)).isNotEqualTo(eventData(second, "done").get(0));
    }

    // ------------------------------------------------------------------
    // 契约表：逐条断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("契约① 未知 Agent 名 → 404，响应体含 timestamp/status/error/message/path")
    void contractUnknownAgentReturns404() {
        ResponseEntity<Map> response = rest.exchange(url("/agents/doesNotExist/chat"),
                HttpMethod.POST, jsonEntity(Map.of("message", "你好")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("timestamp", "status", "error", "message", "path");
        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not Found");
        assertThat(String.valueOf(body.get("message"))).contains("doesNotExist");
        assertThat(body.get("path")).isEqualTo("/llm/agents/doesNotExist/chat");
    }

    @Test
    @DisplayName("契约①' 未知 Agent 名在 SSE 端点上同样是 404（同一个异常处理器）")
    void contractUnknownAgentOnStreamReturns404() {
        ResponseEntity<String> response =
                rest.getForEntity(url("/agents/doesNotExist/stream?message=hello"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("doesNotExist");
    }

    @Test
    @DisplayName("契约② 不传 sessionId → 每次独立（新生成的 http-<uuid>）；显式传则续接历史")
    void contractSessionIdSemantics() {
        Map<String, Object> first = postChat("chatAssistant", "自我介绍");
        String firstId = String.valueOf(first.get("sessionId"));
        assertThat(firstId).startsWith("http-");
        assertThat(firstId).hasSize("http-".length() + 36);   // http- + UUID 文本

        Map<String, Object> second = postChat("chatAssistant", "自我介绍");
        assertThat(String.valueOf(second.get("sessionId"))).isNotEqualTo(firstId);

        // 独立会话 → 每次的模型请求里只有这一轮的 user 消息
        assertThat(nonSystemMessages(mockLlmModel.lastRequest())).hasSize(1);

        // 显式传同一个 sessionId：第二轮能看到上一轮的历史
        rest.exchange(url("/agents/chatAssistant/chat"), HttpMethod.POST,
                jsonEntity(Map.of("message", "第一句", "sessionId", "explicit-session")), Map.class);
        rest.exchange(url("/agents/chatAssistant/chat"), HttpMethod.POST,
                jsonEntity(Map.of("message", "第二句", "sessionId", "explicit-session")), Map.class);

        assertThat(nonSystemMessages(mockLlmModel.lastRequest())).hasSize(3);   // user1 + assistant1 + user2
        assertThat(agentRegistry.get("chatAssistant").history("explicit-session")).hasSize(4);
    }

    @Test
    @DisplayName("契约③ @LlmAgent 不声明 tools/toolNames → 继承容器全局工具表（什么都不写=拿到所有工具）")
    void contractEmptyToolsMeansAllTools() {
        List<Map<String, Object>> agents = rest.getForEntity(url("/agents"), List.class).getBody();
        Map<String, Object> chatEntry = agents.stream()
                .filter(a -> "chatAssistant".equals(a.get("name")))
                .findFirst().orElseThrow();

        assertThat(((List<?>) chatEntry.get("tools")).stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate", "describe_context",
                        FailingProbeTool.NAME);
    }

    @Test
    @DisplayName("契约④ 方法级 @SystemPrompt / @Ctx / @User 在 HTTP 端点上完全不生效（只认 Agent 级 systemPrompt）")
    void contractMethodLevelAnnotationsAreIgnored() {
        mockLlmModel.reset();
        postChat("chatAssistant", "介绍一下你自己");

        ChatRequest request = mockLlmModel.lastRequest();
        assertThat(request).isNotNull();
        String system = request.systemText();

        // Agent 级提示词在
        assertThat(system).contains("示例 08 的助手");
        // 方法级那段（只写在 askWithMethodPrompt 上）不在
        assertThat(system).doesNotContain("【方法级提示词】");
        // @User 也不会被加工：端点直接下发一条普通 user 消息
        assertThat(nonSystemMessages(request)).hasSize(1);
        assertThat(nonSystemMessages(request).get(0).text()).isEqualTo("介绍一下你自己");
    }

    @Test
    @DisplayName("契约④' 对照：Java 接口代理调用时方法级 @SystemPrompt 确实生效")
    void methodLevelAnnotationWorksThroughProxy() {
        mockLlmModel.reset();
        chatAssistant.askWithMethodPrompt("介绍一下你自己");

        assertThat(mockLlmModel.lastRequest().systemText())
                .contains("【方法级提示词】")
                .contains("示例 08 的助手");   // inherit=true 时叠加 Agent 级提示词
    }

    @Test
    @DisplayName("契约⑤ 运行失败只广播 onError、不产生 agent_run（无数据库，改为断言监听器口径）")
    void contractFailureOnlyBroadcastsOnError() {
        ResponseEntity<Map> response = rest.exchange(url("/agents/failAssistant/chat"),
                HttpMethod.POST, jsonEntity(Map.of("message", "随便问点什么")), Map.class);

        // 失败是 500（服务端错误），与"未知 Agent 名 → 404"是两条不同的路径
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(monitorListener.count("onError")).isGreaterThanOrEqualTo(1);
        assertThat(monitorListener.details("onError").get(0)).contains("ModelException");
        // 关键断言：失败路径不产生 onAgentEnd —— 对应"不产生 agent_run 行"
        assertThat(monitorListener.count("onAgentEnd")).isZero();
    }

    @Test
    @DisplayName("契约⑤' 失败的运行在 SSE 端点上发 error 事件（而不是 done）")
    void contractFailureOnStreamEmitsError() {
        String body = sse("/agents/failAssistant/stream", "随便问点什么").getBody();

        assertThat(eventTypes(body)).contains("error");
        assertThat(eventTypes(body)).doesNotContain("done");
        assertThat(eventData(body, "error").get(0)).contains("message");
    }

    @Test
    @DisplayName("契约⑥ SSE 里没有 tool_result 事件（工具结果只在 done 的 toolCalls 里）")
    void contractNoToolResultEventInSse() {
        String body = sse("/agents/chatAssistant/stream", "北京天气怎么样？").getBody();

        assertThat(eventTypes(body)).doesNotContain("tool_result");
        assertThat(eventData(body, "done").get(0)).contains("\"toolCalls\"");
    }

    @Test
    @DisplayName("契约⑥' SSE 的事件名是一个封闭集合：只有 delta / thinking / tool_call / done / error")
    void contractSseEventNamesAreClosedSet() {
        // 走完"思考 + 工具 + 收尾"的完整路径：thinkingAssistant 会额外发 thinking 事件
        String mixed = sse("/agents/thinkingAssistant/stream", "北京天气怎么样？").getBody();
        assertThat(eventTypes(mixed)).isSubsetOf("delta", "thinking", "tool_call", "done", "error");
        assertThat(eventTypes(mixed)).contains("thinking", "tool_call", "done");

        // 失败路径只发 error（不发 done），同样不越界
        String failed = sse("/agents/failAssistant/stream", "随便问点什么").getBody();
        assertThat(eventTypes(failed)).isSubsetOf("delta", "thinking", "tool_call", "done", "error");
        assertThat(eventTypes(failed)).containsExactly("error");
    }

    @Test
    @DisplayName("契约⑥'' SSE 的 Content-Type 不带 charset：字节确实是 UTF-8，但客户端必须自己按 UTF-8 解")
    void contractSseCharsetIsNotDeclared() {
        ResponseEntity<byte[]> response = sseBytes("/agents/chatAssistant/stream", "自我介绍");

        // 实测：produces = MediaType.TEXT_EVENT_STREAM_VALUE，响应头里没有 charset。
        // Spring 的 StringHttpMessageConverter 对 text/* 默认按 ISO-8859-1 解，
        // 于是用 body(String.class)/getForEntity(..., String.class) 拿到的中文是乱码
        // （见 ObservabilityDemo 里"按 UTF-8 显式解字节"的说明）。
        assertThat(String.valueOf(response.getHeaders().getContentType()))
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
                .doesNotContain("charset");

        // 字节本身是合法 UTF-8：显式解码就能看到中文（说明服务端没发错编码，只是没声明）
        String decoded = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(decoded).contains("event:delta");
        assertThat(decoded).contains("离线模型");
        // 反过来，按 ISO-8859-1 解就会坏掉 —— 这正是 Spring 客户端的默认选择
        assertThat(new String(response.getBody(), StandardCharsets.ISO_8859_1)).doesNotContain("离线模型");
    }
}
