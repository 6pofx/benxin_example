package com.benxin.exp.database.demo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 数据重置：演示与测试共用的"把库恢复到初始状态"动作。
 *
 * <p>为什么演示也需要它？因为示例演示的是"数据真的落库了"，而落到最后
 * 每张表里都会堆着前几节的痕迹。如果不在每一节之前清一次，打印出来的行数
 * 就是"累积值" —— 读者无法从数字上判断这一节到底写了几行，
 * 整个演示最有说服力的那部分（行数）就废了。</p>
 *
 * <p>测试用的是同一份逻辑：{@code @BeforeEach} 调它一次，用例之间就不会互相污染。
 * 演示与测试共用同一个类，是为了避免"演示里清得干净、测试里漏了一张表"这类
 * 只有 CI 才会发现的偏差。</p>
 */
public final class DatabaseReset {

    /**
     * 先删数据、再按 {@code data.sql} 重灌业务数据。
     *
     * <p>只 DELETE 不 DROP：不依赖 DDL 权限，也不会把 UNIQUE 约束一起丢掉 ——
     * 而 {@code uk_order_item_sku} 正是"事务一致性"演示的靶子，丢了它演示就失效了。</p>
     *
     * <p>删除顺序必须"从子到父"：{@code order_item} 引用 {@code customer}，
     * 两个审计表与记忆表虽然无外键，但排在后面读起来更顺。</p>
     *
     * <h2>为什么 {@code AUTO_INCREMENT = 1} 必须夹在"删除"与"重灌"之间</h2>
     * <p>这三步的顺序是实测出来的，不是随手排的。一开始写成"删除 → 重灌 → 重置自增"，
     * 结果在 H2 上直接炸：<b>下一次不带 id 的 INSERT 会拿到 id = 1，撞主键</b>。
     * 原因是 H2（{@code MODE=MySQL}）的 {@code ALTER TABLE ... AUTO_INCREMENT = 1}
     * 是一条<b>无条件把计数器打回 1</b>的语句，它不看表里已经有什么；
     * 而重灌用的是显式 id（1..5），H2 也不会因为"显式插入了 5"就把计数器推到 6。
     * 两步一叠加，计数器停在 1，而 id = 1 已经被种子数据占了。</p>
     *
     * <p>真实 MySQL 上同样的顺序却不会报错 —— 因为 MySQL 的
     * {@code ALTER TABLE ... AUTO_INCREMENT = 1} 只是"把计数器设为 max(id)+1 与 1 中的较大者"，
     * 表里已经有 id = 5 时它会自动顶到 6。也就是说：<b>"H2 能跑"与"MySQL 能跑"在这里是两回事，
     * 只有把重置放在重灌之前，两种库上的语义才一致。</b></p>
     */
    public static void all(JdbcTemplate jdbcTemplate) {
        String[] deletes = {
                "DELETE FROM approval_request",
                "DELETE FROM tool_audit",
                "DELETE FROM agent_run",
                "DELETE FROM agent_memory",
                "DELETE FROM order_item",
                "DELETE FROM customer"
        };
        for (String sql : deletes) {
            jdbcTemplate.update(sql);
        }

        // 两种库都认自增重置语法（H2 在 MODE=MySQL 下同样支持）。
        // 必须在重灌之前执行 —— 理由见方法注释；不重置的话第二次启动时
        // order_item 的自增会从 6 开始，"改一行数据、答案随之变化"这类断言就得跟着改。
        // 注意 customer / order_item 被显式指定了 id，重置后重新插回同样的 id 是安全的。
        List.of("approval_request", "tool_audit", "agent_run", "order_item", "customer")
                .forEach(table -> jdbcTemplate.execute("ALTER TABLE " + table + " AUTO_INCREMENT = 1"));

        // 把种子数据重新灌一遍。与 data.sql 内容保持一致这件事由测试
        // OrderAssistantTest#schemaAndSeedDataAreComplete 的断言来兜底：
        // 它按 data.sql 里的期望值逐条核对，改了这里忘了改那边会立刻红。
        jdbcTemplate.update("INSERT INTO customer (id, name, level, phone, created_at)"
                + " VALUES (1, '张三', 'VIP', '13800000001', TIMESTAMP '2024-01-05 09:30:00')");
        jdbcTemplate.update("INSERT INTO customer (id, name, level, phone, created_at)"
                + " VALUES (2, '李四', '普通', '13800000002', TIMESTAMP '2024-01-06 10:00:00')");
        jdbcTemplate.update("INSERT INTO customer (id, name, level, phone, created_at)"
                + " VALUES (3, '王五', 'VIP', '13800000003', TIMESTAMP '2024-01-07 11:15:00')");
        jdbcTemplate.update("INSERT INTO order_item (id, customer_id, sku, amount, status, created_at)"
                + " VALUES (1, 1, 'SKU-1001', 1380.00, 'PAID', TIMESTAMP '2024-02-01 10:00:00')");
        jdbcTemplate.update("INSERT INTO order_item (id, customer_id, sku, amount, status, created_at)"
                + " VALUES (2, 1, 'SKU-1002', 1500.00, 'PAID', TIMESTAMP '2024-02-03 14:20:00')");
        jdbcTemplate.update("INSERT INTO order_item (id, customer_id, sku, amount, status, created_at)"
                + " VALUES (3, 1, 'SKU-1003', 260.00, 'PENDING', TIMESTAMP '2024-02-05 16:45:00')");
        jdbcTemplate.update("INSERT INTO order_item (id, customer_id, sku, amount, status, created_at)"
                + " VALUES (4, 2, 'SKU-2001', 880.00, 'PAID', TIMESTAMP '2024-02-06 09:05:00')");
        jdbcTemplate.update("INSERT INTO order_item (id, customer_id, sku, amount, status, created_at)"
                + " VALUES (5, 3, 'SKU-3001', 3200.00, 'REFUNDED', TIMESTAMP '2024-02-07 19:30:00')");
    }

    /** 只清审计与记忆，保留业务数据 —— 演示中"不清业务表"的几节会用它。 */
    public static void auditOnly(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM approval_request");
        jdbcTemplate.update("DELETE FROM tool_audit");
        jdbcTemplate.update("DELETE FROM agent_run");
        jdbcTemplate.update("DELETE FROM agent_memory");
    }

    private DatabaseReset() {
    }
}
