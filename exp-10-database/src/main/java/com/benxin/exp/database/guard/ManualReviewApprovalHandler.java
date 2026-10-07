package com.benxin.exp.database.guard;

import com.benxin.llm.core.sandbox.ApprovalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「永远转人工」的审批处理器：演示 {@code ApprovalPolicy.ALWAYS} 下的逐次审批。
 *
 * <p>它与 {@code DbApprovalHandler} 的差别只有决策规则：<b>无条件返回 false</b>。
 * 存在的意义是让"每一次工具调用都要人点头"这种策略有一个可运行、可断言的实现 ——
 * 在 {@code ALWAYS} 策略下，连只读的 {@code query_orders} 都得走这里。</p>
 *
 * <h2>为什么"永远转人工"必须是 false 而不是抛异常</h2>
 * <p>{@code approve()} 返回 false 是框架正常处理的路径：它会变成
 * {@code ToolResult.error("用户拒绝执行工具 [xxx]")} 回灌给模型，
 * {@code afterTool} 仍会被调用（审计留痕），模型也有机会解释。
 * 抛异常则会穿透整个 Loop，让这次运行以异常收场 —— 连"为什么被拦"都留不下来。
 * 详见 {@code DbApprovalHandler} 的类注释。</p>
 *
 * <p>被拒的请求同样落 {@code approval_request} 表：这张表的语义是
 * "等人处理的待办"，而"永远转人工"策略拒绝的每一条都是真待办。</p>
 */
public class ManualReviewApprovalHandler implements ApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(ManualReviewApprovalHandler.class);

    private static final String INSERT_SQL =
            "INSERT INTO approval_request (tool_name, agent_name, session_id, arguments, reason,"
                    + " decided_by, approved, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final AtomicInteger calls = new AtomicInteger();

    public ManualReviewApprovalHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        calls.incrementAndGet();
        String reason = "策略 ApprovalPolicy.ALWAYS：本次调用需人工逐次放行（示例里没有人可问，故一律转人工）";
        jdbc.update(INSERT_SQL,
                request.toolName(),
                request.agentName(),
                request.sessionId() == null ? "" : request.sessionId(),
                com.benxin.llm.core.util.Json.writeQuietly(request.arguments()),
                reason,
                getClass().getSimpleName(),
                0,
                "PENDING_MANUAL",
                new Timestamp(System.currentTimeMillis()));
        log.info("[exp-10] ALWAYS 策略下逐次审批 → 转人工：{}", request.summary());
        return false;
    }

    /** 被问了几次 —— 断言"ALWAYS 下每个工具都被问过"用。 */
    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }
}
