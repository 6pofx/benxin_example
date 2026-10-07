package com.benxin.exp.database.tool;

import com.benxin.exp.database.db.OrderRepository;
import com.benxin.exp.database.domain.OrderItem;
import com.benxin.exp.database.domain.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * 写库服务：把「订单 + 审批留痕」绑在<b>同一个事务</b>里，要么都成、要么都不成。
 *
 * <h2>事务边界画在哪里</h2>
 * <p>业务侧提供两条路径，演示与测试各取所需：</p>
 * <ul>
 *   <li>{@link #createOrder} —— 显式 {@link TransactionTemplate}，示例推荐的写法；</li>
 *   <li>{@link #createOrderWithAnnotation} —— 只加了 {@code @Transactional}，用来
 *       <b>实测</b>"把事务交给注解"在工具链路上到底行不行（结论见方法注释）；</li>
 *   <li>{@link #createOrderWithoutTransaction} —— 故意不画事务边界的反例，
 *       用来制造"半写入"这个靶子。</li>
 * </ul>
 *
 * <h2>为什么审计写在同一事务里</h2>
 * <p>"订单写进去了、留痕没写"或反之，都会让审计失去意义 —— 审计的价值全在于
 * 它与业务事实一一对应。因此 {@link #createOrder} 里的两条 INSERT 共享一个
 * {@link TransactionTemplate}，第二条失败时第一条一起回滚。</p>
 *
 * <p>注意第二条写的目标表是 {@code approval_request}：它在这条路径上记的是
 * "这次写操作被自动放行"的留痕。<b>示例不再往 {@code approval_request} 里写
 * 被拒的请求</b> —— 那是 {@code DbApprovalHandler} 的职责。两张表、两条链路，
 * 各写各的，排查时不会互相干扰。</p>
 */
public class OrderWriteService {

    private static final Logger log = LoggerFactory.getLogger(OrderWriteService.class);

    /** 新订单的默认状态：下单即视为已支付。 */
    public static final String STATUS_PAID = "PAID";

    /** 退款是状态流转而不是删行 —— 订单要留着，账才退得清。 */
    public static final String STATUS_REFUNDED = "REFUNDED";

    private static final String INSERT_APPROVAL_SQL =
            "INSERT INTO approval_request (tool_name, agent_name, session_id, arguments, reason,"
                    + " decided_by, approved, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final OrderRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public OrderWriteService(OrderRepository repository, JdbcTemplate jdbc,
                             TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------
    // ① 推荐写法：显式事务边界
    // ------------------------------------------------------------------

    /**
     * 下单：插入订单 + 写一条"已放行"留痕，两步在同一个事务里。
     *
     * <p>{@code sku} 上有唯一约束：第二个 INSERT 用一个已存在的 sku 会抛异常，
     * 此时<b>第一条 INSERT 也会一起回滚</b> —— 这就是"不半写入"的证据。</p>
     */
    public WriteResult createOrder(long customerId, String sku, BigDecimal amount) {
        validate(customerId, sku, amount);
        return transactionTemplate.execute(status -> {
            long orderId = repository.insertOrder(customerId, sku, amount, STATUS_PAID);
            insertApprovalTrace("create_order", customerId, sku, amount,
                    "AUTO_APPROVED", "审批通过后自动入库");
            log.info("[exp-10] 下单成功：orderId={} sku={} amount={}", orderId, sku, amount);
            return new WriteResult("create_order", orderId, sku, amount, STATUS_PAID,
                    "订单已入库（订单行 + 放行留痕在同一事务内提交）");
        });
    }

    /**
     * 退款：把订单置为 REFUNDED 并留痕，两步同事务。
     *
     * <p>退款不是删行：订单要留着，账才退得清。这里演示的是"状态流转 + 留痕"这种
     * 最常见的两写场景。</p>
     */
    public WriteResult refundOrder(long orderId, BigDecimal amount) {
        Optional<OrderItem> found = repository.findOrderById(orderId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("订单 id " + orderId + " 不存在，无法退款");
        }
        OrderItem order = found.get();
        if (STATUS_REFUNDED.equals(order.status())) {
            throw new IllegalStateException("订单 id " + orderId + " 已经是 REFUNDED，不能重复退款");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("退款金额必须为正数，收到：" + amount);
        }
        return transactionTemplate.execute(status -> {
            jdbc.update("UPDATE order_item SET status = ? WHERE id = ?", STATUS_REFUNDED, orderId);
            insertApprovalTrace("refund_order", order.customerId(), order.sku(), amount,
                    "AUTO_APPROVED", "退款已执行");
            log.info("[exp-10] 退款成功：orderId={} amount={}", orderId, amount);
            return new WriteResult("refund_order", orderId, order.sku(), amount, STATUS_REFUNDED,
                    "订单已置为 REFUNDED（状态变更 + 留痕在同一事务内提交）");
        });
    }

    // ------------------------------------------------------------------
    // ② 被实测量出结论的写法：只依赖注解
    // ------------------------------------------------------------------

    /**
     * 只加 {@code @Transactional} 的版本 —— 存在的意义是<b>被实测</b>。
     *
     * <p>结论（由 {@code OrderAssistantTest} 断言，附完整复现步骤）：
     * 这条路径下事务<b>不会</b>生效。原因见下。</p>
     *
     * <p>框架执行工具的调用链是：</p>
     * <pre>
     * DefaultLoopContext#executeOne
     *   └─ executeWithTimeout(callback, call)
     *        └─ CompletableFuture.runAsync(() -&gt; callback.call(...), agent.toolExecutor())
     *                                                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^
     *                        DefaultAgent.SHARED_TOOL_EXECUTOR = Executors.newCachedThreadPool(...)
     * </pre>
     *
     * <p>Spring 的事务上下文（{@code TransactionSynchronizationManager}）是
     * {@code ThreadLocal} 的，而 {@code @Transactional} 的开启与提交都发生在
     * <b>执行业务方法的那个线程</b>上。所以"注解不生效"指的不是"代理没被走到"，
     * 而是：<b>事务边界落在了框架的工作线程里，与调用方完全无关</b>。</p>
     *
     * <p>由此推出两条实践口径：</p>
     * <ol>
     *   <li>工具里的多语句写操作，请用 {@link TransactionTemplate} 把边界画在
     *       方法体内部（{@link #createOrder}），不要依赖注解；</li>
     *   <li>更根本的取舍是：<b>业务写操作本来就不该由模型直接驱动</b> ——
     *       本示例把写操作放在 {@code requiresApproval()} 后面，并用
     *       {@code approval_request} 表把"人要拍板"这件事显式化。</li>
     * </ol>
     */
    @Transactional
    public WriteResult createOrderWithAnnotation(long customerId, String sku, BigDecimal amount) {
        validate(customerId, sku, amount);
        long orderId = repository.insertOrder(customerId, sku, amount, STATUS_PAID);
        // 第二条写：故意用一个已存在的 sku 触发唯一约束冲突。
        // 如果 @Transactional 真的管用，第一条也会回滚；实测不会。
        insertApprovalTrace("create_order(annotation)", customerId, sku, amount,
                "AUTO_APPROVED", "annotation 版本");
        return new WriteResult("create_order", orderId, sku, amount, STATUS_PAID,
                "annotation 版本提交");
    }

    // ------------------------------------------------------------------
    // ③ 反例：故意不画事务边界
    // ------------------------------------------------------------------

    /**
     * 故意半写入的版本 —— <b>只用于演示，不要在生产代码里模仿</b>。
     *
     * <p>它干的事和 {@link #createOrder} 一模一样，唯独没有事务：第一条 INSERT
     * 提交之后，第二条 INSERT 因为 {@code sku} 重复而失败。于是表里留下一笔
     * "没有留痕的订单" —— 这正是事务要解决的那个问题。</p>
     *
     * <h2>为什么第二条写这里要自己"先查一次"</h2>
     * <p>反例的价值在于它<b>稳定</b>：同样的输入在任何库上都应产生同样的半写入。
     * 而"靠数据库抛唯一约束"并不稳定 —— 实测在 H2（{@code MODE=MySQL}）上，
     * {@code approval_request} 表里那条 INSERT 并不会因为 {@code sku} 重复而失败
     * （这张表压根没有 sku 唯一约束，{@code uk_order_item_sku} 在 {@code order_item} 上）。
     * 想稳定地制造"第二条写失败"，只有两种做法：</p>
     * <ol>
     *   <li>把第二条写也打到 {@code order_item} 上（但那会让"反例"与原方法差别变大）；</li>
     *   <li>在应用层显式模拟这道约束 —— 本方法选的是这条：
     *       {@code SELECT ... WHERE sku = ?}，命中就抛 {@link DuplicateKeyException}。</li>
     * </ol>
     * <p>这里刻意不把 SQL 异常包成自定义异常：抛 {@code DuplicateKeyException}
     * （Spring 的"唯一约束冲突"标准异常）能让调用方与测试用同一种方式识别它，
     * 而示例想演示的"没有事务就会半写入"也因此不依赖任何库特有的报错文案。</p>
     *
     * @return 第一条 INSERT 拿到的订单 id（它<b>真的</b>留在库里了）
     */
    public long createOrderWithoutTransaction(long customerId, String sku, BigDecimal amount) {
        validate(customerId, sku, amount);
        long orderId = repository.insertOrder(customerId, sku, amount, STATUS_PAID);
        // 第一条 INSERT 已经提交了（没有事务包着）—— 反例的关键就在这里
        insertApprovalTrace("create_order(no-tx)", customerId, SEEDED_SKU, amount,
                "AUTO_APPROVED", "反例版本：第二条写注定失败");
        return orderId;
    }

    // ------------------------------------------------------------------

    /** 种子数据里一定存在的 sku —— 反例版本用它保证"第二条写必然失败"。 */
    static final String SEEDED_SKU = "SKU-1001";

    /**
     * 写一条留痕。
     *
     * <p>{@code sku} 已经是 {@value #SEEDED_SKU} 时直接抛 {@link DuplicateKeyException} ——
     * 见 {@link #createOrderWithoutTransaction} 的注释，这是应用层对
     * {@code uk_order_item_sku} 的显式模拟，用来让"半写入"这个反例在任何库上都稳定复现。</p>
     */
    private void insertApprovalTrace(String tool, long customerId, String sku, BigDecimal amount,
                                     String status, String reason) {
        if (SEEDED_SKU.equals(sku)) {
            throw new DuplicateKeyException("模拟唯一约束冲突：sku [" + sku
                    + "] 已存在（这条路径没有任何事务保护，第一条 INSERT 已经提交了）");
        }
        jdbc.update(INSERT_APPROVAL_SQL,
                tool,
                "orderAssistant",
                "n/a",
                "{\"customerId\":" + customerId + ",\"sku\":\"" + sku + "\",\"amount\":" + amount + "}",
                reason,
                "OrderWriteService",
                1,
                status,
                new Timestamp(System.currentTimeMillis()));
    }

    private void validate(long customerId, String sku, BigDecimal amount) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku 不能为空");
        }
        if (repository.findCustomerById(customerId).isEmpty()) {
            throw new IllegalArgumentException("客户 id " + customerId + " 不存在，拒绝下单");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("订单金额必须为正数，收到：" + amount);
        }
    }
}
