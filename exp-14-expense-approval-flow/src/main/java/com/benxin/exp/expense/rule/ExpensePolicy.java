package com.benxin.exp.expense.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 审批制度：金额阈值 + "风险 × 金额"的分级规则。
 *
 * <p>这段逻辑<b>刻意不交给模型</b>：制度是白纸黑字写死的，谁审批取决于金额落在哪一档，
 * 与措辞无关。让模型来判断"这笔该谁批"，等于让它在每次运行里重新诠释一遍制度 ——
 * 而制度恰恰是不该被诠释的东西。</p>
 *
 * <p>同时它也是"代码节点能注入依赖"的样本：阈值来自配置，测试里换一份构造参数就能
 * 覆盖所有边界，不需要起 Spring、更不需要模型。</p>
 */
@Component
public class ExpensePolicy {

    /** 自动通过 / 部门经理 / 财务总监 / 需更高层签批。 */
    public static final String AUTO = "AUTO";
    public static final String MANAGER = "MANAGER";
    public static final String DIRECTOR = "DIRECTOR";
    public static final String ESCALATE = "ESCALATE";

    private final int singleClaimLimit;
    private final int autoApproveLimit;
    private final int managerLimit;
    private final int maxSupplementRounds;

    public ExpensePolicy(@Value("${exp.expense.single-claim-limit:20000}") int singleClaimLimit,
                         @Value("${exp.expense.auto-approve-limit:2000}") int autoApproveLimit,
                         @Value("${exp.expense.manager-limit:10000}") int managerLimit,
                         @Value("${exp.expense.max-supplement-rounds:2}") int maxSupplementRounds) {
        this.singleClaimLimit = singleClaimLimit;
        this.autoApproveLimit = autoApproveLimit;
        this.managerLimit = managerLimit;
        this.maxSupplementRounds = maxSupplementRounds;
    }

    public int singleClaimLimit() {
        return singleClaimLimit;
    }

    public int maxSupplementRounds() {
        return maxSupplementRounds;
    }

    /**
     * 分级：<b>先看风险，再看金额</b>。
     *
     * <ul>
     *   <li>高风险 → 一律财务总监（金额再小也不自动放行）；</li>
     *   <li>低风险且金额不超过自动通过线 → {@link #AUTO}；</li>
     *   <li>金额超过单笔上限 → {@link #ESCALATE}（需分管副总，本流程里按总监处理）；</li>
     *   <li>其余不超过经理线 → {@link #MANAGER}，超过 → {@link #DIRECTOR}。</li>
     * </ul>
     */
    public String route(String risk, BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        boolean high = "高".equals(risk);
        boolean low = "低".equals(risk);

        if (high) {
            return DIRECTOR;
        }
        if (low && value.compareTo(BigDecimal.valueOf(autoApproveLimit)) <= 0) {
            return AUTO;
        }
        if (value.compareTo(BigDecimal.valueOf(singleClaimLimit)) > 0) {
            return ESCALATE;
        }
        if (value.compareTo(BigDecimal.valueOf(managerLimit)) <= 0) {
            return MANAGER;
        }
        return DIRECTOR;
    }
}
