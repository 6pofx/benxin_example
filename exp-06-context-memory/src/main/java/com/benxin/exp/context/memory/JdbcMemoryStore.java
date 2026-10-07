package com.benxin.exp.context.memory;

import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 自定义 JDBC 记忆后端：把整份会话历史序列化成 JSON 存一张表。
 *
 * <p>它的存在只为了证明插件 README 里那句话 ——<b>"换掉一个 bean 就换了记忆后端"</b>：
 * 声明式 Agent 一行都不用改，{@code @Memory} 的多轮语义、{@code Agent#history()}/{@code clearHistory()}
 * 全部照旧，变的只是历史的落点（进程内存 → 数据库）。</p>
 *
 * <p><b>存储形态的取舍</b>（这也是使用者真正要做的决定）：</p>
 * <ul>
 *   <li>本实现是"<b>整份重写</b>"：{@code DefaultAgent} 每次运行结束都会调用
 *       {@code save(sessionId, 全部历史)}，所以这里 UPDATE 一行、没有就 INSERT。
 *       实现最短、语义最直白；代价是长会话每次都要重写整行。</li>
 *   <li>生产上更常见的是"追加式"（一行一条消息，按 seq 排序）或"键值库 + 序列化"，
 *       两者都不需要改插件 —— 只要实现同一个 {@link MemoryStore} 接口。</li>
 *   <li>表结构由本类自己负责创建（{@code CREATE TABLE IF NOT EXISTS}），
 *       于是"换记忆后端"连建表脚本都不用调用方操心。</li>
 * </ul>
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryStore.class);

    /** 表名固定，便于示例直接 SQL 观察；真实项目可配置化。 */
    public static final String TABLE = "exp06_memory";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS exp06_memory (
                session_id    VARCHAR(200) PRIMARY KEY,
                messages_json CLOB         NOT NULL,
                message_count INT          NOT NULL,
                updated_at    TIMESTAMP    NOT NULL
            )""";

    private final JdbcTemplate jdbc;

    public JdbcMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.execute(DDL);
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<String> rows = jdbc.queryForList(
                "SELECT messages_json FROM " + TABLE + " WHERE session_id = ?", String.class, sessionId);
        return rows.isEmpty() ? List.of() : MessageJson.read(rows.get(0));
    }

    @Override
    public void save(String sessionId, List<ChatMessage> history) {
        if (sessionId == null) {
            return;
        }
        List<ChatMessage> safe = history == null ? List.of() : history;
        String json = MessageJson.write(safe);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int updated = jdbc.update("UPDATE " + TABLE
                        + " SET messages_json = ?, message_count = ?, updated_at = ? WHERE session_id = ?",
                json, safe.size(), now, sessionId);
        if (updated == 0) {
            jdbc.update("INSERT INTO " + TABLE
                            + " (session_id, messages_json, message_count, updated_at) VALUES (?, ?, ?, ?)",
                    sessionId, json, safe.size(), now);
        }
        log.debug("[exp06] 会话 [{}] 的 {} 条历史已落库", sessionId, safe.size());
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId != null) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE session_id = ?", sessionId);
        }
    }

    /**
     * 覆盖默认方法：默认实现的 {@code exists()} 要先 {@code load} 出整份历史再判空，
     * 对 JDBC 后端来说等于"为了回答是不是有，先把几 MB 的 JSON 读出来"。
     * 这里改成一次 COUNT 查询 —— 这是"默认方法可以被实现方按后端特性改写"的示范。
     */
    @Override
    public boolean exists(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE session_id = ?", Integer.class, sessionId);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // 仅供示例/测试观察用的小工具
    // ------------------------------------------------------------------

    /** 直接读表，用来证明"历史真的进了数据库"而不是还留在内存里。 */
    public List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT session_id, message_count, LENGTH(messages_json) AS json_chars,"
                + " updated_at FROM " + TABLE + " ORDER BY session_id");
    }

    /** 清空整张表；测试的 {@code @BeforeEach} 与演示的开场都靠它保证确定性。 */
    public int clearAll() {
        return jdbc.update("DELETE FROM " + TABLE);
    }

    public long sessionCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        return count == null ? 0 : count;
    }

    public String describe() {
        return TABLE + "@jdbc（H2 文件库，整份历史序列化成 JSON）";
    }
}
