package com.benxin.exp.observability.config;

import com.benxin.exp.observability.interceptor.OrderProbeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 三个顺序探针的注册。
 *
 * <p>它们需要"同一个类、不同 order、三个实例"，写成 {@code @Component} 是做不到的，
 * 因此这里用三个 {@code @Bean} 方法显式声明。注意 {@code OrderProbeInterceptor}
 * 上<b>没有</b> {@code @LlmGuard}：有的话 {@code LlmComponentScanner} 会再按类名
 * 注册一个 {@code orderProbeInterceptor}，同一个类就多出第四个实例，
 * "谁参与了顺序测试"会变得难以解释。</p>
 *
 * <p>这是"注解自动注册"与"显式声明"两条路径的边界示范：
 * <b>多实例 / 需要外部依赖的配 {@code @Bean}，单实例且无依赖的直接打注解。</b></p>
 */
@Configuration
public class InterceptorConfiguration {

    /** order 越小越靠外：先进入、先退出（正序，不是洋葱式的逆序）。 */
    @Bean
    public OrderProbeInterceptor orderProbeInterceptorA() {
        return new OrderProbeInterceptor("A", -30);
    }

    @Bean
    public OrderProbeInterceptor orderProbeInterceptorB() {
        return new OrderProbeInterceptor("B", -20);
    }

    @Bean
    public OrderProbeInterceptor orderProbeInterceptorC() {
        return new OrderProbeInterceptor("C", -10);
    }
}
