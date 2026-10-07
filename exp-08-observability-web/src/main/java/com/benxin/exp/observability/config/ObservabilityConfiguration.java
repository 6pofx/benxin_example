package com.benxin.exp.observability.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.observability.listener.MonitorListener;
import com.benxin.exp.observability.listener.ToolActivityListener;
import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.llm.core.hook.AgentListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线模型、工具来源与监听器的装配。
 *
 * <p>这里是本模块最关键的一处<b>装配语义</b>示范：{@code LlmAutoConfiguration} 里的内置
 * {@code llmLoggingListener} 写的是 {@code @ConditionalOnMissingBean(AgentListener.class)} ——
 * 条件按<b>类型</b>判断，所以只要容器里出现任意一个 {@code AgentListener} bean，内置那个就
 * <b>让位（整个不装配）</b>；而同一类型的多个自定义 bean 之间并不互斥，会
 * <b>全部收进监听器链</b>。本类故意注册两个，就是要同时钉住这两条。</p>
 *
 * <p>模型方面刻意准备两个：{@code mock}（脚本化 + 录制，驱动全部正常路径）与
 * {@code failing}（只抛 {@code ModelException}，用来演示 {@code onError} 与
 * "失败路径不产生 {@code agent_run}"）。</p>
 */
@Configuration
public class ObservabilityConfiguration {

    /**
     * 默认模型：脚本化模型外面套一层 {@link RecordingModel}。
     *
     * <p>脚本规则覆盖本模块要演示的每一条路径，全部可在离线下确定性复现：</p>
     * <ul>
     *   <li>{@code 自我介绍} → 一段够长的文本，保证流式演示能收到多个增量分片（分片大小 12 字符）；</li>
     *   <li>{@code 北京+天气} → 触发 {@code get_weather} 工具，跑通"工具调用 → 结果回灌 → 收尾"；</li>
     *   <li>{@code 汇报任务状态} → 子代理的固定应答；</li>
     *   <li>{@code 必定失败} → 触发 {@link FailingProbeTool}，跑通"工具失败 → onToolResult/afterTool
     *       收到 error=true"这条路径（{@code CommonTools} 里没有任何工具会真的失败）；</li>
     *   <li>{@code 会失败} → 抛 {@code ModelException}，驱动 onError 路径；</li>
     *   <li>兜底 → 回声式回答。</li>
     * </ul>
     *
     * <p>{@code thinking(true)} 让模型输出里带上思维链，{@code onThinkingDelta} 才有料可收；
     * {@code chunkSize(12)} 把增量切细，SSE 上能看到"逐字到达"而不是"一次性到达"。</p>
     *
     * <p>{@code toolFor("必定失败", "probe_fail")} 是"关键字 → 工具"规则：它排在脚本模型内建的
     * 关键词表之前，且只在<b>还没有工具结果</b>时才生效 —— 因此失败的工具恰好被调用一次
     * （对照 {@code #tool:probe_fail {}} 那种强制语法：它每一步都会重新命中）。</p>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                .rule("自我介绍", "我是本心示例 08 里的离线模型，负责演示监听器、拦截器与 HTTP 端点，"
                        + "全程不联网、不消耗任何 token。")
                .rule("汇报任务状态", "子代理已完成任务：已收集信息、已核对口径、结论可直接使用。")
                .rule("会失败", "这条规则不会命中：需要抛异常的请求走 FailingModel。")
                .toolFor(FailingProbeTool.TRIGGER, FailingProbeTool.NAME)
                .fallback("（离线脚本化模型）已收到：{input}")
                .thinking(true)
                .chunkSize(12)
                .build();
        return new RecordingModel(scripted);
    }

    /** 工具来源由使用者决定：把一个普通对象交给容器，{@code LlmToolCatalog} 会扫描它。 */
    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    /**
     * 必定失败的工具（见 {@link FailingProbeTool}）。
     *
     * <p>与 {@code CommonTools} 一样是普通对象 + {@code @Bean}：进了容器就进了全局工具表，
     * 于是"不声明 tools 的 Agent 继承全局工具表"这条契约里也包含它。</p>
     */
    @Bean
    public FailingProbeTool failingProbeTool() {
        return new FailingProbeTool();
    }

    // ------------------------------------------------------------------
    // 监听器：两个自定义 bean —— "内置让位" + "多个叠加" 同时成立
    // ------------------------------------------------------------------

    /**
     * 主录制监听器：12 个回调全部留档。
     *
     * <p>bean 名与<类型一致即可；这里显式写方法名是为了让"注册了两个监听器"这件事
     * 在启动日志与测试里一眼可读。</p>
     */
    @Bean
    public AgentListener monitorListener() {
        return new MonitorListener("monitor");
    }

    /** 第二个监听器：证明多个自定义监听器会叠加，而不是互相顶掉。 */
    @Bean
    public AgentListener toolActivityListener() {
        return new ToolActivityListener();
    }
}
