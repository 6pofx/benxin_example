package com.benxin.exp.expense.rule;

/**
 * 发票号规则：{@code FP} + 11 位数字，最后一位是前 10 位的<b>校验位</b>。
 *
 * <p>这是示例里唯一的"真实感"来源之一：真实财务系统不会只数位数，一定会验校验位 ——
 * 因为发票号是人手输的，错一位就得对不上账。把校验位做进来，也顺带说明了
 * <b>为什么这一步必须是代码</b>：这是确定性的算术，没有任何"判断"空间。</p>
 */
public final class InvoiceNumbers {

    public static final String PREFIX = "FP";

    /** 合法发票号的长度（FP + 10 位主体 + 1 位校验）。 */
    public static final int LENGTH = PREFIX.length() + 11;

    private InvoiceNumbers() {
    }

    /**
     * 校验：前缀、长度、是否全数字、校验位是否等于前 10 位之和的个位。
     *
     * @return 非法时返回一句能直接展示给用户的原因；合法返回 {@code null}
     */
    public static String validate(String invoice) {
        if (invoice == null || invoice.isBlank()) {
            return "发票号为空";
        }
        String value = invoice.trim().toUpperCase();
        if (!value.startsWith(PREFIX)) {
            return "发票号「" + invoice + "」缺少 " + PREFIX + " 前缀";
        }
        if (value.length() != LENGTH) {
            return "发票号「" + invoice + "」长度应为 " + LENGTH + " 位";
        }
        String body = value.substring(PREFIX.length());
        if (!body.chars().allMatch(Character::isDigit)) {
            return "发票号「" + invoice + "」必须全部是数字";
        }
        int expected = checksum(body.substring(0, 10));
        int actual = body.charAt(10) - '0';
        if (expected != actual) {
            return "发票号「" + invoice + "」校验位不正确（应为 " + expected + "）";
        }
        return null;
    }

    public static boolean isValid(String invoice) {
        return validate(invoice) == null;
    }

    /** 前 10 位数字之和取个位 —— 简单但足够说明"校验位"这件事。 */
    public static int checksum(String firstTen) {
        int sum = 0;
        for (char c : firstTen.toCharArray()) {
            sum += c - '0';
        }
        return sum % 10;
    }

    /** 由 10 位主体生成一个合法发票号（演示脚本与测试用它造数据）。 */
    public static String of(String firstTen) {
        if (firstTen.length() != 10 || !firstTen.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("发票号主体必须是 10 位数字：" + firstTen);
        }
        return PREFIX + firstTen + checksum(firstTen);
    }
}
