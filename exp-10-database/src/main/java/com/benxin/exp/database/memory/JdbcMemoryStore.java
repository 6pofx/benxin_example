package com.benxin.exp.database.memory;

import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 版 {@link MemoryStore}：把会话记忆落到 {@code agent_memory} 表。
 *
 * <p>它演示的是本心一句很简单的话：<b>"记忆是可插拔的"</b>。
 * 默认实现是 {@code InMemoryMemoryStore}（进程一停就没了），
 * 而只要容器里存在另一个 {@code MemoryStore} bean，starter 的
 * {@code @ConditionalOnMissingBean} 就会让默认实现让位 —— 一行配置都不用改。</p>
 *
 * <h2>落库口径</h2>
 * <ul>
 *   <li><b>save 是全量覆盖</b>：一次 save 对应一次 Agent 运行的完整历史。
 *       先 {@code DELETE} 再批量 {@code INSERT}，语义是"这个会话的历史就是这 N 条"。
 *       这样做而不是增量 upsert，是因为 {@code Agent.call} 传进来的就是完整历史，
 *       增量写反而要额外判断"哪条是新的"，得不偿失。</li>
 *   <li><b>顺序靠 {@code seq} 列</b>：不依赖主键 id 的插入顺序 ——
 *       批量插入在两种库上的自增分配顺序都不保证与列表顺序一致。</li>
 *   <li><b>{@code @Transactional}</b>：DELETE + INSERT 必须在一个事务里。
 *       否则并发或异常时会出现"历史只剩一半"的状态，而记忆被截断的症状
 *       （模型突然忘了前文）偏偏最难归因到一次写库失败上。</li>
 * </ul>
 *
 * <h2>为什么 load 时对坏数据"跳过并告警"而不是抛异常</h2>
 * <p>记忆是<b>旁路</b>数据：它的作用是让对话更连贯，不是业务事实的来源。
 * 一行 payload 因为插件升级而解析不了时，正确的取舍是"丢掉这一条、继续对话"，
 * 而不是让整个 Agent 调用失败 —— 后者会把一次可用的问答变成一次 500。</p>
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryStore.class);

    private static final String SELECT_SQL =
            "SELECT seq, role, payload FROM agent_memory WHERE session_id = ? ORDER BY seq";
    private static final String DELETE_SQL = "DELETE FROM agent_memory WHERE session_id = ?";
    private static final String INSERT_SQL =
            "INSERT INTO agent_memory (session_id, seq, role, payload, created_at) VALUES (?, ?, ?, ?, ?)";
    private static final String EXISTS_SQL =
            "SELECT COUNT(*) FROM agent_memory WHERE session_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ChatMessage> history = new ArrayList<>();
        jdbc.query(SELECT_SQL, rs -> {
            String payload = rs.getString("payload");
            try {
                history.add(ChatMessageCodec.decode(payload));
            } catch (RuntimeException e) {
                log.warn("[exp-10] 会话 [{}] 的第 {} 条记忆无法反序列化，已跳过：{}",
                        sessionId, rs.getInt("seq"), e.getMessage());
            }
        }, sessionId);
        return history;
    }

    @Override
    @Transactional
    public void save(String sessionId, List<ChatMessage> history) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<ChatMessage> messages = history == null ? List.of() : history;
        jdbc.update(DELETE_SQL, sessionId);
        if (messages.isEmpty()) {
            return;
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Object[]> batch = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            batch.add(new Object[]{sessionId, i, message.role().name(),
                    ChatMessageCodec.encode(message), now});
        }
        jdbc.batchUpdate(INSERT_SQL, batch);
    }

    @Override
    @Transactional
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        int removed = jdbc.update(DELETE_SQL, sessionId);
        log.debug("[exp-10] 清空会话 [{}] 的记忆，删除 {} 行", sessionId, removed);
    }

    /**
     * 覆盖接口的默认实现（默认是"load 出来看空不空"）。
     *
     * <p>改成一条 {@code COUNT(*)} 是有意的：默认实现会把整个会话的消息都反序列化一遍，
     * 只为回答一个是非题。而 {@code exists} 恰恰是"每次调用都要问一次"的热路径。</p>
     */
    @Override
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(EXISTS_SQL, Integer.class, sessionId);
        return count != null && count > 0;
    }
}
