package com.benxin.exp.tools.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * {@code @LlmTool} 注解本身的全部属性：{@code name} / {@code value} / {@code description} /
 * {@code returnDirect} / {@code enabled}，外加"名字留空时回落到方法名"这条默认规则。
 *
 * <p>样例里每个方法只演示一个属性，是为了<b>可断言</b>：测试直接读
 * {@code MethodToolCallback.spec()} 对比，不靠输出文本做判断。</p>
 */
public class AnnotationSpectrumTools {

    /** {@code name}：工具名优先取 {@code name}，其次 {@code value}，最后才是方法名。 */
    @LlmTool(name = "named_tool", description = "用 name 指定工具名")
    public String named(@LlmToolParam(value = "q", description = "随便一段输入") String q) {
        return "named_tool ← " + q;
    }

    /** {@code value}：与 {@code name} 等价，只是语义别名。 */
    @LlmTool(value = "valued_tool", description = "用 value 指定工具名（与 name 等价）")
    public String valued(@LlmToolParam(value = "q", description = "随便一段输入") String q) {
        return "valued_tool ← " + q;
    }

    /** 名字留空：工具名回落到<b>方法名</b>（这里就是 {@code methodNameFallback}）。 */
    @LlmTool(description = "name / value 都不写，工具名回落到方法名")
    public String methodNameFallback(@LlmToolParam(value = "q", description = "随便一段输入") String q) {
        return "methodNameFallback ← " + q;
    }

    /**
     * {@code returnDirect = true}：结果直接当最终答案。
     *
     * <p>这里只负责"声明"（测试断言 {@code spec().returnDirect()}），
     * 行为演示在 {@link DirectAnswerTools} —— 那里能同时数模型调用次数。</p>
     */
    @LlmTool(name = "direct_tool", description = "结果直接作为最终答案，不再交回模型", returnDirect = true)
    public String direct(@LlmToolParam(value = "q", description = "随便一段输入") String q) {
        return "direct_tool ← " + q;
    }

    /**
     * {@code enabled = false}：这个工具<b>不会</b>出现在任何 Agent 的工具表里。
     *
     * <p>它是 {@code ToolScanner.scan} 里唯一被跳过的分支，
     * 用来做灰度或"踩刹车"：改一个字段就能让模型看不见某个工具，
     * 比删代码或加开关判断安全得多。</p>
     */
    @LlmTool(name = "disabled_tool", description = "enabled = false：不暴露给模型", enabled = false)
    public String disabled(@LlmToolParam(value = "q", description = "随便一段输入") String q) {
        throw new IllegalStateException("enabled = false 的工具不应该被调用到：" + q);
    }
}
