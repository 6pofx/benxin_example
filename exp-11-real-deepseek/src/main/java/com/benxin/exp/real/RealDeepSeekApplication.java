package com.benxin.exp.real;

import com.benxin.exp.real.config.ApiKeyResolver;
import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 11 —— 真实 DeepSeek 模型。
 *
 * <p>本模块只做一件事：把「本心」接到<b>真实端点</b>上，逐条验证那些离线模型验证不了的东西 ——
 * 认证头、真实 SSE 分帧、真实 {@code tool_calls}、Responses 协议的报文形状、401 的异常形态。
 * 同时把 token 消耗压到极低：默认<b>不联网</b>，打开开关后每个演示最多一次调用，
 * 每次 {@code max-tokens=48}、{@code max-retries=0}。</p>
 *
 * <p>三个必须先记住的事实：</p>
 * <ol>
 *   <li><b>默认 profile 里没有 deepseek 端点</b>：真实模型配置只出现在 application-real.yml，
 *       所以"忘了关开关"不可能导致联网；</li>
 *   <li>{@code @EnableLlmAgents} 是必写的（starter 不会替你扫 {@code @LlmAgent} 接口），
 *       不写 {@code basePackages} 时从主应用包推断；</li>
 *   <li>真实调用的闸门是 {@code exp.real.enabled}，而不是"有没有 key" —— key 只是第二道闸门。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.real")
public class RealDeepSeekApplication {

    public static void main(String[] args) {
        // 把"Java 侧看到的 key"桥接成系统属性 deepseek.api-key，
        // 这样 yml 里的 ${DEEPSEEK_API_KEY:${deepseek.api-key:}} 与代码侧的取值口径同源。
        // 必须在 SpringApplication.run 之前执行 —— 占位符是在 Environment 准备阶段解析的。
        ApiKeyResolver.bridgeToSystemProperty();
        SpringApplication.run(RealDeepSeekApplication.class, args);
    }
}
