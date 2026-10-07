package com.benxin.exp.database.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * 订单行。
 *
 * <p>它是工具方法的返回值，因此会被本心自动 JSON 化后回灌给模型 ——
 * 换成 record 而不是 Map，最大的好处是"模型看到的字段名"在编译期就定下来了，
 * 不会因为某次手抖写错 key 而变成模型眼里的 null。</p>
 *
 * <p>{@code created_at} 用 {@link Timestamp} 而不是 {@code LocalDateTime}：
 * JDBC 4.2 起两者都能直接绑定，但 {@code Timestamp} 在 H2 与 MySQL 上的行为完全一致，
 * 不需要额外配置 Jackson 的 JavaTimeModule。</p>
 */
public record OrderItem(
        long id,
        long customerId,
        String customerName,
        String sku,
        BigDecimal amount,
        String status,
        Timestamp createdAt) {
}
