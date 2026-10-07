package com.benxin.exp.workflow.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.Templates;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 模板 {@code ${}} 的演示：{@link Templates} 四个方法的直接用法 + 全套占位符语义。
 *
 * <p>最值得记住的一条是<b>「取不到的占位符保留原文」</b>。这看起来像"没实现完"，
 * 其实是一处刻意的设计：</p>
 * <ul>
 *   <li>拼错名字时（{@code ${anaylze}}）它会原样出现在提示词与轨迹里，一眼可见；</li>
 *   <li>换成空串则会静默地让提示词缺一块，模型只能靠猜，排查时看不到任何线索。</li>
 *   <li>附带的收益：提示词里合法出现的 {@code ${VAR}}（shell 片段、模板示例）不会被误伤，
 *       省掉了一整套转义语法。</li>
 * </ul>
 */
public final class TemplatesDemo {

    private TemplatesDemo() {
    }

    /** 与 {@code WorkflowState.lookup()} 同构的取值函数：先变量、后保留名。 */
    public static Function<String, Object> wfLookup() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", "把这次发布写成说明");
        vars.put("last", "上一步的输出");
        vars.put("error", null);                    // 没有失败过 ⇒ error 取不到
        vars.put("current", "report");
        vars.put("classify", "分级：紧急");
        vars.put("channel", "加急通道");
        vars.put("count", 12);
        vars.put("blank", "");
        vars.put("step1", Map.of("data", Map.of("title", "嵌套标题")));
        return key -> vars.get(key);
    }

    public static void show(Demo demo) {
        demo.section("模板 ${}：内置变量、节点输出、兜底值与「取不到保留原文」");
        Function<String, Object> lookup = wfLookup();

        // ${input} / ${last} / ${current} 三个保留名
        demo.info("${input}   → %s", render("任务：${input}", lookup));
        demo.info("${last}    → %s", render("上一节点：${last}", lookup));
        demo.info("${current} → %s", render("当前节点：${current}", lookup));
        // ${节点id} 与 ${set 写入的变量}
        demo.info("${classify} → %s", render("分析节点：${classify}", lookup));
        demo.info("${channel}  → %s", render("通道：${channel}", lookup));
        // 数字、空串、嵌套点路径
        demo.info("${count}    → %s（数字原样渲染）", render("计数：${count}", lookup));
        demo.info("${step1.data.title} → %s（点路径逐层下钻 Map）",
                render("${step1.data.title}", lookup));
        // ${名称:-兜底值}
        demo.info("${missing:-兜底值} → %s（有兜底值就能兜住）", render("${missing:-兜底值}", lookup));
        demo.info("${blank:-兜底值}   → %s（\"取到了空串\"不算取不到，兜底值不生效）",
                render("[${blank:-兜底值}]", lookup));
        demo.info("${error:-（无）}  → %s（error 为 null ⇒ 取不到 ⇒ 兜底值生效）",
                render("${error:-（无）}", lookup));

        // ★ 核心口径
        demo.ok("取不到的占位符保留原文 → %s", render("拼错的名字：${anaylze}；不存在的变量：${nope}", lookup));
        demo.info("   这是刻意的：拼写错误必须在提示词与轨迹里一眼可见，而不是静默变成空串。");
        demo.info("   ${input} 里的 shell 片段也因此不会被误伤：%s",
                render("echo ${HOME}/bin", lookup));
    }

    public static void showApis(Demo demo) {
        demo.section("Templates 的四个 API：render / renderArgs / resolve / stringify / placeholders");
        Function<String, Object> lookup = wfLookup();

        demo.info("render(template, lookup)     → %s",
                Templates.render("节点 ${classify} 走 ${channel}", lookup));
        demo.info("render(template, Map)        → %s",
                Templates.render("${a}+${b}", Map.of("a", "1", "b", "2")));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "${input}");
        args.put("n", 3);
        args.put("nested", Map.of("key", "${channel}"));
        args.put("list", List.of("${classify}", "常量"));
        demo.ok("renderArgs（工具参数递归渲染，容器逐层下钻、非字符串原样保留） →");
        demo.info("   %s", Json.writeQuietly(Templates.renderArgs(args, lookup)));

        demo.info("resolve(\"step1.data.title\")  → %s", Templates.resolve("step1.data.title", lookup));
        demo.info("resolve(\"nope\")             → %s（取不到就是 null，由模板层决定怎么显示）",
                Templates.resolve("nope", lookup));

        demo.info("stringify(String)  → %s", Templates.stringify("文本"));
        demo.info("stringify(Number)  → %s", Templates.stringify(12));
        demo.info("stringify(Boolean) → %s", Templates.stringify(true));
        demo.info("stringify(Map)     → %s（复杂对象交给 Jackson 序列化）",
                Templates.stringify(Map.of("k", "v")));
        demo.info("stringify(null)    → %s（null 就是 null，区别于空串）", Templates.stringify(null));

        demo.ok("placeholders 列出模板引用的顶层键（带兜底值的不算硬依赖） →");
        demo.info("   %s", Templates.placeholders(
                "${input} + ${classify} + ${step1.data.title} + ${missing:-x} + ${classify}"));
        demo.info("   ★ 去重、去掉 :- 尾巴、点路径只取首段 —— 供外部校验器/图可视化/文档生成用");
    }

    private static String render(String template, Function<String, Object> lookup) {
        return Templates.render(template, lookup);
    }
}
