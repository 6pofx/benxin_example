package com.benxin.exp.database.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.database.agent.ComplianceAssistant;
import com.benxin.exp.database.agent.FailAssistant;
import com.benxin.exp.database.agent.OrderAssistant;
import com.benxin.exp.database.approval.DbApprovalHandler;
import com.benxin.exp.database.audit.ToolAuditInterceptor;
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
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.exp.common.model.RecordingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 启动即跑的端到端演示：把八个功能点各跑一遍，每一节结束都打印六张表的行数。
 *
 * <p>为什么演示里要反复打印行数？因为这个示例的全部主张都可以归结为一句话 ——
 * <b>"这些东西是真的落到库里了，不是内存里演了一遍"</b>。行数是唯一能用一行文本说清
 * 这件事的证据，而"哪张表涨了几行"又恰好能把"这一步动了什么"钉死。</p>
 *
 * <p>它与测试的分工：演示负责<b>看得见</b>（打印模型看到了什么、哪道闸拦下了谁、
 * 表里现在有几行），测试负责<b>可回归</b>（同一批结论写成断言）。
 * 所以这里的每一节都刻意打印"证据"，而不是把结论复述一遍。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseDemo implements ApplicationRunner {

    /** 演示默认会话：声明式 Agent 不传 sessionId 时都落在这里。 */
    private static final String SESSION = "orderAssistant-default";

    private final AgentRegistry agentRegistry;
    private final RecordingModel model;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final JdbcMemoryStore memoryStore;
    private final DbApprovalHandler approvalHandler;
    private final ToolAuditInterceptor toolAudit;
    private final RefundAmountSandbox sandbox;
    private final OrderWriteService writeService;

    /**
     * 构造器里刻意<b>不</b>直接注入 {@code OrderAssistant} / {@code ComplianceAssistant} 接口，
     * 而是注入 {@link AgentRegistry} 再按名字取。
     *
     * <p>原因是本示例撞到的一个时序细节：{@code @LlmAgent} 接口一旦被注入，
     * 就会在容器启动期间立刻解析成 Agent，而那一刻 {@code LlmToolCatalog} 可能还没
     * 处理完"只读工具"那个 bean —— 日志里会出现一行
     * {@code @LlmAgent(tools = OrderQueryTools.class) 未匹配到任何工具} 的 WARN
     * （最终装配结果是对的，但读者看到 WARN 会以为出了错）。
     * 走注册表按名取就把解析推迟到"真正要用的时候"，那行 WARN 自然消失。</p>
     */
    public DatabaseDemo(AgentRegistry agentRegistry,
                        RecordingModel mockLlmModel,
                        JdbcTemplate jdbc,
                        DataSource dataSource,
                        JdbcMemoryStore jdbcMemoryStore,
                        DbApprovalHandler approvalHandler,
                        ToolAuditInterceptor toolAuditInterceptor,
                        RefundAmountSandbox refundAmountSandbox,
                        OrderWriteService orderWriteService) {
        this.agentRegistry = agentRegistry;
        this.model = mockLlmModel;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.memoryStore = jdbcMemoryStore;
        this.approvalHandler = approvalHandler;
        this.toolAudit = toolAuditInterceptor;
        this.sandbox = refundAmountSandbox;
        this.writeService = orderWriteService;
    }

    private Agent orderAssistantAgent() {
        return agentRegistry.get("orderAssistant");
    }

    private Agent complianceAgent() {
        return agentRegistry.get("complianceAssistant");
    }

    private Agent failAssistantAgent() {
        return agentRegistry.get("failAssistant");
    }

    private Agent refundAssistantAgent() {
        return agentRegistry.get("refundAssistant");
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 10 · 数据库 —— 记忆落库 / 工具查库 / 运行审计 / 工具审计 / 写库审批 / 工作流");
        demo.banner("数据库: %s", DatabaseReport.describe(dataSource));
        demo.banner("模型: %s（离线脚本化，不联网、不消耗 token）", model.name());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("六张业务表: %s", DatabaseReport.TABLES);
        demo.info("下面每一节结束后都会打印一次行数 —— 那是「数据真的落库了」最直接的证据。");

        demo.guard("① JDBC 记忆：多轮历史落库 / 跨重启续接 / clear 删行 / 会话隔离", () -> memory(demo));
        demo.guard("② 工具查库：模型决策 → SQL 查询 → 结果回灌 → 答案随之变化", () -> queryTools(demo));
        demo.guard("③ 运行审计：AgentListener 落 agent_run（成功 OK / 失败补偿写 FAILED）", () -> runAudit(demo));
        demo.guard("④ 工具审计：AgentInterceptor 落 tool_audit（含被短路的那种）", () -> toolAudit(demo));
        demo.guard("⑤ 写库审批：金额阈值放行 / 拒绝转人工 / 沙箱闸优先", () -> approval(demo));
        demo.guard("⑥ 工作流驱动的入库审批：真实数据做分支 + continueOnError 补偿", () -> workflow(demo));
        demo.guard("⑦ 事务一致性：要么都成、要么都不成（含反例）", () -> transaction(demo));
        demo.guard("⑧ 多轮追问：第二轮依赖第一轮的实体与记忆", () -> followUp(demo));

        demo.section("收尾：六张表的最终行数");
        printRowCounts(demo);
        demo.done();
    }

    // ==================================================================
    // ① JDBC 记忆
    // ==================================================================

    private void memory(Demo demo) {
        demo.section("① JDBC MemoryStore：会话历史落到 agent_memory 表");
        resetEverything();

        demo.info("容器里的 MemoryStore 实现 = %s（@Primary 顶替了 starter 的 InMemoryMemoryStore）",
                memoryStore.getClass().getSimpleName());
        demo.info("初始 exists(\"demo-memory\") = %s", memoryStore.exists("demo-memory"));

        orderAssistantAgent().call("你好", "demo-memory");
        orderAssistantAgent().call("介绍一下你自己", "demo-memory");

        demo.ok("两轮问完之后 agent_memory 里 demo-memory 会话的行数 = %d",
                count("agent_memory", "session_id = ?", "demo-memory"));
        demo.info("  逐行内容（seq / role / payload 前 90 字）：");
        jdbc.queryForList("SELECT seq, role, payload FROM agent_memory WHERE session_id = ? ORDER BY seq",
                        "demo-memory")
                .forEach(row -> demo.info("    seq=%s role=%-9s %s",
                        row.get("seq"), row.get("role"), oneLine(String.valueOf(row.get("payload")), 90)));
        demo.info("  注意 payload 是一份可读的 JSON 快照 —— ChatMessage 不能直接被 Jackson 序列化，"
                + "示例自带 ChatMessageCodec 做往返编解码。");

        // 会话隔离：换一个 sessionId，历史互不干扰
        orderAssistantAgent().call("你好", "demo-other");
        demo.ok("另开一个会话 demo-other 之后：demo-memory=%d 行，demo-other=%d 行（互不干扰）",
                count("agent_memory", "session_id = ?", "demo-memory"),
                count("agent_memory", "session_id = ?", "demo-other"));

        // 跨"新 ApplicationContext"续接：直接 new 一个 MemoryStore，读同一个库
        JdbcMemoryStore brandNew = new JdbcMemoryStore(jdbc);
        List<ChatMessage> recovered = brandNew.load("demo-memory");
        demo.ok("换一个全新的 MemoryStore 实例读同一个库，读回 %d 条历史；第一条 = 「%s」",
                recovered.size(), oneLine(recovered.get(0).text(), 30));
        demo.info("  —— 这就是「进程重启后记忆还在」：库在，记忆就在（库文件在 ./target/ 下）。");

        memoryStore.clear("demo-other");
        demo.ok("clear(\"demo-other\") 之后：exists=%s，demo-other=%d 行，demo-memory 仍有 %d 行",
                memoryStore.exists("demo-other"),
                count("agent_memory", "session_id = ?", "demo-other"),
                count("agent_memory", "session_id = ?", "demo-memory"));

        printRowCounts(demo);
    }

    // ==================================================================
    // ② 工具查库
    // ==================================================================

    private void queryTools(Demo demo) {
        demo.section("② 工具查库：模型决策 → JdbcTemplate 查询 → 结果回灌 → 收尾");
        resetEverything();

        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", "demo-query");
        demo.info("orderAssistant 的工具表 = %s", agentRegistry.get("orderAssistant").tools().names());
        demo.ok("工具被调用了 %d 次，第一次是 %s（error=%s）",
                result.toolCalls().size(), result.toolCalls().get(0).name(),
                result.toolCalls().get(0).error());
        demo.info("  工具返回给模型的原文（前 240 字）：");
        demo.info("    %s", oneLine(String.valueOf(result.toolCalls().get(0).result()), 240));
        ChatRequest request = model.lastRequest();
        demo.ok("模型最后一轮收到的消息里有 %d 条 TOOL 角色的消息 —— 真实数据确实回灌了",
                request.messages().stream().filter(m -> m.role() == Role.TOOL).count());
        demo.warn("口径：`#tool:<名字>` 在脚本模型里每一步都会重新命中，"
                + "所以它会一直调同一个工具直到步数预算耗尽（steps=%d，maxStepsReached=%s，"
                + "最终文本为空）。这是 exp-common 脚本模型的既定行为，示例不改它 —— "
                + "想知道答案就看工具结果，别等模型收尾。", result.steps(), result.maxStepsReached());

        // 改一行数据，答案随之变化
        demo.info("现在把张三那笔待支付订单的金额从 260.00 改成 999.99，再问一次：");
        jdbc.update("UPDATE order_item SET amount = 999.99 WHERE sku = 'SKU-1003'");
        AgentResult after = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", "demo-query");
        String text = String.valueOf(after.toolCalls().get(0).result());
        demo.ok("新答案里出现 999.99 = %s，旧的 260.00 已消失 = %s",
                text.contains("999.99"), !text.contains("260.00"));
        demo.ok("只读工具没有产生任何 approval_request 行 = %s",
                count("approval_request", null) == 0);

        printRowCounts(demo);
    }

    // ==================================================================
    // ③ 运行审计
    // ==================================================================

    private void runAudit(Demo demo) {
        demo.section("③ 运行审计：自定义 AgentListener 落 agent_run");
        resetEverything();

        AgentResult ok = agentRegistry.get("orderAssistant").call("你好", "demo-ok");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM agent_run WHERE session_id = ?", "demo-ok");
        demo.ok("成功路径落了一行：agent=%s status=%s steps=%s tokens=%s/%s loop=%s",
                row.get("agent_name"), row.get("status"), row.get("steps"),
                row.get("input_tokens"), row.get("output_tokens"), row.get("loop_name"));
        demo.info("  输出摘要 = %s", oneLine(String.valueOf(row.get("output_summary")), 80));
        demo.info("  与 AgentResult 对得上：steps=%d finishReason=%s durationMs=%d",
                ok.steps(), ok.finishReason(), ok.durationMillis());

        // 失败路径
        demo.info("接着制造一次真实的失败（模型在 chat() 里抛 ModelException）：");
        try {
            failAssistantAgent().call("请触发失败");
            demo.warn("没有抛异常？这不符合预期");
        } catch (ModelException e) {
            demo.ok("调用如预期失败：%s", oneLine(e.getMessage(), 60));
        }
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT * FROM agent_run WHERE agent_name = 'failAssistant'");
        demo.ok("失败路径只有一行，且是监听器【补偿】写入的：status=%s finish_reason=%s",
                failed.get("status"), failed.get("finish_reason"));
        demo.info("  error_message = %s", oneLine(String.valueOf(failed.get("error_message")), 100));
        demo.warn("口径（容易猜错）：框架在失败时只调 onError(Throwable)，"
                + "不调 onAgentEnd、也不给 AgentResult —— 所以 steps/usage/finishReason 都拿不到，"
                + "这行 FAILED 是监听器自己在 onAgentStart 时留了草稿才补出来的。");

        printRowCounts(demo);
    }

    // ==================================================================
    // ④ 工具审计
    // ==================================================================

    private void toolAudit(Demo demo) {
        demo.section("④ 工具审计：自定义 AgentInterceptor 落 tool_audit（含被短路的那种）");
        resetEverything();

        AgentResult normal = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", "demo-tool-ok");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM tool_audit WHERE session_id = ? ORDER BY id LIMIT 1", "demo-tool-ok");
        demo.ok("正常调用留痕：tool=%s short_circuit=%s error=%s",
                row.get("tool_name"), row.get("short_circuit"), row.get("error"));
        demo.info("  参数 = %s", row.get("arguments"));
        demo.info("  结果摘要 = %s", oneLine(String.valueOf(row.get("result_digest")), 100));
        demo.info("  审计行数 %d = 工具调用次数 %d（每一步都重新命中 `#tool:`，所以不止一行）",
                count("tool_audit", "session_id = ?", "demo-tool-ok"), normal.toolCalls().size());

        demo.info("被短路的调用（sku 以 %s 开头）——",
                WriteToolShortCircuitInterceptor.BLOCKED_SKU_PREFIX);
        String sku = WriteToolShortCircuitInterceptor.BLOCKED_SKU_PREFIX + "-0001";
        AgentResult blocked = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"" + sku + "\",\"amount\":100}",
                        "demo-tool-sc");
        demo.ok("工具【没有】执行：order_item 里 %s 的行数 = %d", sku, count("order_item", "sku = ?", sku));
        Map<String, Object> sc = jdbc.queryForMap(
                "SELECT * FROM tool_audit WHERE session_id = ? ORDER BY id LIMIT 1", "demo-tool-sc");
        demo.ok("审计仍然留痕：tool=%s short_circuit=%s", sc.get("tool_name"), sc.get("short_circuit"));
        demo.info("  被短路了 %d 次（每一步都重新命中，于是每一步都被短路一次）",
                toolAudit.shortCircuitCount("create_order"));
        demo.info("  模型收到的是短路结果：%s", oneLine(String.valueOf(blocked.toolCalls().get(0).result()), 90));
        demo.warn("口径：短路的\"工具没执行但审计仍在\"成立，前提是审计拦截器排在短路拦截器【之前】"
                + "（本示例 audit.order()=0 < shortCircuit.order()=500）；"
                + "反过来写的话，被短路的调用在 tool_audit 里会一行都没有。");

        printRowCounts(demo);
    }

    // ==================================================================
    // ⑤ 写库审批
    // ==================================================================

    private void approval(Demo demo) {
        demo.section("⑤ 写库工具的审批：requiresApproval() + 自定义 ApprovalHandler");
        resetEverything();

        demo.info("审批阈值 = %s 元（exp.database.approval-threshold）；沙箱 = %s",
                approvalHandler.threshold(), sandbox.describe());
        demo.info("两道闸的顺序：沙箱 → 审批 → 拦截器 → 真正执行。沙箱拒绝时审批一次都不会被问。");

        // 结局一：小额自动放行
        AgentResult small = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-9001\",\"amount\":300}",
                        "demo-approval-ok");
        demo.ok("【结局 1·放行】order_item(SKU-9001) = %d 行；工具第一次调用 error=%s",
                count("order_item", "sku = ?", "SKU-9001"), small.toolCalls().get(0).error());
        demo.info("  approval_request 里的放行留痕（注意 decided_by）：%s",
                jdbc.queryForList("SELECT tool_name, decided_by, status FROM approval_request"));
        demo.warn("口径：这行 status=AUTO_APPROVED 不是审批处理器写的 —— 它在\"批准\"这条路径上只记内存；"
                + "落库的是 OrderWriteService，它把\"订单行 + 放行留痕\"绑在同一个事务里。");

        // 结局二：大额被拒、转人工
        AgentResult big = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-9002\",\"amount\":900}",
                        "demo-approval-manual");
        demo.ok("【结局 2·拒绝】order_item(SKU-9002) = %d 行；工具报错 = %s",
                count("order_item", "sku = ?", "SKU-9002"), big.toolCalls().get(0).error());
        demo.info("  待人工处理的单子：%s",
                jdbc.queryForList("SELECT tool_name, status, reason FROM approval_request"
                        + " WHERE status = 'PENDING_MANUAL' LIMIT 1"));
        demo.warn("口径：框架回灌给模型的错误文本是固定的\"用户拒绝执行工具 [create_order]\"，"
                + "**不含**审批处理器写下的详细理由；而\"补一句可读原因\"那段代码在工具真正执行时才跑得到，"
                + "被审批闸拦下的调用根本进不到工具里。");

        // 结局三：沙箱先拒绝（审批一次都不问）
        approvalHandler.reset();
        resetEverything();
        AgentResult denied = agentRegistry.get("orderAssistant")
                .call("#tool:refund_order {\"orderId\":2,\"amount\":6000}", "demo-sandbox");
        demo.ok("【结局 3·沙箱拒绝】工具报错 = %s，内容是「%s」",
                denied.toolCalls().get(0).error(), oneLine(String.valueOf(denied.toolCalls().get(0).result()), 60));
        demo.ok("  审批处理器被问了几次 = %d（沙箱拒绝不可被审批覆盖）", approvalHandler.decisions().size());
        demo.ok("  approval_request 行数 = %d（审批没参与，自然没有待办）",
                count("approval_request", null));
        demo.warn("口径：沙箱拒绝与审批拒绝都发生在拦截器循环之前，"
                + "因此这两条路径在 tool_audit 里【不会】留痕（沙箱拒绝时 tool_audit 行数 = %d）。",
                count("tool_audit", null));

        printRowCounts(demo);
    }

    // ==================================================================
    // ⑥ 工作流驱动的入库审批
    // ==================================================================

    private void workflow(Demo demo) {
        demo.section("⑥ 工作流驱动的入库审批：真实数据做分支 + continueOnError 补偿");
        resetEverything();

        demo.info("complianceAssistant 的 Loop = %s（由 llm.workflows.order-compliance 注册）",
                agentRegistry.get("complianceAssistant").loop().name());
        demo.info("工作流输入约定为【客户 id】：\"1\" 走大额/小额分支，\"张三\" 走补偿分支。");

        AgentResult high = complianceAgent().call("1", "demo-wf");
        WorkflowRun run = (WorkflowRun) high.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        demo.ok("张三（客户 1）真实总额 3140.00 > 阈值 500，访问路径 = %s", run.visited());
        demo.info("  最终答复（前 3 行）：");
        java.util.Arrays.stream(high.text().split("\\R")).limit(3)
                .forEach(line -> demo.info("    %s", oneLine(line, 100)));

        demo.info("现在把张三的订单金额全改成 100（合计 300 < 500），同一个输入再跑一次：");
        jdbc.update("UPDATE order_item SET amount = 100 WHERE customer_id = 1");
        AgentResult low = complianceAgent().call("1", "demo-wf");
        WorkflowRun lowRun = (WorkflowRun) low.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        demo.ok("分支跟着真实数据变了：路径 = %s", lowRun.visited());
        demo.info("  最终答复（前 3 行）：");
        java.util.Arrays.stream(low.text().split("\\R")).limit(3)
                .forEach(line -> demo.info("    %s", oneLine(line, 100)));

        demo.info("最后给一段不是数字的输入（\"张三\"），看补偿分支：");
        AgentResult bad = agentRegistry.get("complianceAssistant").call("张三", "demo-wf-error");
        WorkflowRun badRun = (WorkflowRun) bad.attributes().get(WorkflowLoop.RUN_ATTRIBUTE);
        demo.ok("continueOnError 生效，路径 = %s", badRun.visited());
        demo.info("  黑板上的失败原因被带进了答复：%s",
                oneLine(String.valueOf(bad.attributes().get(WorkflowLoop.VARIABLES_ATTRIBUTE)), 140));
        demo.warn("口径：tool 节点的参数只从黑板取值，没有默认值兜底 —— "
                + "黑板上没有的名字会被 Templates 原样保留成 \"${xxx}\"。"
                + "所以\"把输入翻译成参数\"必须是图里显式的一步（本图的 resolve 节点）。");

        printRowCounts(demo);
    }

    // ==================================================================
    // ⑦ 事务一致性
    // ==================================================================

    private void transaction(Demo demo) {
        demo.section("⑦ 事务一致性：订单 + 留痕要么都成、要么都不成");
        resetEverything();

        long orders = count("order_item", null);
        long approvals = count("approval_request", null);
        demo.info("起始：order_item=%d 行，approval_request=%d 行", orders, approvals);

        demo.info("先看【有事务】的版本：用已存在的 sku（SKU-1001）下单，第二条写必然失败——");
        try {
            writeService.createOrder(1L, "SKU-1001", new BigDecimal("100.00"));
            demo.warn("没有抛异常？这不符合预期");
        } catch (DuplicateKeyException e) {
            demo.ok("如预期失败：%s", oneLine(e.getMessage(), 70));
        }
        demo.ok("回滚得干干净净：order_item=%d 行（未变），approval_request=%d 行（未变）",
                count("order_item", null), count("approval_request", null));

        demo.info("再看【没有事务】的反例：第一条 INSERT 提交后第二条写失败——");
        try {
            writeService.createOrderWithoutTransaction(1L, "SKU-NO-TX", new BigDecimal("100.00"));
            demo.warn("没有抛异常？这不符合预期");
        } catch (DuplicateKeyException e) {
            demo.ok("如预期失败：%s", oneLine(e.getMessage(), 70));
        }
        demo.ok("半写入出现了：order_item=%d 行（多了 SKU-NO-TX 那一笔 = %s），approval_request=%d 行（没留痕）",
                count("order_item", null), count("order_item", "sku = ?", "SKU-NO-TX") == 1,
                count("approval_request", null));
        demo.info("  —— 一笔没有留痕的订单，正是事务要解决的那个问题。");

        demo.info("最后看【工具链路】：工具里的多语句写操作用 TransactionTemplate 画边界——");
        resetEverything();
        AgentResult result = agentRegistry.get("orderAssistant")
                .call("#tool:create_order {\"customerId\":1,\"sku\":\"SKU-1001\",\"amount\":50}",
                        "demo-tx-tool");
        demo.ok("工具把 SQL 异常转成可读错误回灌：%s",
                oneLine(String.valueOf(result.toolCalls().get(0).result()), 80));
        demo.ok("库里的业务数据一行都没多：order_item=%d 行，approval_request=%d 行",
                count("order_item", null), count("approval_request", null));
        demo.warn("口径：工具是在框架的工作线程里执行的（DefaultAgent 的 cached thread pool），"
                + "而 Spring 的事务上下文是 ThreadLocal —— 所以工具方法上的 @Transactional 指望不上，"
                + "多语句写操作请用 TransactionTemplate 把边界画在方法体内部。");

        printRowCounts(demo);
    }

    // ==================================================================
    // ⑧ 多轮追问
    // ==================================================================

    private void followUp(Demo demo) {
        demo.section("⑧ 多轮追问：第二轮依赖第一轮的实体与记忆");
        resetEverything();

        AgentResult first = agentRegistry.get("orderAssistant")
                .call("#tool:query_orders {\"customerId\":1}", SESSION);
        demo.ok("第一轮：查张三的订单 → 工具第一次返回 %s",
                oneLine(String.valueOf(first.toolCalls().get(0).result()), 90));
        demo.info("  第一轮结束后 agent_memory(%s) = %d 行（1 条 user + 每一步的 assistant/tool 配对 + 1 条收尾）",
                SESSION, count("agent_memory", "session_id = ?", SESSION));

        AgentResult second = agentRegistry.get("orderAssistant")
                .call("其中金额最大的那笔退款", SESSION);
        demo.ok("第二轮：问\"其中金额最大的那笔退款\" → 回答 = %s", oneLine(second.text(), 90));

        ChatRequest request = model.lastRequest();
        List<String> texts = request.messages().stream()
                .filter(m -> m.role() != Role.SYSTEM)
                .map(ChatMessage::allText)
                .toList();
        demo.ok("模型这一轮收到的历史里：带 query_orders = %s，带 SKU-1002 = %s，带第二轮问题 = %s",
                texts.stream().anyMatch(t -> t.contains("query_orders")),
                texts.stream().anyMatch(t -> t.contains("SKU-1002")),
                texts.stream().anyMatch(t -> t.contains("其中金额最大的那笔退款")));
        demo.ok("两轮历史都在库里：agent_memory(%s) = %d 行（第二轮净增 2 条）",
                SESSION, count("agent_memory", "session_id = ?", SESSION));
        demo.info("  —— 关键在\"第二轮的提示词里带着第一轮的工具结果\"：续接靠的是"
                + " MemoryStore 在 call() 开始时 load 出来的历史，而这份历史存在数据库里。");

        printRowCounts(demo);
    }

    // ==================================================================

    /** 把六张表的行数打成一行 —— 演示里反复出现的那句"数据真的落库了"的证据。 */
    private void printRowCounts(Demo demo) {
        demo.info("表行数 → %s", DatabaseReport.formatRowCounts(jdbc));
    }

    private long count(String table, String where, Object... args) {
        return com.benxin.exp.common.db.Db.count(dataSource, table, where, args);
    }

    /**
     * 每一节之前把库恢复到初始状态。
     *
     * <p>为什么演示也需要这个：不重置的话，打印出来的行数是"累积值"，
     * 读者没法从数字上判断"这一节到底写了几行" —— 而那份数字正是演示最有说服力的部分。</p>
     */
    private void resetEverything() {
        DatabaseReset.all(jdbc);
        model.reset();

        // 清空所有 Agent 的历史。走 names() + find() 而不是 resolved()：
        // @LlmAgent 接口在注册表里是惰性条目，resolved() 只列出已经被解析过的那些 ——
        // 直接用它遍历，一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(SESSION);
            agent.clearHistory("demo-memory");
            agent.clearHistory("demo-other");
        }));
    }

    private static String oneLine(String text, int limit) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "...";
    }

    /** 便于演示打印"脚本模型到底被调了几次"。 */
    @SuppressWarnings("unused")
    private ScriptedLlmModel scripted() {
        return (ScriptedLlmModel) model.unwrap();
    }
}
