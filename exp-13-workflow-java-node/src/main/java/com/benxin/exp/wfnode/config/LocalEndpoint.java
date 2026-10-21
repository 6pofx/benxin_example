package com.benxin.exp.wfnode.config;

import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机模型端点的可用性探测。
 *
 * <p>exp-12 里有一份同源但更完整的实现（那里把探测结果用于"换模型还是保持离线"的决策，
 * 因此有 {@code auto/on/off} 三态与模型清单）。本模块只需要回答一个问题：<b>现在能不能连上</b> ——
 * 因为本模块没有离线模型可退，连不上就只能跳过需要模型的那张图。</p>
 *
 * <p>探测用 JDK 自带的 {@link HttpClient}，连接超时 2 秒：端口没开时是"立刻拒绝"，
 * 不会让演示停在一个看不出原因的地方。</p>
 */
@Component
public class LocalEndpoint {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String mode;
    private final String baseUrl;
    private final String model;

    private volatile Boolean reachable;
    private volatile List<String> modelIds;
    private volatile String failure;

    public LocalEndpoint(@Value("${exp.local.mode:auto}") String mode,
                         @Value("${exp.local.base-url:http://127.0.0.1:1234}") String baseUrl,
                         @Value("${exp.local.model:qwen/qwen3-vl-8b}") String model) {
        this.mode = mode == null ? "auto" : mode.trim().toLowerCase();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
    }

    public String mode() {
        return mode;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    /** 端点是否可达；探测一次后缓存（端点不会在演示中途出现或消失）。 */
    public boolean available() {
        probe();
        return Boolean.TRUE.equals(reachable);
    }

    /** 是否应该跑依赖模型的那张图。 */
    public boolean enabled() {
        return switch (mode) {
            case "on" -> true;
            case "off" -> false;
            default -> available();
        };
    }

    public List<String> availableModels() {
        probe();
        return modelIds == null ? List.of() : modelIds;
    }

    /** 端点是否真的加载了我们要用的模型 —— 只提示、不阻断（LM Studio 会自动换载）。 */
    public boolean modelLooksLoaded() {
        return availableModels().stream().anyMatch(id -> id.equalsIgnoreCase(model));
    }

    /** 一句话说明"为什么这次跑/不跑真实演示"，避免让人对着安静的日志猜。 */
    public String describe() {
        probe();
        StringBuilder sb = new StringBuilder().append("mode=").append(mode)
                .append("，端点 ").append(baseUrl);
        if (Boolean.TRUE.equals(reachable)) {
            sb.append(" 可达，已加载模型 ").append(modelIds);
            if (!modelLooksLoaded()) {
                sb.append("（注意：里面没有 ").append(model).append("，LM Studio 一般会按需换载）");
            }
        } else {
            sb.append(" 不可达（").append(failure).append("）");
        }
        sb.append(" → 需要模型的演示 ").append(enabled() ? "会执行" : "跳过");
        return sb.toString();
    }

    private void probe() {
        if (reachable != null) {
            return;
        }
        synchronized (this) {
            if (reachable != null) {
                return;
            }
            if ("off".equals(mode)) {
                reachable = false;
                failure = "mode=off（刻意不探测）";
                return;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    reachable = false;
                    failure = "HTTP " + response.statusCode();
                    return;
                }
                modelIds = parseModelIds(response.body());
                reachable = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reachable = false;
                failure = "探测被中断";
            } catch (Exception e) {
                reachable = false;
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /** 从 {@code /v1/models} 的响应里取出模型 id 列表。 */
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
