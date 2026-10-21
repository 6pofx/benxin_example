package com.benxin.exp.wfnode.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 节点②：合规校验（敏感词 + 字数上限 + 非空）。
 *
 * <p><b>业务规则为什么不该写成提示词？</b> 因为这类判断要求"同一段文本永远得到同一个结论"。
 * 写进提示词，模型会时灵时不灵，而且换一个模型结论就变；写在这里，它是一段可以被单测钉住的代码。</p>
 *
 * <p>它还演示了 NodeOutput 文档里强调的那句话：<b>处理器不决定下一条边</b>。
 * 这里只写 {@code ${verdict}} 与 {@code ${violations}}，走 {@code pack} 还是 {@code reject}
 * 由 {@code decide} 节点的出边条件决定 —— 整张图只有一种路由机制。</p>
 *
 * <p>注意 {@code violations} 用的是 {@code String} 而不是 {@code List}：模板 {@code ${violations}}
 * 会把它拼进 end 节点的文案里，用字符串最直接。真要结构化，就再给一个 List 变量 ——
 * 变量通道本来就是给这种"下游要按名字读"的东西准备的。</p>
 */
@Component
@LlmNode("compliance")
public class ComplianceHandler implements WorkflowHandler {

    @Override
    public NodeOutput handle(NodeInput input) {
        String text = input.arg("text").strip();
        String banned = input.arg("banned");
        int maxChars = parseInt(input.arg("maxChars"), 60);

        List<String> violations = new ArrayList<>();
        if (text.isEmpty()) {
            violations.add("正文为空");
        }
        for (String raw : banned.split("[,，]")) {
            String word = raw.strip();
            if (!word.isEmpty() && text.contains(word)) {
                violations.add("命中敏感词「" + word + "」");
            }
        }
        int chars = text.codePointCount(0, text.length());
        if (chars > maxChars) {
            violations.add("字数 " + chars + " 超过上限 " + maxChars);
        }

        boolean pass = violations.isEmpty();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("verdict", pass ? "通过" : "不通过");
        variables.put("violations", String.join("；", violations));
        variables.put("violationCount", violations.size());

        return NodeOutput.of(
                pass ? "合规校验通过（敏感词 0 处，字数 " + chars + " ≤ " + maxChars + "）"
                        : "合规校验未通过：" + String.join("；", violations),
                variables);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
