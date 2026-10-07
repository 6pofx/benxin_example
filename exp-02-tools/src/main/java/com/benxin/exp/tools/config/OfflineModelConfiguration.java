package com.benxin.exp.tools.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.tools.support.ConcurrencyRecorder;
import com.benxin.exp.tools.support.ScriptedToolCallModel;
import com.benxin.exp.tools.tool.AnnotationSpectrumTools;
import com.benxin.exp.tools.tool.ClassLevelToolSet;
import com.benxin.exp.tools.tool.ContextAwareTools;
import com.benxin.exp.tools.tool.DirectAnswerTools;
import com.benxin.exp.tools.tool.FaultyTools;
import com.benxin.exp.tools.tool.NoteTools;
import com.benxin.exp.tools.tool.ParallelProbeTools;
import com.benxin.exp.tools.tool.ParamAnnotationTools;
import com.benxin.exp.tools.tool.ParameterSpectrumTools;
import com.benxin.exp.tools.tool.ReturnSpectrumTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型与工具来源的装配。
 *
 * <p><b>工具是怎么"进容器"的：</b>这里的所有 {@code @Bean} 都是<b>普通对象</b>
 * （不是 {@code @Component}，也没有实现任何接口）。{@code LlmToolCatalog} 作为
 * {@code BeanPostProcessor} 会把每个 bean 过一遍，扫出带 {@code @LlmTool} 的方法。
 * 所以"哪些工具存在"完全由使用者的装配决定 —— 想让某个工具消失，
 * 不注册这个 bean 就行，不必去改注解。</p>
 *
 * <p><b>模型有两个：</b>{@code mock}（默认，exp-common 的脚本化对话模型）与
 * {@code scripted-tools}（本模块的夹具，能一轮发多个工具调用、能给嵌套 JSON 参数）。
 * 两个都是 bean，因此都会按 {@code name()} 进 {@code ModelRegistry}；
 * {@code llm.default-model: mock} 指定了默认值，其余 Agent 想换模型写 {@code model = "scripted-tools"} 即可。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    /**
     * 默认模型 {@code mock}：规则覆盖本示例要用到的"非工具"路径。
     *
     * <p>外面套一层 {@link RecordingModel} 是为了留档每次真正发给模型的请求 ——
     * "模型到底看到了什么"（尤其是错误结果回灌那一条）必须能被断言，
     * 而不是靠读日志猜。</p>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                .rule("你好", "你好！我是本心示例里的离线模型，当前不联网、不消耗 token。")
                .rule("介绍", "我是一个用规则驱动的离线模型，用来让 exp-02 在离线环境下也能完整跑通工具闭环。")
                .fallback("（离线脚本模型）已收到：{input}。"
                        + "想让我调工具，可以写成 #tool:<工具名> {\"参数\":\"值\"}。")
                .build();
        return new RecordingModel(scripted);
    }

    /**
     * 夹具模型 {@code scripted-tools}：按脚本发起工具调用，收尾文本也是脚本里的固定串。
     *
     * <p>它不是"演示用的玩具"：{@code parallelSafe} 的并行判定要求同一轮里有多个工具调用，
     * 而 exp-common 的 {@code ScriptedLlmModel} 一轮只发一个 —— 少了这个模型，
     * "并行 vs 串行"这一条就只能靠嘴说。</p>
     */
    @Bean
    public ScriptedToolCallModel scriptedToolCallModel() {
        return new ScriptedToolCallModel("scripted-tools");
    }

    // ------------------------------------------------------------------
    // 工具来源：普通对象交给容器，剩下的由 LlmToolCatalog 负责
    // ------------------------------------------------------------------

    /** exp-common 的公共工具集：本模块用它演示"按来源类整包拿工具"。 */
    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    @Bean
    public AnnotationSpectrumTools annotationSpectrumTools() {
        return new AnnotationSpectrumTools();
    }

    @Bean
    public ClassLevelToolSet classLevelToolSet() {
        return new ClassLevelToolSet();
    }

    @Bean
    public ParamAnnotationTools paramAnnotationTools() {
        return new ParamAnnotationTools();
    }

    @Bean
    public ParameterSpectrumTools parameterSpectrumTools() {
        return new ParameterSpectrumTools();
    }

    @Bean
    public ReturnSpectrumTools returnSpectrumTools() {
        return new ReturnSpectrumTools();
    }

    @Bean
    public FaultyTools faultyTools() {
        return new FaultyTools();
    }

    @Bean
    public ContextAwareTools contextAwareTools() {
        return new ContextAwareTools();
    }

    @Bean
    public DirectAnswerTools directAnswerTools() {
        return new DirectAnswerTools();
    }

    @Bean
    public ParallelProbeTools parallelProbeTools(ConcurrencyRecorder recorder) {
        return new ParallelProbeTools(recorder);
    }

    @Bean
    public NoteTools noteTools() {
        return new NoteTools();
    }
}
