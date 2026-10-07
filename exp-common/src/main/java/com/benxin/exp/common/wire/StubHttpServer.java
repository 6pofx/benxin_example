/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test 的 testkit：
 *   benxin_test/testkit/src/main/java/com/benxin/testkit/http/StubHttpServer.java
 * 改编内容：包名，以及读取请求体用的 JSON 门面换成插件自带的 Json（只影响断言侧的解析，
 * 不影响桩服务吐出的协议原文 —— 后者由同包的 WireJson 用独立 mapper 生成）。
 */
package com.benxin.exp.common.wire;

import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 嵌入式 HTTP 桩服务：让 benxin 的<b>真实</b>传输层（{@code JdkHttpTransport}）连上一个真实的 TCP 端点。
 *
 * <p>为什么不用 {@code HttpTransport} 的假实现？因为那样测不到：
 * URL 是怎么拼的、认证头叫什么名字、请求体是不是协议要求的形状、
 * SSE 是不是真的按 {@code data:} 分帧、超时与重试是不是走通了 socket。
 * 桩服务把这些问题摊开成一个真实 HTTP 端点，同时把每一笔往来完整录下来，
 * 于是"协议一致性"变成一个可以被断言的对象。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * try (StubHttpServer server = StubHttpServer.start()) {
 *     server.onPath("/v1/chat/completions", req -> Reply.openAiText("你好"));
 *     ModelConfig config = ModelConfig.builder("deepseek")
 *             .baseUrl(server.baseUrl()).apiKey("sk-test").model("deepseek-chat").build();
 *     LlmModel model = ModelFactory.create(config);
 *     ChatResponse response = model.chat(ChatRequest.builder().message(ChatMessage.user("hi")).build());
 *
 *     assertEquals("你好", response.text());
 *     assertEquals("Bearer sk-test", server.lastRequest().header("Authorization"));
 * }
 * }</pre>
 */
