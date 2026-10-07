package com.benxin.exp.real.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.real.tool.TinyTools;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.exp.real.wire.WireLog;
import com.benxin.exp.real.wire.WireLogTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 离线基础设施 + 传输层装配。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>离线模型</b>：注册名 {@code mock}，与默认 profile 的 {@code llm.default-model} 对应。
 *       脚本规则是照着本示例真正要断言的那几条路径写的。</li>
 *   <li><b>手写的极小工具</b>：交给容器，{@code LlmToolCatalog}（BeanPostProcessor）
 *       会扫描它里面的 {@code @LlmTool} 方法 —— "工具来源完全由使用者决定"。</li>
 *   <li><b>报文留档传输层</b>：{@code HttpTransport} 在本心里是带
 *       {@code @ConditionalOnMissingBean} 的 bean，所以这里定义同类型 bean 就自动接管，
 *       不需要排包、不需要开关。默认 profile 下它只是躺在那里（离线模型不走 HTTP），
 *       real profile 下它会把每一笔真实往来写进 {@code target/wire-log/}。</li>
 * </ol>
 */
@Configuration
public class OfflineModelConfiguration {

    /**
     * 离线脚本模型（外面套一层 {@link RecordingModel} 以便查看"这一轮到底发了什么"）。
     *
     * <p>规则刻意排了顺序 —— {@code ScriptedLlmModel} 是"先命中先返回"：</p>
     * <ul>
     *   <li>{@code 记住...} 必须排在 {@code 代号} 前面，否则多轮演示的第一问会直接
     *       命中第二条规则、回一个"青龙"（这正是"规则顺序即语义"的直观例子）；</li>
     *   <li>{@code toolFor("几点", "now")}：脚本模型默认的关键词表把"几点"映射到名字里含
     *       {@code time} 的工具，而本示例的工具叫 {@code now}，所以显式补一条映射
     *       —— 这样离线与真实两条路径调用的是同一个工具、同一条代码路径。</li>
     * </ul>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                .rule("1+1", "2")
                .rule("记住", "好")
                .rule("代号", "青龙")
                .rule("完成", "完成")
                .rule("收到", "收到")
                .toolFor("几点", "now")
                .toolFor("时间", "now")
                .fallback("（离线脚本模型）已收到：{input}")
                .build();
        return new RecordingModel(scripted);
    }

    /** 唯一的工具来源。见 {@link TinyTools} 里"为什么不复用 CommonTools"的说明。 */
    @Bean
    public TinyTools tinyTools() {
        return new TinyTools();
    }

    /**
     * 报文留档传输层。
     *
     * <p>{@code @ConditionalOnMissingBean} 是这个 bean 能生效的全部原因：
     * 用户 bean 与自动配置 bean 同类型时，自动配置让位。</p>
     */
    @Bean
    public HttpTransport llmHttpTransport(WireLog wireLog) {
        return new WireLogTransport(wireLog);
    }
}
