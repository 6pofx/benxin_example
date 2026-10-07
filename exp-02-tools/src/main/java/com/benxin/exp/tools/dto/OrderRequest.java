package com.benxin.exp.tools.dto;

import java.util.List;

/**
 * 嵌套 POJO 参数：对象里套对象、套 {@code List<POJO>}、套枚举。
 *
 * <p>{@code JsonSchemaGenerator.forType} 会把它<b>递归展开</b>成
 * {@code type=object + properties + required}；绑定则交给 Jackson
 * （{@code Json.convert}），因此 record 的构造器参数名就是官方字段名，
 * 这里不需要任何注解。</p>
 *
 * <p>注意 schema 生成器是"按<b>字段</b>展开"（{@code getDeclaredFields()}），
 * 而绑定是"按 Jackson 反序列化"：两者在 record 上天然一致，
 * 但如果你在 POJO 上写了 {@code @JsonProperty} 改名，schema 里的名字不会跟着变。</p>
 */
public record OrderRequest(String orderId, Customer customer, List<OrderItem> items, Priority priority) {

    /** 二级嵌套。 */
    public record Customer(String name, String phone) {
    }

    /**
     * {@code List<POJO>} 的元素。
     *
     * <p>基本类型字段（{@code int quantity}）在 schema 里是 {@code integer}，
     * 而且和所有其它字段一样被无条件列进 {@code required} ——
     * 嵌套 POJO 的"必填"是<b>全字段必填</b>，没有"可选字段"的表达方式。</p>
     */
    public record OrderItem(String sku, int quantity) {
    }
}
