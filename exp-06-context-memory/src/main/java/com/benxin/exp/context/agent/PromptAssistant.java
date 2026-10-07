package com.benxin.exp.context.agent;

import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 提示词来源示例 Agent：把"系统提示词从哪来"的三条路径摆在一起。
 *
 * <p>三条路径的优先级是（从高到低）：</p>
 * <ol>
 *   <li><b>方法级 {@code @SystemPrompt}</b> —— 声明式代理渲染后放进运行属性
 *       {@code benxin.systemPrompt}，{@code DefaultAgent} 优先取它，
 *       因此容器里的 {@code SystemPromptProvider} 完全不会被调用；</li>
 *   <li><b>自定义 {@code SystemPromptProvider}</b> —— 只对"方法上没有 {@code @SystemPrompt}"的
 *       调用生效（见 {@link #fromProvider(String)}）；</li>
 *   <li><b>{@code @LlmAgent(systemPrompt = ...)}</b> —— 作为 provider 的输入（默认模板实现）
 *       或兜底（本示例的表驱动平台在平台里没有该 Agent 时回落）。
 *       注意本接口故意<b>不</b>写 {@code systemPrompt}，用来演示"平台里配了就用平台的，
 *       没配就什么都不注入"这条路径。</li>
 * </ol>
 */
@LlmAgent(
        name = "promptAssistant",
        model = "mock",
        loop = "dsh-minimal",
        maxSteps = 4)
public interface PromptAssistant {

    /**
     * 方法上没有 {@code @SystemPrompt}：系统提示词完全由容器里的
     * {@code SystemPromptProvider} 决定 —— 平台里有这个 Agent 就用平台的模板。
     */
    String fromProvider(@User String question);

    /** 方法级 {@code @SystemPrompt(value, inherit = false)}：只用这一段，不看 Agent 级提示词。 */
    @SystemPrompt(value = "（方法级 · inherit=false）你是严格的评审助手，只回一句话，不要寒暄。",
            inherit = false)
    String methodOverride(@User String question);

    /**
     * {@code inherit = true}（默认）+ {@code append}：方法级 + append + Agent 级提示词
     * 三段用空行拼起来。Agent 级提示词为空时，拼出来就是前两段。
     */
    @SystemPrompt(value = "（方法级 · append）先给结论。", append = {"（append 段）再补一句依据。"})
    String withAppend(@User String question);

    /**
     * 方法级提示词里的 {@code {变量}} 由声明式代理渲染：普通参数名（{@code question}）
     * 与 {@code @Ctx} 参数都会进变量表，{@code {agentName}} / {@code {loop}} 这类内置变量
     * 取自 AgentSpec。
     */
    @SystemPrompt("请用 {style} 的风格回答：{question}（本次由 {agentName} 处理，循环 {loop}）")
    String withVars(@User String question, @Ctx("style") String style);
}
