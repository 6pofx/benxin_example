package com.benxin.exp.database.audit;

import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用审计拦截器：每次工具调用落一行到 {@code tool_audit} 表。
 *
 * <p>与 {@link AgentRunAuditListener} 的分工，正是本心对这两类扩展点的定义：</p>
 * <ul>
 *   <li>监听器<b>只能看</b>（{@code AgentListener}）；</li>
 *   <li>拦截器<b>能改</b>（{@code AgentInterceptor}：{@code beforeTool} 返回非 null 即短路）。</li>
 * </ul>
 * <p>审计本身不需要改行为，那为什么用拦截器而不是监听器？因为"被短路的那次调用"
 * 只有拦截器看得见：短路发生在 {@code beforeTool} 里，工具根本没执行，
 * 但框架<b>仍然会</b>接着调用 {@code afterTool} 并把短路结果交给它。
 * 于是这一张表能同时回答两个问题 ——「工具真的执行了吗」和「谁把它拦下来了」。</p>
 *
 * <h2>实测出来的两个口径</h2>
 * <ol>
 *   <li><b>{@code afterTool} 一定会被调用。</b>看 {@code DefaultLoopContext#executeOne}：
 *       沙箱拒绝、审批拒绝、拦截器短路、正常执行 —— 四条路径最后都会汇到
 *       {@code for (interceptor : interceptors) interceptor.afterTool(call, result)}。
 *       因此"只在 afterTool 里插一行"是不会漏记的。</li>
 *   <li><b>但 {@code beforeTool} / {@code afterTool} 不一定在同一个线程上。</b>
 *       真正执行工具那一步跑在 {@code agent.toolExecutor()} 上（一个 cached thread pool，
 *       见 {@code DefaultAgent.SHARED_TOOL_EXECUTOR}），而 {@code beforeTool} / {@code afterTool}
 *       跑在调用线程上。因此本类<b>不能用 ThreadLocal 暂存"开始时间"</b> ——
 *       这正好也是"{@code @Transactional} 写在工具方法上不生效"的同一个根因，
 *       详见 {@code OrderWriteService} 的类注释。</li>
 * </ol>
 *
 * <p>落库分两步：{@code beforeTool} 先 INSERT 一行（拿到 {@code tool_use_id}），
 * {@code afterTool} 再按 {@code tool_use_id} UPDATE 结果与耗时。
 * 这样即使工具把整个进程搞崩了，表里也留着"它试图调什么"的记录。</p>
 */
public class ToolAuditInterceptor implements AgentInterceptor {

    private static final String INSERT_SQL =
            "INSERT INTO tool_audit (tool_use_id, tool_name, agent_name, session_id, step, arguments,"
                    + " error, short_circuit, duration_ms, result_digest, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, NULL, ?)";

    private static final String UPDATE_SQL =
            "UPDATE tool_audit SET error = ?, short_circuit = ?, duration_ms = ?, result_digest = ?"
                    + " WHERE tool_use_id = ?";

    /** 参数与结果摘要的截断长度：审计表存的是"看得出发生了什么"，不是完整报文。 */
    private static final int DIGEST_LIMIT = 500;

    private final JdbcTemplate jdbc;

    /** toolUseId → 行 id 与开始时间。用 ConcurrentHashMap 而不是 ThreadLocal，原因见类注释。 */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    /**
     * 被短路（工具没有真的执行）的调用集合。
     *
     * <p>{@code afterTool} 收到的 {@link ToolResult} 是<b>短路结果</b>，
     * 它和"工具真的执行并返回了同样的字符串"在数据上无法区分 ——
     * 因此靠这个集合把"没执行"这件事记下来。</p>
     */
    private final Map<String, Boolean> shortCircuited = new ConcurrentHashMap<>();

    public ToolAuditInterceptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        // 值越小越靠前，**审计必须是第一个**。
        //
        // 这一条是实测出来的，而且第一版写反了（写成 100，排在短路的那个拦截器之后），
        // 症状极具误导性：只要某个调用被 beforeTool 短路，审计表里就**一行都没有** ——
        // 因为"短路"的语义就是"跳出拦截器循环、不再问后面的拦截器"，
        // 排在后面的审计根本没机会执行；连 afterTool 的 UPDATE 都只会更新 0 行。
        // 于是"工具没执行但审计仍在"这句承诺会静默失效，而这恰恰是本表示例的核心结论。
        //
        // 放在最前面还顺带解决另一件事：沙箱拒绝与审批拒绝这两条路径
        // （见 DefaultLoopContext#executeOne）**根本不会进入拦截器循环**，
        // 所以它们本来就没有 beforeTool 可言 —— 那两条路径的留痕只能靠 afterTool 的 UPDATE，
        // 而 UPDATE 能命中的前提，是别的调用已经 INSERT 过同一 tool_use_id 的行。
        return 0;
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        String key = invocationKey(invocation);
        startedAt.put(key, System.currentTimeMillis());
        jdbc.update(INSERT_SQL,
                key,
                invocation.toolName(),
                invocation.agentName(),
                invocation.sessionId(),
                invocation.step(),
                abbreviate(Json.writeQuietly(invocation.arguments())),
                new Timestamp(System.currentTimeMillis()));
        // 返回 null = 放行；审计拦截器永远不改变行为
        return null;
    }

    @Override
    public void afterTool(ToolInvocation invocation, ToolResult result) {
        String key = invocationKey(invocation);
        Long start = startedAt.remove(key);
        boolean shortCircuit = Boolean.TRUE.equals(shortCircuited.remove(key));
        long duration = start == null ? 0L : System.currentTimeMillis() - start;
        jdbc.update(UPDATE_SQL,
                result != null && result.error() ? 1 : 0,
                shortCircuit ? 1 : 0,
                duration,
                result == null ? null : abbreviate(result.content()),
                key);
    }

    /**
     * 标记"下一次 afterTool 对应的调用是被短路的"。
     *
     * <p>由短路拦截器主动调用 —— 审计无法自己判断，原因见 {@link #shortCircuited} 的注释。
     * 让"做决定的那个拦截器"负责声明这件事，比在审计里猜要可靠得多。</p>
     */
    public void markShortCircuit(ToolInvocation invocation) {
        shortCircuited.put(invocationKey(invocation), Boolean.TRUE);
    }

    /** 行数统计，演示与测试都用它证明"审计真的写进去了"。 */
    public int auditCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tool_audit", Integer.class);
        return count == null ? 0 : count;
    }

    /** 按工具名统计被短路的次数。 */
    public int shortCircuitCount(String toolName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_audit WHERE short_circuit = 1 AND tool_name = ?",
                Integer.class, toolName);
        return count == null ? 0 : count;
    }

    /**
     * 命中行的键。
     *
     * <p>优先用 {@code tool_use_id}（框架为每次工具调用生成的稳定 id）；
     * 它为空时退化成"工具名 + 步数 + 当前时间戳"，保证几乎不会撞车。</p>
     */
    private static String invocationKey(ToolInvocation invocation) {
        String id = invocation.toolUseId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return invocation.toolName() + "#" + invocation.step() + "#" + System.nanoTime();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= DIGEST_LIMIT ? text : text.substring(0, DIGEST_LIMIT) + "...";
    }
}
