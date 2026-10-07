package com.benxin.exp.database.db;

import com.benxin.exp.database.domain.Customer;
import com.benxin.exp.database.domain.OrderItem;
import com.benxin.exp.database.domain.OrderStat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 查询侧仓储：所有"读库"的 SQL 都集中在这里。
 *
 * <p>为什么单独抽一层，而不是把 SQL 直接写在 {@code @LlmTool} 方法里？</p>
 * <ol>
 *   <li><b>工具层要薄</b>：{@code @LlmTool} 方法的职责是"把模型给的参数翻译成一次业务调用"，
 *       它不该同时承担 SQL 拼接、列名映射这些事；</li>
 *   <li><b>可测</b>：仓储可以脱离 Agent 单独断言（测试里就是这么做的：
 *       "改一行数据，答案随之变化"验证的是仓储，不是模型）；</li>
 *   <li><b>工作流也要用</b>：workflow 的 {@code tool} 节点直调的是工具，但工具背后就是这层。</li>
 * </ol>
 *
 * <p>全部用 {@link JdbcTemplate} 的参数绑定（{@code ?}），不做字符串拼接 ——
 * 工具参数直接来自模型输出，拼接等于把注入面敞开给模型。</p>
 */
@Repository
public class OrderRepository {

    /** 订单行 + 客户名的联表映射；工具返回给模型的就是这个形状。 */
    private static final RowMapper<OrderItem> ORDER_MAPPER = (rs, rowNum) -> new OrderItem(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("customer_name"),
            rs.getString("sku"),
            rs.getBigDecimal("amount"),
            rs.getString("status"),
            rs.getTimestamp("created_at"));

    private static final RowMapper<Customer> CUSTOMER_MAPPER = (rs, rowNum) -> new Customer(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("level"),
            rs.getString("phone"),
            rs.getTimestamp("created_at"));

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 客户
    // ------------------------------------------------------------------

    /**
     * 按姓名精确查客户。
     *
     * <p>只做精确匹配，不做"模糊搜索"：示例要演示的是"模型决策 → 查库 → 回灌"，
     * 模糊匹配会让"为什么返回了这三条"变成一个需要解释的额外变量。</p>
     */
    public Optional<Customer> findCustomerByName(String name) {
        List<Customer> rows = jdbc.query(
                "SELECT id, name, level, phone, created_at FROM customer WHERE name = ?",
                CUSTOMER_MAPPER, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Customer> findCustomerById(long customerId) {
        List<Customer> rows = jdbc.query(
                "SELECT id, name, level, phone, created_at FROM customer WHERE id = ?",
                CUSTOMER_MAPPER, customerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ------------------------------------------------------------------
    // 订单
    // ------------------------------------------------------------------

    /**
     * 按客户 + 可选状态查订单。
     *
     * <p>{@code status} 传 null 或空串表示"不限状态"。刻意用 SQL 里判空
     * （{@code (? IS NULL OR status = ?)}）而不是在 Java 里拼两条 SQL：
     * 两条 SQL 会产生两份执行计划，示例想说明的是"一条参数化的查询足够表达可选条件"。</p>
     */
    public List<OrderItem> findOrders(long customerId, String status) {
        boolean anyStatus = status == null || status.isBlank();
        String sql = "SELECT o.id, o.customer_id, c.name AS customer_name, o.sku, o.amount, o.status, o.created_at"
                + " FROM order_item o JOIN customer c ON c.id = o.customer_id"
                + " WHERE o.customer_id = ?"
                + (anyStatus ? "" : " AND o.status = ?")
                + " ORDER BY o.amount DESC, o.id";
        return anyStatus
                ? jdbc.query(sql, ORDER_MAPPER, customerId)
                : jdbc.query(sql, ORDER_MAPPER, customerId, status);
    }

    /** 金额最大的那一笔订单；该客户没有订单时返回空。 */
    public Optional<OrderItem> findTopOrder(long customerId) {
        List<OrderItem> rows = jdbc.query(
                "SELECT o.id, o.customer_id, c.name AS customer_name, o.sku, o.amount, o.status, o.created_at"
                        + " FROM order_item o JOIN customer c ON c.id = o.customer_id"
                        + " WHERE o.customer_id = ? ORDER BY o.amount DESC, o.id",
                ORDER_MAPPER, customerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 只取订单总额 —— 工作流的条件表达式需要一个纯数字，因此单独给一个入口。 */
    public BigDecimal totalAmount(long customerId) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM order_item WHERE customer_id = ?",
                BigDecimal.class, customerId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** 按状态分组的统计，外加一行 {@code ALL} 总计。 */
    public List<OrderStat> statistics(long customerId, String status) {
        boolean anyStatus = status == null || status.isBlank();
        String sql = "SELECT status, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total"
                + " FROM order_item WHERE customer_id = ?"
                + (anyStatus ? "" : " AND status = ?")
                + " GROUP BY status ORDER BY status";
        List<OrderStat> rows = anyStatus
                ? jdbc.query(sql, (rs, i) -> new OrderStat(rs.getString("status"),
                        rs.getInt("cnt"), rs.getBigDecimal("total")), customerId)
                : jdbc.query(sql, (rs, i) -> new OrderStat(rs.getString("status"),
                        rs.getInt("cnt"), rs.getBigDecimal("total")), customerId, status);
        List<OrderStat> result = new java.util.ArrayList<>(rows);
        BigDecimal sum = rows.stream()
                .map(OrderStat::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int count = rows.stream().mapToInt(OrderStat::count).sum();
        result.add(new OrderStat("ALL", count, sum));
        return result;
    }

    // ------------------------------------------------------------------
    // 写库（供事务演示使用）
    // ------------------------------------------------------------------

    /** 插入一笔订单，返回自增主键。 */
    public long insertOrder(long customerId, String sku, BigDecimal amount, String status) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO order_item (customer_id, sku, amount, status, created_at) VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, customerId);
            ps.setString(2, sku);
            ps.setBigDecimal(3, amount);
            ps.setString(4, status);
            ps.setTimestamp(5, new java.sql.Timestamp(System.currentTimeMillis()));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key != null) {
            return key.longValue();
        }
        // H2 / MySQL 都会回填主键；真取不到时退化为一次查询，避免"插入成功却返回 0"
        Long fallback = jdbc.queryForObject(
                "SELECT id FROM order_item WHERE sku = ?", Long.class, sku);
        return fallback == null ? 0L : fallback;
    }

    /** 改一笔订单的状态。 */
    public int updateStatus(long orderId, String status) {
        return jdbc.update("UPDATE order_item SET status = ? WHERE id = ?", status, orderId);
    }

    public Optional<OrderItem> findOrderById(long orderId) {
        List<OrderItem> rows = jdbc.query(
                "SELECT o.id, o.customer_id, c.name AS customer_name, o.sku, o.amount, o.status, o.created_at"
                        + " FROM order_item o JOIN customer c ON c.id = o.customer_id WHERE o.id = ?",
                ORDER_MAPPER, orderId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
