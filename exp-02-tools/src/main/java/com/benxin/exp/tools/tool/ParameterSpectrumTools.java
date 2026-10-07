package com.benxin.exp.tools.tool;

import com.benxin.exp.tools.dto.OrderRequest;
import com.benxin.exp.tools.dto.TemperatureUnit;
import com.benxin.exp.tools.dto.TreeNode;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 参数类型全谱：一个方法里把"能被反射成 JSON Schema 的参数类型"摆一遍。
 *
 * <p>它同时是两个证据的来源：</p>
 * <ol>
 *   <li><b>schema 侧</b>：调用 {@code JsonSchemaGenerator.forMethod(...)} 打印自动生成的
 *       JSON Schema，逐条对照类型；</li>
 *   <li><b>绑定侧</b>：真调一次，把方法里实际收到的值原样返回 ——
 *       "schema 说是什么"与"运行时收到的什么"必须对得上，否则 schema 只是装饰。</li>
 * </ol>
 *
 * <p>另外注意 {@code count} / {@code total} 这些基本类型参数如果缺参，绑定层给的是
 * {@code 0}（{@code MethodToolCallback.primitiveDefault}），不会报错 ——
 * 基本类型 + 必填 是"最容易静默跑偏"的组合。</p>
 */
public class ParameterSpectrumTools {

    private volatile Map<String, Object> lastSeen = Map.of();

    @LlmTool(name = "param_spectrum",
            description = "参数类型全谱：基本类型/包装类型/String/枚举/List/Set/Map/数组/Optional/嵌套 POJO/defaultValue/无注解")
    public Map<String, Object> spectrum(
            @LlmToolParam(value = "count", description = "基本类型 int") int count,
            @LlmToolParam(value = "total", description = "基本类型 long") long total,
            @LlmToolParam(value = "ratio", description = "基本类型 double") double ratio,
            @LlmToolParam(value = "enabled", description = "基本类型 boolean") boolean enabled,
            @LlmToolParam(value = "boxedCount", description = "包装类型 Integer") Integer boxedCount,
            @LlmToolParam(value = "boxedFlag", description = "包装类型 Boolean") Boolean boxedFlag,
            @LlmToolParam(value = "keyword", description = "String") String keyword,
            @LlmToolParam(value = "unit", description = "枚举 TemperatureUnit") TemperatureUnit unit,
            @LlmToolParam(value = "tags", description = "List<String>") List<String> tags,
            @LlmToolParam(value = "codes", description = "Set<Integer>", required = false) Set<Integer> codes,
            @LlmToolParam(value = "extras", description = "Map<String,Object>", required = false)
            Map<String, Object> extras,
            @LlmToolParam(value = "aliases", description = "数组 String[]", required = false) String[] aliases,
            @LlmToolParam(value = "note", description = "Optional<String>", required = false) Optional<String> note,
            @LlmToolParam(value = "order", description = "嵌套 POJO（含 List<POJO> 与内层枚举）")
            OrderRequest order,
            @LlmToolParam(value = "limit", description = "带 defaultValue 的 int", required = false,
                    defaultValue = "200") int limit,
            String realNameParam) {

        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("int", count);
        seen.put("long", total);
        seen.put("double", ratio);
        seen.put("boolean", enabled);
        seen.put("Integer", boxedCount);
        seen.put("Boolean", boxedFlag);
        seen.put("String", keyword);
        seen.put("枚举", String.valueOf(unit));
        seen.put("List<String>", tags);
        seen.put("Set<Integer>", codes);
        seen.put("Map<String,Object>", extras);
        seen.put("String[]", aliases == null ? null : List.of(aliases));
        seen.put("Optional<String>", note.map(v -> "Optional[" + v + "]").orElse("Optional.empty"));
        seen.put("嵌套 POJO", order);
        seen.put("defaultValue(limit)", limit);
        seen.put("无注解参数名", realNameParam);
        // 刻意不用 Map.copyOf：它拒绝 null，而"包装类型参数缺参"本来就该是 null，
        // 那份"我收到了什么"的记录不能因为一个 null 就抛异常。
        this.lastSeen = Collections.unmodifiableMap(new LinkedHashMap<>(seen));
        return seen;
    }

    /**
     * 自引用类型：验证 schema 生成器的深度闸门（{@code MAX_DEPTH = 4}）。
     *
     * <p>参数本身照样能绑（Jackson 没这个限制），被截断的只是"写给模型看的 schema"。</p>
     */
    @LlmTool(name = "tree_depth", description = "自引用 POJO 参数：观察 schema 的深度截断")
    public String treeDepth(@LlmToolParam(value = "node", description = "TreeNode，可以为多层") TreeNode node) {
        int depth = 0;
        TreeNode cursor = node;
        while (cursor != null) {
            depth++;
            cursor = cursor.child();
        }
        return "收到一棵深度为 " + depth + " 的树，根节点 name=" + (node == null ? "null" : node.name());
    }

    /** 最近一次调用实际收到的参数（测试与演示读它，避免去解析返回的 JSON 文本）。 */
    public Map<String, Object> lastSeen() {
        return lastSeen;
    }
}
