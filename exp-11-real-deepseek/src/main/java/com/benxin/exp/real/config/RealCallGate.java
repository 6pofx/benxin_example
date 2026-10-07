package com.benxin.exp.real.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 真实调用的唯一闸门：{@code exp.real.enabled}。
 *
 * <p>默认值是 {@code false}（写在 application.yml 里），两种打开方式都能被这一个组件识别：</p>
 * <ul>
 *   <li>{@code -Dexp.real.enabled=true} —— Spring 的 Environment 里就有 systemProperties，
 *       所以 {@code env.getProperty(...)} 能读到；</li>
 *   <li>{@code --spring.profiles.active=real} —— application-real.yml 里同样把它置为 true。</li>
 * </ul>
 *
 * <p>这里额外再读一次 {@link System#getProperty(String)}：不是多余，而是"闸门"这种东西
 * 不应该依赖"Environment 一定包含系统属性"这个前提 —— 一旦有人替换了
 * {@code PropertySource} 的顺序或包了一层自定义 Environment，闸门必须还是关着的那个默认值，
 * 而不是"读不到就等于打开"。</p>
 *
 * <p>注意它与 {@code @ConditionalOnProperty} 的分工：注解用来决定"要不要装配
 * {@code RealCallMetrics} 这类真实模式专属的 bean"，本类用来在演示里做运行期判断与提示。
 * 两者读的是同一个配置项。</p>
 */
@Component
public class RealCallGate {

    private final boolean enabled;
    private final String source;

    public RealCallGate(Environment env) {
        String fromEnvironment = env.getProperty("exp.real.enabled");
        String fromSystem = System.getProperty("exp.real.enabled");
        String effective = fromEnvironment != null ? fromEnvironment : fromSystem;
        this.enabled = "true".equalsIgnoreCase(effective == null ? "" : effective.trim());
        this.source = fromEnvironment != null
                ? "Spring Environment（yml / profile / -D 都汇聚在这里）"
                : (fromSystem != null ? "JVM 系统属性" : "未配置（默认关闭）");
    }

    /** 是否允许发起真实调用。 */
    public boolean enabled() {
        return enabled;
    }

    /** 这个值是从哪儿读到的，方便排查"我明明写了 -D 却没生效"。 */
    public String source() {
        return source;
    }
}
