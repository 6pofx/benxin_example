package com.benxin.exp.workflow;

import com.benxin.exp.workflow.demo.ConditionsDemo;
import com.benxin.llm.core.workflow.Conditions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Conditions} 一张表跑到底：完整语法逐个断言。
 *
 * <p>表由 {@link ConditionsDemo#rows()} 提供，演示与测试共用同一份数据 ——
 * 演示里打印出来的每一行，在这里都被钉成可回归的用例。</p>
 *
 * <p>所有断言都反映<b>实测行为</b>：凡与文档/直觉不一致的地方（例如条件表达式里的
 * {@code ${x:-兜底}} 不生效），都在下面单独写了用例，并在最终汇报的「插件问题」里列出。</p>
 */
@DisplayName("条件表达式 Conditions 全谱")
class ConditionsTest {

    static List<ConditionsDemo.Row> rows() {
        return ConditionsDemo.rows();
    }

    static List<ConditionsDemo.Row> pitfalls() {
        return ConditionsDemo.pitfalls();
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @MethodSource("rows")
    @DisplayName("语法表：每一个运算符/连接词/中文别名都断言到位")
    void evaluatesEveryRow(ConditionsDemo.Row row) {
        assertThat(Conditions.evaluate(row.expression(), row.lookup()))
                .as("%s（期望 %s）", row.expression(), row.expected())
                .isEqualTo(row.expected());
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @MethodSource("pitfalls")
    @DisplayName("几处容易猜错的口径：exists ≠ 非空、matches 的引号规则、条件里的 :- 不生效")
    void evaluatesPitfalls(ConditionsDemo.Row row) {
        assertThat(Conditions.evaluate(row.expression(), row.lookup()))
                .as("%s（期望 %s）", row.expression(), row.expected())
                .isEqualTo(row.expected());
    }

    @Test
    @DisplayName("not contains 是支持的：not 是前缀否定，作用在整个比较上")
    void notContainsIsSupported() {
        Function<String, Object> lookup = ConditionsDemo.seededLookup();

        // log = "ERROR: 连接超时" —— 里面没有"异常"，所以「不包含异常」成立
        assertThat(Conditions.evaluate("${log} not contains 异常", lookup)).isTrue();
        assertThat(Conditions.evaluate("${log} not contains ERROR", lookup)).isFalse();
        // 与 not (… contains …) 完全等价，两种写法都可以用
        assertThat(Conditions.evaluate("not (${log} contains 异常)", lookup))
                .isEqualTo(Conditions.evaluate("${log} not contains 异常", lookup));
        // 加载期校验也认它
        Conditions.validate("${log} not contains 异常");
    }

    @Test
    @DisplayName("★ 实测：条件表达式里的 ${x:-兜底} 不生效（:- 只有模板渲染才认），与 triage.yml 踩到的一致")
    void fallbackInsideConditionsDoesNotWork() {
        Function<String, Object> lookup = ConditionsDemo.seededLookup();

        // Conditions 把 "missingValue:-0" 当成**变量名**去查表，查不到就是 null，
        // 于是数值比较判 false —— 而不是像写的人期望的那样拿 0 去比。
        assertThat(Conditions.evaluate("${missingValue:-0} <= 2", lookup)).isFalse();
        assertThat(Conditions.evaluate("${missingValue:-1} == 1", lookup)).isFalse();
        // null 的语义仍然是 null：带兜底的写法也一样 is empty / missing
        assertThat(Conditions.evaluate("${missingValue:-有兜底} is empty", lookup)).isTrue();
        assertThat(Conditions.evaluate("${missingValue:-有兜底} exists", lookup)).isFalse();

        // 对照：同一个占位符交给模板渲染，兜底值就生效了
        assertThat(com.benxin.llm.core.workflow.Templates.render("${missingValue:-0} <= 2", lookup))
                .isEqualTo("0 <= 2");
        assertThat(com.benxin.llm.core.workflow.Templates.render("${missingValue:-有兜底}", lookup))
                .isEqualTo("有兜底");

        // 所以循环计数器这类变量必须在**上游 set 节点先播种**，不能指望条件里的 :-。
        // 这不是示例的写法偏好，而是本次实测出来的插件口径（见汇报的「插件问题」）。
    }

    @Test
    @DisplayName("exists 的语义是「≠ null」，不是「非空」")
    void existsMeansNotNull() {
        Function<String, Object> lookup = ConditionsDemo.seededLookup();

        // blank 是一个真实存在的空串：exists 为 true，is empty 也为 true
        assertThat(Conditions.evaluate("${blank} exists", lookup)).isTrue();
        assertThat(Conditions.evaluate("${blank} is empty", lookup)).isTrue();
        assertThat(Conditions.evaluate("${blank} is not empty", lookup)).isFalse();

        // 想判断「有内容」必须用 is not empty；用 exists 会放进空串
        assertThat(Conditions.evaluate("${blank} exists and ${blank} is not empty", lookup)).isFalse();

        // 表里根本没有的键才是 missing
        assertThat(Conditions.evaluate("${missingValue} exists", lookup)).isFalse();
        assertThat(Conditions.evaluate("${missingValue} missing", lookup)).isTrue();
    }

    @Test
    @DisplayName("matches 的右操作数按原文截取：与 and / or / ) 相邻必须加引号")
    void matchesOperandIsTakenVerbatim() {
        Function<String, Object> lookup = ConditionsDemo.seededLookup();

        // 不加引号：正则从下一个 token 起一直读到顶层的 and 为止，所以只是「(?i)error」
        assertThat(Conditions.evaluate("${log} matches (?i)error and ${count} > 10", lookup)).isTrue();

        // 加了引号：整段按字面量当正则，于是「(?i)error and」当然匹配不上
        assertThat(Conditions.evaluate("${log} matches \"(?i)error and\"", lookup)).isFalse();

        // 这也是为什么 (?i) 这种最常见的忽略大小写写法能直接写 ——
        // 先分词再拼回去的话，(?i) 会被拆成括号 + 裸词，直接变成语法错误
        assertThat(Conditions.evaluate("${log} matches (?i)ERROR", lookup)).isTrue();

        // 正则里带小括号时按深度配对，不会被误当成表达式的括号
        assertThat(Conditions.evaluate("${log} matches (?i)(ERROR|WARN)", lookup)).isTrue();
    }

    @Test
    @DisplayName("裸词先当变量查、查不到才当字面量")
    void bareWordsFallBackToLiterals() {
        // 没有这个变量 → 通过 就是字面量，于是能和 ${x} 比
        assertThat(Conditions.evaluate("${x} == 通过", ConditionsDemo.emptyLookup())).isFalse();
        assertThat(Conditions.evaluate("${verdict} == 通过", ConditionsDemo.seededLookup())).isTrue();

        // 数字字面量优先于变量名：写成 10 的人不会期望它是变量
        assertThat(Conditions.evaluate("${count} == 12", ConditionsDemo.seededLookup())).isTrue();
        assertThat(Conditions.evaluate("${count} == 010", ConditionsDemo.seededLookup())).isFalse();
    }

    @Test
    @DisplayName("非数字参与 > 比较时判 false，而不是抛异常（走默认分支比整图崩掉可接受）")
    void nonNumericComparisonIsFalse() {
        assertThat(Conditions.evaluate("${log} > 10", ConditionsDemo.seededLookup())).isFalse();
        assertThat(Conditions.evaluate("${log} <= 10", ConditionsDemo.seededLookup())).isFalse();
    }

    @Test
    @DisplayName("正则写错时判 false，而不是抛异常")
    void brokenRegexIsFalse() {
        assertThat(Conditions.evaluate("${log} matches [", ConditionsDemo.seededLookup())).isFalse();
    }

    @Test
    @DisplayName("取值为 null 时「== null」成立、「!= x」成立")
    void nullSemantics() {
        Function<String, Object> none = ConditionsDemo.emptyLookup();
        assertThat(Conditions.evaluate("${x} == null", none)).isTrue();
        assertThat(Conditions.evaluate("${x} != null", none)).isFalse();
        assertThat(Conditions.evaluate("${x} == nil", none)).isTrue();
    }

    @Test
    @DisplayName("空表达式视为恒真（即默认分支）")
    void blankExpressionIsTrue() {
        assertThat(Conditions.evaluate(null, ConditionsDemo.emptyLookup())).isTrue();
        assertThat(Conditions.evaluate("   ", ConditionsDemo.emptyLookup())).isTrue();
        assertThat(Conditions.evaluate("", ConditionsDemo.emptyLookup())).isTrue();
    }

    @Test
    @DisplayName("lookup 为 null 也不炸，等于「什么变量都没有」")
    void nullLookupIsTolerated() {
        assertThat(Conditions.evaluate("${x} == null", null)).isTrue();
        assertThat(Conditions.evaluate("${x} exists", null)).isFalse();
    }

    @Test
    @DisplayName("兼容 Map 与集合：contains 对集合是「含某项」，对 Map 是「有这个键」")
    void containsSupportsCollections() {
        Map<String, Object> vars = Map.of(
                "tags", List.of("紧急", "线上"),
                "meta", Map.of("owner", "值班"));
        Function<String, Object> lookup = vars::get;

        assertThat(Conditions.evaluate("${tags} contains 紧急", lookup)).isTrue();
        assertThat(Conditions.evaluate("${tags} contains 无关", lookup)).isFalse();
        assertThat(Conditions.evaluate("${meta} contains owner", lookup)).isTrue();
        assertThat(Conditions.evaluate("${meta} contains nobody", lookup)).isFalse();
    }

    @Test
    @DisplayName("validate：合法表达式静默通过")
    void validateAcceptsValidExpressions() {
        for (String expression : List.of(
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
                "${x} is not empty",
                "",
                "   ")) {
            Conditions.validate(expression);
        }
    }

    @Test
    @DisplayName("validate：非法表达式在加载期就抛，消息可读")
    void validateRejectsBrokenExpressions() {
        assertThatThrownBy(() -> Conditions.validate("${a} =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("条件表达式解析失败");

        assertThatThrownBy(() -> Conditions.validate("(${a} == 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少右括号");

        assertThatThrownBy(() -> Conditions.validate("${a} == 1 or"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少取值");

        assertThatThrownBy(() -> Conditions.validate("${a} == 1 ${b} == 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多余内容");

        assertThatThrownBy(() -> Conditions.validate("${a} is 差不多"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is 之后只支持 empty");

        // 未闭合的 ${ 必须在这里被拦下，否则它会退化成字面量 ⇒ 条件恒为真
        assertThatThrownBy(() -> Conditions.validate("${a == 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有闭合");

        assertThatThrownBy(() -> Conditions.validate("${a} not 差不多 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not 之后需要");
    }

    @Test
    @DisplayName("validate 的表格式演示里那三条非法例子确实非法")
    void demoInvalidExamplesAreReallyInvalid() {
        for (String expression : com.benxin.exp.workflow.demo.WorkflowIoDemo.invalidConditions()) {
            assertThatThrownBy(() -> Conditions.validate(expression))
                    .as("本该非法的表达式：%s", expression)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}