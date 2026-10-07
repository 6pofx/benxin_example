package com.benxin.exp.database.domain;

import java.math.BigDecimal;

/**
 * 写库结果：一次成功的落库操作。
 *
 * <p>写库工具（{@code create_order / refund_order}）同样返回一个 record 而不是字符串：
 * 模型据此知道"订单 id 是多少、扣减后的状态是什么"，而测试可以直接按字段断言，
 * 不必去解析一段自然语言。</p>
 */
public record WriteResult(String action, long orderId, String sku,
                          BigDecimal amount, String status, String detail) {
}
