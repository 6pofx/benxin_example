package com.benxin.exp.sandbox.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.sandbox.model.OnceForcedToolModel;
import com.benxin.exp.sandbox.tool.SandboxGuardTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型与自写工具的装配。
 *
 * <p>模型链是 {@code RecordingModel → OnceForcedToolModel → ScriptedLlmModel}，
 * 三层各司其职，正好是"模型可以被任意装饰"的实物示范：</p>
 * <ul>
 *   <li>{@link ScriptedLlmModel}：真正做决策的脚本模型（不联网、零 token）；</li>
 *   <li>{@link OnceForcedToolModel}：给 {@code #tool:} 语法补上"花括号配平"与"一次运行只强制一次"
 *       两条工程化处理（原因见该类注释）；</li>
 *   <li>{@link RecordingModel}：把每次真正下发的请求留档，测试靠它断言"模型这一轮到底看到了什么"。</li>
 * </ul>
 *
 * <p>注意只暴露<b>一个</b> {@code LlmModel} bean：容器里任何 {@code LlmModel} bean 都会被收进
 * 模型注册表，多暴露一个就会把注册名 {@code mock} 覆盖成没有录制能力的那一个。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                // 子代理的收尾：只让它命中子代理自己的系统提示词，避免影响父 Agent 的每一轮
                .systemRule("researchAssistant",
                        "（子代理结论）沙箱工作区里可读的普通文件有 4 个：a.txt、docs/guide.txt、"
                                + "docs/notes.txt、data/config.txt；另有 5 条敏感路径被默认禁止规则屏蔽。")
                .fallback("（离线脚本化模型）已收到：{input}。"
                        + "想让我强制调用某个工具，可以写成 #tool:<工具名> {\"参数\":\"值\"}。")
                .build();
        return new RecordingModel(new OnceForcedToolModel(scripted));
    }

    /**
     * 自写工具交给容器即被 {@code LlmToolCatalog}（BeanPostProcessor）扫进全局工具表，
     * 因此 {@code @LlmAgent} 什么都不写时也能拿到它们 —— 与 exp-01 里 {@code CommonTools} 同一条路径。
     */
    @Bean
    public SandboxGuardTools sandboxGuardTools() {
        return new SandboxGuardTools();
    }
}
