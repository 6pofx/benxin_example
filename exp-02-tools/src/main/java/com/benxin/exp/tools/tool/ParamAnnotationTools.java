package com.benxin.exp.tools.tool;

import com.benxin.exp.tools.dto.RunMode;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code @LlmToolParam} 的五个属性：{@code value} / {@code name} / {@code description} /
 * {@code required} / {@code defaultValue} —— 以及"不写注解"这条默认路径。
 *
 * <p>工具方法都返回一个"我到底收到了什么"的 Map：这样"默认值有没有生效"、
 * "required=false 与 true 差在哪"就不再是读文档，而是<b>看得见的返回值</b>。</p>
 */
public class ParamAnnotationTools {

    /** {@code value} 与 {@code name} 都是"参数名"，{@code value} 优先；不写注解时用真实参数名。 */
    @LlmTool(name = "param_names", description = "参数名从哪来：value / name / 真实参数名")
    public String paramNames(@LlmToolParam(value = "by_value", description = "来自 value") String a,
                             @LlmToolParam(name = "by_name", description = "来自 name") String b,
                             String byRealName) {
        return "by_value=" + a + ", by_name=" + b + ", 真实参数名=" + byRealName;
    }

    /**
     * {@code required} 的<b>真实语义</b>：它只决定 JSON Schema 里这个属性进不进
     * {@code required} 数组，框架<b>不做运行期校验</b>。
     *
     * <p>所以"模型没给必填参数"的结果是：</p>
     * <ul>
     *   <li>{@code String} → 收到 {@code null}（不是异常）；</li>
     *   <li>基本类型 {@code int} → 收到 {@code 0}（{@code MethodToolCallback.bind} 里的
     *       {@code primitiveDefault}）；</li>
     *   <li>{@code Optional<T>} → 收到 {@code Optional.empty()}。</li>
     * </ul>
     * <p>想让"缺参数"变成模型看得见的错误，得自己在方法体里校验并
     * {@code return ToolResult.error(...)}。</p>
     */
    @LlmTool(name = "required_vs_optional",
            description = "required=true/false 在缺参时各自收到什么（框架不强制校验）")
    public Map<String, Object> requiredVsOptional(
            @LlmToolParam(value = "must", description = "required = true（默认值）") String must,
            @LlmToolParam(value = "may", description = "required = false", required = false) String may,
            @LlmToolParam(value = "count", description = "required = false 的基本类型 int", required = false) int count,
            @LlmToolParam(value = "note", description = "Optional 参数，缺参时是 empty", required = false)
            Optional<String> note) {
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("must", String.valueOf(must));         // 缺参时是字符串 "null"，故意让它可见
        seen.put("may", String.valueOf(may));
        seen.put("count", count);
        seen.put("note", note.map(v -> "Optional[" + v + "]").orElse("Optional.empty"));
        return seen;
    }

    /**
     * {@code defaultValue}：<b>只有"参数根本没给"时才生效</b>（{@code MethodToolCallback#bind}
     * 里 {@code raw == null} 的那个分支）；一旦模型给了值（哪怕是空串），默认值就不参与。
     *
     * <p>两个副作用值得记住：</p>
     * <ul>
     *   <li>写了 {@code defaultValue} 的参数<b>自动不算必填</b> ——
     *       schema 生成器只在"required 为真且 defaultValue 为空"时才把它塞进
     *       {@code required}；</li>
     *   <li>schema 里的 {@code default} 永远是<b>字符串</b>（注解值是 String），
     *       哪怕参数是 {@code int}：你会看到 {@code "type":"integer"} 和
     *       {@code "default":"200"} 并排出现。</li>
     * </ul>
     */
    @LlmTool(name = "param_default", description = "defaultValue：不传时生效，传了就以传入值为准")
    public Map<String, Object> defaultValue(
            @LlmToolParam(value = "unit", description = "String 默认值", required = false,
                    defaultValue = "celsius") String unit,
            @LlmToolParam(value = "limit", description = "int 默认值（注意 schema 里是字符串）",
                    required = false, defaultValue = "200") int limit,
            @LlmToolParam(value = "ratio", description = "double 默认值", required = false,
                    defaultValue = "0.5") double ratio,
            @LlmToolParam(value = "flag", description = "boolean 默认值", required = false,
                    defaultValue = "true") boolean flag,
            @LlmToolParam(value = "mode", description = "枚举默认值（必须与常量名完全一致）",
                    required = false, defaultValue = "FAST") RunMode mode) {
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("unit", unit);
        seen.put("limit", limit);
        seen.put("ratio", ratio);
        seen.put("flag", flag);
        seen.put("mode", String.valueOf(mode));
        return seen;
    }
}
