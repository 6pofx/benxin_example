package com.benxin.exp.database;

import com.benxin.exp.common.db.Db;
import com.benxin.exp.common.db.SimpleDataSource;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.database.memory.ChatMessageCodec;
import com.benxin.exp.database.memory.JdbcMemoryStore;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 独立的「JDBC 记忆」测试：<b>完全不启动 Spring 容器</b>。
 *
 * <p>为什么值得单独一个测试类？因为它演示的是本示例最有说服力的一条结论 ——</p>
 *
 * <blockquote>
 * 记忆是可插拔的：换一个 {@code MemoryStore} 实现，会话历史就从"进程内存"
 * 变成"数据库"，而 <b>Agent 的装配方式一行都不用改</b>。
 * </blockquote>
 *
 * <p>要做到"跨重启"的演示，最干净的方式就是"真的重启"：这里不启动 Spring，
 * 而是手工 new 出<b>两个完全独立的 Agent 实例</b>（各自的 {@code MemoryStore}），
 * 让它们共享同一个 H2 文件库。第二个 Agent 读得到第一个写进去的历史 ——
 * 这就是"重启后记忆还在"，而且没有任何容器缓存可以作弊。</p>
 *
 * <p>库文件放在 {@code target/} 下（构建产物目录），每次测试前删掉重建，
 * 因此它既证明了持久化，又不会污染开发时 {@code mvn spring-boot:run} 用的那个库。</p>
 */
@DisplayName("示例 10 · JDBC 记忆（不启动 Spring）")
class JdbcMemoryStoreStandaloneTest {

    /** 独立测试用库文件名（H2 2.x 的实际文件是 {@code <name>.mv.db}）。 */
    private static final String DB_NAME = "exp10-memory-standalone";

