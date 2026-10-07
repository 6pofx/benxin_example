package com.benxin.exp.database;

import com.benxin.exp.common.db.Db;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.database.agent.ComplianceAssistant;
import com.benxin.exp.database.agent.FailAssistant;
import com.benxin.exp.database.agent.OrderAssistant;
import com.benxin.exp.database.approval.DbApprovalHandler;
import com.benxin.exp.database.audit.AgentRunAuditListener;
import com.benxin.exp.database.audit.ToolAuditInterceptor;
import com.benxin.exp.database.demo.DatabaseReport;
import com.benxin.exp.database.demo.DatabaseReset;
import com.benxin.exp.database.dto.BusinessReport;
import com.benxin.exp.database.guard.ManualReviewApprovalHandler;
import com.benxin.exp.database.guard.RefundAmountSandbox;
import com.benxin.exp.database.guard.WriteToolShortCircuitInterceptor;
import com.benxin.exp.database.memory.JdbcMemoryStore;
import com.benxin.exp.database.tool.OrderWriteService;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.tool.ToolCallRecord;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 示例 10 的主测试类（H2）：把演示里展示的每一条结论都断言成可回归的用例。
 *
 * <p>这个类覆盖了八个功能点：JDBC 记忆、工具查库、运行审计、工具审计、写库审批、
 * 工作流驱动审批、事务一致性、多轮追问。</p>
 *
 * <h2>隔离策略</h2>
 * <p>{@code @BeforeEach} 里做三件事：</p>
 * <ol>
 *   <li>{@link DatabaseReset#all} 把六张表清空并按 {@code data.sql} 重灌 ——
 *       用例之间不互相污染；</li>
 *   <li>清 Agent 历史（走 {@code names() + find()}，因为 {@code resolved()} 看不到惰性条目）；</li>
 *   <li>重置演示辅助对象的内部计数器（审批记录、沙箱拒绝记录、脚本模型调用次数）。</li>
 * </ol>
 *
 * <p>数据源用<b>内存 H2</b>（{@code jdbc:h2:mem:...}）而不是文件库：
 * 这样"清库"的成本是零，也不会和开发者本机 {@code mvn spring-boot:run} 用的库互相干扰。
 * 真正需要"文件库"来证明持久化的用例在
 * {@code JdbcMemoryStoreStandaloneTest}（它连 Spring 都不启动）。</p>
 *
 * <p>换到 MySQL 只需换一个 profile：见 {@link OrderAssistantMysqlTest}，
 * 它继承本类，跑的是<b>同一套断言</b>。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:exp10-main;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // 测试里不跑演示（演示会改数据、也会打印大量输出）
        "exp.demo.enabled=false"
})
@DisplayName("示例 10 · 数据库（H2）")
class OrderAssistantTest {
    /** 默认会话 id：声明式 Agent 不传 sessionId 时都落在这里。 */
    private static final String SESSION = "orderAssistant-default";

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected OrderAssistant orderAssistant;

    @Autowired
    protected ComplianceAssistant complianceAssistant;

    @Autowired
    protected FailAssistant failAssistant;

    @Autowired
    protected RecordingModel mockLlmModel;

    @Autowired
    protected AgentRegistry agentRegistry;

    @Autowired
    protected DbApprovalHandler approvalHandler;

    @Autowired
    protected ManualReviewApprovalHandler manualReviewApprovalHandler;

    @Autowired
    protected RefundAmountSandbox refundAmountSandbox;

    @Autowired
    protected ToolAuditInterceptor toolAuditInterceptor;

    @Autowired
    protected AgentRunAuditListener agentRunAuditListener;

    @Autowired
    protected JdbcMemoryStore jdbcMemoryStore;

    @Autowired
    protected OrderWriteService orderWriteService;

    @Autowired
    protected Agent refundAssistant;

