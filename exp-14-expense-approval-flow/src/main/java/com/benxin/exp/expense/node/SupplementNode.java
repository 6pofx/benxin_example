package com.benxin.exp.expense.node;

import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;

import java.util.Map;

/**
 * 补件：把"缺什么"变成"员工补了什么"，然后交回主流程重新解析。
 *
 * <p>真实系统里这一步会发一条待办给申请人，等对方上传材料。演示里把它压缩成
 * <b>同步模拟</b>：根据 {@code issues} 生成一条"已补交"的记录，写进
 * {@code pendingSupplement} 变量，回边回到收单节点重新解析。</p>
 *
 * <p><b>为什么补件次数要记在变量里？</b> 因为回边会让节点重复执行，而处理器本身应当是无状态的
 * （同一个实例会被多张图、多次运行复用）。把"第几轮"放进黑板，既是引擎推荐的写法，
 * 也让"补件超时"这个业务规则变成一次普通的数值比较，而不是藏在节点里的隐藏状态。</p>
 */
public class SupplementNode implements WorkflowHandler {

    private final int maxRounds;

    public SupplementNode(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    @Override
    public NodeOutput handle(NodeInput input) {
        String issues = input.arg("issues");
        int attempt = parseInt(input.arg("attempt"), 0);

        if (attempt >= maxRounds) {
            // 补件次数用尽：不再补，交给兜底分支关闭流程
            return NodeOutput.of(
                    "补件已达上限（%d 轮），不再受理：%s".formatted(maxRounds, issues),
                    Map.of("supplemented", false,
                            "supplementNote", "补件超时，流程关闭"));
        }

        String note = buildSupplement(issues);
        return NodeOutput.of(
                "已通知申请人补件（第 %d 轮）：%s".formatted(attempt + 1, note),
                Map.of("supplemented", true,
                        "supplementNote", note,
                        // 回边回来后 intake 会把这一段拼到原文后面重新解析
                        "pendingSupplement", "\n【补件】" + note,
                        "attempt", attempt + 1));
    }

    /**
     * 按缺件原因生成一份"员工补交了什么"。
     *
     * <p>注意这里补的是<b>文本</b>：流程关心的不是文件，而是"材料里现在有没有发票号"这类事实。
     * 真实系统会把附件解析后写进同一个变量，图完全不用改。</p>
     */
    private String buildSupplement(String issues) {
        StringBuilder note = new StringBuilder();
        if (issues.contains("缺少发票号")) {
            note.append("补传发票：FP20261012004（高铁票）；");
        }
        if (issues.contains("缺少出差日期")) {
            note.append("出差日期：2026-10-12 至 2026-10-14；");
        }
        if (issues.contains("缺少申请部门")) {
            note.append("申请部门：销售部；");
        }
        if (issues.contains("校验位")) {
            note.append("发票号按财务系统回执更正：FP20261012004；");
        }
        if (issues.contains("单笔上限")) {
            note.append("已附分管副总签批单；");
        }
        if (note.isEmpty()) {
            note.append("已按财务要求补充说明；");
        }
        return note.toString();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
