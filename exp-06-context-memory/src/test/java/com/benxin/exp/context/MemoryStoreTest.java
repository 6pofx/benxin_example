package com.benxin.exp.context;

import com.benxin.exp.common.db.SimpleDataSource;
import com.benxin.exp.context.demo.DemoText;
import com.benxin.exp.context.memory.JdbcMemoryStore;
import com.benxin.exp.context.memory.MessageJson;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryStore 三种来源 + 会话记忆语义的断言。
 *
 * <p>覆盖：默认 {@code InMemoryMemoryStore}（含 LRU 容量构造）、自定义 JDBC 实现、
 * {@code MemoryStore.inMemory()} / {@code disabled()} 两个工厂的差别，
 * 以及 {@code @Memory} 的多轮续接、会话隔离、清空与"关掉记忆"的两条路径。</p>
 */
@DisplayName("示例 06 · 记忆后端与会话语义")
class MemoryStoreTest extends AbstractContextMemoryTest {

    @Autowired
    private JdbcMemoryStore store;

    @Value("${exp.store.url}")
    private String storeUrl;

    @Value("${exp.store.username}")
    private String storeUsername;

    @Value("${exp.store.password}")
    private String storePassword;

    // ------------------------------------------------------------------
    // ① 默认的内存实现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("InMemoryMemoryStore(容量) 是 LRU：写满之后淘汰最久未访问的会话")
    void inMemoryStoreEvictsLeastRecentlyAccessedSession() {
        InMemoryMemoryStore small = new InMemoryMemoryStore(2);
        Agent lru = agent("memoryAssistant").toBuilder().memoryStore(small).build();

        lru.call("你好", "lru-a");
        lru.call("你好", "lru-b");
        // 访问一次 a，让 b 变成"最久未访问"
        assertThat(small.load("lru-a")).hasSize(2);
        lru.call("你好", "lru-c");

        assertThat(small.exists("lru-b")).isFalse();
        assertThat(small.exists("lru-a")).isTrue();
        assertThat(small.exists("lru-c")).isTrue();
    }

    @Test
    @DisplayName("MemoryStore.inMemory() 是 new InMemoryMemoryStore() 的工厂别名，读写语义一致")
    void inMemoryFactoryMatchesDefaultImplementation() {
        MemoryStore store = MemoryStore.inMemory();
        List<ChatMessage> history = List.of(ChatMessage.user("你好"), ChatMessage.assistant("你也好"));

        assertThat(store.exists("s")).isFalse();
        store.save("s", history);

        assertThat(store.load("s")).isEqualTo(history);
        assertThat(store.exists("s")).isTrue();

        // 已经取出的那份是快照：后续 save 不会回头改它
        List<ChatMessage> snapshot = store.load("s");
        store.save("s", List.of(ChatMessage.user("新的一轮")));
        assertThat(snapshot).hasSize(2);
        assertThat(store.load("s")).hasSize(1);

        store.clear("s");
        assertThat(store.exists("s")).isFalse();
    }

    // ------------------------------------------------------------------
    // ② 自定义 JDBC 实现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("容器里的 MemoryStore 就是自定义 JDBC 实现，Agent 用的也是它")
    void containerMemoryStoreIsTheJdbcImplementation() {
        assertThat(store).isSameAs(memoryStore);
        assertThat(agent("memoryAssistant")).isInstanceOf(com.benxin.llm.core.agent.DefaultAgent.class);
        com.benxin.llm.core.agent.DefaultAgent defaultAgent =
                (com.benxin.llm.core.agent.DefaultAgent) agent("memoryAssistant");

        assertThat(defaultAgent.memoryStore()).isSameAs(store);
        assertThat(store.describe()).contains(JdbcMemoryStore.TABLE);
    }

