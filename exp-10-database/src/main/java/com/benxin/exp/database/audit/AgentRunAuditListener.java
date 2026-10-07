package com.benxin.exp.database.audit;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行审计监听器：把每一次 Agent 运行落一行到 {@code agent_run} 表。
 *
 * <p>这是「可观测性」这一章的落库版本。本心的 {@link AgentListener} 是<b>只读</b>观察者
 * （要改行为请用 {@code AgentInterceptor}），因此这里只做一件事：记账。</p>
 *
 * <h2>框架会调哪些回调，不会调哪些</h2>
 * <p>看 {@code DefaultAgent#run} 就能数清楚：</p>
 * <pre>
 * listener.onAgentStart(invocation);
 * try {
 *     ... 跑 Loop ...
 *     listener.onAgentEnd(result);      // ← 只有成功路径才走这里
 * } catch (RuntimeException | Error e) {
 *     listener.onError(e);              // ← 失败路径只有这一个回调
 *     throw e;
 * }
 * </pre>
 *
 * <p><b>关键结论：失败时框架不会给出任何"结果对象"。</b>
 * {@code onError} 只拿到一个 {@link Throwable}，没有 {@link AgentResult}，
 * 因此也没有 steps / usage / finishReason；而且 {@code onError} 的签名里
 * <b>连 agentName 与 sessionId 都没有</b>（对比 {@code onAgentStart} 拿到的是完整的
 * {@link AgentInvocation}）。于是本监听器：</p>
 * <ol>
 *   <li>在 {@code onAgentStart} 里抓住 invocation，留一份"运行草稿"；</li>
 *   <li>在 {@code onError} 里用草稿补写一行 {@code status='FAILED'}。
 *       <b>这是使用者侧的补偿动作，不是框架行为</b> —— 不补的话，一次失败的运行
 *       在审计表里完全没有痕迹，而"哪些运行失败了"恰恰是审计最该回答的问题。</li>
 * </ol>
 *
 * <h2>并发下的键怎么选</h2>
 * <p>监听器是<b>单例</b>（一个 bean 被所有 Agent 共享），而 {@code onStepEnd} /
 * {@code onStepStart} 只给 {@code agentName}，不给会话。为了不串台，草稿表的键取
 * {@code agentName + "|" + sessionId}（{@code onAgentStart} / {@code onToolCall} 都拿得到
 * 这两个值），需要按 agent 名回查时再扫一遍。示例是单线程演示场景，
 * 真并发下同一对 (agent, session) 会互相覆盖草稿，这一点在日志里留了 DEBUG。</p>
 *
 * <h2>steps 为什么由监听器自己数</h2>
 * <p>刻意不用 {@code onStepStart(step)} 里的那个 step 值：它由 Loop 维护，
 * 语义是"模型往返第几步"，和工作流节点数不是一回事。审计表里的 {@code steps}
 * 口径统一成"框架广播了几次 {@code onStepEnd}"，两种 Loop 下都可比。</p>
 */
public class AgentRunAuditListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(AgentRunAuditListener.class);

    /** 输出摘要截断长度：审计表要的是"能看出这次问了什么、答了什么"，不是全文备份。 */
    private static final int SUMMARY_LIMIT = 200;

    private static final String INSERT_SQL =
            "INSERT INTO agent_run (agent_name, session_id, loop_name, model_name, steps,"
                    + " input_tokens, output_tokens, duration_ms, finish_reason, max_steps_reached,"
                    + " status, error_message, output_summary, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final RecordingModel model;

    /** 正在运行中的草稿：键 = {@code agentName|sessionId}。 */
    private final Map<String, Draft> running = new ConcurrentHashMap<>();

    public AgentRunAuditListener(JdbcTemplate jdbc, RecordingModel model) {
        this.jdbc = jdbc;
        this.model = model;
    }

    /** 一次运行的中间状态；只被监听器自己用。 */
    private static final class Draft {
        private final String agentName;
        private final String sessionId;
        private final String loopName;
        private final String modelName;
        private final String inputSummary;
        private final long startedAt = System.currentTimeMillis();
        private int steps;
        private int toolCalls;
        private String lastText = "";

        private Draft(AgentInvocation invocation) {
            this.agentName = invocation.agentName();
            this.sessionId = invocation.sessionId() == null ? "" : invocation.sessionId();
            this.loopName = invocation.spec() == null ? null : invocation.spec().loop();
            this.modelName = invocation.spec() == null ? null : invocation.spec().model();
            this.inputSummary = abbreviate(invocation.input(), SUMMARY_LIMIT);
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onAgentStart(AgentInvocation invocation) {
        Draft previous = running.put(key(invocation.agentName(), invocation.sessionId()), new Draft(invocation));
        if (previous != null) {
            log.debug("[exp-10] 同一 (agent, session) 上出现并发运行，审计草稿被覆盖：{}/{}",
                    invocation.agentName(), invocation.sessionId());
        }
    }

    @Override
    public void onStepEnd(String agentName, int step, ChatResponse response) {
        Draft draft = findByAgent(agentName);
        if (draft == null) {
            return;
        }
        draft.steps++;
        if (response != null && response.message() != null) {
            String text = response.message().text();
            if (text != null && !text.isBlank()) {
                draft.lastText = text;
            }
        }
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        Draft draft = running.get(key(invocation.agentName(), invocation.sessionId()));
        if (draft != null) {
            draft.toolCalls++;
        }
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        // 工具结果本身由 tool_audit 表负责留痕（见 ToolAuditInterceptor），
        // 这里不动它 —— 两张表的职责不重叠，是排查问题时最省事的形态。
    }

    /** 成功路径：框架在 Loop 正常结束后调用它，这里落一行 {@code status='OK'}。 */
    @Override
    public void onAgentEnd(AgentResult result) {
        Draft draft = running.remove(key(result.agentName(), result.sessionId()));
        if (draft == null) {
            // onAgentStart 没被调用过（例如自己 new 了一个 Agent 却没挂这个监听器）
            log.debug("[exp-10] onAgentEnd 找不到对应的 onAgentStart，跳过审计：{}", result.agentName());
            return;
        }
        write(result.agentName(), result.sessionId(), result.loopName(), result.modelName(),
                result.steps(), result.usage().inputTokens(), result.usage().outputTokens(),
                result.durationMillis(), String.valueOf(result.finishReason()),
                result.maxStepsReached(), "OK", null, summaryOf(result.text(), draft));
    }

    /**
     * 失败路径 —— 框架只调用这一个回调，<b>不会</b>调用 {@code onAgentEnd}。
     *
     * <p>补写一行 FAILED 记录。可用信息只有：抛出来的异常 + 启动时抓到的草稿。
     * steps / usage / finishReason 一律写成"不可得"的样子并写进 {@code error_message} ——
     * 宁可显式留空，也不要编一个看起来正常的 0，让人误以为"这次真的跑了 0 步"。</p>
     */
    @Override
    public void onError(Throwable error) {
        String message = error == null
                ? "未知错误"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        Draft draft = latestDraft();
        if (draft == null) {
            write("unknown-agent", "unknown-session", null, model.name(), 0, 0, 0, 0,
                    null, false, "FAILED", message + "（onError 时找不到任何运行草稿）", null);
            return;
        }
        running.remove(key(draft.agentName, draft.sessionId));
        write(draft.agentName, draft.sessionId, draft.loopName, draft.modelName,
                draft.steps, 0, 0, System.currentTimeMillis() - draft.startedAt,
                null, false, "FAILED",
                message + "（失败路径由监听器补偿写入：onError 拿不到 AgentResult，"
                        + "故 steps/usage/finishReason 不可得）",
                summaryOf(draft.lastText, draft));
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private void write(String agentName, String sessionId, String loopName, String modelName,
                       int steps, int inputTokens, int outputTokens, long durationMillis,
                       String finishReason, boolean maxStepsReached, String status,
                       String errorMessage, String outputSummary) {
        jdbc.update(INSERT_SQL,
                agentName,
                sessionId == null ? "" : sessionId,
                loopName,
                modelName,
                steps,
                inputTokens,
                outputTokens,
                durationMillis,
                finishReason,
                maxStepsReached ? 1 : 0,
                status,
                errorMessage,
                outputSummary,
                new Timestamp(System.currentTimeMillis()));
    }

    private Draft findByAgent(String agentName) {
        return running.values().stream()
                .filter(d -> d.agentName.equals(agentName))
                .findFirst()
                .orElse(null);
    }

    private Draft latestDraft() {
        long newest = Long.MIN_VALUE;
        Draft newestDraft = null;
        for (Draft draft : running.values()) {
            if (draft.startedAt >= newest) {
                newest = draft.startedAt;
                newestDraft = draft;
            }
        }
        return newestDraft;
    }

    /** 草稿里没有任何文本时退化为"输入摘要"，让审计行至少能看出这次问了什么。 */
    private static String summaryOf(String text, Draft draft) {
        String chosen = text == null || text.isBlank() ? draft.inputSummary : text;
        String flat = flat(chosen);
        return flat.length() <= SUMMARY_LIMIT ? flat : flat.substring(0, SUMMARY_LIMIT) + "...";
    }

    private static String abbreviate(String text, int limit) {
        String flat = flat(text);
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "...";
    }

    private static String flat(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String key(String agentName, String sessionId) {
        return agentName + "|" + (sessionId == null ? "" : sessionId);
    }

    /** 便于测试断言"这次运行有没有被正确配对掉"（不为 0 就说明有草稿泄漏）。 */
    public int runningCount() {
        return running.size();
    }
}