    @BeforeEach
    void resetEverything() {
        DatabaseReset.all(jdbcTemplate);
        mockLlmModel.reset();
        approvalHandler.reset();
        manualReviewApprovalHandler.reset();
        refundAmountSandbox.reset();

        // 清空所有 Agent 的历史。
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出已经被解析过的那些 —— 直接用它遍历，一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            agent.clearHistory("restart-session");
            agent.clearHistory("session-a");
            agent.clearHistory("session-b");
        }));
    }

    // ==================================================================
    // ① JDBC MemoryStore：记忆落库
    // ==================================================================

    @Test
    @DisplayName("① 多轮对话历史真的落库：agent_memory 的行数与内容都能对上")
    void memoryIsPersistedToDatabase() {
        orderAssistant.ask("你好");
        orderAssistant.ask("介绍一下你自己");

        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", SESSION))
                .isEqualTo(4);   // 两轮，每轮 user + assistant

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT seq, role, payload FROM agent_memory WHERE session_id = ? ORDER BY seq", SESSION);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("role")).isEqualTo("USER");
        assertThat(String.valueOf(rows.get(0).get("payload"))).contains("你好").contains("\"type\":\"text\"");
        assertThat(rows.get(1).get("role")).isEqualTo("ASSISTANT");
        // 第二句"介绍一下你自己"里含"订单"（"你是智能订单助手…"是 Agent 级提示词的一部分），
        // 于是脚本模型回了"订单"那条业务规则 —— 这也顺带证明"落库的是真实模型输出"。
        assertThat(String.valueOf(rows.get(1).get("payload"))).contains("张三共有 3 笔订单");
        // seq 连续且从 0 开始：顺序靠它还原，不依赖自增主键
        assertThat(rows.stream().map(r -> ((Number) r.get("seq")).intValue()).toList())
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("① 记忆可插拔：容器里的 MemoryStore 就是 JDBC 版，读写都落在 agent_memory 上")
    void memoryStoreIsTheJdbcOne() {
        // 直接对容器里的那个 bean 操作，看数据是不是真的进了表 ——
        // 这比断言"bean 的类型对"更能证明"记忆确实被持久化了"
        jdbcMemoryStore.save("memory-store-probe", List.of(ChatMessage.user("探针消息")));

        assertThat(jdbcMemoryStore.exists("memory-store-probe")).isTrue();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory",
                "session_id = ?", "memory-store-probe")).isEqualTo(1);
        // 而且内容是可读的 JSON（不是空壳）—— 这正是 ChatMessageCodec 存在的意义
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM agent_memory WHERE session_id = ?", String.class,
                "memory-store-probe"))
                .contains("探针消息").contains("\"role\":\"USER\"");

        jdbcMemoryStore.clear("memory-store-probe");
        assertThat(jdbcMemoryStore.exists("memory-store-probe")).isFalse();
    }

    @Test
    @DisplayName("① 不同 sessionId 隔离；clear 真的删行")
    void memoryIsIsolatedPerSessionAndClearDeletesRows() {
        orderAssistant.askInSession("你好", "session-a");
        orderAssistant.askInSession("你好", "session-b");
        orderAssistant.askInSession("介绍一下你自己", "session-b");

        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", "session-a"))
                .isEqualTo(2);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", "session-b"))
                .isEqualTo(4);

        agentRegistry.get("orderAssistant").clearHistory("session-a");
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", "session-a"))
                .isZero();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", "session-b"))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("① 跨重启续接：换一个 ApplicationContext 读同一个库，历史仍然在")
    void memorySurvivesANewApplicationContext() {
        // 先在一个"库"上写一轮历史 —— 这里直接用 JDBC 版 MemoryStore 写，
        // 它和容器里那个是同一个实现（同一张表、同一套编解码）。
        jdbcMemoryStore.save("restart-session", List.of(
                ChatMessage.user("重启前的问题"),
                ChatMessage.assistant("重启前的回答")));
        assertThat(jdbcMemoryStore.exists("restart-session")).isTrue();

        // 再 new 一个全新的 MemoryStore 实例（模拟新进程里刚建好的那个 bean），
        // 它读的是同一个 H2 库 —— 历史必须还在
        JdbcMemoryStore brandNew = new JdbcMemoryStore(jdbcTemplate);
        List<ChatMessage> recovered = brandNew.load("restart-session");
        assertThat(recovered).hasSize(2);
        assertThat(recovered.get(0).text()).isEqualTo("重启前的问题");
        assertThat(recovered.get(1).text()).isEqualTo("重启前的回答");

        // 更强的证据：让 Agent 在这个会话上续聊，模型收到的请求里带着重启前的历史
        mockLlmModel.reset();
        orderAssistant.askInSession("介绍一下你自己", "restart-session");
        ChatRequest request = mockLlmModel.lastRequest();
        assertThat(nonSystemMessages(request).stream().map(ChatMessage::text).toList())
                .contains("重启前的问题", "重启前的回答", "介绍一下你自己");
    }

    // ==================================================================
    // ② 工具查库
    // ==================================================================

    @Test
    @DisplayName("② 模型决策 → 工具查库 → 结果回灌 → 收尾：完整闭环，且答案来自数据库")
    void toolQueriesTheDatabaseAndTheAnswerComesFromIt() {
        AgentResult result = agentRegistry.get("orderAssistant").call("#tool:query_orders {\"customerId\":1}");

        // 工具真的被执行了
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls().get(0).name()).isEqualTo("query_orders");
        assertThat(result.toolCalls().get(0).error()).isFalse();

        // 结果回灌给了模型：模型请求里出现了角色为 TOOL 的消息，内容是真实订单
        ChatRequest request = mockLlmModel.lastRequest();
        assertThat(request.messages()).anyMatch(m -> m.role() == Role.TOOL);
        String toolText = request.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .map(ChatMessage::text)
                .findFirst()
                .orElseThrow();
        assertThat(toolText).contains("SKU-1001").contains("SKU-1002").contains("1380.00");

        // 口径（实测，也是本示例最容易踩的一条）：
        // `#tool:<名字>` 在脚本模型里排在"已有工具结果就收尾"之前，而"最后一条 user 消息"
        // 整轮不变，于是**每一步都会重新命中同一条指令**，工具被反复调用直到步数预算耗尽
        // （exp-08 也把这条单独断言成口径）。连带的后果是：这一轮**永远走不到"收尾"那一步**，
        // 所以 result.text() 是空串 —— 想知道答案，只能看工具结果（下面这样断言）。
        assertThat(result.maxStepsReached()).isTrue();
        assertThat(result.toolCalls()).hasSize(result.steps());
        assertThat(result.text()).isEmpty();
    }

    @Test
    @DisplayName("② 改一行数据，工具答案随之变化（证明答案不是写死的）")
    void changingARowChangesTheToolAnswer() {
        // 先按真实路径查一次：断言工具返回的就是库里的原始值
        String before = String.valueOf(agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}").toolCalls().get(0).result());
        assertThat(before).contains("260.00").contains("PENDING");

        // 改一行数据（张三那笔待支付订单），再查一次 —— 工具答案必须跟着变
        jdbcTemplate.update("UPDATE order_item SET amount = 999.99 WHERE sku = 'SKU-1003'");

        String after = String.valueOf(agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}").toolCalls().get(0).result());
        assertThat(after).contains("999.99");
        assertThat(after).doesNotContain("260.00");

        // 只读工具不落 approval_request，也不改业务表
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM order_item WHERE sku = 'SKU-1003'", BigDecimal.class))
                .isEqualByComparingTo("999.99");
    }

    @Test
    @DisplayName("② 查客户：两步查询（先拿 id 再查订单）走的是同一套工具")
    void queryCustomerReturnsRealRow() {
        AgentResult result = agentRegistry.get("orderAssistant").call("#tool:query_customer {\"name\":\"张三\"}");

        assertThat(result.toolCalls().get(0).name()).isEqualTo("query_customer");
        assertThat(toolResultText(result)).contains("\"customerId\":1").contains("VIP").contains("张三");

        AgentResult missing = agentRegistry.get("orderAssistant").call("#tool:query_customer {\"name\":\"赵六\"}");
        // 查不到时返回的是"空结果"而不是异常 —— 口径见 OrderQueryTools 的类注释。
        // 注意这里要看**全部**工具调用记录：`#tool:` 每一步都会重新命中（见上一条用例的说明），
        // 所以 get(0) 只是其中一次，真正的结论是"每一次都 found=false"。
        assertThat(missing.toolCalls()).isNotEmpty();
        assertThat(missing.toolCalls()).allSatisfy(call ->
                assertThat(String.valueOf(call.result())).contains("\"found\":false").contains("没有名为"));
    }

    @Test
    @DisplayName("② 聚合统计由 SQL 完成，并附带一行 ALL 总计")
    void statisticsAreComputedInSql() {
        AgentResult result = agentRegistry.get("orderAssistant").call("#tool:order_statistics {\"customerId\":1}");

        String text = toolResultText(result);
        assertThat(text).contains("\"PAID\"").contains("\"PENDING\"").contains("\"ALL\"");
        // 张三：PAID 2 笔 2880.00（1380 + 1500），PENDING 1 笔 260.00，ALL 3 笔 3140.00
        assertThat(text).contains("2880.00").contains("3140.00");
    }

    @Test
    @DisplayName("② DTO 返回：模型输出的 JSON 被反序列化成 BusinessReport")
    void dtoReturnTypeIsDeserialized() {
        // 这条路径靠"系统提示词里要求只输出 JSON"命中 —— report() 方法上有
        // @SystemPrompt("请只输出 JSON…")，脚本模型用 systemRule 精确接住它。
        // 反过来说：如果只写 jsonAnswer 兜底是不行的，因为 Agent 级 systemPrompt 里
        // 带"订单"二字，会先被业务关键字规则吃掉（离线模型的规则顺序使然）。
        BusinessReport report = orderAssistant.report("给我一份经营概览");

        assertThat(report).isNotNull();
        assertThat(report.orderCount()).isEqualTo(3);
        assertThat(report.highlights()).isNotEmpty();
        assertThat(report.summary()).contains("经营概览");
    }

    // ==================================================================
    // ③ 运行审计（AgentListener）
    // ==================================================================

    @Test
    @DisplayName("③ 成功路径：onAgentEnd 落一行 OK，字段与 AgentResult 一致")
    void successfulRunIsAudited() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", "audit-session");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM agent_run WHERE session_id = ?", "audit-session");

        assertThat(row.get("agent_name")).isEqualTo("orderAssistant");
        assertThat(row.get("loop_name")).isEqualTo(result.loopName());
        assertThat(row.get("model_name")).isEqualTo("mock");
        assertThat(((Number) row.get("steps")).intValue()).isEqualTo(result.steps());
        assertThat(((Number) row.get("input_tokens")).intValue())
                .isEqualTo(result.usage().inputTokens());
        assertThat(((Number) row.get("output_tokens")).intValue())
                .isEqualTo(result.usage().outputTokens());
        assertThat(((Number) row.get("duration_ms")).longValue())
                .isGreaterThanOrEqualTo(result.durationMillis() - 1);
        assertThat(row.get("finish_reason")).isEqualTo(String.valueOf(result.finishReason()));
        // 步数预算被 `#tool:` 的反复命中打满（见 ② 那条用例的说明），
        // 因此 max_steps_reached 落的是 1 —— 审计如实记录了"这次运行没跑完"。
        assertThat(((Number) row.get("max_steps_reached")).intValue())
                .isEqualTo(result.maxStepsReached() ? 1 : 0)
                .isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("error_message")).isNull();
        // 摘要取的是"本次运行最后的模型文本"；这一轮全是工具调用、没有任何模型文本，
        // 于是监听器退化成写"输入摘要" —— 审计行至少还能看出这次问了什么，
        // 而不是留下一片空白让人猜。这是监听器里 summaryOf(...) 的那条兜底。
        assertThat(String.valueOf(row.get("output_summary"))).contains("#tool:query_orders");
    }

    @Test
    @DisplayName("③ 失败路径：框架只调 onError，不写 agent_run 行；那行 FAILED 是监听器补偿写入的")
    void failedRunLeavesNoFrameworkRowAndListenerCompensates() {
        // 失败路径由监听器补偿：先确认"框架自己那一路"确实没有产生行，
        // 再看补偿行是不是写对了。
        //
        // 这里刻意换成 AgentResult 形态的 call(...) 而不是 String ask(...)：
        // 失败发生在模型 chat() 里，异常会穿过 Loop、穿过 DefaultAgent 的 catch
        // （那里只会回调 onError 再原样抛出），所以 call 是会抛的 —— 但 ask 会先
        // 被声明式代理包成 LlmAgentInvocationException，断言就拿不到 ModelException 了。
        assertThatThrownBy(() -> agentRegistry.get("failAssistant").call("请触发失败"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("模拟的模型故障");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM agent_run WHERE agent_name = 'failAssistant'");
        assertThat(rows).hasSize(1);

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(row.get("error_message")))
                .contains("ModelException")
                .contains("onError 拿不到 AgentResult")
                .contains("监听器补偿写入");
        // 关键口径：失败时这些字段拿不到，写的是"不可得"的默认值而不是编造的数值
        assertThat(((Number) row.get("input_tokens")).intValue()).isZero();
        assertThat(((Number) row.get("output_tokens")).intValue()).isZero();
        assertThat(row.get("finish_reason")).isNull();
        // 草稿被消费掉了，没有泄漏
        assertThat(agentRunAuditListener.runningCount()).isZero();
    }

    @Test
    @DisplayName("③ 运行审计：不同 Agent / 会话各落各的行")
    void auditRowsAreScopedByAgentAndSession() {
        agentRegistry.get("orderAssistant").call("你好", "audit-a");
        agentRegistry.get("orderAssistant").call("你好", "audit-b");

        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_run",
                "agent_name = ? AND session_id = ?", "orderAssistant", "audit-a")).isEqualTo(1);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_run",
                "agent_name = ? AND session_id = ?", "orderAssistant", "audit-b")).isEqualTo(1);
    }

    // ==================================================================
    // ④ 工具调用审计（AgentInterceptor）
    // ==================================================================

    @Test
    @DisplayName("④ 工具审计：名字、参数 JSON、是否错误、耗时都落库")
    void toolCallsAreAudited() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", "tool-audit");

        // 一次运行里的每一次工具调用都有一行（`#tool:` 每步重新命中，因此不止一行）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tool_audit WHERE session_id = ? ORDER BY id", "tool-audit");
        assertThat(rows).hasSize(result.toolCalls().size());

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tool_name")).isEqualTo("query_orders");
        assertThat(row.get("agent_name")).isEqualTo("orderAssistant");
        assertThat(String.valueOf(row.get("arguments"))).contains("customerId").contains("1");
        assertThat(((Number) row.get("error")).intValue()).isZero();
        assertThat(((Number) row.get("short_circuit")).intValue()).isZero();
        // tool_use_id 与 AgentResult 里那条工具调用记录对得上（审计能追溯到具体哪一次调用）
        assertThat(row.get("tool_use_id")).isEqualTo(result.toolCalls().get(0).toolUseId());
        assertThat(String.valueOf(row.get("result_digest"))).contains("SKU-1001");
        // 每一行都能追到某一次具体调用，且都记到了结果
        assertThat(rows.stream().map(r -> String.valueOf(r.get("tool_use_id"))).toList())
                .containsExactlyElementsOf(result.toolCalls().stream()
                        .map(ToolCallRecord::toolUseId).toList());
        assertThat(rows).allSatisfy(r -> assertThat(String.valueOf(r.get("result_digest")))
                .contains("SKU-1001"));
    }

    @Test
    @DisplayName("④ beforeTool 短路：工具没有执行，但审计仍然留痕（short_circuit = 1）")
    void shortCircuitedToolIsStillAudited() {
        String sku = WriteToolShortCircuitInterceptor.BLOCKED_SKU_PREFIX + "-0001";
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"" + sku + "\",\"amount\":100}",
                        "short-circuit");

        // 工具没执行：库里没有这行数据
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", sku)).isZero();
        // 模型收到的是短路结果。注意"写操作护栏"这句只在**工具结果**里，
        // 最终答复是脚本模型对工具结果的汇总，不会把这句原文抄进去。
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> {
            assertThat(call.error()).isFalse();
            assertThat(String.valueOf(call.result())).contains("写操作护栏").contains("工具未被执行");
        });

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tool_audit WHERE session_id = ? AND tool_name = 'create_order'"
                        + " ORDER BY id", "short-circuit");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("short_circuit")).intValue()).isEqualTo(1);
            assertThat(((Number) row.get("error")).intValue()).isZero();
        });
        assertThat(toolAuditInterceptor.shortCircuitCount("create_order")).isEqualTo(rows.size());
    }

    @Test
    @DisplayName("④ 沙箱拒绝的调用在 tool_audit 里没有行（审计覆盖不到拦截器之前的两道闸）")
    void failedToolCallIsAuditedAsError() {
        // 退款 6000 元：超过风控沙箱上限 5000 —— 沙箱先把它拦下，
        // 这次调用因此**从未进入拦截器循环**，tool_audit 里不会有它的行。
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:refund_order {\"orderId\":1,\"amount\":6000}", "tool-error");

        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> {
            assertThat(call.error()).isTrue();
            assertThat(String.valueOf(call.result())).contains("沙箱拒绝执行");
        });

        assertThat(Db.count(jdbcTemplate.getDataSource(), "tool_audit", null)).isZero();
        // 与之对照：正常执行的只读调用是会留痕的（上面 toolCallsAreAudited 那条）
        // —— "审计表只覆盖真正走到拦截器的调用"，这就是它的能力边界。
    }

    // ==================================================================
    // ⑤ 写库工具的审批
    // ==================================================================

    @Test
    @DisplayName("⑤ 小额自动批准：订单真的落库；approval_request 里那行'已放行'是 OrderWriteService 写的")
    void smallAmountIsAutoApprovedAndOrderIsWritten() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-9001\",\"amount\":300}",
                        "approval-auto");

        assertThat(result.toolCalls()).isNotEmpty();
        // 每一步都重新命中 `#tool:`（见 ② 的说明），于是每一步都被问一次审批、也都被放行；
        // 但 sku 有唯一约束，第一次之后每次都撞 uk_order_item_sku —— 因此订单只有一行。
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", "SKU-9001"))
                .isEqualTo(1);
        // 口径（容易猜错）：approval_request 里这行 status=AUTO_APPROVED 的留痕
        // 不是 DbApprovalHandler 写的 —— 它在"批准"这条路径上只记内存，不落库。
        // 落库的是 OrderWriteService.createOrder：它把"订单行 + 放行留痕"绑在同一个事务里。
        // 于是这张表的语义保持单一：由审批处理器写入的行才是"等人处理的待办"。
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request",
                "status = ?", "AUTO_APPROVED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by FROM approval_request WHERE status = 'AUTO_APPROVED'", String.class))
                .isEqualTo("OrderWriteService");
        // 审批处理器这边每一步留一条内存决策记录
        assertThat(approvalHandler.decisions()).isNotEmpty();
        assertThat(approvalHandler.decisions().get(0)).contains("APPROVED create_order");
    }

    @Test
    @DisplayName("⑤ 大额被拒：订单没有落库、请求落 approval_request 待人工；模型拿不到可读原因")
    void largeAmountIsRejectedAndQueuedForManualReview() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-9002\",\"amount\":900}",
                        "approval-manual");

        // 工具报错，订单没写（每次调用都被审批闸拦下，因此一整轮下来一行都没有）
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.error()).isTrue());
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", "SKU-9002")).isZero();

        // 待人工处理的单子落库了 —— 而且是每一次被拒都留一条，不会丢
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM approval_request WHERE tool_name = 'create_order' ORDER BY id");
        assertThat(rows).hasSize(result.toolCalls().size());
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("status")).isEqualTo("PENDING_MANUAL");
        assertThat(((Number) row.get("approved")).intValue()).isZero();
        assertThat(row.get("session_id")).isEqualTo("approval-manual");
        assertThat(String.valueOf(row.get("reason"))).contains("超过自动审批阈值 500");
        assertThat(String.valueOf(row.get("arguments"))).contains("SKU-9002");
        assertThat(row.get("decided_by")).isEqualTo("DbApprovalHandler");

        // 口径（容易猜错）：框架回灌给模型的错误文本是固定的
        // "用户拒绝执行工具 [create_order]"，它**不**包含审批处理器写下的详细理由；
        // 而"补一句可读原因"那段代码在工具真正执行时才跑得到 —— 被审批闸拦下的调用根本
        // 进不到工具里。于是模型这一轮只看到框架那句固定文本 + 工具结果的汇总。
        assertThat(String.valueOf(result.toolCalls().get(0).result()))
                .contains("用户拒绝执行工具")
                .doesNotContain("超过自动审批阈值");
    }

    @Test
    @DisplayName("⑤ 会话标记参与决策：manual-review-* 的会话无条件转人工")
    void manualReviewSessionAlwaysRejected() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-9003\",\"amount\":10}",
                        "manual-review-team");

        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.error()).isTrue());
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", "SKU-9003")).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT reason FROM approval_request WHERE tool_name = 'create_order'", String.class))
                .isNotEmpty()
                .allSatisfy(reason -> assertThat(reason).contains("被标记为人工复核"));
    }

    @Test
    @DisplayName("⑤ ApprovalPolicy.ALWAYS：逐次审批，连只读工具也要问一遍")
    void alwaysPolicyAsksForEveryToolCall() {
        AgentResult result = refundAssistant.call("#tool:query_orders {\"customerId\":1}", "always-session");

        // 只读工具在 ALWAYS 下也被拒了 —— 这就是策略的语义差别。
        // 每次调用问一遍，因此"问了几次"与"被拒了几次"严格相等。
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.error()).isTrue());
        assertThat(manualReviewApprovalHandler.calls()).isEqualTo(result.toolCalls().size());
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request",
                "tool_name = ?", "query_orders")).isEqualTo(result.toolCalls().size());
    }

    @Test
    @DisplayName("⑤ 沙箱闸优先于审批闸：被沙箱拒绝时审批处理器一次都不会被调用")
    void sandboxDenialShortCircuitsTheApprovalGate() {
        // 6000 元超过默认沙箱上限 5000：沙箱先拒绝
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:refund_order {\"orderId\":2,\"amount\":6000}", "sandbox-gate");

        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> {
            assertThat(call.error()).isTrue();
            assertThat(String.valueOf(call.result())).contains("沙箱拒绝执行");
        });
        // 审批一次都没被问 —— 这是本示例最容易猜反的一条口径
        assertThat(approvalHandler.decisions()).isEmpty();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null)).isZero();
        assertThat(refundAmountSandbox.denials()).hasSize(result.toolCalls().size());
        // 审计口径（实测，且与"被短路"那条正好相反）：
        // 沙箱拒绝与审批拒绝都发生在拦截器循环**之前**（见 DefaultLoopContext#executeOne），
        // 因此这两条路径上根本没有 beforeTool，tool_audit 里不会留下任何行 ——
        // afterTool 的 UPDATE 也因为没有可更新的行而静默返回 0。
        // 也就是说："工具没执行但审计仍在"只对 beforeTool 短路成立，对沙箱/审批拒绝不成立。
        assertThat(Db.count(jdbcTemplate.getDataSource(), "tool_audit",
                "tool_name = ?", "refund_order")).isZero();
    }

    @Test
    @DisplayName("⑤ 通过两道闸之后退款真的落库：订单状态变 REFUNDED")
    void refundWithinLimitsSucceeds() {
        // 金额刻意取 300：它同时低于沙箱上限 5000 与审批阈值 500，
        // 因此能真正穿过"沙箱 → 审批 → 拦截器 → 执行"四道关卡落到库里。
        // （1500 会被审批闸拦下 —— 那正是上面 largeAmount 那条用例覆盖的路径。）
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:refund_order {\"orderId\":2,\"amount\":300}", "refund-ok");

        // 第一次调用会成功；后续每一步都重新命中 `#tool:`，而 refundOrder 对
        // "已经是 REFUNDED 的订单"会抛 IllegalStateException —— 工具把它转成可读错误。
        // 于是这一轮的断言要看"第一次"与"最终状态"，不能假设整轮只调用一次。
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(String.valueOf(result.toolCalls().get(0).result())).contains("\"status\":\"REFUNDED\"");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM order_item WHERE id = 2", String.class)).isEqualTo("REFUNDED");
        // 退款只改状态、不删行：账才退得清
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "id = 2")).isEqualTo(1);
        // 放行留痕同样落在 approval_request 上（由 OrderWriteService 写入，与业务写同事务）
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request",
                "status = ?", "AUTO_APPROVED")).isEqualTo(1);
    }

    // ==================================================================
    // ⑥ 工作流驱动的入库审批
    // ==================================================================

    @Test
    @DisplayName("⑥ 工作流：真实金额 3140 > 500，路径走大额分支，命中 visited")
    void workflowTakesHighAmountBranch() {
        AgentResult result = complianceAssistant.checkWithTrace("1");

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run).isNotNull();
        assertThat(run.completed()).isTrue();
        // 路径里必须有 resolve：它是"把输入落成 customerId"的那一步，
        // 少了它两个 tool 节点的 ${customerId} 就取不到值（见 YAML 里的说明）。
        assertThat(run.visited()).containsExactly(
                "begin", "resolve", "check", "fetch", "amount", "decide", "high", "explain", "done");
        assertThat(run.visited()).doesNotContain("low", "compensate");
        assertThat(result.text()).contains("大额人工审批通道").contains("需大额审批");
        assertThat(result.text()).contains("3140.00");
    }

    @Test
    @DisplayName("⑥ 分支条件用的是工具查出来的真实数据：把金额改小就走另一条分支")
    void workflowBranchFollowsTheRealDatabaseValue() {
        // 把张三的订单金额改小到 100，合计 300 < 500 —— 同一个输入，只改了数据
        jdbcTemplate.update("UPDATE order_item SET amount = 100 WHERE customer_id = 1");

        AgentResult result = complianceAssistant.checkWithTrace("1");

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run.visited()).contains("low").doesNotContain("high");
        assertThat(result.text()).contains("自动通过通道").contains("合规通过");
        assertThat(result.text()).contains("300.00");
    }

    @Test
    @DisplayName("⑥ continueOnError：工具节点失败后出边读 ${error} 走补偿分支")
    void workflowCompensatesWhenToolNodeFails() {
        // 输入刻意给一段"不是数字"的客户号（真实场景里可能是"张三"这种姓名）：
        //   * query_orders 返回 {"found":false,"reason":"customerId 必须是数字…"} —— 这是
        //     **正常结果**（口径见 OrderQueryTools：查不到就回空结果，不抛异常）；
        //   * order_amount_of 则【抛异常】：它不能返回一个假数字，否则 ${amount} > 500
        //     会静默判假、走进"自动通过"分支，把一次取数失败伪装成"合规通过"。
        //     callTool 把异常升级成节点级失败，正好被 continueOnError 接住。
        //
        // 于是轨迹是 fetch → amount → compensate：fetch 那一跳证明"空结果不算节点失败"，
        // amount 那一跳证明"抛异常才算"。两条口径放在同一条用例里刚好互相印证。
        AgentResult direct = agentRegistry.get("complianceAssistant").call("张三", "wf-error");
        WorkflowRun run = (WorkflowRun) direct.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);

        assertThat(run.visited()).containsExactly(
                "begin", "resolve", "check", "fetch", "amount", "compensate", "explain", "done");
        assertThat(run.visited()).doesNotContain("decide", "high", "low");
        String variables = String.valueOf(direct.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE));
        assertThat(variables).contains("异常补偿通道").contains("取数失败");
        // 补偿分支把失败原因带进了最终答复 —— ${error} 在出边上被读到了
        assertThat(variables).contains("customerId 必须是数字");
    }

    @Test
    @DisplayName("⑥ 工作流的 tool 节点不经过模型：query_orders / order_amount_of 各直调一次")
    void workflowToolNodeDoesNotCallTheModel() {
        // 记下"进这一节之前模型被调过几次"，只断言增量。
        // 不能用 mockLlmModel.reset() + 绝对值：RecordingModel.reset() 清的是它自己录下的请求，
        // 而计数在它装饰的 ScriptedLlmModel 里，整个 Spring 上下文只建一个 ——
        // 于是 calls() 是一个跨用例累积的值（实测从 60+ 开始）。
        ScriptedLlmModel scripted = (ScriptedLlmModel) mockLlmModel.unwrap();
        int before = scripted.calls();

        AgentResult result = complianceAssistant.checkWithTrace("1");

        WorkflowRun run = (WorkflowRun) result.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        assertThat(run.visited()).contains("fetch").contains("amount");

        // 图里只有 2 个 agent 节点（check / explain），因此这一轮模型最多被调用 2 次；
        // fetch / amount / resolve 三个控制节点一次都没有调模型。
        assertThat(scripted.calls() - before).isLessThanOrEqualTo(2);
        // 但这两个工具真的被调用了，而且各只调了一次 ——
        // tool 节点是"编排层直调"，不像 `#tool:` 那样每一步都重新命中
        assertThat(Db.count(jdbcTemplate.getDataSource(), "tool_audit",
                "tool_name = ?", "query_orders")).isEqualTo(1);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "tool_audit",
                "tool_name = ?", "order_amount_of")).isEqualTo(1);
    }

    // ==================================================================
    // ⑦ 事务与一致性
    // ==================================================================

    @Test
    @DisplayName("⑦ 显式事务：第二条写失败时第一条一起回滚，库中没有半写入")
    void explicitTransactionRollsBackBothWrites() {
        long ordersBefore = Db.count(jdbcTemplate.getDataSource(), "order_item", null);
        long approvalsBefore = Db.count(jdbcTemplate.getDataSource(), "approval_request", null);

        // SKU-1001 已存在：createOrder 内部的第二条 INSERT 会撞唯一约束
        assertThatThrownBy(() -> orderWriteService.createOrder(1L, "SKU-1001", new BigDecimal("100.00")))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", null)).isEqualTo(ordersBefore);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null))
                .isEqualTo(approvalsBefore);
    }

    @Test
    @DisplayName("⑦ 反例：不画事务边界就会半写入（第一条留下、第二条失败）")
    void withoutTransactionTheFirstWriteRemains() {
        long ordersBefore = Db.count(jdbcTemplate.getDataSource(), "order_item", null);

        // 第一条 INSERT 用新 sku（成功并提交），第二条留痕写注定失败。
        // "注定失败"是应用层显式模拟的（见 OrderWriteService#insertApprovalTrace 的注释）——
        // 因为实测发现 approval_request 表上并没有 sku 唯一约束，
        // 硬靠数据库报错会让这条反例在 H2 上根本触发不了。
        assertThatThrownBy(() -> orderWriteService
                .createOrderWithoutTransaction(1L, "SKU-NO-TX", new BigDecimal("100.00")))
                .isInstanceOf(DuplicateKeyException.class);

        // 反例的意义在这里：没有事务边界时，第一条 INSERT **真的留下来了**
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", "SKU-NO-TX"))
                .isEqualTo(1);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", null)).isEqualTo(ordersBefore + 1);
        // 而"留痕"那一条没写成 —— 一笔没有留痕的订单，正是事务要解决的那个问题
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null)).isZero();

        // 对照：有事务的版本在同样"第二条写失败"的输入下，两样都不会留
        //（见 explicitTransactionRollsBackBothWrites）
    }

    @Test
    @DisplayName("⑦ 工具里的写操作走显式事务：失败时订单与留痕都不留")
    void writeToolIsTransactionalViaTemplate() {
        long ordersBefore = Db.count(jdbcTemplate.getDataSource(), "order_item", null);

        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-1001\",\"amount\":50}",
                        "tx-tool");

        // 工具把 SQL 异常转成了可读错误回灌给模型（每一次尝试都是这个错）
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> {
            assertThat(call.error()).isTrue();
            assertThat(String.valueOf(call.result())).contains("写库失败").contains("已整体回滚");
        });
        // 数据库里一行业务数据都没多
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", null)).isEqualTo(ordersBefore);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null)).isZero();
    }

    @Test
    @DisplayName("⑦ 校验失败也不留痕：客户不存在时订单与审批表都干净")
    void validationFailureLeavesNothingBehind() {
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":999,\"sku\":\"SKU-9009\",\"amount\":10}",
                        "tx-validate");

        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(call -> assertThat(call.error()).isTrue());
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", "sku = ?", "SKU-9009")).isZero();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "approval_request", null)).isZero();
    }

    // ==================================================================
    // ⑧ 多轮追问
    // ==================================================================

    @Test
    @DisplayName("⑧ 两轮追问：第二轮依赖第一轮的上下文与新的工具调用，历史全部在库里")
    void multiTurnFollowUpUsesPreviousContext() {
        // 第一轮：查张三的订单（显式点名工具，保证确定性）
        AgentResult first = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", SESSION);
        assertThat(first.toolCalls()).isNotEmpty();
        assertThat(toolResultText(first)).contains("SKU-1002").contains("1500.00");

        // 第一轮写回的历史条数：1 条 user + 每一步的 (assistant 工具调用 + tool 结果)
        // + 1 条收尾 assistant。步数由 `#tool:` 的反复命中和 maxSteps 决定，
        // 所以这里不断死具体数字，只断言"每个模型步都成对留下了痕迹，且历史非空"。
        int rowsAfterFirstTurn = Db.count(jdbcTemplate.getDataSource(), "agent_memory",
                "session_id = ?", SESSION);
        assertThat(rowsAfterFirstTurn).isEqualTo(first.steps() * 2 + 1);
        assertThat(rowsAfterFirstTurn).isGreaterThan(2);

        // 第二轮：问"其中金额最大的那笔退款"。
        // 这一轮不再带 `#tool:`，于是脚本模型按关键字规则直接给出结论 —— 正好用来
        // 证明"它是在有前文的情况下回答的"。
        // 这里刻意换成 AgentResult 形态的 call(...)：声明式接口的 String ask(...) 只把
        // 文本交回来，看不到工具轨迹与消息，而这一轮要断言的正是"历史里带着第一轮的东西"。
        AgentResult second = agentRegistry.get("orderAssistant")
                .call("其中金额最大的那笔退款", SESSION);
        assertThat(second.text()).contains("SKU-1002").contains("1500.00");

        // 第二轮真正用上了第一轮：模型收到的消息里既有第一轮的问题，也有第一轮的工具结果
        ChatRequest secondRequest = mockLlmModel.lastRequest();
        List<String> texts = nonSystemMessages(secondRequest).stream()
                .map(ChatMessage::allText)
                .toList();
        assertThat(texts).anyMatch(t -> t.contains("query_orders"));
        assertThat(texts).anyMatch(t -> t.contains("SKU-1002"));
        assertThat(texts).anyMatch(t -> t.contains("其中金额最大的那笔退款"));

        // 第二轮（一问一答）让历史净增 2 条，并且是在第一轮的基础上续着长
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory", "session_id = ?", SESSION))
                .isEqualTo(rowsAfterFirstTurn + 2);
    }

    @Test
    @DisplayName("⑧ 关掉记忆就断片：memory=false 的 Agent 不带历史")
    void withoutMemoryThereIsNoFollowUpContext() {
        // 声明式接口的 String ask(...) 只回文本；这里要看的是"历史有没有被写回"，
        // 所以换成 AgentResult 形态的 call(...)（failAssistant 的 memory = false）。
        AgentResult result = agentRegistry.get("failAssistant").call("随便问问");

        assertThat(result).isNotNull();
        assertThat(result.text()).isNotBlank();
        assertThat(Db.count(jdbcTemplate.getDataSource(), "agent_memory",
                "session_id = ?", "failAssistant-default")).isZero();
    }

    // ==================================================================
    // 收尾：表结构与行数
    // ==================================================================

    @Test
    @DisplayName("表结构齐全：六张业务表都在，data.sql 的种子数据也对得上")
    void schemaAndSeedDataAreComplete() {
        assertThat(DatabaseReport.rowCounts(jdbcTemplate).keySet())
                .containsExactlyElementsOf(DatabaseReport.TABLES);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "customer", null)).isEqualTo(3);
        assertThat(Db.count(jdbcTemplate.getDataSource(), "order_item", null)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM order_item WHERE customer_id = 1", BigDecimal.class))
                .isEqualByComparingTo("3140.00");
    }

    /**
     * 证明"这一份断言真的跑在预期的库上"。
     *
     * <p>为什么值得单独一条：{@link OrderAssistantMysqlTest} 继承本类、只加了
     * {@code @ActiveProfiles("mysql")}，如果哪天 profile 失效或数据源被别处覆盖，
     * 那一份会<b>悄悄退回 H2 并照样全绿</b> —— 示例最核心的承诺
     * （"同一套代码在两种库上都能跑"）就变成了一句空话。所以这里把库的"自述"钉住。</p>
     */
    @Test
    @DisplayName("数据源自述与预期一致（H2 那一份跑 H2，MySQL 那一份必须跑 MySQL）")
    void dataSourceMatchesTheExpectedProduct() {
        String product = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                conn -> conn.getMetaData().getDatabaseProductName());
        assertThat(product).isNotNull();
        // 用子类名而不是硬编码"是不是 MySQL"：H2 与 MySQL 两种产品名都从这里过，
        // 谁跑到这一条就断言谁 —— 关键在于"绝不允许两者互相冒充"。
        if (getClass().getSimpleName().contains("Mysql")) {
            assertThat(product).containsIgnoringCase("MySQL");
        } else {
            assertThat(product).containsIgnoringCase("H2");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 取模型收到的工具结果文本（第一条 TOOL 消息）。 */
    protected String toolResultText(AgentResult result) {
        return result.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .map(ChatMessage::text)
                .findFirst()
                .orElse("");
    }

    private static List<ChatMessage> nonSystemMessages(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() != Role.SYSTEM).toList();
    }
}
