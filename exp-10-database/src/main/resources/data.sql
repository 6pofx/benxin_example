-- ============================================================================
-- exp-10 · 智能订单助手 —— 示例数据
-- ----------------------------------------------------------------------------
-- 幂等策略：先 DELETE 再 INSERT（两种库都认，且比 MERGE / REPLACE INTO 更直观）。
-- 之所以要幂等，是因为 application.yml 里写了 spring.sql.init.mode=always：
-- 每次启动、每次 @SpringBootTest 都会重跑这份脚本，不幂等的话第二次启动就会因为
-- 主键冲突而直接启动失败。
--
-- 删除顺序必须"从子到父"：approval_request / tool_audit / agent_run / agent_memory
-- 都是审计与记忆，order_item 引用 customer —— 先删引用方，再删被引用方。
--
-- 重置自增：MySQL 认 ALTER TABLE ... AUTO_INCREMENT = 1；H2 在 MODE=MySQL 下也认。
-- 不重置的话"第二次启动"的订单 id 会从 4 开始，断言里就得写 4 而不是 1，
-- 示例的可读性会被这点小事毁掉。
-- ============================================================================

DELETE FROM approval_request;
DELETE FROM tool_audit;
DELETE FROM agent_run;
DELETE FROM agent_memory;
DELETE FROM order_item;
DELETE FROM customer;

ALTER TABLE approval_request AUTO_INCREMENT = 1;
ALTER TABLE tool_audit AUTO_INCREMENT = 1;
ALTER TABLE agent_run AUTO_INCREMENT = 1;
ALTER TABLE order_item AUTO_INCREMENT = 1;
ALTER TABLE customer AUTO_INCREMENT = 1;

-- ---- 客户 ----------------------------------------------------------------
-- 张三刻意给 3 笔订单（含一笔 1500.00 的大额已支付订单），
-- 让"统计金额""金额最大的那笔""超过阈值走审批分支"三条演示都有真实数据可依。
INSERT INTO customer (id, name, level, phone, created_at) VALUES
    (1, '张三', 'VIP',   '13800000001', TIMESTAMP '2024-01-05 09:30:00'),
    (2, '李四', '普通',  '13800000002', TIMESTAMP '2024-01-06 10:00:00'),
    (3, '王五', 'VIP',   '13800000003', TIMESTAMP '2024-01-07 11:15:00');

-- ---- 订单 ----------------------------------------------------------------
-- 状态取值只有三个：PAID / PENDING / REFUNDED。
-- 张三合计 1380 + 1500 + 260 = 3140.00，演示里的"统计金额"就是查这个数。
INSERT INTO order_item (id, customer_id, sku, amount, status, created_at) VALUES
    (1, 1, 'SKU-1001', 1380.00, 'PAID',     TIMESTAMP '2024-02-01 10:00:00'),
    (2, 1, 'SKU-1002', 1500.00, 'PAID',     TIMESTAMP '2024-02-03 14:20:00'),
    (3, 1, 'SKU-1003',  260.00, 'PENDING',  TIMESTAMP '2024-02-05 16:45:00'),
    (4, 2, 'SKU-2001',  880.00, 'PAID',     TIMESTAMP '2024-02-06 09:05:00'),
    (5, 3, 'SKU-3001', 3200.00, 'REFUNDED', TIMESTAMP '2024-02-07 19:30:00');
