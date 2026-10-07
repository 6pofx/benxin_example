package com.benxin.exp.tools.support;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 示例与测试共用的小工具：反射找方法、美化打印 JSON、造一个最简 ToolCallback。 */
public final class ToolFixtures {

    private ToolFixtures() {
    }

    /**
     * 按工具名在某个类里找回那个方法 —— 名字的推导规则与框架一致
     * （{@code ToolScanner} 决定"哪个方法是工具"，{@code MethodToolCallback.buildSpec} 决定"它叫什么"）。
     *
     * <p>第三个匹配条件是<b>方法名</b>：注解上 name / value 都留空的工具，
     * 工具名就是方法名，只按注解找会漏掉它。</p>
     */
    public static Method methodOf(Class<?> type, String toolName) {
        List<String> available = new ArrayList<>();
        boolean classLevel = type.isAnnotationPresent(LlmTool.class);
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() == Object.class
                    || method.getReturnType() == void.class) {
                continue;
            }
            LlmTool annotation = method.getAnnotation(LlmTool.class);
            boolean exposed = annotation != null ? annotation.enabled() : classLevel;
            if (!exposed) {
                continue;
            }
            String name = annotation == null ? null : firstNonBlank(annotation.name(), annotation.value());
            if (name == null) {
                name = method.getName();
            }
            available.add(name);
            if (name.equals(toolName)) {
                return method;
            }
        }
        throw new IllegalStateException("在 " + type.getName() + " 里找不到工具方法 ["
                + toolName + "]，可用的有 " + available);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    /** 美化打印 JSON：schema 是"给人看的证据"，压成一行就没法对照了。 */
    public static String pretty(JsonNode node) {
        if (node == null) {
            return "(null)";
        }
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    /** 把一个 JSON 文本压成一行并截断，适合塞进演示输出。 */
    public static String oneLine(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** schema 里 properties 的名字（保持声明顺序）。 */
    public static List<String> propertyNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        if (schema != null && schema.has("properties")) {
            schema.get("properties").fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    /** 把 {@code required} / {@code enum} 这类字符串数组读成 List（缺省时给空表）。 */
    public static List<String> textArray(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    /** 造一个只会回显的最简工具：注册表 API 的演示不需要真业务。 */
    public static ToolCallback simple(String name, String description,
                                      Function<Map<String, Object>, String> body) {
        return new ToolCallback() {

            private final ToolSpec spec = new ToolSpec(name, description, Json.object(), false);

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                return ToolResult.ok(body.apply(arguments));
            }
        };
    }
}