    private static final String JDBC_URL =
            "jdbc:h2:file:./target/" + DB_NAME + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";

    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() throws IOException {
        // 删掉上次留下的库文件，保证用例之间绝对干净。
        // 用 deleteIfExists 而不是断言不存在：第一次运行时它本来就不存在。
        Files.deleteIfExists(Path.of("target", DB_NAME + ".mv.db"));
        Files.deleteIfExists(Path.of("target", DB_NAME + ".trace.db"));

        SimpleDataSource dataSource = new SimpleDataSource(JDBC_URL, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        // 建表脚本直接复用 classpath 上那一份：于是"独立测试"验证的仍然是产品代码用的 schema
        Db.executeClasspathScript(dataSource, "schema.sql");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("实现 MemoryStore 的四个方法：save 落库、load 还原、clear 删行、exists 判存在")
    void implementsAllMemoryStoreOperations() {
        MemoryStore store = new JdbcMemoryStore(jdbc);

        assertThat(store.exists("s-1")).isFalse();
        assertThat(store.load("s-1")).isEmpty();

        store.save("s-1", List.of(
                ChatMessage.user("你好"),
                ChatMessage.assistant("你好！我是本心示例里的离线模型，当前不联网、不消耗 token。")));

        assertThat(store.exists("s-1")).isTrue();
        assertThat(Db.count(jdbc.getDataSource(), "agent_memory", "session_id = ?", "s-1")).isEqualTo(2);

        List<ChatMessage> loaded = store.load("s-1");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).role()).isEqualTo(Role.USER);
        assertThat(loaded.get(0).text()).isEqualTo("你好");
        assertThat(loaded.get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(loaded.get(1).text()).contains("离线模型");

        // save 是全量覆盖：再存一次更短的历史，表里就只剩那么长
        store.save("s-1", List.of(ChatMessage.user("只剩这一条")));
        assertThat(store.load("s-1")).hasSize(1);

        store.clear("s-1");
        assertThat(store.exists("s-1")).isFalse();
        assertThat(Db.count(jdbc.getDataSource(), "agent_memory", null)).isZero();
    }

    @Test
    @DisplayName("不同 sessionId 完全隔离：清空一个会话不影响另一个")
    void sessionsAreIsolated() {
        MemoryStore store = new JdbcMemoryStore(jdbc);
        store.save("s-a", List.of(ChatMessage.user("A 的第一句")));
        store.save("s-b", List.of(ChatMessage.user("B 的第一句"), ChatMessage.assistant("B 的回答")));

        assertThat(store.load("s-a")).hasSize(1);
        assertThat(store.load("s-b")).hasSize(2);

        store.clear("s-a");
        assertThat(store.load("s-a")).isEmpty();
        assertThat(store.load("s-b")).hasSize(2);
        assertThat(payloads("s-b")).hasSize(2);
    }

    @Test
    @DisplayName("跨重启：新建一个 Agent 实例读同一个文件库，历史仍然在并且能续聊")
    void historySurvivesAcrossIndependentAgentInstances() {
        // ---- 第一次"启动"：一个全新的 Agent 实例 ----
        JdbcMemoryStore firstStore = new JdbcMemoryStore(jdbc);
        Agent first = newAgent("standaloneAssistant", firstStore);
        AgentResult firstResult = first.call("你好", "restart-session");
        assertThat(firstResult.text()).contains("离线模型");
        assertThat(firstStore.load("restart-session")).hasSize(2);

        // ---- 第二次"启动"：所有对象都重新 new 一遍，只共享同一个库文件 ----
        JdbcMemoryStore secondStore = new JdbcMemoryStore(jdbc);
        Agent second = newAgent("standaloneAssistant", secondStore);

        List<ChatMessage> recovered = second.history("restart-session");
        assertThat(recovered).hasSize(2);
        assertThat(recovered.get(0).text()).isEqualTo("你好");

        // 而且能续聊：第二轮跑完之后历史变成 4 条，说明第二轮是在已有历史上接着跑的
        AgentResult secondResult = second.call("介绍一下你自己", "restart-session");
        // 断言"命中了自我介绍那条规则"而不是"没命中"：脚本模型的规则是子串匹配，
        // 这里刻意写成片段关键字（"介绍"），否则换个说法就会掉进 fallback，
        // 断言会以一个与"记忆"毫无关系的理由红掉。
        assertThat(secondResult.text()).contains("规则驱动的离线模型");
        assertThat(secondStore.load("restart-session")).hasSize(4);
    }

    @Test
    @DisplayName("Codec 能往返全部内容块（含思维链、工具调用与工具结果）")
    void codecRoundTripsEveryContentPart() {
        ChatMessage rich = ChatMessage.assistant(List.of(
                new ThinkingPart("（思考）我应该先查订单", "sig-1"),
                new TextPart("我先查一下。"),
                new ToolUsePart("call_1", "query_orders", "{\"customerId\":1}")));

        ChatMessage decoded = ChatMessageCodec.decode(ChatMessageCodec.encode(rich));

        assertThat(decoded.role()).isEqualTo(Role.ASSISTANT);
        assertThat(decoded.parts()).hasSize(3);
        assertThat(decoded.thinking()).isEqualTo("（思考）我应该先查订单");
        assertThat(decoded.text()).isEqualTo("我先查一下。");
        assertThat(decoded.toolUses()).hasSize(1);
        assertThat(decoded.toolUses().get(0).name()).isEqualTo("query_orders");
        assertThat(decoded.toolUses().get(0).argumentsJson()).isEqualTo("{\"customerId\":1}");
        // equals 覆盖了 role + parts + name + toolCallId，因此这一条断言足够强
        assertThat(decoded).isEqualTo(rich);

        ChatMessage toolMessage = ChatMessage.toolResult("call_1", "query_orders",
                "[{\"orderId\":1}]", false);
        ChatMessage toolDecoded = ChatMessageCodec.decode(ChatMessageCodec.encode(toolMessage));
        assertThat(toolDecoded.role()).isEqualTo(Role.TOOL);
        assertThat(toolDecoded.name()).isEqualTo("query_orders");
        assertThat(toolDecoded.toolCallId()).isEqualTo("call_1");
        assertThat(toolDecoded.parts().get(0)).isInstanceOf(ToolResultPart.class);

        ChatMessage image = ChatMessage.user(new ImagePart("image/png", "aGVsbG8=", null));
        ChatMessage imageDecoded = ChatMessageCodec.decode(ChatMessageCodec.encode(image));
        assertThat(imageDecoded).isEqualTo(image);
        assertThat(((ImagePart) imageDecoded.parts().get(0)).isInline()).isTrue();
    }

    /**
     * 这条用例是「插件问题」的登记处，而不是本示例的功能演示。
     *
     * <p>它把两件事钉住：</p>
     * <ol>
     *   <li>{@code Json.write(ChatMessage)} <b>不抛异常</b>，但只输出
     *       {@code {"textEmpty":false}} —— message 的 role 与全部内容静默丢失。
     *       这比"抛异常"危险得多：一个不加防护的 JDBC MemoryStore 会以为写入成功，
     *       实际存进去的是一堆空壳；</li>
     *   <li>{@code Json.convert(..., ChatMessage.class)} 反序列化直接失败（没有 Creator）。</li>
     * </ol>
     * <p>结论：走 {@code Json} 直通的路是死的，必须自己写编解码，也就是
     * {@link ChatMessageCodec}。</p>
     */
    @Test
    @DisplayName("插件问题：Json 直通 ChatMessage 会静默丢字段，反序列化则直接失败")
    void jsonCannotRoundTripChatMessage() {
        ChatMessage message = ChatMessage.user("你好");

        String written = Json.write(message);
        // 不抛异常 —— 这正是最危险的地方
        assertThat(written).isEqualTo("{\"textEmpty\":false}");
        assertThat(written).doesNotContain("你好").doesNotContain("USER");

        assertThatThrownBy(() -> Json.convert(Json.parse(
                "{\"role\":\"USER\",\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]}"),
                ChatMessage.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot construct instance");

        // 而自定义编解码器能往返
        assertThat(ChatMessageCodec.decode(ChatMessageCodec.encode(message))).isEqualTo(message);
    }

    @Test
    @DisplayName("插件问题：Role 的 Jackson 绑定只认枚举名，不认 wire name")
    void roleBindsByEnumNameOnly() {
        // 注意这里用的是 mapper.readValue("...", Role.class) 而不是 convert(...)：
        // ObjectMapper 上根本没有 convert(Object, Class) 这个方法（那是插件门面
        // Json.convert(Object, Class) 的名字，它内部转调的是 mapper.convertValue(...)）。
        // 两种写法对枚举的绑定口径完全一致，都用"枚举名"反序列化。
        assertThat(readRole("\"USER\"")).isEqualTo(Role.USER);
        assertThat(Json.convert("USER", Role.class)).isEqualTo(Role.USER);

        // 小写的 wire name 会被拒。注意报错类型是 IllegalArgumentException：
        // Jackson 的 InvalidFormatException 继承自 JsonProcessingException（受检），
        // ObjectMapper 上的两种入口都会把它包成 IllegalArgumentException 抛出。
        assertThatThrownBy(() -> readRole("\"user\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> Json.convert("user", Role.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not one of the values accepted");

        // 而插件自己的 fromWire 是认 wire name 的，两处口径不一致
        assertThat(Role.fromWire("user")).isEqualTo(Role.USER);
    }

    // ------------------------------------------------------------------
    // 手工装配一个"最小可用 Agent"，不依赖 Spring
    // ------------------------------------------------------------------

    /**
     * 手工装配一个 Agent。
     *
     * <p>与 exp-01 那种"接口 + 注解"的路径不同，这里连 Loop 都得自己给：
     * {@code AgentBuilder} 在没有 {@code loop} / {@code loopRegistry} 时会去问默认注册表，
     * 而裸 Java 场景下没有人替我们建过它，于是 {@code DefaultAgent} 的构造器会抛
     * {@code IllegalStateException: 尚未注册任何 Agent Loop}。
     * 提交一个 {@link BuiltinLoops#defaultRegistry()} 即可 —— 它装的就是 starter
     * 在 Spring 侧注册的同一批内置 Loop（默认 {@code dsh-minimal}），
     * 因此这里的 Agent 与容器里那些跑的是同一套循环逻辑。</p>
     */
    private Agent newAgent(String name, MemoryStore store) {
        LlmModel model = ScriptedLlmModel.builder("mock")
                .rule("你好", "你好！我是本心示例里的离线模型，当前不联网、不消耗 token。")
                // 关键字取"介绍"而不是"自我介绍"：脚本模型的规则是子串匹配，
                // 用更短的片段才能同时命中"介绍一下你自己""自我介绍"这些说法。
                .rule("介绍", "我是一个用规则驱动的离线模型，用来让示例在离线环境下也能完整跑通。")
                .fallback("（离线脚本模型）已收到：{input}")
                .build();
        return Agent.builder(name)
                .spec(AgentSpec.builder().name(name).maxSteps(2).memory(true).build())
                .model(model)
                // 显式给一份"只有内置 Loop"的注册表：默认 dsh-minimal，
                // 与容器里那些声明式 Agent 用的是同一个 Loop 实现
                .loopRegistry(BuiltinLoops.defaultRegistry())
                .memoryStore(store)
                .build();
    }

    /** 便于在断言失败时看清"到底存了什么"。 */
    private List<String> payloads(String sessionId) {
        return jdbc.queryForList(
                "SELECT payload FROM agent_memory WHERE session_id = ? ORDER BY seq",
                String.class, sessionId);
    }

    /**
     * 把 JSON 文本绑成 {@link Role}。
     *
     * <p>单独包一层不是为了好看：{@code ObjectMapper#readValue} 抛的是<b>受检</b>异常，
     * 而 AssertJ 的 {@code assertThatThrownBy(...)} 收的是不声明异常的 lambda，
     * 直接写会在编译期被 javac 拦下。</p>
     */
    private static Role readRole(String json) {
        try {
            return Json.mapper().readValue(json, Role.class);
        } catch (JsonProcessingException e) {
            // 转成不受检异常向外抛，让 AssertJ 能断言到原始类型
            throw new IllegalArgumentException(e.getClass().getName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
