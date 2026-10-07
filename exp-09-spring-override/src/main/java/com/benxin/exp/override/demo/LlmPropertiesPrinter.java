package com.benxin.exp.override.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.spring.LlmProperties;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link LlmProperties} 的取值<b>递归</b>打印出来。
 *
 * <p>为什么不用 {@code toString()}：{@code LlmProperties} 没有重写它，
 * 打出来是一串对象地址，等于什么都没说。这里的做法是走 JavaBeans 的
 * {@link PropertyDescriptor}（也就是 Spring 绑定时用的同一套 getter 约定），
 * 把嵌套对象、Map、List 逐层展开。</p>
 *
 * <p>它存在的意义是给出「yml → 领域对象」这条翻译链的对照证据：
 * {@code application.yml} 里把 {@code llm.*} 的每个字段都写了一遍，
 * 这份输出就是它们在 Java 侧的实际取值。字段名对不上、或者某个字段悄悄没被绑定，
 * 在几十行输出里一眼可见。</p>
 *
 * <p>顺带也演示了"配置 → 领域对象"的最后一跳：{@code llm.models.<key>} 那段
 * 会同时打印出 {@code toModelConfig(key)} 的结果，也就是真正交给
 * {@code ModelFactory} 的那个 {@link ModelConfig}。</p>
 *
 * <p>{@link #render(LlmProperties)} 与 {@link #print(Demo, LlmProperties)} 是同一份数据，
 * 前者给测试断言、后者给控制台演示 —— 免得两处各写一遍、慢慢漂移。</p>
 */
public final class LlmPropertiesPrinter {

    private static final int MAX_DEPTH = 6;

    private LlmPropertiesPrinter() {
    }

    /** 演示用：把嵌套结构打到 Demo 输出里。 */
    public static void print(Demo demo, LlmProperties properties) {
        if (properties == null) {
            demo.warn("LlmProperties 不在容器里（llm.enabled=false？）");
            return;
        }
        render(properties).lines().forEach(line -> demo.info("%s", line));
    }

    /** 测试用：返回同一份文本，不打印。 */
    public static String render(LlmProperties properties) {
        StringBuilder sb = new StringBuilder();
        boolean[] headerPrinted = {false};
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        walk(sb, headerPrinted, properties, "llm", 0, visited);

        sb.append(System.lineSeparator())
                .append("最后一跳：llm.models.<key> → ModelConfig（ModelFactory 真正拿到的那个对象）")
                .append(System.lineSeparator());
        properties.getModels().forEach((key, model) -> {
            ModelConfig config = model.toModelConfig(key);
            sb.append("   llm.models.").append(key)
                    .append(" → protocol=").append(config.protocol().id())
                    .append(", baseUrl=").append(config.baseUrl())
                    .append(", model=").append(config.model())
                    .append(", hasApiKey=").append(config.hasApiKey())
                    .append(", maxRetries=").append(config.maxRetries())
                    .append(System.lineSeparator());
            sb.append("        connectTimeout=").append(config.connectTimeout())
                    .append(", readTimeout=").append(config.readTimeout())
                    .append(", streamReadTimeout=").append(config.streamReadTimeout())
                    .append(", temperature=").append(config.temperature())
                    .append(", maxTokens=").append(config.maxTokens())
                    .append(System.lineSeparator());
            sb.append("        headers=").append(config.headers())
                    .append(", extra=").append(config.extra())
                    .append(System.lineSeparator());
        });

        sb.append(System.lineSeparator())
                .append("workflows 的每个 key 都会注册成 Loop：")
                .append(properties.getWorkflows().keySet().stream().map(k -> "workflow:" + k).toList())
                .append(System.lineSeparator());
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static void walk(StringBuilder sb, boolean[] headerPrinted, Object value, String path,
                             int depth, IdentityHashMap<Object, Boolean> visited) {
        String indent = "   " + "  ".repeat(depth);
        if (value == null) {
            line(sb, indent + path + " = null");
            return;
        }
        if (isScalar(value)) {
            line(sb, indent + path + " = " + value);
            return;
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            line(sb, indent + path + " = （已在上面打印过，避免环）");
            return;
        }
        if (depth >= MAX_DEPTH) {
            line(sb, indent + path + " = …（超过 " + MAX_DEPTH + " 层，不再展开）");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                line(sb, indent + path + " = {}（空）");
                return;
            }
            line(sb, indent + path + ":");
            map.forEach((k, v) -> walk(sb, headerPrinted, v, String.valueOf(k), depth + 1, visited));
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                line(sb, indent + path + " = []（空）");
                return;
            }
            line(sb, indent + path + ":");
            int index = 0;
            for (Object element : collection) {
                walk(sb, headerPrinted, element, "[" + index++ + "]", depth + 1, visited);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            line(sb, indent + path + "（数组 " + length + " 项）:");
            for (int i = 0; i < length; i++) {
                walk(sb, headerPrinted, Array.get(value, i), "[" + i + "]", depth + 1, visited);
            }
            return;
        }
        // 普通 JavaBean：按属性名排序后逐项展开。
        // 排序是刻意的 —— 输出要能跟 yml 一行行对照，顺序稳定才好读。
        line(sb, indent + path + "（" + value.getClass().getSimpleName() + "）:");
        for (PropertyDescriptor descriptor : readableProperties(value.getClass())) {
            Method getter = descriptor.getReadMethod();
            if (getter == null) {
                continue;
            }
            Object nested;
            try {
                nested = getter.invoke(value);
            } catch (ReflectiveOperationException e) {
                line(sb, indent + "  " + descriptor.getName() + " = （读取失败：" + e + "）");
                continue;
            }
            // 只读集合/映射（没有 setter）也算可绑定字段，所以这里不按 setter 是否存在过滤。
            walk(sb, headerPrinted, nested, descriptor.getName(), depth + 1, visited);
        }
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text).append(System.lineSeparator());
    }

    private static List<PropertyDescriptor> readableProperties(Class<?> type) {
        List<PropertyDescriptor> descriptors = new ArrayList<>();
        try {
            BeanInfo info = Introspector.getBeanInfo(type, Object.class);
            for (PropertyDescriptor descriptor : info.getPropertyDescriptors()) {
                if (descriptor.getReadMethod() != null && !"class".equals(descriptor.getName())) {
                    descriptors.add(descriptor);
                }
            }
        } catch (java.beans.IntrospectionException e) {
            return List.of();
        }
        descriptors.sort(Comparator.comparing(PropertyDescriptor::getName));
        return descriptors;
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAmount
                || value instanceof java.util.Date
                || value.getClass().isPrimitive();
    }
}
