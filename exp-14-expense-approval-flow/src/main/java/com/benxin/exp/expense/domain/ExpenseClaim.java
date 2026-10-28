package com.benxin.exp.expense.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一张报销单的<b>结构化</b>形态。
 *
 * <p>为什么要先解析成这个再进图？因为后面的每一步都只关心"金额是多少、有没有发票、属于哪个部门"，
 * 而不关心员工是怎么写的。把自然语言压成这样一份确定的数据，是这个流程里<b>唯一</b>该由代码做、
 * 也必须由代码做的事 —— 模型做这件事会时对时错，而金额算错是财务事故。</p>
 *
 * @param department 申请部门（识别不到时为空串）
 * @param period     出差日期（识别不到时为空串）
 * @param amount     报销金额合计（识别不到时为 0）
 * @param invoices   识别到的发票号（已去重，按出现顺序）
 * @param issues     不能受理 / 需要补件的原因；空表示资料齐全
 * @param status     {@code OK} / {@code NEED_INFO} / {@code REJECT}
 * @param raw        原始文本（保留下来给模型看，它需要上下文才能判断类别与风险）
 */
public record ExpenseClaim(String department,
                           String period,
                           BigDecimal amount,
                           List<String> invoices,
                           List<String> issues,
                           String status,
                           String raw) {

    public static final String OK = "OK";
    public static final String NEED_INFO = "NEED_INFO";
    public static final String REJECT = "REJECT";

    public ExpenseClaim {
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
        issues = issues == null ? List.of() : List.copyOf(issues);
        department = department == null ? "" : department;
        period = period == null ? "" : period;
        amount = amount == null ? BigDecimal.ZERO : amount;
        status = status == null ? OK : status;
    }

    public boolean ok() {
        return OK.equals(status);
    }

    /** 给人看的一句话（写进节点输出，便于在轨迹与日志里核对）。 */
    public String summary() {
        String money = amount.stripTrailingZeros().toPlainString();
        return "部门=%s，日期=%s，金额=%s 元，发票 %d 张%s"
                .formatted(department.isEmpty() ? "?" : department,
                        period.isEmpty() ? "?" : period,
                        money,
                        invoices.size(),
                        issues.isEmpty() ? "" : "，问题：" + String.join("；", issues));
    }
}
