package com.benxin.exp.context.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.context.model.VisionCapableModel;
import com.benxin.exp.context.tool.LongDocTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型与工具装配：三个模型 + 一个"长文本"工具。
 *
 * <p>三个模型各有分工，它们的存在本身就在说明"模型能力是数据"：</p>
 * <ul>
 *   <li>{@code mock} —— 默认模型，窗口 64000。日常问答与记忆演示用它，
 *       因为窗口大，上下文切面不会插手，看到的都是记忆本身的行为。</li>
 *   <li>{@code tiny} —— 窗口只有 6000。<b>压缩演示必须有一个小窗口模型</b>：
 *       {@code DefaultContextManager} 的触发条件是"估算 token &gt; (窗口 - 预留输出) × 阈值"，
 *       窗口 64000 时这条线在 47923 token，离线示例永远够不着。</li>
 *   <li>{@code vision} —— 在 {@code tiny} 之外再演示一个能力位：同一个离线脚本模型，
 *       靠 {@link VisionCapableModel} 声明 {@code vision = true}，用来说明
 *       "能力声明是可装饰的，而插件不会因为能力位去改写你的消息"。</li>
 * </ul>
 *
 * <p>每个模型都套一层 {@link RecordingModel}：本示例的绝大多数结论
 * （"压缩真的多花了一次模型调用""自定义 ContextManager 改了下发序列""图片确实发出去了"）
 * 都靠它留档的 {@code ChatRequest} 来断言。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    /** 压缩指令的特征片段：{@code SummarizingCompactor} 注入的默认指令里就有这句。 */
    public static final String COMPRESSION_PROMPT_HINT = "压缩成一份高密度的工作摘要";

    /** 脚本模型对压缩请求的"摘要"回答；真实模型会在这里生成真正的工作摘要。 */
    public static final String SCRIPTED_SUMMARY =
            "【脚本摘要】此前对话的核心诉求是观察上下文压缩；已确认阈值与保留条数共同决定下发内容；"
                    + "待办是核对压缩后的请求里是否出现本摘要。";

    /** 默认模型窗口：64000，够大，日常演示不会触发压缩。 */
    public static final int WIDE_CONTEXT_TOKENS = 64_000;

    /** 小窗口模型：6000，配合 (0.8, 预留 4096) 时触发线只有 1523 token，离线也够得着。 */
    public static final int TINY_CONTEXT_TOKENS = 6_000;

    @Bean
    public RecordingModel mockLlmModel() {
        return new RecordingModel(scripted("mock", WIDE_CONTEXT_TOKENS));
    }

    @Bean
    public RecordingModel tinyLlmModel() {
        return new RecordingModel(scripted("tiny", TINY_CONTEXT_TOKENS));
    }

    @Bean
    public RecordingModel visionLlmModel() {
        // ScriptedLlmModel 的 builder 没有 vision 开关（能力位写死成 false），
        // 于是用装饰器补上 —— 这正好顺带示范了"模型可以被任意装饰"。
        return new RecordingModel(new VisionCapableModel(scripted("vision", WIDE_CONTEXT_TOKENS), true));
    }

    /**
     * 工具来源由使用者决定：普通对象交给容器，{@code LlmToolCatalog} 会扫描其中的
     * {@code @LlmTool} 方法。
     */
    @Bean
    public LongDocTools longDocTools() {
        return new LongDocTools();
    }

    // ------------------------------------------------------------------

    /**
     * 一份脚本：所有模型共用同一套规则，只有窗口大小不同。
     *
     * <p>规则顺序即优先级（先命中先返回），因此<b>压缩摘要规则必须排第一</b>：
     * 摘要请求的"用户消息"是整段历史转写，里面可能命中任何一条普通规则。</p>
     */
    private static ScriptedLlmModel scripted(String name, int maxContextTokens) {
        return ScriptedLlmModel.builder(name)
                .maxContextTokens(maxContextTokens)
                // ① 压缩请求：提示词在 system 里，所以用 systemRule（只在系统提示词上匹配）
                .systemRule(COMPRESSION_PROMPT_HINT, SCRIPTED_SUMMARY)
                // ② 普通问答：关键字都刻意避开各 Agent 系统提示词里出现的词，
                //    否则"系统提示词命中规则"会让每一轮都返回同一句话
                .rule("你好", "你好！我是离线脚本化模型，本次演示不联网、不消耗 token。")
                .rule("第二轮", "收到第二轮的输入。如果你在同一个 sessionId 上，此刻我应该已经能看到第一轮的历史。")
                .rule("继续", "好，我接着上一轮继续。")
                .rule("打个招呼", "你好，我在。")
                // ③ 关键词 → 工具：输入里提到"文档"时先取一次资料，用来演示"一轮工具调用后收尾"
                //    （注意 #tool:xxx 的显式指定优先级更高，因此不会影响 claude-code 那个跑满步数的演示）
                .toolFor("文档", "read_document")
                .fallback("（离线脚本化模型）我收到了你的请求：{input}")
                .build();
    }
}
