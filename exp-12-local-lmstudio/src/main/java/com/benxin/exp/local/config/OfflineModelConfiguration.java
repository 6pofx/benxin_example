package com.benxin.exp.local.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.local.tool.LocalTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线兜底模型与工具。
 *
 * <p>本模块同时注册两个模型：{@code mock}（这里）与 {@code local-lmstudio}（yml）。
 * "注册"与"调用"是两件事 —— 默认路径只用 {@code mock}，
 * 因此 LM Studio 没开时整个模块照样能跑（这正是示例要传达的：模型是可插拔的，
 * 但**不该让一个可选依赖变成启动前提**）。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    /** 注册名 {@code mock}，与 {@code llm.default-model} 对应。 */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                .rule("自我介绍", "我是 exp-12 里的离线脚本模型，用来在 LM Studio 没开时兜底。")
                .rule("保持沉默", "")
                .jsonAnswer("""
                        {"summary":"离线兜底回答","issues":[],"score":60}""")
                .fallback("（离线模型）已收到：{input}")
                .build();
        return new RecordingModel(scripted);
    }

    @Bean
    public LocalTools localTools() {
        return new LocalTools();
    }
}
