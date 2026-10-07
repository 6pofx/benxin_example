package com.benxin.exp.workflow;

import com.benxin.exp.workflow.demo.TemplatesDemo;
import com.benxin.llm.core.workflow.Templates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板 {@code ${}}：内置变量、节点输出、兜底值、嵌套路径，以及<b>「取不到保留原文」</b>。
 */
@DisplayName("模板 ${} Templates")
class TemplatesTest {

    private final Function<String, Object> lookup = TemplatesDemo.wfLookup();

    // ------------------------------------------------------------------
    // 内置变量与节点输出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("${input} / ${last} / ${current} / ${节点id} / ${set 变量} 都能渲染")
    void rendersBuiltinsAndNodeOutputs() {
        assertThat(Templates.render("任务：${input}", lookup)).isEqualTo("任务：把这次发布写成说明");
        assertThat(Templates.render("上一节点：${last}", lookup)).isEqualTo("上一节点：上一步的输出");
        assertThat(Templates.render("当前节点：${current}", lookup)).isEqualTo("当前节点：report");
        assertThat(Templates.render("分析：${classify}", lookup)).isEqualTo("分析：分级：紧急");
        assertThat(Templates.render("通道：${channel}", lookup)).isEqualTo("通道：加急通道");
    }

    @Test
    @DisplayName("点路径在 Map 里逐层下钻；断链就返回 null")
    void resolvesNestedPaths() {
        assertThat(Templates.render("${step1.data.title}", lookup)).isEqualTo("嵌套标题");
        assertThat(Templates.resolve("step1.data.title", lookup)).isEqualTo("嵌套标题");
        assertThat(Templates.resolve("step1.data.nope", lookup)).isNull();
        assertThat(Templates.resolve("step1.nope.title", lookup)).isNull();
        assertThat(Templates.resolve("nope.data", lookup)).isNull();
        // ${x} 解析成 path "x"（没有点），走 lookup
        assertThat(Templates.resolve("channel", lookup)).isEqualTo("加急通道");
    }

    @Test
    @DisplayName("List 也能按数字下标下钻")
    void resolvesListIndex() {
        Map<String, Object> vars = Map.of("items", List.of("甲", "乙", "丙"));
        assertThat(Templates.render("${items.1}", vars::get)).isEqualTo("乙");
        assertThat(Templates.resolve("items.9", vars::get)).isNull();
        assertThat(Templates.resolve("items.x", vars::get)).isNull();
    }

    // ------------------------------------------------------------------
    // 兜底值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("${名称:-兜底值}：取不到才用兜底值，「取到空串」不算取不到")
    void fallbackOnlyAppliesWhenMissing() {
        assertThat(Templates.render("${missing:-兜底值}", lookup)).isEqualTo("兜底值");
        // blank 取到的是空串，stringify 得到 ""（不是 null）⇒ 兜底值不生效
        assertThat(Templates.render("[${blank:-兜底值}]", lookup)).isEqualTo("[]");
        // error 在取值函数里是 null ⇒ 算取不到
        assertThat(Templates.render("${error:-（无）}", lookup)).isEqualTo("（无）");
        // 兜不住的（没有 :- 且取不到）：保留原文
        assertThat(Templates.render("${nope}", lookup)).isEqualTo("${nope}");
    }

    // ------------------------------------------------------------------
    // ★ 核心口径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 取不到的占位符保留原文（让拼写错误一眼可见）")
    void keepsPlaceholdersWhenUnresolved() {
        assertThat(Templates.render("拼错的名字：${anaylze}", lookup)).isEqualTo("拼错的名字：${anaylze}");
        assertThat(Templates.render("${a}-${b}", lookup)).isEqualTo("${a}-${b}");
        // 合法的 shell 片段因此不会被误伤，省掉一整套转义语法
        assertThat(Templates.render("echo ${HOME}/bin", lookup)).isEqualTo("echo ${HOME}/bin");
        // 部分取到时，只有取不到的那个保留原文
        assertThat(Templates.render("${input} + ${nope}", lookup))
                .isEqualTo("把这次发布写成说明 + ${nope}");
    }

    @Test
    @DisplayName("空模板 / null 模板原样返回，不抛异常")
    void handlesEmptyTemplates() {
        assertThat(Templates.render(null, lookup)).isNull();
        assertThat(Templates.render("", lookup)).isEmpty();
        assertThat(Templates.render("没有占位符", lookup)).isEqualTo("没有占位符");
        // lookup 为 null 时原样返回（契约如此）
        assertThat(Templates.render("${x}", (Function<String, Object>) null)).isEqualTo("${x}");
    }

    // ------------------------------------------------------------------
    // stringify
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stringify：基本类型原样、复杂对象走 JSON、null 就是 null")
    void stringifyContract() {
        assertThat(Templates.stringify("文本")).isEqualTo("文本");
        assertThat(Templates.stringify(12)).isEqualTo("12");
        assertThat(Templates.stringify(1.5)).isEqualTo("1.5");
        assertThat(Templates.stringify(true)).isEqualTo("true");
        assertThat(Templates.stringify(null)).isNull();
        assertThat(Templates.stringify(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("数字渲染成字符串后，条件表达式仍能按数值比较")
    void numbersStayComparable() {
        assertThat(Templates.render("还剩 ${count} 步", lookup)).isEqualTo("还剩 12 步");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("count", 12);
        assertThat(com.benxin.llm.core.workflow.Conditions.evaluate("${count} > 10", vars::get)).isTrue();
    }

    // ------------------------------------------------------------------
    // renderArgs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("renderArgs：字符串按模板替换，容器逐层下钻，非字符串原样保留")
    void renderArgsWalksContainers() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "${input}");
        args.put("n", 3);
        args.put("flag", true);
        args.put("nested", Map.of("key", "${channel}"));
        args.put("list", List.of("${classify}", "常量", 7));

        Map<String, Object> rendered = Templates.renderArgs(args, lookup);

        assertThat(rendered).containsEntry("city", "把这次发布写成说明");
        assertThat(rendered).containsEntry("n", 3);
        assertThat(rendered).containsEntry("flag", true);
        assertThat(rendered.get("nested")).isEqualTo(Map.of("key", "加急通道"));
        assertThat(rendered.get("list")).isEqualTo(List.of("分级：紧急", "常量", 7));
    }

    @Test
    @DisplayName("renderArgs：null / 空 Map 得到空 Map，不抛异常")
    void renderArgsHandlesNull() {
        assertThat(Templates.renderArgs(null, lookup)).isEmpty();
        assertThat(Templates.renderArgs(Map.of(), lookup)).isEmpty();
    }

    // ------------------------------------------------------------------
    // placeholders
    // ------------------------------------------------------------------

    @Test
    @DisplayName("placeholders：去重、去掉 :- 尾巴、点路径只取首段")
    void placeholdersListsTopLevelKeys() {
        List<String> keys = Templates.placeholders(
                "${input} + ${classify} + ${step1.data.title} + ${missing:-x} + ${classify}");

        assertThat(keys).containsExactly("input", "classify", "step1");
        assertThat(keys).doesNotContain("missing");     // 带兜底值的不算硬依赖
        assertThat(keys).doesNotContain("step1.data.title");
    }

    @Test
    @DisplayName("placeholders：空模板得到空表")
    void placeholdersOfEmptyTemplate() {
        assertThat(Templates.placeholders(null)).isEmpty();
        assertThat(Templates.placeholders("")).isEmpty();
        assertThat(Templates.placeholders("没有占位符")).isEmpty();
    }
}
