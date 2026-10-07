package com.benxin.exp.workflow;

import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记录一条<b>实测出来的坑</b>：{@code WorkflowIo} 的"自动识别格式"太朴素。
 *
 * <p>{@code WorkflowIo.read(String)} 只看<b>首个非空字符是不是 <code>{</code></b>，
 * 是就当 JSON。但行内 flow 写法的 YAML 也以 <code>{</code> 开头 ——
 * 于是 {code {name: x, nodes: [...]}} 这种完全合法的 YAML 会被送去 Jackson，
 * 报出一句与真实原因无关的错误。</p>
 *
 * <p>★ 错误消息的<b>真实样子</b>（本用例实测，不是猜的）：</p>
 * <pre>
 *   java.lang.IllegalArgumentException: 非法 JSON: {name: inline-loop, nodes: [...], edges: []}
 * </pre>
 * <p>它来自 {@code Json.parse} 的包装，<b>不会</b>出现文档/直觉里那句 Jackson 原文
 * {@code Unexpected character ('n'): was expecting double-quote to start field name}
 * —— 根因（"明明是 YAML 却被当 JSON"）得从 <code>非法 JSON</code> 这四个字读出来。</p>
 *
 * <p>这个测试<b>不是在批准这个行为</b>，而是把它的边界钉住：</p>
 * <ul>
 *   <li>现状（断言为 true）：flow 写法的 YAML 走自动识别会失败；</li>
 *   <li>正确用法（断言为 true）：显式传 {@code Format.YAML}，或者用块写法。</li>
 * </ul>
 *
 * <p>对使用者的直接影响：{@code llm.workflows.<key>.inline} 里的定义<b>不要写成 flow 写法</b>，
 * 用块写法（{@code name: ...} 换行缩进）即可 —— Spring Boot 从 YAML 源读进来时会自动去掉
 * 公共缩进，块标量完全可用。</p>
 */
@DisplayName("已知坑：WorkflowIo 自动识别格式把 flow 写法的 YAML 当成 JSON")
class WorkflowIoDetectionTest {

    private static final String FLOW_STYLE_YAML =
            "{name: inline-loop, nodes: [{id: only, type: end, output: 跑通了}], edges: []}";

    private static final String BLOCK_STYLE_YAML = """
            name: inline-loop
            nodes:
              - id: only
                type: end
                output: 跑通了
            edges: []
            """;

    /** 去掉所有空白，用于把两个错误消息放在一起比。 */
    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    @Test
    @DisplayName("现状：flow 写法的 YAML 走 read(String) 会被当 JSON，报一句与真实原因无关的错")
    void flowStyleYamlIsMistakenForJson() {
        assertThatThrownBy(() -> WorkflowIo.read(FLOW_STYLE_YAML))
                .isInstanceOf(IllegalArgumentException.class)
                // ★ 实测消息就是这一句：包装层说的是"非法 JSON" —— 但我们给的明明是 YAML。
                //   它既没有行号也没有"是不是 YAML"的提示，所以只能靠人认出这个形状。
                .hasMessageContaining("非法 JSON")
                .hasMessageContaining("inline-loop")
                .hasMessageContaining("nodes");
    }

    @Test
    @DisplayName("flow 风格 YAML 的报错：1.1.1 起两条路径都给出「它其实是 YAML」的可操作建议")
    void flowStyleYamlUnderJsonFormatFailsTheSameWay() {
        String auto = messageOf(() -> WorkflowIo.read(FLOW_STYLE_YAML));
        String forced = messageOf(() -> WorkflowIo.read(FLOW_STYLE_YAML, WorkflowIo.Format.JSON));

        // 1.1.0 时两条路径都只有一句"非法 JSON"（把排查引向 JSON 括号）；
        // 1.1.1 起自动识别与显式 JSON 都会点明"这其实是 YAML 的 flow 风格"并给出两条出路
        // （FINDINGS 第 15 条）。两条路径的文案一致，所以这里对它们做同一组断言。
        assertThat(auto).startsWith("工作流定义看起来像 JSON");
        assertThat(auto).contains("YAML", "Format.YAML");
        assertThat(forced).isEqualTo(auto);
    }

    private static String messageOf(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("本该抛异常，却通过了");
    }

    @Test
    @DisplayName("正确做法一：显式声明 Format.YAML，同一份 flow 文本就能读进来")
    void explicitYamlFormatWorks() {
        WorkflowDefinition definition = WorkflowIo.read(FLOW_STYLE_YAML, WorkflowIo.Format.YAML);

        assertThat(definition.name()).isEqualTo("inline-loop");
        assertThat(definition.nodeIds()).containsExactly("only");
        // 与块写法读出来的是同一个图
        WorkflowDefinition block = WorkflowIo.read(BLOCK_STYLE_YAML);
        assertThat(definition.nodes().get(0).output()).isEqualTo(block.nodes().get(0).output());
    }

    @Test
    @DisplayName("正确做法二：块写法（application.yml 的 inline 用的就是它）")
    void blockStyleYamlWorksWithAutoDetection() {
        WorkflowDefinition definition = WorkflowIo.read(BLOCK_STYLE_YAML);

        assertThat(definition.name()).isEqualTo("inline-loop");
        assertThat(definition.nodeIds()).containsExactly("only");
        assertThat(definition.resolveEntry().id()).isEqualTo("only");
    }

    @Test
    @DisplayName("对照：真正的 JSON 走自动识别没有问题（所以这个坑只在 flow 写法的 YAML 上）")
    void jsonIsDetectedFine() {
        String json = WorkflowIo.writeJson(WorkflowIo.read(BLOCK_STYLE_YAML));

        assertThat(json.stripLeading()).startsWith("{");
        assertThat(WorkflowIo.read(json).name()).isEqualTo("inline-loop");
    }
}
