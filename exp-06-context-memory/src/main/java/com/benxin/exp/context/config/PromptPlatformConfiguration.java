package com.benxin.exp.context.config;

import com.benxin.exp.context.prompt.JdbcPromptPlatform;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 提示词来源的装配：把 {@code SystemPromptProvider} 换成"表驱动的提示词平台"。
 *
 * <p>插件默认的 {@code TemplateSystemPromptProvider} 会把
 * {@code @LlmAgent(systemPrompt = ...)} 当作模板做 {@code {变量}} 插值。换成
 * {@link JdbcPromptPlatform} 之后，模板的来源变成数据库里的一行，
 * 而 {@code @LlmAgent} 上的提示词退化成"平台没配时的兜底"。</p>
 *
 * <p>要注意一条容易被忽略的边界：<b>方法级 {@code @SystemPrompt} 走的是另一条路</b> ——
 * 声明式代理把它渲染好之后放进运行属性 {@code benxin.systemPrompt}，
 * {@code DefaultAgent} 优先取属性值，因此方法上写了 {@code @SystemPrompt} 时
 * 本 provider 根本不会被调用（示例测试直接断言了这一点）。</p>
 */
@Configuration
public class PromptPlatformConfiguration {

    @Bean
    public JdbcPromptPlatform systemPromptProvider(JdbcTemplate exp06JdbcTemplate) {
        return new JdbcPromptPlatform(exp06JdbcTemplate);
    }
}
