package com.benxin.exp.database.config;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.database.approval.DbApprovalHandler;
import com.benxin.exp.database.audit.AgentRunAuditListener;
import com.benxin.exp.database.audit.ToolAuditInterceptor;
import com.benxin.exp.database.db.OrderRepository;
import com.benxin.exp.database.guard.ManualReviewApprovalHandler;
import com.benxin.exp.database.guard.RefundAmountSandbox;
import com.benxin.exp.database.guard.WriteToolShortCircuitInterceptor;
import com.benxin.exp.database.memory.JdbcMemoryStore;
import com.benxin.exp.database.tool.OrderQueryTools;
import com.benxin.exp.database.tool.OrderWriteService;
import com.benxin.exp.database.tool.OrderWriteTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * 全部可插拔组件的装配。
 *
 * <p>这个类是本示例的"接线板"：本心的每一个 {@code @ConditionalOnMissingBean} 默认实现，
 * 都在这里被一个"真的会落库"的版本顶替掉。一共六处，正好对应示例要演示的六个切面：</p>
 *
 * <table border="1">
 *   <caption>被顶替的默认实现</caption>
 *   <tr><th>切面</th><th>本示例提供的 bean</th><th>落哪张表</th></tr>
 *   <tr><td>记忆</td><td>{@link JdbcMemoryStore}</td><td>agent_memory</td></tr>
 *   <tr><td>运行审计</td><td>{@link AgentRunAuditListener}</td><td>agent_run</td></tr>
 *   <tr><td>工具审计</td><td>{@link ToolAuditInterceptor}</td><td>tool_audit</td></tr>
 *   <tr><td>写操作审批</td><td>{@link DbApprovalHandler}</td><td>approval_request</td></tr>
 *   <tr><td>风控沙箱</td><td>{@link RefundAmountSandbox}</td><td>（拒绝留痕在内存）</td></tr>
 *   <tr><td>模型</td><td>{@link RecordingModel}（离线脚本化）</td><td>（不落库）</td></tr>
 * </table>
 *
 * <p>注意 {@link JdbcMemoryStore} 是 {@code @Primary}：本心 starter 里已经有一个
 * {@code @ConditionalOnMissingBean} 的 {@code InMemoryMemoryStore}，谁先谁后取决于
 * bean 的注册顺序，而 {@code @ConditionalOnMissingBean} 并不保证"用户 bean 一定先到"。
 * 加 {@code @Primary} 是"两害相权"的稳妥做法：即使两个都在，注入点也会拿到 JDBC 这个。</p>
 *
 * <p>本类还手写装配了一个<b>不走注解路径</b>的 Agent：{@code refundAssistant}。
 * 原因见 {@link #refundAssistant} 的注释 —— 这是示例发现的插件局限之一。</p>
 */
@Configuration
public class DatabaseConfiguration {

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    /**
     * 离线脚本化模型：把"模型决策"变成可复现的规则，于是整个示例零 token 也能跑通闭环。
     *
     * <p>规则顺序即优先级，注册顺序刻意"具体在前、笼统在后"：</p>
     * <ol>
     *   <li>{@code #tool:名字 {JSON}} —— 显式点名工具，用来做确定性演示
     *       （本心脚本模型的最优先规则，这里不需要额外注册）；</li>
     *   <li>"退掉其中金额最大的" —— 多轮追问的第二轮，必须排在"订单"之前，
     *       否则会被更笼统的那条吃掉；</li>
     *   <li>各种业务关键词 → 固定回答，让输出可断言；</li>
     *   <li>{@code toolFor(...)} 关键词 → 工具名片段，让"不带 #tool: 的自然提问"
     *       也能走到正确的工具上。</li>
     * </ol>
     *
     * <p>外面套一层 {@link RecordingModel} 不是摆设：演示要打印"模型这一轮到底看到了什么"
     * （尤其是第二轮追问时历史里有没有带上第一轮的工具结果）。</p>
     */
    @Bean
    public RecordingModel mockLlmModel() {
        ScriptedLlmModel scripted = ScriptedLlmModel.builder("mock")
                // ---- 多轮追问的第二轮：必须排在"订单"之前 ----
                .rule("金额最大", "已按上一轮查到的订单列表定位到金额最大的那笔（SKU-1002 / 1500.00 元），"
                        + "并据此发起退款。这条回答依赖上一轮的对话历史。")
                .rule("最大", "已按上一轮查到的订单列表定位到金额最大的那笔（SKU-1002 / 1500.00 元）。")
                // ---- 业务关键词 → 可断言的固定回答 ----
                .rule("订单", "张三共有 3 笔订单，合计 3140.00 元；其中金额最大的一笔是 SKU-1002 / 1500.00 元。")
                .rule("统计", "按状态聚合：PAID 2 笔 / 1640.00 元，PENDING 1 笔 / 260.00 元。")
                .rule("下单", "已提交下单请求（写操作，需先过审批闸）。")
                .rule("退款", "已提交退款请求（写操作，需先过审批闸；超过风控上限会被沙箱直接拒绝）。")
                // ---- 工作流里的 agent 节点：systemText 含"审批"，用 systemRule 精确命中 ----
                .systemRule("合规审核员", "合规结论：需大额审批")
                .systemRule("审批说明生成器", "说明：已按审批通道完成合规校验，真实数据来自数据库直查。")
                // ---- DTO 返回：靠系统提示词里的"只输出 JSON"精确命中 ----
                // 为什么必须用 systemRule 而不是让 jsonAnswer 兜底：脚本模型的规则求值顺序是
                // 「使用者注册的规则 → … → 要求 JSON」，而 report() 那次的**系统提示词**里
                // 恰好含"订单"二字（Agent 级 systemPrompt 是"你是智能订单助手…"），
                // 于是"订单"那条业务规则会先命中，返回一句中文，DTO 反序列化必然失败。
                // 用 systemRule 把它变成一次显式、可断言的命中，是"离线模型必须可复现"的代价。
                .systemRule("只输出 JSON", """
                        {
                          "summary": "经营概览：订单集中在 VIP 客户，已支付金额占主体。",
                          "highlights": ["张三 3 笔订单合计 3140.00 元", "其中 2 笔已支付、1 笔待支付"],
                          "orderCount": 3
                        }""")
                .rule("闲聊", "我是订单助手，可以查订单、查客户、做统计，也能在审批通过后下单或退款。")
                // ---- 自然提问 → 工具（大小写不敏感的片段匹配） ----
                .toolFor("订单", "order")
                .toolFor("下单", "create")
                .toolFor("退款", "refund")
                .toolFor("客户", "customer")
                .toolFor("统计", "statistic")
                .jsonAnswer("""
                        {
                          "summary": "经营概览：订单集中在 VIP 客户，已支付金额占主体。",
                          "highlights": ["张三 3 笔订单合计 3140.00 元", "其中 2 笔已支付、1 笔待支付"],
                          "orderCount": 3
                        }""")
                .fallback("（离线脚本化模型）我收到了：{input}。"
                        + "想让我调用工具，可以写成 #tool:<工具名> {\"参数\":\"值\"}。")
                .build();
        return new RecordingModel(scripted);
    }

    /**
     * 一个"会抛异常"的模型：专门用来演示失败路径的审计。
     *
     * <p>为什么需要它：{@code DefaultAgent} 在<b>构造期</b>就解析模型与 Loop
     * （见 {@code DefaultAgent} 构造器里的 {@code modelRegistry.get(...)}），
     * 所以"模型名写错"是<b>启动期</b>错误，根本走不到监听器的 {@code onError}。
     * 要在运行期制造一次失败，只能让模型在 {@code chat()} 里抛。</p>
     *
     * <p>触发方式是输入里带 {@value #FAILURE_MARKER}。这是示例刻意留的"故障注入开关"，
     * 而不是把脚本模型改坏 —— 后者会让所有演示一起失效。</p>
     */
    public static final String FAILURE_MARKER = "触发失败";

    @Bean
    public LlmModel unstableLlmModel() {
        ScriptedLlmModel delegate = ScriptedLlmModel.builder("unstable")
                .fallback("（不稳定的离线模型）这一轮正常返回。")
                .build();
        return new LlmModel() {
            @Override
            public String name() {
                return "unstable";
            }

            @Override
            public ModelCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public com.benxin.llm.core.chat.ChatResponse chat(com.benxin.llm.core.chat.ChatRequest request) {
                if (containsFailureMarker(request)) {
                    throw new ModelException("模拟的模型故障：上游 503（输入里带了「"
                            + FAILURE_MARKER + "」，这是示例刻意注入的失败）");
                }
                return delegate.chat(request);
            }

            @Override
            public void stream(com.benxin.llm.core.chat.ChatRequest request, LlmStreamHandler handler) {
                handler.onStart();
                com.benxin.llm.core.chat.ChatResponse response = chat(request);
                handler.onTextDelta(response.message().text());
                handler.onComplete(response);
            }

            private boolean containsFailureMarker(com.benxin.llm.core.chat.ChatRequest request) {
                return request.messages().stream()
                        .map(ChatMessage::text)
                        .anyMatch(text -> text != null && text.contains(FAILURE_MARKER));
            }
        };
    }

    // ------------------------------------------------------------------
    // 记忆：顶替 InMemoryMemoryStore
    // ------------------------------------------------------------------

    /**
     * JDBC 版记忆。
     *
     * <p>{@code @Primary} 的用意见类注释：starter 用 {@code @ConditionalOnMissingBean}
     * 注册了默认的内存实现，两个 bean 同时在容器里时，注入点需要有人告诉它用哪个。</p>
     */
    @Bean
    @Primary
    public JdbcMemoryStore jdbcMemoryStore(JdbcTemplate jdbcTemplate) {
        return new JdbcMemoryStore(jdbcTemplate);
    }

    // ------------------------------------------------------------------
    // 可观测性：运行审计 + 工具审计
    // ------------------------------------------------------------------

    /**
     * 运行审计监听器。
     *
     * <p>注意这里<b>不要</b>再加一个 {@code LoggingListener}：starter 的内置监听器是
     * {@code @ConditionalOnMissingBean(AgentListener.class)}，容器里出现任意一个
     * {@code AgentListener} bean 就会让它让位。示例因此自己接管了"运行观察"这件事。</p>
     */
    @Bean
    public AgentRunAuditListener agentRunAuditListener(JdbcTemplate jdbcTemplate, RecordingModel mockLlmModel) {
        return new AgentRunAuditListener(jdbcTemplate, mockLlmModel);
    }

    /** 工具审计拦截器。注册成 bean 即自动进入拦截器链。 */
    @Bean
    public ToolAuditInterceptor toolAuditInterceptor(JdbcTemplate jdbcTemplate) {
        return new ToolAuditInterceptor(jdbcTemplate);
    }

    /**
     * 写操作短路拦截器。
     *
     * <p>它是 {@code agent_run} / {@code tool_audit} 之外唯一一个"会改变行为"的 bean，
     * 因此刻意与两个审计 bean 放在一起：读者一眼能看到
     * "观察"（Listener）与"介入"（Interceptor）的分界线。</p>
     */
    @Bean
    public WriteToolShortCircuitInterceptor writeToolShortCircuitInterceptor(ToolAuditInterceptor audit) {
        return new WriteToolShortCircuitInterceptor(audit);
    }

    // ------------------------------------------------------------------
    // 审批闸与沙箱闸
    // ------------------------------------------------------------------

    /**
     * 默认审批处理器：金额阈值 + 会话标记共同决策。
     *
     * <p>阈值来自 {@code exp.database.approval-threshold}（默认 500），
     * 演示与测试各自把它调成不同值就能跑到不同分支。</p>
     *
     * <p><b>为什么必须标 {@code @Primary}</b>：本类同时提供了两个
     * {@link com.benxin.llm.core.sandbox.ApprovalHandler}（另一个是
     * {@link ManualReviewApprovalHandler}），而 starter 的
     * {@code LlmAgentAutoConfiguration#llmAgentFactory(...)} 是按<b>类型</b>直接注入
     * {@code ApprovalHandler} 的，参数上既没有 {@code @Qualifier} 也不按 bean 名匹配。
     * 于是"同类型有多个实现"会直接让容器启动失败（{@code NoUniqueBeanDefinitionException}）。
     * 这里把"按金额阈值审批"标成默认的那个，正是 {@code llm.agent.approval-policy: on-request}
     * 想要的口径；想要"永远转人工"的 Agent 自己用 {@code @Qualifier} 取另一个。
     * 这条约束是示例在真实启动中撞出来的，已记入 README 的「插件问题」。</p>
     */
    @Bean
    @Primary
    public DbApprovalHandler dbApprovalHandler(JdbcTemplate jdbcTemplate,
                                               ToolAuditInterceptor audit,
                                               @Value("${exp.database.approval-threshold:500}") BigDecimal threshold) {
        return new DbApprovalHandler(jdbcTemplate, audit, threshold);
    }

    /** 「永远转人工」的处理器：给 {@code ALWAYS} 策略的 agent 用。 */
    @Bean
    public ManualReviewApprovalHandler manualReviewApprovalHandler(JdbcTemplate jdbcTemplate) {
        return new ManualReviewApprovalHandler(jdbcTemplate);
    }

    /**
     * 风控沙箱：{@code refund_order} 单笔超过 5000 元直接拒绝。
     *
     * <p>注册成 {@code ToolSandbox} bean 之后，它会成为<b>所有</b> Agent 的默认沙箱；
     * {@code refundAssistant} 另外拿一个更严的实例（见下）。</p>
     *
     * <p><b>{@code @Primary} 不是可选项</b>，原因与 {@link #dbApprovalHandler} 那段完全一样：
     * starter 的 {@code llmAgentFactory(...)} 按类型注入 {@code ToolSandbox}，
     * 而本类提供了两个（这一个 + {@code refundAssistantSandbox}）。
     * 不标 {@code @Primary} 的话，容器在实例化 {@code llmAgentFactory} 时就会以
     * {@code No qualifying bean of type 'ToolSandbox' available: expected single matching
     * bean but found 2} 启动失败 —— 注解路径的声明式 Agent 一个都装配不出来。</p>
     */
    @Bean
    @Primary
    public RefundAmountSandbox refundAmountSandbox() {
        return new RefundAmountSandbox();
    }

    /**
     * {@code refundAssistant} 专用沙箱：更严，单笔上限 1000 元。
     *
     * <p>它是 {@code @LlmTool} 路径做不到的另一件事的示范：<b>沙箱可以按 Agent 换</b>。
     * 因为 {@code DefaultLoopContext#sandbox()} 取的是 {@code agent.sandbox()}，
     * 每个 Agent 记着自己那一份。</p>
     */
    @Bean("refundAssistantSandbox")
    public ToolSandbox refundAssistantSandbox() {
        return new ToolSandbox() {
            @Override
            public Decision check(com.benxin.llm.core.tool.ToolInvocation invocation) {
                if (!"refund_order".equals(invocation.toolName())) {
                    return Decision.allow();
                }
                Object amount = invocation.arguments().get("amount");
                double value = amount instanceof Number number ? number.doubleValue()
                        : parse(amount == null ? null : String.valueOf(amount));
                if (value > 1000.0) {
                    return Decision.deny("退款金额 " + value + " 元超过退款专员的授权上限 1000 元");
                }
                return Decision.allow();
            }

            private double parse(String text) {
                try {
                    return text == null ? 0d : Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    return 0d;
                }
            }

            @Override
            public String toString() {
                return "RefundDedicatedSandbox{上限 1000 元}";
            }
        };
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 只读查库工具：{@code @LlmTool} 路径，工具来源类就是它自己。 */
    @Bean
    public OrderQueryTools orderQueryTools(OrderRepository repository) {
        return new OrderQueryTools(repository);
    }

    /**
     * 写库工具的<b>工厂</b>。
     *
     * <p>它本身不是工具，只是一个持有 {@link OrderWriteService} 与
     * {@link DbApprovalHandler} 的普通 bean —— 真正的工具由下面两个 {@code @Bean} 产出。</p>
     */
    @Bean
    public OrderWriteTools orderWriteTools(OrderWriteService service, DbApprovalHandler handler) {
        return new OrderWriteTools(service, handler);
    }

    /**
     * 把两个写工具各自做成一个 {@link ToolCallback} bean。
     *
     * <p><b>为什么必须这样注册，而不能只给一个 {@code OrderWriteTools} bean？</b>
     * （这是示例在真实启动里撞出来的，第一版就是这么写的 —— 结果是
     * {@code orderAssistant} 的工具表里只有 5 个只读工具，{@code create_order} /
     * {@code refund_order} 一个都不在，所有写库演示静默失效。）</p>
     *
     * <p>原因在 {@code LlmToolCatalog#collect}：它认两条路 ——</p>
     * <ol>
     *   <li>bean <b>本身</b>实现 {@link ToolCallback} → 直接登记，并按
     *       {@code bean.getClass()} 记进"来源类"表；</li>
     *   <li>bean 的方法上标了 {@code @LlmTool} → 扫描成 {@code MethodToolCallback}。</li>
     * </ol>
     * <p>而 {@code OrderWriteTools} 两条都不占：它没实现 {@code ToolCallback}，
     * 方法上也没有 {@code @LlmTool}（写工具需要 {@code requiresApproval() = true}，
     * 注解路径表达不了，见 {@link OrderWriteTools} 的类注释），它只是一个
     * <b>方法返回 {@code ToolCallback} 的工厂对象</b>。</p>
     *
     * <p>于是 {@code @LlmAgent(tools = {OrderWriteTools.class})} 里的
     * {@code OrderWriteTools.class} 在 {@code LlmToolCatalog.forClasses(...)} 里查不到任何东西，
     * 插件只会打一行 WARN 就跳过。把工具做成 bean 之后，路径 ① 生效：
     * 目录按 {@code bean.getClass()} 记账，而 {@code all()} 返回的正是命名的匿名子类 ——
     * 它们的 {@code getClass()} 就是那个来源类，注解里写的 {@code OrderWriteTools.class} 因此能精确命中。</p>
     */
    @Bean
    public ToolCallback createOrderTool(OrderWriteTools writeTools) {
        return writeTools.createOrder();
    }

    /** {@code refund_order}，注册理由同 {@link #createOrderTool}。 */
    @Bean
    public ToolCallback refundOrderTool(OrderWriteTools writeTools) {
        return writeTools.refundOrder();
    }

    // ------------------------------------------------------------------
    // 事务
    // ------------------------------------------------------------------

    /**
     * 显式事务模板。
     *
     * <p>为什么手写一个 bean 而不是直接注入 Spring Boot 自动配置好的那个？
     * 因为示例想强调"事务边界必须被显式画出来"，用一个自己声明的 bean
     * 比"反正容器里有"更能表达这个意图。它只是包装了容器里的事务管理器，
     * 行为与自动配置的完全一致。</p>
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** 写库服务：事务边界画在它内部。 */
    @Bean
    public OrderWriteService orderWriteService(OrderRepository repository, JdbcTemplate jdbcTemplate,
                                               TransactionTemplate transactionTemplate) {
        return new OrderWriteService(repository, jdbcTemplate, transactionTemplate);
    }

    // ------------------------------------------------------------------
    // 手写装配的 Agent
    // ------------------------------------------------------------------

    /**
     * 退款专员：唯一一个"不走 {@code @LlmAgent} 注解"的 Agent。
     *
     * <p><b>为什么必须手写装配？</b>因为它需要两样注解表达不了的东西：</p>
     * <ol>
     *   <li>{@code ApprovalPolicy.ALWAYS} —— 注解上没有 {@code approvalPolicy} 属性，
     *       而 {@code LlmAgentFactory} 里是写死的
     *       {@code ApprovalPolicy.from(defaults.getApprovalPolicy())}，即全局 {@code llm.agent.approval-policy}。
     *       想给单个 Agent 换策略，只能自己装配；</li>
     *   <li>一份<b>专属沙箱</b>（单笔 1000 元上限）—— 注解上同样没有沙箱属性。</li>
     * </ol>
     *
     * <p>装配完顺手 {@code agentRegistry.register(...)}：注册进注册表之后，
     * 演示与测试里 {@code names() + find()} 的清理循环就能覆盖到它，
     * 它也因此会出现在 {@code AgentRegistry.names()} 里，与声明式 Agent 享受同等待遇。</p>
     */
    @Bean
    public Agent refundAssistant(LlmAgentFactory factory,
                                 AgentRegistry registry,
                                 LlmToolCatalog toolCatalog,
                                 OrderWriteTools writeTools,
                                 @Qualifier("refundAssistantSandbox") ToolSandbox refundSandbox,
                                 ManualReviewApprovalHandler handler) {
        // 退款专员要"先查订单再退款"，因此工具表 = 查库工具 + 写工具（人工拼一张）
        ToolRegistry tools = toolCatalog.forClasses(new Class<?>[]{OrderQueryTools.class});
        writeTools.all().forEach(tools::register);

        Agent agent = factory.create(
                        AgentSpec.builder()
                                .name("refundAssistant")
                                .model("mock")
                                .loop("dsh-minimal")
                                .maxSteps(6)
                                .memory(true)
                                .systemPrompt("你是退款专员。任何退款都必须先说明订单与金额，再申请执行。")
                                .build(),
                        tools)
                .toBuilder()
                .sandbox(refundSandbox)
                .approvalHandler(handler)
                .approvalPolicy(ApprovalPolicy.ALWAYS)
                .build();
        registry.register(agent);
        return agent;
    }
}
