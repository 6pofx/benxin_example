package com.benxin.exp.expense;

import com.benxin.exp.expense.domain.ExpenseClaim;
import com.benxin.exp.expense.rule.ClaimParser;
import com.benxin.exp.expense.rule.ExpensePolicy;
import com.benxin.exp.expense.rule.InvoiceNumbers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务规则：发票校验、文本解析、分级制度。
 *
 * <p><b>这个类不需要模型，也不需要 Spring。</b>这不是巧合 —— 它恰好说明了代码编排里
 * "规则"那一侧的边界：金额、校验位、阈值都能被单元测试钉死，任何一次改动都会立刻变红，
 * 而不是等某天模型心情好才暴露。</p>
 */
@DisplayName("报销规则（无模型、无容器）")
class ExpenseRulesTest {

    private static final int SINGLE_LIMIT = 20_000;

    @Nested
    @DisplayName("发票号校验")
    class Invoices {

        @Test
        @DisplayName("校验位正确才通过：body 数字和取个位")
        void validInvoicePasses() {
            // 主体 2026101200 的数字和 = 14 → 校验位 4
            assertThat(InvoiceNumbers.of("2026101200")).isEqualTo("FP20261012004");
            assertThat(InvoiceNumbers.isValid("FP20261012004")).isTrue();
            assertThat(InvoiceNumbers.validate("FP20261012004")).isNull();
        }

        @Test
        @DisplayName("校验位错一位就拒绝，并说明应有的值")
        void wrongChecksumRejected() {
            String reason = InvoiceNumbers.validate("FP20261012005");
            assertThat(reason).contains("校验位不正确").contains("4");
        }

        @Test
        @DisplayName("前缀 / 长度 / 非数字都要拦，且各有各的说法")
        void shapeIsChecked() {
            assertThat(InvoiceNumbers.validate("XX20261012004")).contains("FP 前缀");
            assertThat(InvoiceNumbers.validate("FP20261012")).contains("长度");
            assertThat(InvoiceNumbers.validate("FP2026101200A")).contains("必须全部是数字");
            assertThat(InvoiceNumbers.validate(" ")).contains("为空");
        }

        @Test
        @DisplayName("生成器的入参也校验：主体必须 10 位数字")
        void generatorRejectsBadBody() {
            assertThatThrownBy(() -> InvoiceNumbers.of("123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("10 位数字");
        }
    }

    @Nested
    @DisplayName("文本解析")
    class Parsing {

        @Test
        @DisplayName("多笔金额求和，且不会把'合计'重复算一遍")
        void sumsEachAmountOnce() {
            ExpenseClaim claim = ClaimParser.parse(
                    "技术部 王强，2026-10-15 出差深圳。机票 8600 元，酒店 4200 元，发票号 FP20261012026。",
                    SINGLE_LIMIT);

            assertThat(claim.amount()).isEqualByComparingTo(new BigDecimal("12800"));
            assertThat(claim.status()).isEqualTo(ExpenseClaim.OK);
            assertThat(claim.department()).isEqualTo("技术部");
            assertThat(claim.period()).isEqualTo("2026-10-15");
        }

        @Test
        @DisplayName("千分位与小数都认")
        void thousandsAndDecimals() {
            BigDecimal total = ClaimParser.sumAmounts("机票 1,280.50 元；打车 86 元");
            assertThat(total).isEqualByComparingTo(new BigDecimal("1366.50"));
        }

        @Test
        @DisplayName("缺发票号 → 需补件，且原因写清楚")
        void missingInvoiceNeedsInfo() {
            ExpenseClaim claim = ClaimParser.parse(
                    "市场部 赵敏，2026-10-20 出差成都，餐饮费 520 元。", SINGLE_LIMIT);

            assertThat(claim.status()).isEqualTo(ExpenseClaim.NEED_INFO);
            assertThat(claim.issues()).contains("缺少发票号");
        }

        @Test
        @DisplayName("发票号校验位不对 → 需补件，原因指向校验位")
        void badChecksumNeedsInfo() {
            ExpenseClaim claim = ClaimParser.parse(
                    "销售部 张伟，2026-10-12 出差上海。高铁票 380 元，发票号 FP20261012005。", SINGLE_LIMIT);

            assertThat(claim.status()).isEqualTo(ExpenseClaim.NEED_INFO);
            assertThat(String.join("；", claim.issues())).contains("校验位");
        }

        @Test
        @DisplayName("连金额都解析不出来 → 无法受理（补件也补不出来）")
        void noAmountIsRejected() {
            ExpenseClaim claim = ClaimParser.parse("上个月出差花了一些钱，帮忙报一下。", SINGLE_LIMIT);

            assertThat(claim.status()).isEqualTo(ExpenseClaim.REJECT);
            assertThat(claim.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("超过单笔上限 → 需分管副总签批（补件而不是静默通过）")
        void overSingleLimitNeedsInfo() {
            ExpenseClaim claim = ClaimParser.parse(
                    "销售部 张伟，2026-10-12 出差上海。会务费 28800 元，发票号 FP20261012004。", SINGLE_LIMIT);

            assertThat(claim.status()).isEqualTo(ExpenseClaim.NEED_INFO);
            assertThat(String.join("；", claim.issues())).contains("单笔上限");
        }

        @Test
        @DisplayName("资料齐全 → OK，且发票去重")
        void completeClaimIsOk() {
            ExpenseClaim claim = ClaimParser.parse(
                    "销售部 张伟，2026-10-12 出差上海。高铁票 380 元，发票号 FP20261012004，"
                            + "另附 FP20261012004 复印件。", SINGLE_LIMIT);

            assertThat(claim.status()).isEqualTo(ExpenseClaim.OK);
            assertThat(claim.invoices()).hasSize(1);
            assertThat(claim.summary()).contains("金额=380");
        }
    }

    @Nested
    @DisplayName("分级制度")
    class Grading {

        private final ExpensePolicy policy = new ExpensePolicy(SINGLE_LIMIT, 2_000, 10_000, 2);

        @Test
        @DisplayName("低风险 + 不超过自动通过线 → AUTO")
        void lowRiskSmallIsAuto() {
            assertThat(policy.route("低", new BigDecimal("380"))).isEqualTo(ExpensePolicy.AUTO);
            assertThat(policy.route("低", new BigDecimal("2000"))).isEqualTo(ExpensePolicy.AUTO);
        }

        @Test
        @DisplayName("超过自动通过线 → 部门经理")
        void aboveAutoGoesToManager() {
            assertThat(policy.route("低", new BigDecimal("2000.01"))).isEqualTo(ExpensePolicy.MANAGER);
            assertThat(policy.route("中", new BigDecimal("800"))).isEqualTo(ExpensePolicy.MANAGER);
        }

        @Test
        @DisplayName("超过经理线 → 财务总监")
        void aboveManagerGoesToDirector() {
            assertThat(policy.route("低", new BigDecimal("12800"))).isEqualTo(ExpensePolicy.DIRECTOR);
        }

        @Test
        @DisplayName("高风险一律总监，金额再小也不自动放行")
        void highRiskAlwaysDirector() {
            assertThat(policy.route("高", new BigDecimal("100"))).isEqualTo(ExpensePolicy.DIRECTOR);
        }

        @Test
        @DisplayName("超过单笔上限 → ESCALATE（需分管副总）")
        void overSingleLimitEscalates() {
            assertThat(policy.route("低", new BigDecimal("28800"))).isEqualTo(ExpensePolicy.ESCALATE);
        }
    }
}
