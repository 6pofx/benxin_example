package com.benxin.exp.expense.rule;

import com.benxin.exp.expense.domain.ExpenseClaim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一段自由格式的报销说明解析成 {@link ExpenseClaim}。
 *
 * <p>这是整张图里<b>最应该由代码做</b>的一步：金额要对上账、发票要验校验位、
 * 部门与日期要对得上制度。这些都有唯一正确答案，模型给的只是"高概率答案"。</p>
 *
 * <p>解析器的态度是"宁可报缺件，也不猜"：识别不到就记一条 issue，
 * 让流程走补件分支，而不是用一个默认值蒙混过去 —— 财务流程里蒙错一次的代价远高于多问一句。</p>
 */
public final class ClaimParser {

    /** 金额：允许千分位与小数，必须带"元"字，避免把日期里的数字当地金额。 */
    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*元");

    /** 发票号：FP + 11 位数字。 */
    private static final Pattern INVOICE = Pattern.compile("FP\\d{11}", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPARTMENT =
            Pattern.compile("(销售部|技术部|市场部|财务部|人力资源部|产品部|运营部|法务部)");

    /** 出差日期：取第一个出现的中文日期区间起点即可，制度上只要求"能定位到一次出差"。 */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private ClaimParser() {
    }

    public static ExpenseClaim parse(String text, int singleClaimLimit) {
        String raw = text == null ? "" : text.strip();
        List<String> issues = new ArrayList<>();

        BigDecimal amount = sumAmounts(raw);
        Set<String> invoices = new LinkedHashSet<>();
        Matcher invoiceMatcher = INVOICE.matcher(raw);
        while (invoiceMatcher.find()) {
            invoices.add(invoiceMatcher.group().toUpperCase());
        }
        String department = first(DEPARTMENT, raw);
        String period = first(DATE, raw);

        // ---- 硬性缺件：缺任何一项都无法受理 ----
        if (amount.signum() <= 0) {
            issues.add("未识别到报销金额");
        }
        if (invoices.isEmpty()) {
            issues.add("缺少发票号");
        }
        if (department.isEmpty()) {
            issues.add("缺少申请部门");
        }
        if (period.isEmpty()) {
            issues.add("缺少出差日期");
        }

        // ---- 发票号本身不合法：属于"可以补正"的问题 ----
        for (String invoice : invoices) {
            String invalid = InvoiceNumbers.validate(invoice);
            if (invalid != null) {
                issues.add(invalid);
                break;
            }
        }

        // ---- 制度上限：不是资料问题，而是需要更高层级签批 ----
        if (amount.compareTo(BigDecimal.valueOf(singleClaimLimit)) > 0) {
            issues.add("金额超过单笔上限 " + singleClaimLimit + " 元，需分管副总签批");
        }

        String status;
        if (amount.signum() <= 0) {
            // 连金额都没有，补件也补不出来 —— 直接退回，不进审批流
            status = ExpenseClaim.REJECT;
        } else if (!issues.isEmpty()) {
            status = ExpenseClaim.NEED_INFO;
        } else {
            status = ExpenseClaim.OK;
        }

        return new ExpenseClaim(department, period, amount, List.copyOf(invoices),
                issues, status, raw);
    }

    /** 把所有"…元"相加；千分位去掉再转 BigDecimal。 */
    public static BigDecimal sumAmounts(String text) {
        BigDecimal total = BigDecimal.ZERO;
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).replace(",", "");
            try {
                total = total.add(new BigDecimal(value));
            } catch (NumberFormatException ignored) {
                // 解析不了的片段跳过：宁可少算（之后会走补件），也不要抛异常中断流程
            }
        }
        return total;
    }

    /**
     * 取第一个匹配的<b>整段</b>文本。
     *
     * <p>刻意用 {@code group()} 而不是 {@code group(1)}：这两个 Pattern 里有的带捕获组、
     * 有的（日期）不带，统一取整段匹配才不会"换一个正则就抛 No group 1"。
     * 第一版就是踩了这个坑 —— 而且是在真跑演示时才炸出来的。</p>
     */
    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }
}