    @Test
    @DisplayName("JDBC 记忆：整份历史（含各种内容块）序列化成 JSON 落库，换个 DataSource 还能读回来")
    void jdbcStoreRoundTripsWholeHistory() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("系统提示词"),
                ChatMessage.user("你好"),
                ChatMessage.assistant("", List.of(new ToolUsePart("call_1", "read_document", "{}"))),
                ChatMessage.toolResult("call_1", "read_document", "文档内容", false),
                ChatMessage.assistant(List.of(new ThinkingPart("思考中"), new TextPart("结论"))),
                ChatMessage.user(new TextPart("看图"), ImagePart.ofBase64("image/png", "AAAA")),
                ChatMessage.of(com.benxin.llm.core.message.Role.TOOL, "纯文本工具消息"));

        store.save("round-trip", history);
        assertThat(store.exists("round-trip")).isTrue();

        // ① 同一个 store 读回来必须逐字段相等（含 name / toolCallId / 内容块顺序）
        assertThat(store.load("round-trip")).isEqualTo(history);

        // ② 全新 DataSource + 全新 MemoryStore 实例读同一行：证明数据真的在数据库里
        JdbcMemoryStore reopened = new JdbcMemoryStore(new JdbcTemplate(
                new SimpleDataSource(storeUrl, storeUsername, storePassword)));
        assertThat(reopened.load("round-trip")).isEqualTo(history);

        // ③ 表里确实有一行，且记录的是"消息条数"而不是空串
        assertThat(store.rows()).hasSize(1);
        assertThat(store.rows().get(0)).containsEntry("SESSION_ID", "round-trip")
                .containsEntry("MESSAGE_COUNT", 7);

        store.clear("round-trip");
        assertThat(store.exists("round-trip")).isFalse();
    }

    @Test
    @DisplayName("MessageJson 是自定义 MemoryStore 的编解码：JSON 里带类型标签，能逐块还原")
    void messageJsonKeepsPartTypes() {
        List<ChatMessage> history = List.of(
                ChatMessage.user(new TextPart("文本"), ImagePart.ofUrl("https://example.com/a.png")),
                ChatMessage.toolResult("call_9", "read_document", "结果", true));

        String json = MessageJson.write(history);
        assertThat(json).contains("\"type\":\"text\"").contains("\"type\":\"image\"")
                .contains("\"type\":\"tool_result\"").contains("\"error\":true");

        assertThat(MessageJson.read(json)).isEqualTo(history);
    }

    @Test
    @DisplayName("save 是整份重写：同一会话写两次不会留下两份历史")
    void saveOverwritesTheWholeSession() {
        store.save("overwrite", List.of(ChatMessage.user("一")));
        store.save("overwrite", List.of(ChatMessage.user("一"), ChatMessage.assistant("二")));

        assertThat(store.load("overwrite")).hasSize(2);
        assertThat(store.sessionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ③ disabled()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MemoryStore.disabled() 永远返回空、save 不做事 —— 但 Agent 仍然会去 load/save")
    void disabledStoreNeverPersistsAnything() {
        MemoryStore disabled = MemoryStore.disabled();
        disabled.save("s", List.of(ChatMessage.user("你好")));

        assertThat(disabled.load("s")).isEmpty();
        assertThat(disabled.exists("s")).isFalse();
        // clear 是空实现：不抛异常就是它的全部语义
        disabled.clear("s");

        // 把 MemoryStore 换成计数装饰器后交给 Agent：语义与 disabled() 一样"存不进去"，
        // 但能看出 Agent 到底有没有去碰存储（disabled() 返回的是匿名类，数不了）
        CountingStore counting = new CountingStore();
        Agent agent = agent("memoryAssistant").toBuilder().memoryStore(counting).build();
        agent.call("第一轮", "no-store");
        agent.call("第二轮", "no-store");

        // 记忆开关是开着的：每轮都真的 load 了一次、结束又 save 了一次
        assertThat(counting.loads.get()).isEqualTo(2);
        assertThat(counting.saves.get()).isEqualTo(2);
        // 但存不进去 → 第二轮下发的只有本轮输入（系统提示词由 ContextManager 注入，不属于历史）
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(1);
        assertThat(nonSystem(mockModel.lastRequest()).get(0).text()).isEqualTo("第二轮");
        assertThat(agent.history("no-store")).isEmpty();
    }
    @Test
    @DisplayName("disabled() 与 memory(false) 的差别：前者存不进去，后者连 load/save 都不调用")
    void disabledVersusMemoryFalse() {
        // memory = false：Agent 根本不碰 MemoryStore —— 用计数装饰器把"没碰"变成可断言的数字。
        // 这正是它与 disabled() 的分水岭：两者最终都"历史为空"，但成因完全不同。
        CountingStore counting = new CountingStore();
        Agent stateless = agent("memoryAssistant").toBuilder().memoryStore(counting).memory(false).build();
        stateless.call("第一轮", "diff-session");
        stateless.call("第二轮", "diff-session");

        assertThat(counting.loads.get()).isZero();
        assertThat(counting.saves.get()).isZero();
        // 容器里那个 JDBC 记忆同样一行都没写
        assertThat(store.exists("diff-session")).isFalse();
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(1);
        assertThat(nonSystem(mockModel.lastRequest()).get(0).text()).isEqualTo("第二轮");
        // history() 是显式查询，这时才会 load 一次 —— 所以上面两条计数断言必须写在它前面
        assertThat(stateless.history("diff-session")).isEmpty();
        assertThat(counting.loads.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ④ @Memory 的多轮语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个 sessionId 连续多轮：历史线性增长，下发的消息里带着前几轮")
    void sameSessionGrowsLinearly() {
        memoryAssistant.chat("grow", "你好");
        assertThat(agent("memoryAssistant").history("grow")).hasSize(2);

        memoryAssistant.chat("grow", "第二轮");
        assertThat(agent("memoryAssistant").history("grow")).hasSize(4);

        memoryAssistant.chat("grow", "继续");
        assertThat(agent("memoryAssistant").history("grow")).hasSize(6);

        // 最后一轮下发的消息 = 前两轮 4 条 + 本轮 1 条（系统提示词不算历史）
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(5);
        assertThat(nonSystem(mockModel.lastRequest())).extracting(ChatMessage::role)
                .containsExactly(com.benxin.llm.core.message.Role.USER,
                        com.benxin.llm.core.message.Role.ASSISTANT,
                        com.benxin.llm.core.message.Role.USER,
                        com.benxin.llm.core.message.Role.ASSISTANT,
                        com.benxin.llm.core.message.Role.USER);
    }

    @Test
    @DisplayName("不同 sessionId 完全隔离；clearHistory 与 AgentSession.clear 等价")
    void sessionsAreIsolatedAndCanBeCleared() {
        memoryAssistant.chat("iso-1", "你好");
        memoryAssistant.chat("iso-1", "第二轮");
        memoryAssistant.chat("iso-2", "你好");

        Agent agent = agent("memoryAssistant");
        assertThat(agent.history("iso-1")).hasSize(4);
        assertThat(agent.history("iso-2")).hasSize(2);

        agent.clearHistory("iso-2");
        assertThat(agent.history("iso-2")).isEmpty();
        assertThat(store.exists("iso-2")).isFalse();
        // 清一个会话不影响另一个
        assertThat(agent.history("iso-1")).hasSize(4);

        AgentSession session = agent.session("iso-3");
        session.chat("打个招呼");
        assertThat(session.history()).hasSize(2);
        assertThat(session.id()).isEqualTo("iso-3");

        session.clear();
        assertThat(session.history()).isEmpty();
    }

    @Test
    @DisplayName("不传 sessionId 时都落在 <agentName>-default 会话上")
    void defaultSessionIdIsAgentNameWithSuffix() {
        memoryAssistant.ask("你好");
        memoryAssistant.ask("第二轮");

        assertThat(agent("memoryAssistant").history("memoryAssistant-default")).hasSize(4);
        // 第二次调用下发的 = 第一轮 2 条 + 本轮 1 条；系统提示词由 ContextManager 单独注入
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(3);
    }

    @Test
    @DisplayName("@LlmAgent(memory = false)：传了 sessionId 也不写回，连 save 都不调用")
    void annotationLevelMemoryFalseNeverPersists() {
        statelessAssistant.ask("stateless-1", "你好");
        statelessAssistant.ask("stateless-1", "第二轮");

        assertThat(agent("statelessAssistant").spec().memory()).isFalse();
        assertThat(agent("statelessAssistant").history("stateless-1")).isEmpty();
        // 注解级关闭是"这个 Agent 的所有调用都不装载历史"，所以每次只剩本轮输入
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(1);
        assertThat(nonSystem(mockModel.lastRequest()).get(0).text()).isEqualTo("第二轮");
    }

    @Test
    @DisplayName("AgentBuilder.memory(false) 派生：只影响这一个实例，原 Agent 不受影响")
    void builderLevelMemoryFalseOnlyAffectsDerivedInstance() {
        Agent derived = agent("memoryAssistant").toBuilder().memory(false).build();
        derived.call("第一轮", "builder-nomem");
        derived.call("第二轮", "builder-nomem");

        assertThat(derived.history("builder-nomem")).isEmpty();
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(1);

        // 原 Agent 的记忆照旧
        memoryAssistant.chat("builder-mem", "第一轮");
        memoryAssistant.chat("builder-mem", "第二轮");
        assertThat(agent("memoryAssistant").history("builder-mem")).hasSize(4);
        assertThat(nonSystem(mockModel.lastRequest())).hasSize(3);
    }

    @Test
    @DisplayName("JDBC 记忆下多轮同样成立：历史进表、条数与消息数一致")
    void jdbcBackedSessionIsVisibleInTheTable() {
        memoryAssistant.chat("jdbc-multi", "你好");
        memoryAssistant.chat("jdbc-multi", "第二轮");

        assertThat(store.load("jdbc-multi")).isEqualTo(agent("memoryAssistant").history("jdbc-multi"));
        assertThat(store.rows()).anySatisfy(row -> assertThat(row)
                .containsEntry("SESSION_ID", "jdbc-multi")
                .containsEntry("MESSAGE_COUNT", 4));
        // 表里的 JSON 能独立还原出同样的历史（换进程、换语言都能读）
        String json = (String) new JdbcTemplate(new SimpleDataSource(storeUrl, storeUsername, storePassword))
                .queryForObject("SELECT messages_json FROM " + JdbcMemoryStore.TABLE
                        + " WHERE session_id = ?", String.class, "jdbc-multi");
        assertThat(MessageJson.read(json)).isEqualTo(agent("memoryAssistant").history("jdbc-multi"));
    }

    @Test
    @DisplayName("记忆里存的是完整历史：工具调用与工具结果也一起落库")
    void toolMessagesAreStoredToo() {
        Agent agent = agent("memoryAssistant");
        // 输入里带「文档」→ 脚本模型第一轮请求 read_document，第二轮拿工具结果收尾，共 2 步
        agent.call("请读一下这份文档", "tool-session");

        List<ChatMessage> history = agent.history("tool-session");
        // 4 条 = user + assistant(tool_use) + tool(result) + assistant(收尾结论)：
        // 工具闭环的最后一轮同样是"模型回答"，它当然也会进历史
        assertThat(history).hasSize(4);
        assertThat(history.get(0).role()).isEqualTo(com.benxin.llm.core.message.Role.USER);
        assertThat(history.get(1).hasToolUse()).isTrue();
        assertThat(history.get(2).role()).isEqualTo(com.benxin.llm.core.message.Role.TOOL);
        assertThat(history.get(2).toolCallId()).isEqualTo(history.get(1).toolUses().get(0).id());
        assertThat(history.get(2).text()).hasSize(com.benxin.exp.context.tool.LongDocTools.DOC_CHARS);
        assertThat(history.get(3).role()).isEqualTo(com.benxin.llm.core.message.Role.ASSISTANT);
    }

    @Test
    @DisplayName("大历史也能落库：内容块不会被截断（表里存的是原文长度）")
    void largeHistoryIsStoredVerbatim() {
        String big = DemoText.chinese(4000, "jdbc");
        store.save("big", List.of(ChatMessage.user(big), ChatMessage.user(ContentPart.text("x"))));

        assertThat(store.load("big").get(0).text()).isEqualTo(big);
    }

    // ------------------------------------------------------------------

    /**
     * "计数版"的 {@link MemoryStore#disabled()}。
     *
     * <p><b>为什么需要它</b>：{@code MemoryStore.disabled()}（存不进去）与 {@code memory = false}
     * （根本不去碰存储）最终都表现为"历史是空的"，只看结果无法区分。这里保留 disabled() 的全部行为
     * （load 恒为空、save 是空操作），只额外记录 load / save 被调了几次 ——
     * 于是"Agent 到底有没有去碰存储"变成可断言的事实。这也是本模块最想钉住的一条口径：
     * <b>换存储后端</b>与<b>关掉记忆</b>解决的是两个不同的问题。</p>
     */
    private static final class CountingStore implements MemoryStore {

        private final MemoryStore delegate = MemoryStore.disabled();
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();

        @Override
        public List<ChatMessage> load(String sessionId) {
            loads.incrementAndGet();
            return delegate.load(sessionId);
        }

        @Override
        public void save(String sessionId, List<ChatMessage> history) {
            saves.incrementAndGet();
            delegate.save(sessionId, history);
        }

        @Override
        public void clear(String sessionId) {
            delegate.clear(sessionId);
        }
    }
}
