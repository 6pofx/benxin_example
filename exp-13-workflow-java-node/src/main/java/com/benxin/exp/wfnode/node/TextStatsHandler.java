package com.benxin.exp.wfnode.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 节点①：字数统计与篇幅判定。
 *
 * <p><b>为什么这件事不能交给模型？</b> 因为"数"是一个有唯一正确答案的问题，而模型给的是
 * 一个高概率的答案 —— 短文本它常常数对，长一点就开始编。而这段逻辑写成 Java 只要几行，
 * 且每次都对。这张图里 {@code stats} 的结果会决定"要不要压缩"，所以它必须可信。</p>
 *
 * <p><b>两条输出通道都用上了</b>：</p>
 * <ul>
 *   <li>文本通道（{@code NodeOutput.of} 的第一个参数）→ 写进 {@code ${stats}}，给人看；</li>
 *   <li>变量通道（第二个参数）→ 写进 {@code ${chars}} / {@code ${level}} / {@code ${text}}，
 *       给下游节点与出边条件用。</li>
 * </ul>
 *
 * <p>变量 {@code text} 会回写这次统计的正文（去掉首尾空白）。它让
 * {@code stats} 与 {@code restats} 两个节点可以共用同一套下游逻辑 —— 压缩之后
 * {@code ${text}} 自然指向压缩后的那一版。</p>
 */
@Component
@LlmNode("text-stats")
public class TextStatsHandler implements WorkflowHandler {

    @Override
    public NodeOutput handle(NodeInput input) {
        // args 已经被引擎渲染过 ${...}，这里拿到的是最终值
        String text = input.arg("text").strip();
        int maxChars = parseInt(input.arg("maxChars"), 40);

        int chars = text.codePointCount(0, text.length());
        int visible = visibleChars(text);
        int sentences = sentences(text);
        String level = chars > maxChars ? "偏长" : "适中";

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("chars", chars);
        variables.put("visibleChars", visible);
        variables.put("sentences", sentences);
        variables.put("level", level);
        variables.put("text", text);

        return NodeOutput.of(
                "字数=%d（不含空白 %d），句数=%d，上限=%d ⇒ %s"
                        .formatted(chars, visible, sentences, maxChars, level),
                variables);
    }

    /** 去掉空白后的码点数：中文排版里空白不该算进"篇幅"。 */
    private static int visibleChars(String text) {
        return (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
    }

    /** 句数：按中英文句末标点切；没有任何标点也算一句（非空时）。 */
    private static int sentences(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        long marks = text.codePoints()
                .filter(cp -> cp == '。' || cp == '！' || cp == '？' || cp == '.' || cp == '!' || cp == '?')
                .count();
        return (int) Math.max(1, marks);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