public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RouteEntry> routes = Collections.synchronizedList(new ArrayList<>());
    private final BlockingDeque<Reply> defaultQueue = new LinkedBlockingDeque<>();
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger requestSeq = new AtomicInteger();

    private StubHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** 在回环地址的随机端口上启动。用 0 端口可以避免测试之间的端口冲突。 */
    public static StubHttpServer start() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AtomicInteger seq = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "stub-llm-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            httpServer.setExecutor(pool);
            StubHttpServer stub = new StubHttpServer(httpServer, pool);
            httpServer.createContext("/", stub::dispatch);
            httpServer.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 HTTP 桩服务", e);
        }
    }

    /** 例如 {@code http://127.0.0.1:53124}，可直接作为 {@code base-url}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ------------------------------------------------------------------
    // 路由注册
    // ------------------------------------------------------------------

    /** 通用注册：只要路径满足断言就交给这个处理器。 */
    public StubHttpServer on(Predicate<String> pathMatcher, Route route) {
        routes.add(new RouteEntry(pathMatcher, Objects.requireNonNull(route, "route")));
        return this;
    }

    /** 精确路径匹配，例如 {@code /v1/chat/completions}。 */
    public StubHttpServer onPath(String exactPath, Route route) {
        return on(exactPath::equals, route);
    }

    /** 前缀匹配，例如 {@code /v1beta/models}。 */
    public StubHttpServer onPrefix(String prefix, Route route) {
        return on(path -> path.startsWith(prefix), route);
    }

    /** 后缀匹配；Gemini 的 {@code :generateContent} 这类端点用它最方便。 */
    public StubHttpServer onSuffix(String suffix, Route route) {
        return on(path -> path.endsWith(suffix), route);
    }

    /** 把回复排进默认队列：没命中任何路由时按 FIFO 依次吐。 */
    public StubHttpServer enqueue(Reply reply) {
        defaultQueue.add(reply);
        return this;
    }

    public StubHttpServer enqueue(int status, String body) {
        return enqueue(Reply.json(status, body));
    }

    // ------------------------------------------------------------------
    // 请求录制
    // ------------------------------------------------------------------

    /** 按到达顺序返回全部请求。 */
    public List<Recorded> requests() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    public int requestCount() {
        return recorded.size();
    }

    public Optional<Recorded> lastRequest() {
        synchronized (recorded) {
            return recorded.isEmpty() ? Optional.empty() : Optional.of(recorded.get(recorded.size() - 1));
        }
    }

    /** 最后一次命中某个路径片段的请求。用于同一测试里同时打多个协议的场景。 */
    public Optional<Recorded> lastRequestMatching(String pathFragment) {
        synchronized (recorded) {
            for (int i = recorded.size() - 1; i >= 0; i--) {
                if (recorded.get(i).path().contains(pathFragment)) {
                    return Optional.of(recorded.get(i));
                }
            }
            return Optional.empty();
        }
    }

    public List<Recorded> requestsMatching(String pathFragment) {
        synchronized (recorded) {
            return recorded.stream().filter(r -> r.path().contains(pathFragment)).toList();
        }
    }

    /** 清空录制的请求与默认队列（路由保留）。 */
    public void reset() {
        recorded.clear();
        defaultQueue.clear();
        requestSeq.set(0);
    }

    /**
     * 清空全部路由。
     *
     * <p>给"同一份桩服务要服务很多互不相干的场景"的用法准备的：路由是<b>按注册顺序</b>匹配的，
     * 一条宽泛的路由（例如"任何路径都回 503"）会一直留在表里，把之后所有场景的请求都截胡 ——
     * 而场景之间本该彼此独立。测试里每进入一个新场景就清一次，是让"这一节抓到的请求"
     * 重新变成确定事实的最省事办法。</p>
     *
     * <p>注意它<b>不</b>清默认队列（{@link #enqueue} 排进去的回复）：两者解决的是不同问题，
     * "路由残留"是要清掉的污染，而"队列里排好的下一批回复"往往正是调用方刻意准备的。</p>
     *
     * @return this，便于链式调用
     */
    public StubHttpServer clearRoutes() {
        synchronized (routes) {
            routes.clear();
        }
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void dispatch(HttpExchange exchange) throws IOException {
        Recorded request = capture(exchange);
        recorded.add(request);
        Reply reply = resolve(request);
        if (reply == null) {
            write(exchange, Reply.json(404, "{\"error\":{\"message\":\"桩服务未配置该路径: "
                    + request.path() + "\"}}"));
            return;
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        write(exchange, reply);
    }

    private Reply resolve(Recorded request) {
        List<RouteEntry> snapshot;
        synchronized (routes) {
            snapshot = List.copyOf(routes);
        }
        for (RouteEntry entry : snapshot) {
            if (entry.matcher().test(request.path())) {
                return entry.route().handle(request);
            }
        }
        return defaultQueue.poll();
    }

    private Recorded capture(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
        String rawQuery = exchange.getRequestURI().getRawQuery();
        return new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                rawQuery == null ? "" : rawQuery,
                Collections.unmodifiableMap(headers),
                body,
                requestSeq.incrementAndGet(),
                System.currentTimeMillis());
    }

    private static void write(HttpExchange exchange, Reply reply) throws IOException {
        byte[] payload = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        if (reply.sse()) {
            if (!reply.headers().containsKey("Content-Type")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            // 长度为 0 表示使用 chunked 编码：SSE 必须是流式的，不能先算总长
            exchange.sendResponseHeaders(reply.status(), 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String frame : reply.frames()) {
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } else {
            if (!reply.headers().containsKey("Content-Type")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            }
            exchange.sendResponseHeaders(reply.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    private record RouteEntry(Predicate<String> matcher, Route route) {
    }

    /** 一个路由的处理器：拿到录下来的请求，返回一个回复。 */
    @FunctionalInterface
    public interface Route {
        Reply handle(Recorded request);
    }

    /**
     * 一笔已录制的请求。
     *
     * <p>{@code headers} 的 key 保持原始大小写（HTTP 头本身大小写不敏感，
     * {@link #header(String)} 会做忽略大小写的查找）。</p>
     */
    public record Recorded(String method,
                           String path,
                           String query,
                           Map<String, List<String>> headers,
                           String body,
                           int seq,
                           long receivedAtMillis) {

        /**
         * 手工构造一笔录制记录。
         *
         * <p>给"录制型 HttpTransport"这类不在 HTTP 桩服务里的场景用：
         * 它与桩服务录下来的东西是同一个形状，于是美化打印与断言可以共用一套代码。</p>
         *
         * @param method   HTTP 方法
         * @param path     路径（含查询串也没问题，只是 {@link #query()} 会是空的）
         * @param headers  请求头，key 保持原始大小写
         * @param body     请求体原文
         * @param seq      序号，仅用于展示
         */
        public static Recorded of(String method, String path, Map<String, List<String>> headers,
                                  String body, int seq) {
            return new Recorded(method, path, "", headers == null ? Map.of() : headers, body, seq,
                    System.currentTimeMillis());
        }

        /** 忽略大小写地取请求头。 */
        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        /** 把请求体解析成 JSON 树；不是 JSON 时抛异常，便于立刻定位问题。 */
        public JsonNode json() {
            return Json.parse(body);
        }

        /** 请求体解析成 JSON，失败返回 {@code null}（用于"这里本来就不该是 JSON"的场景）。 */
        public JsonNode jsonQuietly() {
            return Json.parseQuietly(body);
        }

        public boolean hasQuery(String key) {
            return query != null && query.contains(key + "=");
        }

        public String queryParam(String key) {
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(key)) {
                    return pair.substring(eq + 1);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return method + " " + path + (query == null || query.isBlank() ? "" : "?" + query)
                    + " <- " + Json.abbreviate(body);
        }
    }

    /** 桩服务的回复。 */
    public record Reply(int status,
                        Map<String, String> headers,
                        String body,
                        List<String> frames,
                        boolean sse,
                        long delayMillis) {

        public Reply {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            frames = frames == null ? List.of() : List.copyOf(frames);
        }

        /** 200 + JSON 正文。 */
        public static Reply json(String body) {
            return new Reply(200, Map.of(), body, List.of(), false, 0);
        }

        /** 指定状态码 + 正文（用于构造 429 / 503 等瞬时错误）。 */
        public static Reply json(int status, String body) {
            return new Reply(status, Map.of(), body, List.of(), false, 0);
        }

        /** 200 + JSON 正文，但附加自定义响应头。 */
        public static Reply json(String body, Map<String, String> headers) {
            return new Reply(200, headers, body, List.of(), false, 0);
        }

        /**
         * SSE 流式回复。
         *
         * <p>每个 frame 会被<b>原样</b>写入 socket，因此需要自己带上 {@code data: } 前缀
         * 与结尾的空行 —— 这个"自己写帧"的能力正是用来验证 SSE 解析器边界行为
         * （多行 data、注释行、CRLF、缺尾空行）所必需的。</p>
         */
        public static Reply sseFrames(String... frames) {
            return new Reply(200, Map.of("Content-Type", "text/event-stream; charset=utf-8"),
                    null, List.of(frames), true, 0);
        }

        public static Reply sseFrames(List<String> frames) {
            return new Reply(200, Map.of("Content-Type", "text/event-stream; charset=utf-8"),
                    null, frames, true, 0);
        }

        /** 每个 payload 自动包装成 {@code data: <payload>\n\n}。 */
        public static Reply sseData(String... payloads) {
            List<String> frames = new ArrayList<>();
            for (String payload : payloads) {
                frames.add("data: " + payload + "\n\n");
            }
            return sseFrames(frames);
        }

        public static Reply sseData(List<String> payloads) {
            return sseData(payloads.toArray(String[]::new));
        }

        /** 追加 OpenAI 风格的结束标记。 */
        public Reply withDone() {
            List<String> more = new ArrayList<>(frames);
            more.add("data: [DONE]\n\n");
            return new Reply(status, headers, body, more, true, delayMillis);
        }

        /** 追加任意原始帧。 */
        public Reply withFrame(String frame) {
            List<String> more = new ArrayList<>(frames);
            more.add(frame);
            return new Reply(status, headers, body, more, sse, delayMillis);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(name, value);
            return new Reply(status, more, body, frames, sse, delayMillis);
        }

        /** 人为延迟，用于验证读超时 / 重试退避。 */
        public Reply delay(long millis) {
            return new Reply(status, headers, body, frames, sse, millis);
        }
    }
}

