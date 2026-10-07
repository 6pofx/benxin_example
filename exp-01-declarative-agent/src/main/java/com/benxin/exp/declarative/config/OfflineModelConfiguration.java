package com.benxin.exp.declarative.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型与工具的装配。
 *
 * <p>这里是<b>"模型不一定要来自 yml"</b> 的示范：任何 {@code LlmModel} bean 都会被
 * starter 收进模型注册表，注册名取 {@code @LlmModelDef} 或 {@code name()}。
 * 于是"我在 yml 里配一个模型端点"与"我用代码给一个模型实现"在使用体验上完全等价。</p>
 *
 * <p>外面套一层 {@link RecordingModel} 不是摆设：它把每次真正发给模型的
 * {@code ChatRequest} 留档，示例与测试靠它证明 {@code @Ctx} 变量没有泄漏进消息体、
 * {@code temperature} 确实被下发这类"看不见的口径"。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    /**
     * 注册名 {@code mock}，与 {@code llm.default-model} 对应。
     *
     * <p>脚本规则刻意覆盖了本示例要演示的每一条路径：</p>
     * <ul>
     *   <li>普通问答 → 一段固定回答（让输出可断言）；</li>
     *   <li>{@code 保持沉默} → 空字符串（用来演示 {@code Optional<String>} 落到 empty）；</li>
     *   <li>要求 JSON → 返回 {@code ReviewReport} 的 JSON（演示 DTO 返回值）；</li>
     *   <li>天气 / 时间 → 调工具（演示 {@code tools} / {@code toolNames} 属性）。</li>
     * </ul>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                .rule("你好", "你好！我是本心示例里的离线模型，当前不联网、不消耗 token。")
                .rule("自我介绍", "我是一个用规则驱动的离线模型，用来让示例在离线环境下也能完整跑通。")
                .rule("保持沉默", "")
                .rule("解释一下", "这段代码做了一件事：把一次方法调用翻译成一次 Agent 运行。")
                .jsonAnswer("""
                        {
                          "summary": "示例代码整体结构清晰，有一处可以改进。",
                          "issues": ["缺少对空入参的校验"],
                          "score": 78
                        }""")
                .fallback("（离线模型）已收到：{input}")
                .build();
        return new RecordingModel(scripted);
    }

    /**
     * 工具来源也由使用者决定：这里把一个普通对象交给容器，
     * {@code LlmToolCatalog}（BeanPostProcessor）会扫描它里面的 {@code @LlmTool} 方法。
     */
    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    // 注意：这里刻意不再单独暴露一个 ScriptedLlmModel bean。
    // 容器里任何 LlmModel bean 都会被收进模型注册表，多暴露一个就会把注册名 mock
    // 覆盖成"没有录制能力的那一个"。想看底层脚本模型请用 recordingModel.unwrap()。
}
