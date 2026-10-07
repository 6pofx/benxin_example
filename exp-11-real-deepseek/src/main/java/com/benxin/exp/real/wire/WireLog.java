package com.benxin.exp.real.wire;

import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实报文的留档仓库：把每一次 HTTP 往来的 URL / 请求头（脱敏）/ 请求体 / 响应体写进
 * {@code target/wire-log/}，同时留一份内存摘要供演示直接打印。
 *
 * <p>这是"对着真实报文学习协议"的证据链：模型输出的文本可以骗人，
 * 报文不行 —— 请求体里到底有没有 {@code tools}、{@code max_tokens} 是不是 48、
 * 响应体的 {@code status} 与 {@code usage} 长什么样，看文件。</p>
 *
 * <p><b>脱敏是本类的硬约束</b>：{@code Authorization} 一律写成 {@code Bearer sk-****}，
 * 任何名字里带 {@code key} 的请求头一律写成 {@code ****}。真实 key 绝不落盘、绝不打印。</p>
 */
@Component
public class WireLog {

    /** 本地回环主机名，用于区分"打桩测试"与"真实联网"。 */
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "::1", "0.0.0.0");

    /** 一笔已留档的往来（内存摘要，文件路径一并带上）。 */
    public record Entry(int seq,
                        String kind,
                        String url,
                        int status,
                        Map<String, String> requestHeaders,
                        String requestBody,
                        String responseBody,
                        String responseRaw,
                        Path file) {

        /** 响应是不是 SSE 原文（流式请求没有整体 JSON 响应体）。 */
        public boolean streaming() {
            return responseRaw != null;
        }

        /** 单行摘要，演示里直接打印。 */
        public String summary() {
            String target = streaming() ? summarizeSse(responseRaw) : Json.abbreviate(responseBody);
            return "#" + seq + " " + kind + " " + status + " " + url + " ← " + target;
        }

        private static String summarizeSse(String raw) {
            long frames = raw.lines().filter(line -> line.startsWith("data:")).count();
            return "SSE " + frames + " 帧 / " + raw.length() + " 字符";
        }
    }

    private final Path dir;
    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger realRequests = new AtomicInteger();

    public WireLog(Environment env) {
        this.dir = Paths.get(env.getProperty("exp.wire-log.dir", "target/wire-log")).toAbsolutePath().normalize();
    }

    /** 留档目录（绝对路径，演示里会打印出来）。 */
    public Path dir() {
        return dir;
    }

    /** 记录一次非流式往来。 */
    public Entry recordPost(String url, Map<String, String> headers, String requestBody,
                            int status, String responseBody) {
        return record("post", url, headers, requestBody, status, responseBody, null);
    }

    /** 记录一次流式往来（响应是 SSE 原文）。 */
    public Entry recordStream(String url, Map<String, String> headers, String requestBody,
                              int status, String rawSse) {
        return record("stream", url, headers, requestBody, status, null, rawSse);
    }

    /** 全部留档摘要（按发生顺序）。 */
    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** 最近一笔留档。 */
    public Entry last() {
        synchronized (entries) {
            return entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }
    }

    /** 收到的 HTTP 请求总数（含打桩服务）。 */
    public int requests() {
        return requests.get();
    }

    /** 其中打到**真实端点**（非回环地址）的请求数 —— 这是"一次调用 = 一笔请求"的硬证据。 */
    public int realRequests() {
        return realRequests.get();
    }

    /** 清空计数与内存摘要（磁盘文件保留，便于事后翻看）。 */
    public void reset() {
        synchronized (entries) {
            entries.clear();
        }
        seq.set(0);
        requests.set(0);
        realRequests.set(0);
    }

    /** 该 URL 是否指向真实端点（非本机回环）。 */
    public static boolean isRealEndpoint(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return true;
            }
            return LOOPBACK_HOSTS.stream().noneMatch(host.toLowerCase(Locale.ROOT)::equals);
        } catch (IllegalArgumentException e) {
            // URL 解析不了说明本来就不是本机打桩，按真实端点计数更安全
            return true;
        }
    }

    // ------------------------------------------------------------------

    private Entry record(String kind, String url, Map<String, String> headers, String requestBody,
                         int status, String responseBody, String responseRaw) {
        int index = seq.incrementAndGet();
        requests.incrementAndGet();
        if (isRealEndpoint(url)) {
            realRequests.incrementAndGet();
        }
        Path file = write(index, kind, url, headers, requestBody, status, responseBody, responseRaw);
        Entry entry = new Entry(index, kind, url, status, sanitize(headers), requestBody,
                responseBody, responseRaw, file);
        entries.add(entry);
        return entry;
    }

    private Path write(int index, String kind, String url, Map<String, String> headers, String requestBody,
                       int status, String responseBody, String responseRaw) {
        ObjectNode root = Json.object();
        root.put("seq", index);
        root.put("kind", kind);
        root.put("url", url);
        ObjectNode headerNode = root.putObject("requestHeaders");
        sanitize(headers).forEach(headerNode::put);
        setJsonOrText(root, "request", requestBody);
        root.put("status", status);
        if (responseRaw != null) {
            // 流式：SSE 原文（多帧拼接）原样保留，读文件就能看到每一帧的真实形状
            root.put("responseRaw", responseRaw);
        } else {
            setJsonOrText(root, "response", responseBody);
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("%03d-%s.json", index, kind));
            Files.writeString(file, pretty(root), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写入报文留档失败: " + dir, e);
        }
    }

    /** 能解析成 JSON 就嵌成对象（便于阅读），否则原样存字符串（绝不丢信息）。 */
    private static void setJsonOrText(ObjectNode root, String field, String text) {
        if (text == null) {
            root.putNull(field);
            return;
        }
        var node = Json.parseQuietly(text);
        if (node != null) {
            root.set(field, node);
        } else {
            root.put(field, text);
        }
    }

    private static String pretty(ObjectNode node) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    /**
     * 请求头脱敏：{@code Authorization} → {@code Bearer sk-****}，
     * 其余名字里带 {@code key} 的头 → {@code ****}。
     */
    public static Map<String, String> sanitize(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        headers.forEach((name, value) -> result.put(name, sanitize(name, value)));
        return result;
    }

    /** 单个请求头的脱敏规则。 */
    public static String sanitize(String name, String value) {
        if (name == null || value == null) {
            return value;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean sensitive = lower.equals("authorization") || lower.contains("api-key")
                || lower.contains("apikey") || lower.contains("token");
        if (!sensitive) {
            return value;
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return "Bearer sk-****";
        }
        return "****";
    }
}
