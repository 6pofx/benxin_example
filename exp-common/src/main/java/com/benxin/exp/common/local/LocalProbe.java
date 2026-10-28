package com.benxin.exp.common.local;

import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机 OpenAI 兼容端点（LM Studio / Ollama / llama.cpp）的<b>最小探测</b>。
 *
 * <p>exp-12 与 exp-13 各自带了一个更完整的 {@code LocalModelGate}（它们要按
 * {@code auto/on/off} 三态决定"跑不跑真实演示"，还缓存探测结果）。这里只保留两个问题：
 * <b>能不能连上</b>、<b>都装了哪些模型</b> —— 给只需要在演示开头打印一句状态的模块用。</p>
 *
 * <p>刻意做成静态工具而不是 {@code @Component}：它没有状态，也不该被容器管。
 * 谁需要谁调，连不上时拿到的是一个可展示的失败原因，而不是异常。</p>
 */
public final class LocalProbe {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private LocalProbe() {
    }

    /**
     * 探测结果。
     *
     * @param reachable 端点是哪个端口都能连上（只看 {@code /v1/models} 是否 2xx）
     * @param models    端点当前可见的模型 id
     * @param failure   连不上时的原因；连得上时为 {@code null}
     */
    public record Result(boolean reachable, List<String> models, String failure) {

        /** 一句话状态，可直接打给用户看。 */
        public String describe(String baseUrl) {
            if (reachable) {
                return "端点 " + baseUrl + " 可达，可见模型 " + models;
            }
            return "端点 " + baseUrl + " 不可达（" + failure + "）";
        }

        /** 期望的模型是否在列表里 —— 只提示，不阻断（LM Studio 会按需装载）。 */
        public boolean contains(String modelId) {
            return models.stream().anyMatch(id -> id.equalsIgnoreCase(modelId));
        }
    }

    /** 探测 {@code baseUrl}（不带 {@code /v1}）上的 {@code GET /v1/models}。 */
    public static Result probe(String baseUrl) {
        String base = stripTrailingSlash(baseUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/models"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new Result(false, List.of(), "HTTP " + response.statusCode());
            }
            return new Result(true, parseModelIds(response.body()), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, List.of(), "探测被中断");
        } catch (Exception e) {
            return new Result(false, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 从 {@code /v1/models} 的响应体里取出模型 id 列表。 */
    public static List<String> parseModelIds(String body) {
        List<String> ids = new ArrayList<>();
        JsonNode root = Json.parseQuietly(body);
        if (root == null) {
            return ids;
        }
        for (JsonNode item : root.path("data")) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
