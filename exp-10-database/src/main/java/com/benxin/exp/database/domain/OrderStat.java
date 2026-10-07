package com.benxin.exp.database.domain;

import java.math.BigDecimal;

/**
 * 订单统计行（按状态分组）。
 *
 * @param status 订单状态；{@code ALL} 表示"不分组的总计"这一行
 * @param count  该状态下的订单笔数
 * @param amount 该状态下的金额合计
 */
public record OrderStat(String status, int count, BigDecimal amount) {
}
