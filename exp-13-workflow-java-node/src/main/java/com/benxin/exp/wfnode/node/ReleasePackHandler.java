package com.benxin.exp.wfnode.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 节点③：打包成一条"发布记录"。
 *
 * <p>它演示的是<b>两条通道的分工</b>：文本通道给"给人看"的那一版（{@code ${pack}}），
 * 变量通道给下游节点与条件表达式按名字读的字段（{@code ${title}} / {@code ${chars}} / …）。
 * 两者是同一份数据的两个视角，不需要谁把谁解析一遍。</p>
 *
 * <p>它还顺手演示了一个真实项目里常见的坑：<b>节点的输出只存在自己 id 名下</b>。
 * 终点的 {@code done} 节点两条分支都要引用同一句话，所以这里额外写了一个
 * {@code packed} 变量，让"通过"（pack 写）与"不通过"（reject 节点的 set 写）能共用一个名字。
 * 如果只依赖 {@code ${pack}}，走 reject 分支时它就是空的。</p>
 *
 * <p>{@code chars} 由上游 {@code stats}/{@code restats} 算好传进来（节点参数里写
 * {@code chars: "${chars}"}）。它到这儿是字符串 —— 模板渲染的结果总是字符串，
 * 需要数字就在这里转回数字，别指望它一路保持类型。这一点很容易猜错，所以写成了显式的 {@code parseInt}。</p>
 */
@Component
@LlmNode("release-pack")
public class ReleasePackHandler implements WorkflowHandler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public NodeOutput handle(NodeInput input) {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("title", input.arg("title"));
        pack.put("summary", input.arg("summary"));
        pack.put("chars", parseInt(input.arg("chars"), 0));
        pack.put("model", "LM Studio / Qwen3-VL-8B（本机）");
        pack.put("builtAt", LocalDateTime.now().format(TIME));
        // 这一步同时证明了"处理器能读到黑板"：变量不是通过参数传进来的，而是从 NodeInput 里按名取
        pack.put("reviewedLevel", input.variable("level"));

        // 文本通道给"给人看"的那一版；变量通道给下游/条件用。两条通道是同一份数据的两个视角。
        String readable = "【发布包】%s%n摘要：%s%n字数：%s（篇幅 %s）%n模型：%s%n生成时间：%s"
                .formatted(pack.get("title"), pack.get("summary"), pack.get("chars"),
                        pack.get("reviewedLevel"), pack.get("model"), pack.get("builtAt"));

        Map<String, Object> variables = new LinkedHashMap<>(pack);
        // 额外给一个 packed 变量：end 节点在"通过"与"不通过"两条分支上引用同一个名字，
        // 于是终点的文案只需要写一次 ${packed}（reject 分支的 set 节点写的也是它）
        variables.put("packed", readable);
        return NodeOutput.of(readable, variables);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
