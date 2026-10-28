package com.benxin.exp.expense.rule;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 账务口径：费用类别 → 会计科目，以及凭证号的生成。
 *
 * <p>做成一个可注入的 bean 而不是静态工具，是为了说明代码编排的一个实际收益：
 * <b>节点持有的是对象，不是字符串名字</b>。真实项目里这里往往是一个
 * {@code FinanceClient} / {@code ErpGateway}，构造时注入、测试时替换，都不需要动图的形状。</p>
 */
@Component
public class AccountingService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 类别 → 一级科目。真实系统查的是科目表，这里用一张常量表代替。 */
    private static final Map<String, String> SUBJECTS = new LinkedHashMap<>();

    static {
        SUBJECTS.put("交通", "差旅费-城市间交通");
        SUBJECTS.put("住宿", "差旅费-住宿费");
        SUBJECTS.put("餐饮", "业务招待费");
        SUBJECTS.put("其他", "其他费用-杂项");
    }

    private final AtomicInteger sequence = new AtomicInteger();

    /** 类别对应的科目；类别不在表里时落到"其他费用-杂项"，而不是抛异常。 */
    public String subjectFor(String category) {
        return SUBJECTS.getOrDefault(category == null ? "" : category.trim(), SUBJECTS.get("其他"));
    }

    /** 生成凭证号：{@code PY + yyyyMMdd + 4 位流水}。 */
    public String nextVoucherNo() {
        return "PY" + LocalDate.now().format(DAY) + String.format("%04d", sequence.incrementAndGet());
    }

    /** 供测试重置流水号（演示里不需要）。 */
    public void reset() {
        sequence.set(0);
    }
}
