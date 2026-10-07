package com.benxin.exp.workflow.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.workflow.Conditions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 条件表达式与模板的<b>表格式</b>演示：不经过工作流，直接对着
 * {@link Conditions#evaluate(String, Function)} 逐条打表。
 *
 * <p>为什么要单独来一遍？因为条件表达式是"小语言"，一门语言的边界情况必须一条条列出来看，
 * 混在工作流里看只会看到"走了哪条边"，看不到"为什么"。这里的每一行都会在
 * {@code ConditionsTest} 里被断言成用例，因此这张表既是文档也是回归测试。</p>
 *
 * <p>两组取值刻意分开，因为 {@code Conditions} 的取值语义本身就分两层：</p>
 * <ul>
 *   <li><b>空取值函数</b>：什么变量都没有。此时 {@code ${x}} 是 {@code null}，
 *       而裸词（如 {@code 通过}）查不到就<b>退化成字面量</b>，所以 {@code ${x} == 通过}
 *       这种写法能成立。</li>
 *   <li><b>有值取值函数</b>：放了 {@code status=done} / {@code count=12} 之类的样本，
 *       用来验证"裸词先当变量查、查不到才当字面量"这个顺序，以及数值/真值/正则的语义。</li>
 * </ul>
 *
 * <p>{@link #pitfalls()} 那一组专门收"按直觉会猜错"的口径，包括两处容易误判的地方：
 * {@code exists} 不是"非空"，{@code matches} 的右操作数按原文截取（与 {@code and}/{@code or}/{@code )} 相邻要加引号）；
 * 以及一条实测出来的坑：<b>条件表达式里的 {@code ${x:-兜底}} 不生效</b>
 * （{@code :-} 只有 {@link com.benxin.llm.core.workflow.Templates#render} 才认）。</p>
 */
public final class ConditionsDemo {

    /** 一行断言：表达式 + 取值函数 + 期望值。 */
    public record Row(String category, String expression, Function<String, Object> lookup, boolean expected) {
    }

    private ConditionsDemo() {
    }

    /** 全空的取值函数：模拟"这些变量一个都不存在"。 */
    public static Function<String, Object> emptyLookup() {
        return key -> null;
    }

    /**
     * 有样本值的取值函数。
     *
     * <p>{@code blank} 被显式放成空串 —— 这是 {@code exists} 与 {@code is not empty}
     * 分道扬镳的那一格；{@code missingValue} 干脆不放进表里，代表"这个变量根本不存在"。</p>
     */
    public static Function<String, Object> seededLookup() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("status", "done");
        vars.put("count", 12);
        vars.put("log", "ERROR: 连接超时");
        vars.put("note", "订单 10 号已发货");
        vars.put("blank", "");
        vars.put("flag", true);
        vars.put("verdict", "通过");
        return vars::get;
    }

    /** 完整表达式表。 */
    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        Function<String, Object> none = emptyLookup();
        Function<String, Object> seed = seededLookup();

        // ---- 比较运算符 ----
        rows.add(new Row("==", "${x} == 10", none, false));
        rows.add(new Row("==", "${status} == done", seed, true));
        rows.add(new Row("==", "${status} == done2", seed, false));
        rows.add(new Row("!=", "${status} != down", seed, true));
        rows.add(new Row("!=", "${x} != 10", none, true));
        rows.add(new Row(">", "${count} > 10", seed, true));
        rows.add(new Row(">", "${count} > 12", seed, false));
        rows.add(new Row(">=", "${count} >= 12", seed, true));
        rows.add(new Row("<", "${count} < 12", seed, false));
        rows.add(new Row("<=", "${count} <= 12", seed, true));
        // 非数字参与 > 比较时判 false 而不是抛异常 —— 条件写错时走默认分支通常比整图崩掉可接受
        rows.add(new Row("<=", "${log} <= 12", seed, false));

        // ---- contains / matches ----
        rows.add(new Row("contains", "${note} contains 10", seed, true));
        rows.add(new Row("contains", "${note} contains 99", seed, false));
        rows.add(new Row("matches", "${log} matches (?i)error", seed, true));
        // 中文正则不会被当成运算符，可以直接写；但紧跟 and/or/) 时仍要加引号
        rows.add(new Row("matches", "${log} matches 超时", seed, true));
        rows.add(new Row("matches", "${log} matches ^ERROR\\b", seed, true));
        rows.add(new Row("matches", "${log} matches ^WARN", seed, false));
        // regex 写错时判 false（走默认分支），而不是抛异常
        rows.add(new Row("matches", "${log} matches [", seed, false));

        // ---- 空值判断 ----
        rows.add(new Row("exists", "${x} exists", none, false));
        rows.add(new Row("exists", "${blank} exists", seed, true));
        rows.add(new Row("exists", "${missingValue} exists", seed, false));
        rows.add(new Row("missing", "${missingValue} missing", seed, true));
        rows.add(new Row("is empty", "${blank} is empty", seed, true));
        rows.add(new Row("is empty", "${x} is empty", none, true));
        rows.add(new Row("is not empty", "${blank} is not empty", seed, false));
        rows.add(new Row("is not empty", "${status} is not empty", seed, true));
        // 中文简写：省掉 is，直接「X 为空 / X 不为空」
        rows.add(new Row("为空", "${blank} 为空", seed, true));
        rows.add(new Row("不为空", "${status} 不为空", seed, true));
        rows.add(new Row("非空", "${status} 非空", seed, true));

        // ---- 裸词与真值 ----
        rows.add(new Row("裸词字面量", "${x} == 通过", none, false));
        rows.add(new Row("裸词取值", "${verdict} == 通过", seed, true));
        rows.add(new Row("真值", "${x}", none, false));
        rows.add(new Row("真值", "${status}", seed, true));
        rows.add(new Row("真值", "${blank}", seed, false));
        rows.add(new Row("真值", "${flag}", seed, true));

        // ---- 逻辑与括号 ----
        rows.add(new Row("and", "${count} > 10 and ${status} == done", seed, true));
        rows.add(new Row("and(假)", "${count} > 100 and ${status} == done", seed, false));
        rows.add(new Row("&&", "${count} > 10 && ${status} == done", seed, true));
        rows.add(new Row("且", "${count} > 10 且 ${status} == done", seed, true));
        rows.add(new Row("or", "${x} == 1 or ${status} == done", seed, true));
        rows.add(new Row("||", "${x} == 1 || ${status} == done", seed, true));
        rows.add(new Row("或", "${x} == 1 或 ${status} == done", seed, true));
        rows.add(new Row("not", "not (${count} > 10)", seed, false));
        rows.add(new Row("!", "!(${count} > 10)", seed, false));
        rows.add(new Row("非", "非 (${count} > 10)", seed, false));
        // not contains 是**支持**的：not 是前缀否定，作用在整个比较上
        // （${log} 里没有"异常"，所以「不包含异常」成立）。这一点容易猜成"不支持"。
        rows.add(new Row("not contains", "${log} not contains 异常", seed, true));
        rows.add(new Row("not contains(假)", "${log} not contains ERROR", seed, false));
        rows.add(new Row("括号+混合", "(${count} > 10 or ${x} == 1) and not ${blank}", seed, true));
        rows.add(new Row("中文混合", "${count} > 10 且 非 (${status} == down)", seed, true));
        rows.add(new Row("空表达式", "", seed, true));

        return List.copyOf(rows);
    }

    /** 特别演示几处"按直觉会猜错"的口径 —— 每一条都在测试里被钉住。 */
    public static List<Row> pitfalls() {
        Function<String, Object> seed = seededLookup();
        List<Row> rows = new ArrayList<>();
        // ① exists 的语义是 "≠ null"，不是"非空"
        rows.add(new Row("exist≠非空", "${blank} exists", seed, true));
        rows.add(new Row("exist≠非空", "${blank} is empty", seed, true));
        rows.add(new Row("exist≠非空", "${blank} is not empty", seed, false));
        // ② matches 的右操作数按原文截取，与 and/or/) 相邻必须加引号
        rows.add(new Row("matches 引号", "${log} matches (?i)error and ${count} > 10", seed, true));
        rows.add(new Row("matches 引号", "${log} matches \"(?i)error and\"", seed, false));
        // ③ not contains 是支持的（前缀否定），别把它当成"没实现"而绕远路
        rows.add(new Row("not contains", "${log} not contains 异常", seed, true));
        // ④ ★ 条件表达式里的 ${x:-兜底} **不生效**：":-" 只有模板渲染才认，
        //    Conditions 会拿 "missingValue:-0" 这个整串去查表 ⇒ null ⇒ 比较判 false。
        //    这与"模板里带兜底值"的直观相反，triage.yml 的循环条件就踩过它。
        rows.add(new Row("条件里 :- 不生效", "${missingValue:-0} <= 2", seed, false));
        rows.add(new Row("条件里 :- 不生效", "${missingValue:-有兜底} is empty", seed, true));
        rows.add(new Row("条件里 :- 不生效", "${missingValue:-1} == 1", seed, false));
        return List.copyOf(rows);
    }

    /** 打表并返回失败的行数。 */
    public static int show(Demo demo, String title, List<Row> rows) {
        demo.section(title);
        int failures = 0;
        for (Row row : rows) {
            boolean actual;
            try {
                actual = Conditions.evaluate(row.expression(), row.lookup());
            } catch (RuntimeException e) {
                demo.warn("%-46s → 抛异常 %s: %s", quote(row.expression()),
                        e.getClass().getSimpleName(), e.getMessage());
                failures++;
                continue;
            }
            boolean ok = actual == row.expected();
            if (!ok) {
                failures++;
            }
            demo.info("%s %-14s %-46s → %-5s (期望 %s)",
                    ok ? "✓" : "✗", row.category(), quote(row.expression()), actual, row.expected());
        }
        if (failures == 0) {
            demo.ok("共 %d 条条件表达式，全部符合预期", rows.size());
        } else {
            demo.warn("共 %d 条里有 %d 条不符合预期", rows.size(), failures);
        }
        return failures;
    }

    /** {@code Conditions.validate} 的表格式演示：合法的静默通过，非法的抛可读异常。 */
    public static void showValidate(Demo demo) {
        demo.section("加载期语法校验 Conditions.validate —— 定义有问题要在启动期报错");
        List<String> valid = List.of(
                "${x} == 1",
                "${a} contains 严重 and ${b} > 10",
                "${log} matches (?i)error|异常",
                "${summary} is not empty",
                "not (${a} == 1 or ${b} > 10)",
                "${x} exists",
                "${x} missing",
                "${x} 为空",
                "${x} 不为空",
                "${x} 非空",
                "${x} is empty",
                "");
        for (String expression : valid) {
            try {
                Conditions.validate(expression);
                demo.ok("通过  %s", quote(expression));
            } catch (RuntimeException e) {
                demo.warn("本该通过却报错 %s → %s", quote(expression), e.getMessage());
            }
        }

        List<String> invalid = List.of(
                "${a} ==",                       // 运算符后面没有取值
                "(${a} == 1",                    // 缺少右括号
                "${a} == 1 or",                  // or 后面没有东西
                "${a} == 1 ${b} == 2",           // 两个表达式之间没有连接词
                "${a} is 差不多",                 // is 之后只支持 empty / 空 / 为空
                "${a == 1"                       // 未闭合的 ${ 必须拦下，否则会退化成字面量
        );
        for (String expression : invalid) {
            try {
                Conditions.validate(expression);
                demo.warn("本该报错却通过了 %s", quote(expression));
            } catch (IllegalArgumentException e) {
                demo.ok("如期报错 %s", quote(expression));
                demo.info("      %s", PureEngineDemo.flatten(e.getMessage()));
            }
        }
    }

    private static String quote(String expression) {
        return "\"" + expression + "\"";
    }
}
