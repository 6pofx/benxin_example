package com.benxin.exp.models.config;

import com.benxin.exp.models.support.StubRegistry;
import com.benxin.llm.core.model.ModelConfig;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;

/**
 * {@code application.yml} 里 {@code ${exp.stub.base-url}} 的最终解释者，同时兼任
 * "yml / 程序化两条通道 → 同一个 {@link ModelConfig}" 的转换器。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>本示例的硬性约定是<b>完全离线</b>：四条协议通道的 base-url 都必须指向本地桩服务。
 * 但桩服务的端口是<b>启动后</b>才确定的，而 {@code application.yml} 是在启动时解析的 ——
 * 于是需要一个"在配置绑定之前就已经知道答案"的来源。</p>
 *
 * <p>取值的优先级刻意是这样：</p>
 * <ol>
 *   <li><b>进程参数</b>（{@code --exp.stub.base-url=…}）—— 手工指定时以它为准；</li>
 *   <li>{@code StubRegistry} 里已登记的桩服务（测试由 {@code StubRegistry.stub()} 提前启动）；</li>
 *   <li>系统属性 {@code exp.stub.base-url}（最外层的兜底）。</li>
 * </ol>
 *
 * <p>三者都没有时返回空串，配置里的占位符会解析成空 —— 此时任何一次真实调用都会
 * 立刻因为"未配置 baseUrl"而报错，<b>而不是悄悄连到公网</b>。这是刻意的失败方向。</p>
 */
public class BaseUrlSupplier {

    private final PropertySources sources;

    public BaseUrlSupplier(ConfigurableEnvironment environment) {
        this.sources = environment.getPropertySources();
    }

    /** 解析 {@code ${exp.stub.base-url}} 时被调用的入口。 */
    public String stubBaseUrl() {
        String fromArgs = lookup(StubRegistry.BASE_URL_PROPERTY);
        if (isPresent(fromArgs)) {
            return fromArgs;
        }
        String fromRegistry = StubRegistry.baseUrl();
        if (isPresent(fromRegistry)) {
            return fromRegistry;
        }
        String fromSystem = System.getProperty(StubRegistry.BASE_URL_PROPERTY);
        return isPresent(fromSystem) ? fromSystem : "";
    }

    /**
     * 把 {@link ModelSettings} 转成插件认识的 {@link ModelConfig}。
     *
     * <p>与 {@code LlmProperties.ModelProperties#toModelConfig} 是同一件事的两份实现 ——
     * 一份由 starter 在绑定 {@code llm.models.*} 时调用，一份在这里给"程序化装配"用。
     * 把它们并排读一遍，就能看清"yml 配置"与"new ModelConfig(...)"之间没有任何魔法。</p>
     */
    public ModelConfig toModelConfig(ModelSettings settings) {
        return ModelConfig.builder(settings.name())
                .protocol(settings.protocol())
                .baseUrl(settings.resolvedBaseUrl())
                .apiKey(settings.apiKey())
                .model(settings.model())
                .headers(settings.headers())
                .connectTimeout(settings.connectTimeout())
                .readTimeout(settings.readTimeout())
                .streamReadTimeout(settings.streamReadTimeout())
                .maxRetries(settings.maxRetries() == null ? 0 : settings.maxRetries())
                .temperature(settings.temperature())
                .maxTokens(settings.maxTokens())
                .extra(settings.extra())
                .build();
    }

    /** 便于断言"yml 里的占位符到底解析成了什么"。 */
    public String resolvedFromYml(String key) {
        return lookup(key);
    }

    private String lookup(String key) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
