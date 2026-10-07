package com.benxin.exp.declarative.agent;

import com.benxin.exp.declarative.dto.ReviewReport;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.Assistant;
import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmStreamHandler;

import java.util.List;
import java.util.Optional;

/**
 * 声明式 Agent 的"注解全谱"示例：一个接口里把 {@code @LlmAgent} 的全部属性、
 * 五种参数语义、方法级 {@code @SystemPrompt} 与全部返回类型都摆一遍。
 *
 * <p>注意它<b>没有实现类</b>：{@code LlmAgentRegistrar} 会把接口本身注册成候选组件，
 * 用 JDK 动态代理生成实现 —— 所以你只需要 {@code @Autowired} 就能用。</p>
 */
@LlmAgent(
        // ① 名字：value 与 name 等价，都不写时用接口名的首字母小写（basicAssistant）
        name = "basicAssistant",
        // ② 模型：这里是"模型注册表的 key"，不是报文里的模型 id（后者由 llm.models.<key>.model 决定）
        model = "mock",
        // ③ 循环：LoopRegistry 里的名字，未注册时会打 WARN 并回退默认 Loop
        loop = "dsh-minimal",
        // ④ Agent 级系统提示词；方法级 @SystemPrompt 会叠加在它之上（见 explain/review）
        systemPrompt = "你是本心示例里的助手，回答要简短、直接、不要客套。",
        // ⑤ 步数预算：单次运行最多调用几次模型
        maxSteps = 6,
        // ⑥ 采样与输出上限：显式写下的值会随请求一起下发（示例里用录制到的请求断言这一点）
        temperature = 0.2,
        maxTokens = 512,
        // ⑦ 记忆：默认即为 true；这里显式写出来，因为示例要演示多轮续接
        memory = true)
public interface BasicAssistant {

    /** 最朴素的写法：无注解参数默认就是 user 内容，所以 {@code @User} 可省。 */
    String ask(String question);

    /** 多参数按声明顺序拼成多条 user 消息；{@code @User} 的 prefix/suffix 会包在值外面。 */
    String askWithPrefix(@User(prefix = "背景：") String background,
                         @User(suffix = "（请用一句话回答）") String question);

    /**
     * {@code @Assistant} 是"预填充的 assistant 消息"，用于少样本引导：
     * 它会把这段文本作为 assistant 角色塞进历史，模型据此续写。
     */
    String guided(@Assistant String fewShotAnswer, @User String question);

    /**
     * 方法级 {@code @SystemPrompt} + {@code @Ctx} 模板变量。
     *
     * <p>{@code {question}} 能取到值，是因为无注解 / {@code @User} 参数同时也会进模板变量表 ——
     * 不必为了引用同一个值再加一个 {@code @Ctx} 参数。</p>
     *
     * <p>{@code @Ctx} 参数<b>不会</b>进消息体（示例测试里会断言这一点），
     * 它只参与提示词渲染；{@code attribute = true}（默认）时还会写进运行时属性。</p>
     */
    @SystemPrompt("请以 {style} 的风格，面向 {audience} 解释下面这段代码：\n{question}")
    String explain(@User String question,
                   @Ctx("style") String style,
                   @Ctx("audience") String audience);

    /**
     * {@code inherit = false}：<b>不</b>继承 Agent 级系统提示词，只用方法级这一段。
     * 适合"这一步需要完全不同的角色"的场景。
     */
    @SystemPrompt(value = "你是代码审查员，只输出 JSON，不要任何解释文字。", inherit = false)
    ReviewReport reviewStrict(@User String code);

    /**
     * {@code inherit = true}（默认）：方法级提示词 + {@code append} 的内容 + Agent 级提示词
     * 用空行拼成一段。想让"全局人格"在某个方法上继续生效就保持默认。
     */
    @SystemPrompt(value = "你是代码审查员，只输出 JSON。", append = {"评分取值范围 0-100。"})
    ReviewReport reviewStrictWithAppend(@User String code);

    /**
     * {@code @Memory} 参数的值会被当作 sessionId：框架自动装载 / 保存会话历史，
     * 于是同一个 sessionId 的多次调用能续上上下文。
     */
    String chatWithMemory(@Memory String sessionId, @User String question);

    // ------------------------------------------------------------------
    // 返回类型全谱：同一个接口，方法声明什么就得到什么
    // ------------------------------------------------------------------

    /** String：模型输出的纯文本。 */
    String text(@User String question);

    /** AgentResult：完整运行结果（步数 / 用量 / 工具记录 / 属性 / 结束原因）。 */
    AgentResult result(@User String question);

    /** Optional&lt;String&gt;：模型什么都没说（输出空白）时给出 empty，让调用方的 orElse 兜底生效。 */
    Optional<String> maybe(@User String question);

    /** List&lt;ChatMessage&gt;：本次运行的完整消息轨迹。 */
    List<ChatMessage> transcript(@User String question);

    /** int：本次运行的步数（调用了几次模型）。 */
    int steps(@User String question);

    /** long：本次运行的耗时（毫秒）。 */
    long elapsedMillis(@User String question);

    /** boolean：模型是否给出了非空白回答。 */
    boolean answered(@User String question);

    /** void：只关心副作用（记忆写回、监听器事件）时用。 */
    void fireAndForget(@User String question);

    /**
     * 任意 DTO：模型输出的 JSON 会被自动反序列化（容忍 ```json 围栏与前后闲聊）。
     *
     * <p>提示词里必须真的要求 JSON —— 反序列化是"把模型输出的 JSON 变成对象"，
     * 不是"让模型输出 JSON"。模型回了一段散文时这里会抛
     * {@code LlmAgentInvocationException} 并带上原始输出，不会静默返回 null。</p>
     */
    @SystemPrompt("你是代码审查员，只输出 JSON。")
    ReviewReport review(@User String code);

    /** 流式：多一个 {@code LlmStreamHandler} 参数即可，不需要另一套 API。 */
    String stream(@User String question, LlmStreamHandler handler);

    /**
     * {@code @Ctx(attribute = false)}：只做模板变量，<b>不</b>写进运行时属性。
     * 与默认的 {@code attribute = true} 对照着看，能明白这个开关的边界。
     */
    @SystemPrompt("请用 {tone} 的语气回答：{question}")
    String tone(@User String question,
                @Ctx(value = "tone", attribute = false) String tone);
}
