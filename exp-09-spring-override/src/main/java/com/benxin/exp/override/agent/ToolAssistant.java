package com.benxin.exp.override.agent;

import com.benxin.llm.core.annotation.LlmAgent;

/**
 * 显式点名 {@code stub} 模型的声明式 Agent。
 *
 * <p>为什么不跟 {@link SupportAssistant} 一样留空：{@code model = "stub"} 这个端点名字
 * 恰好也是 {@code DefaultOverrideConfiguration.stubLlmModel()} 那个 bean 的
 * {@code @LlmModelDef} 名字。于是这一个 Agent 就能同时证明两件事：</p>
 * <ol>
 *   <li>"容器里的 {@code LlmModel} bean 会以注解名进模型表"（yml 里的 {@code llm.models.stub} 被它覆盖）；</li>
 *   <li>被覆盖之后的模型走的是<b>我自己换过的 {@code HttpTransport}</b>——
 *       调用它能打到 {@code StubHttpServer} 上，而默认的 {@code JdkHttpTransport}
 *       只会打到 {@code https://api.example.invalid}（yml 里那个假域名）。</li>
 * </ol>
 */
@LlmAgent(model = "stub",
        toolNames = {"get_weather", "calculate"},
        systemPrompt = "你是 exp-09 的工具助手，需要外部信息时请调用工具。")
public interface ToolAssistant {

    /** 一句会触发工具调用的话（离线脚本模型按关键词挑工具）。 */
    String ask(String question);
}
