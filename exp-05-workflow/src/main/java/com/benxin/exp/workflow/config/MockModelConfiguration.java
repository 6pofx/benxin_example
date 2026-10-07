package com.benxin.exp.workflow.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.workflow.tool.FlakyTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型与工具的装配。
 *
 * <p>工作流演示看起来"只跟图有关"，但它其实需要模型：{@code agent} 节点背后是一次完整的
 * 模型子任务。这里用一个脚本化模型把那次调用变成可复现的规则，于是
 * {@code mvn spring-boot:run} 与 {@code mvn test} 都不联网、不花一个 token。</p>
 *
 * <p>{@link RecordingModel} 也不是摆设：工作流的 {@code prompt} 是模板渲染出来的，
 * 渲染结果只在下发给模型的那一刻才真正成真。把 {@code ChatRequest} 留档，
 * 示例与测试才能断言"${input} 真的被替换成了工单原文""instruction 真的拼在了 prompt 前面"。</p>
 */
@Configuration
public class MockModelConfiguration {

    /**
     * 脚本规则刻意覆盖了本示例要演示的每一条路径。
     *
     * <p>注意它<b>不</b>包含编排类 Loop 的内建应答（{@code 编号计划} / {@code 验收者}）——
     * 那几条 {@link ScriptedLlmModel} 自己就会处理，用来驱动 {@code staged} 路线。</p>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                // 分诊演示：让 classify / urgent 两个 agent 节点的输出可断言
                //
                // ★ 规则是"先命中先返回"，所以更具体的规则必须排在更泛的规则前面。
                //   这一条普通工单的规则**必须**放在下面那条 "待分诊工单" 之前 ——
                //   否则任何工单都会被分到「紧急」，triage.yml 里 route 的默认边（normal）
                //   永远走不到，"两条分支各写各的变量"这个演示点就白写了。
                .rule("错别字", "分级：一般。判断依据：只是文档表述问题，不影响线上功能。")
                .rule("待分诊工单",
                        "分级：紧急。判断依据：工单描述了线上不可用，符合紧急的定义。")
                .rule("补三条处置建议",
                        "1. 立即回滚最近一次发布 2. 拉值班群并同步影响面 3. 保留现场快照再排查")
                // 预算演示：四个串联的 agent 节点各要一句话
                .rule("第 1 步", "第 1 步结论：本次变更可以发布，但需要加一行风险提示。")
                .rule("第 2 步", "第 2 步依据：上一次同类变更的回归率只有 0.3%。")
                .rule("第 3 步", "第 3 步边界：若灰度期间错误率上升，结论立即失效。")
                .rule("第 4 步", "第 4 步答复：可以发布。")
                .fallback("（离线模型）已收到：{input}")
                .build();
        return new RecordingModel(scripted);
    }

    /**
     * 基础工具集由 {@code exp-common} 提供，演示之间共享同一套工具。
     *
     * <p>工作流里的 {@code tool} 节点会<b>直接</b>调它们（不经过模型），
     * 所以这些 bean 必须先被 {@code LlmToolCatalog} 扫到。</p>
     */
    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    /**
     * 工作流专用工具：一个"会偶发失败"的脚本化工具体。
     *
     * <p>它是 {@code retry} 与 {@code continueOnError} 唯一的靶子 ——
     * 真实工具的失败不可控，而这两个能力必须能被反复验证。</p>
     *
     * <p>它同时提供 {@code next_attempt}（整数 +1），循环计数靠它 ——
     * 因为 {@code set} 的 {@code ${}} 只做插值不做算术。所以
     * {@code TriageAgent} 与 {@code LoopAgent} 都把它装配进了工具表。</p>
     */
    @Bean
    public FlakyTools flakyTools() {
        // 前 2 次调用必失败：配 flaky-tools.yml 里 stamp 节点的 retry: 3，恰好 "失败→失败→成功"
        return new FlakyTools(2);
    }

    // 注意：这里刻意不再单独暴露一个 ScriptedLlmModel bean。
    // 容器里任何 LlmModel bean 都会被收进模型注册表，多暴露一个就会把注册名 mock
    // 覆盖成"没有录制能力的那一个"。想看底层脚本模型请用 recordingModel.unwrap()。
}
