package com.benxin.exp.local.config;

import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 本地端点开关与探测。
 *
 * <p>为什么"本机端点"也需要一个开关？因为它同样是**可能不在的外部依赖**：
 * 同事 clone 下来没装 LM Studio、CI 上没有桌面环境 —— 这两种情况下
 * "连不上 localhost"不应该让示例或测试失败，只应该让真实演示**跳过并说明原因**。</p>
 *
 * <p>三种模式：</p>
 * <ul>
 *   <li>{@code auto}（默认）：探一次 {@code GET /v1/models}，可达 → 跑真实演示；不可达 → 只跑离线演示；</li>
 *   <li>{@code on}：强制真实演示，不可达时报错（"我就是要测它"时用，避免静默跳过）；</li>
 *   <li>{@code off}：强制离线（CI 用，连探测都不做）。</li>
 * </ul>
 *
 * <p>探测用的是 JDK 自带的 {@link HttpClient}，超时 2 秒 —— 端口没开时是"立刻拒绝"，
 * 不会有可感知的等待。</p>
 */
@Component
public class LocalModelGate {

    private static final Logger log = LoggerFactory.getLogger(LocalModelGate.class);

    /** 探测用客户端：连不上时应当"立刻失败"，所以连接超时给 2 秒。 */
    private static final HttpClient PROBE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String mode;
    private final String baseUrl;
    private final String model;

    /** 探测结果的缓存：一次进程只探一次（端点不会在演示中途出现或消失）。 */
    private volatile List<String> probedModels;
    private volatile String probeFailure;

    public LocalModelGate(@Value("${exp.local.mode:auto}") String mode,
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

    /** 期望下发给上游的模型 id（与 yml 里 {@code llm.models.local-lmstudio.model} 一致）。 */
    public String model() {
        return model;
    }

    public String chatCompletionsUrl() {
        return baseUrl + "/v1/chat/completions";
    }

    /** 端点当前加载了哪些模型；探测失败时返回空表。 */
    public List<String> availableModels() {
        probe();
        return probedModels == null ? List.of() : probedModels;
    }

    /** 探测失败的原因（成功时为 {@code null}）。 */
    public String probeFailure() {
        probe();
        return probeFailure;
    }

    /** 端点是否可达。 */
    public boolean available() {
        probe();
        return probeFailure == null;
    }

    /** 是否应该跑真实演示。 */
    public boolean enabled() {
        return switch (mode) {
            case "on" -> true;
            case "off" -> false;
            default -> available();
        };
    }

    /**
     * 给演示/测试用的一句话结论 —— 让"为什么这次没跑真实演示"永远看得见，
     * 而不是让人对着一段安静的日志猜。
     */
    public String describe() {
        probe();
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode).append("，端点 ").append(baseUrl);
        if (probeFailure == null) {
            sb.append(" 可达，已加载模型 ").append(probedModels);
        } else {
            sb.append(" 不可达（").append(probeFailure).append("）");
        }
        sb.append(" → 真实演示 ").append(enabled() ? "会执行" : "跳过");
        if ("on".equals(mode) && probeFailure != null) {
            sb.append("（mode=on 却连不上：这是配置错误，不是跳过）");
        }
        return sb.toString();
    }

    /**
     * 真实演示前的最后一次确认。
     *
     * @throws IllegalStateException {@code mode=on} 但端点不可达 —— 此时静默跳过才是错的
     */
    public void requireEnabled() {
        if ("on".equals(mode) && !available()) {
            throw new IllegalStateException("exp.local.mode=on 但本地端点不可达：" + describe());
        }
        if (!enabled()) {
            throw new IllegalStateException("本地端点不可用：" + describe());
        }
    }

    private void probe() {
        if (probedModels != null || probeFailure != null) {
            return;
        }
        synchronized (this) {
            if (probedModels != null || probeFailure != null) {
                return;
            }
            if ("off".equals(mode)) {
                probeFailure = "mode=off（刻意不探测）";
                return;
            }
            try {
                // 注意：JDK 17 的 HttpClient 还没有实现 AutoCloseable（那是 JDK 21 的事），
                // 所以这里不能写 try-with-resources；用一个静态客户端复用连接即可。
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = PROBE_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    probeFailure = "HTTP " + response.statusCode();
                    return;
                }
                probedModels = parseModelIds(response.body());
                log.info("[exp-12] 本地端点可用，已加载模型 {}", probedModels);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                probeFailure = "探测被中断";
            } catch (Exception e) {
                probeFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
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

    /** 当前端点是否真的加载了我们要用的那个模型 —— 只提示，不阻断（LM Studio 会自动换载）。 */
    public boolean modelLooksLoaded() {
        return availableModels().stream().anyMatch(id -> id.equalsIgnoreCase(model));
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
