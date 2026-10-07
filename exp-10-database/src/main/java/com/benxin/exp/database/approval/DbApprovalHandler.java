package com.benxin.exp.database.approval;

import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.util.Json;
import com.benxin.exp.database.audit.ToolAuditInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 写库审批处理器：按「金额阈值 + 调用方 sessionId」决定放行还是拒绝。
 *
 * <p>这是「工作流驱动的写库审批」里"最后一道闸"的落地实现。它同时演示了三件事：</p>
 *
 * <ol>
 *   <li><b>决策口径写在配置里</b>（{@code exp.database.approval-threshold}），
 *       而不是散在 if 里 —— 演示与测试各自把阈值调成不同的值，就能跑到不同分支；</li>
 *   <li><b>拒绝不是终点，而是"转人工"</b>：被拒的请求落 {@code approval_request} 表，
 *       同时把一句<b>可读的</b>原因回给模型（见下），让模型能向用户解释，
 *       而不是收到一句干巴巴的 "工具执行失败"；</li>
 *   <li><b>sessionId 参与决策</b>：以 {@value #MANUAL_REVIEW_PREFIX} 开头的会话
 *       无条件转人工 —— 这模拟真实系统里的"高权限/高风险会话"标记。</li>
 * </ol>
 *
 * <h2>拒绝时的返回值为什么必须是 false，而不能抛异常</h2>
 * <p>框架里的调用点是
 * {@code DefaultLoopContext#executeOne}：</p>
 * <pre>
 * } else if (needsApproval(call, callback)
 *         &amp;&amp; !approvalHandler().approve(new ApprovalRequest(...))) {
 *     result = ToolResult.error("用户拒绝执行工具 [" + call.toolName() + "]");
 * } else { ... 沙箱 / 拦截器 / 真正执行 ... }
 * </pre>
 * <p>也就是说：<b>{@code approve()} 返回 false 是一条被框架正常处理的路径</b> ——
 * 它会变成一个 {@code ToolResult.error}，回灌给模型，模型因此有机会解释并给出替代方案，
 * 而 {@code afterTool} 仍会被调用（审计留痕）。反过来，如果这里抛异常，
 * 异常会穿过 {@code executeOne} → {@code DefaultLoopContext#callTool}
 * 一路冒到 {@code DefaultAgent#run} 的 catch 里：整次 Agent 调用以异常收场，
 * 审计只留下一行 FAILED，模型连"为什么被拒"都不知道。示例刻意走前者。</p>
 *
 * <p><b>一句必须说清的话</b>：框架给模型的原因是固定的
 * {@code "用户拒绝执行工具 [xxx]"}，它不携带本处理器写进 {@code approval_request} 的
 * 详细理由。想让模型看到可读原因，只能从处理器内部主动"补" ——
 * 本类通过 {@link #lastRejectionReason()} 把它暴露出来，工具实现再把它
 * 包进给模型的错误文本里（见 {@code OrderWriteTools}）。这是示例对插件的一处适配，
 * 不是框架能力。</p>
 */
public class DbApprovalHandler implements ApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(DbApprovalHandler.class);

    /** 以这个前缀开头的会话一律转人工，用来演示"调用方身份参与决策"。 */
    public static final String MANUAL_REVIEW_PREFIX = "manual-review";

    private static final String INSERT_SQL =
            "INSERT INTO approval_request (tool_name, agent_name, session_id, arguments, reason,"
                    + " decided_by, approved, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ToolAuditInterceptor audit;

    /** 金额阈值：{@code <=} 自动批准，{@code >} 转人工。演示与测试会改成不同的值。 */
    private final BigDecimal threshold;

    /** 决策留痕：演示要打印"批了几笔、拒了几笔"，测试也用它断言。 */
    private final List<String> decisions = new ArrayList<>();

    /** 最近一次拒绝理由；工具层会把它拼进回给模型的错误文本。 */
    private volatile String lastRejectionReason;

    public DbApprovalHandler(JdbcTemplate jdbc,
                             ToolAuditInterceptor audit,
                             @Value("${exp.database.approval-threshold:500}") BigDecimal threshold) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.threshold = threshold;
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        BigDecimal amount = amountOf(request.arguments());
        String sessionId = request.sessionId() == null ? "" : request.sessionId();
        String tool = request.toolName();

        // ① 会话标记优先：风控会话无条件转人工
        if (sessionId.startsWith(MANUAL_REVIEW_PREFIX)) {
            return reject(request, amount,
                    "会话 [" + sessionId + "] 被标记为人工复核，写操作一律不自动放行");
        }

        // ② 金额决策：这是最常见、也最好解释的一条口径
        if (amount == null) {
            return reject(request, null, "参数里没有可用的金额字段 tools=" + tool
                    + " args=" + Json.writeQuietly(request.arguments()));
        }
        if (amount.compareTo(threshold) > 0) {
            return reject(request, amount, "金额 " + amount.toPlainString()
                    + " 超过自动审批阈值 " + threshold.toPlainString());
        }

        // ③ 自动批准：留一条内存决策记录即可，不写数据库 ——
        //    "批准"是常态，全部落库只会让 approval_request 表变成日志表，
        //    而它是给人工处理用的待办队列，只应该出现"需要人管"的行。
        record("APPROVED " + tool + " amount=" + amount.toPlainString() + " session=" + sessionId);
        lastRejectionReason = null;
        return true;
    }

    private boolean reject(ApprovalRequest request, BigDecimal amount, String reason) {
        String sessionId = request.sessionId() == null ? "" : request.sessionId();
        jdbc.update(INSERT_SQL,
                request.toolName(),
                request.agentName(),
                sessionId,
                Json.writeQuietly(request.arguments()),
                reason,
                "DbApprovalHandler",
                0,
                "PENDING_MANUAL",
                new Timestamp(System.currentTimeMillis()));
        lastRejectionReason = reason;
        record("REJECTED " + request.toolName() + " amount="
                + (amount == null ? "?" : amount.toPlainString()) + " session=" + sessionId
                + " reason=" + reason);
        log.info("[exp-10] 写操作被拒并转人工：{} —— {}", request.summary(), reason);
        return false;
    }

    /** 从工具参数里取金额；参数来自模型，因此必须是"取不到就返回 null"的宽松口径。 */
    private static BigDecimal amountOf(Map<String, Object> arguments) {
        Object raw = arguments.get("amount");
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void record(String line) {
        synchronized (decisions) {
            decisions.add(line);
        }
    }

    // ------------------------------------------------------------------
    // 读取给演示与测试用
    // ------------------------------------------------------------------

    public BigDecimal threshold() {
        return threshold;
    }

    public List<String> decisions() {
        synchronized (decisions) {
            return List.copyOf(decisions);
        }
    }

    public void reset() {
        synchronized (decisions) {
            decisions.clear();
        }
        lastRejectionReason = null;
    }

    /**
     * 最近一次拒绝理由。
     *
     * <p>存在的唯一原因是：框架回灌给模型的错误文本是固定的
     * {@code "用户拒绝执行工具 [xxx]"}，不含本处理器写下的详细理由。
     * 想让模型"收到可读的拒绝原因"，只能由工具层在拿到批准结果后自己补一句。</p>
     */
    public String lastRejectionReason() {
        return lastRejectionReason;
    }

    /**
     * 清掉上一次的拒绝理由。
     *
     * <p>写工具在每次调用前会先调它：{@code lastRejectionReason} 是跨调用保留的状态，
     * 不清的话"这一次因为参数错误而失败"会被解释成"上一次审批拒绝了" ——
     * 一个只在偶发顺序下才出现的误导性提示，比没有提示更糟。</p>
     */
    public void clearRejectionReason() {
        lastRejectionReason = null;
    }

    /** 审计拦截器引用，供演示打印"审批与审计是两条独立的链"。 */
    public ToolAuditInterceptor audit() {
        return audit;
    }
}
