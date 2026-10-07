package com.benxin.exp.models.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 演示用的模型端点参数，绑定到 {@code exp.model.*}。
 *
 * <p>为什么还要一个自己的前缀：{@code llm.models.<key>.*} 里只放"四个协议的装配"，
 * 而 {@code ModelConfig} 的<b>全字段</b>（超时、重试、headers、extra…）需要一份可以
 * 对着桩服务逐条断言的样本。把样本放在独立的 {@code exp.model.*} 下，
 * 既不会污染"协议演示"那几条配置，也便于用 {@code @SpringBootTest(properties = …)}
 * 覆盖其中任意一项来观察它怎么进到请求里。</p>
 *
 * <p>字段刻意与 {@code llm.models.<key>.*} 保持一一对应，
 * 于是 {@link #toModelConfig(String)} 与 starter 的
 * {@code LlmProperties.ModelProperties#toModelConfig} 可以直接对照着读 ——
 * 这是"yml 与 java 两条通道最终落到同一个 ModelConfig"的证据。</p>
 *
 * @param name              模型注册名
 * @param protocol          协议名（openai / anthropic / gemini / responses / demo）
 * @param baseUrl           端点基址；{@code null} 或空串时由 {@link #resolvedBaseUrl()} 兜底
 * @param apiKey            API key
 * @param model             上游模型 id
 * @param headers           附加请求头（可覆盖内置认证头）
 * @param connectTimeout    建连超时
 * @param readTimeout       非流式读超时
 * @param streamReadTimeout 流式读超时
 * @param maxRetries        瞬时错误重试次数（不含首次调用）
 * @param temperature       采样温度
 * @param maxTokens         输出上限
 * @param extra             直接透传到请求体顶层的厂商私有参数
 */
@ConfigurationProperties(prefix = "exp.model")
public record ModelSettings(String name,
                            String protocol,
                            String baseUrl,
                            String apiKey,
                            String model,
                            java.util.Map<String, String> headers,
                            Duration connectTimeout,
                            Duration readTimeout,
                            Duration streamReadTimeout,
                            Integer maxRetries,
                            Double temperature,
                            Integer maxTokens,
                            java.util.Map<String, Object> extra) {

    /**
     * 在构造时把缺省值补齐。
     *
     * <p>用 record 而不是 setter 绑定：record 的规范构造器天然就是"构造器绑定"，
     * 于是"这份配置一定是完整的"成为类型层面的事实 —— 下面的演示与测试都直接读它，
     * 不必到处判空。</p>
     */
    public ModelSettings {
        name = name == null || name.isBlank() ? "settings" : name;
        protocol = protocol == null || protocol.isBlank() ? "openai" : protocol;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model;
        headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        extra = extra == null ? java.util.Map.of() : java.util.Map.copyOf(extra);
    }

    /**
     * yml / 程序化装配时没写 base-url 的回落值：本地桩服务地址。
     *
     * <p>刻意<b>不</b>回落到任何厂商默认地址 —— 插件内置的 Codec 在 baseUrl 为空时
     * 会退到 {@code https://api.openai.com} 之类的官方端点，而本示例的硬约定是
     * "进程内不存在任何会连外网的模型"。所以这里兜底成桩服务地址；
     * 桩服务没起来时返回空串，让调用立刻以"未配置 baseUrl"失败。</p>
     */
    public String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        String stub = com.benxin.exp.models.support.StubRegistry.baseUrl();
        return stub == null ? "" : stub;
    }
}
