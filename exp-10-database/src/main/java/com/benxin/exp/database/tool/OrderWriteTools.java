package com.benxin.exp.database.tool;

import com.benxin.exp.database.approval.DbApprovalHandler;
import com.benxin.exp.database.domain.WriteResult;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.JsonSchemaGenerator;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import org.springframework.dao.DataAccessException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 写库工具：{@code create_order} / {@code refund_order}，一次一个 {@link ToolCallback}。
 *
 * <h2>为什么不用 {@code @LlmTool}，而要手写 ToolCallback</h2>
 * <p>因为"这个工具需要人工审批"这件事，<b>在 {@code @LlmTool} 上表达不出来</b>：</p>
 * <ul>
 *   <li>{@code @LlmTool} 注解只有 {@code name / value / description / returnDirect / enabled}
 *       五个属性，没有 {@code requiresApproval}；</li>
 *   <li>注解路径落到的 {@code MethodToolCallback#requiresApproval()} 是<b>写死的 false</b>。</li>
 * </ul>
 *
 * <p>于是在默认的 {@code ON_REQUEST} 策略下（{@code needsApproval} 会去问
 * {@code callback.requiresApproval()}），任何用 {@code @LlmTool} 声明的工具都<b>永远不会
 * 触发审批</b>。想让它触发，只有两条路：</p>
 * <ol>
 *   <li>把全局策略改成 {@code ALWAYS}（那读工具也要挨个问一遍）；</li>
 *   <li>手写 {@link ToolCallback} 并实现 {@code requiresApproval()} —— 本类走的是这条。</li>
 * </ol>
 *
 * <p>手写 ToolCallback 的代价是要自己写 JSON Schema。这里没有手抄一份，
 * 而是用插件自带的 {@link JsonSchemaGenerator#forMethod(Method)} 从"影子方法"上生成 ——
 * 于是 {@code create_order} 的 schema 与 {@code @LlmTool} 路径生成的<b>完全同源</b>，
 * 不会出现两套定义各自漂移的问题。</p>
 *
 * <h2>给模型的拒绝原因为什么要在工具里补一句</h2>
 * <p>框架在审批被拒时回灌给模型的文本是固定的
 * {@code "用户拒绝执行工具 [create_order]"}（见 {@code DefaultLoopContext#executeOne}），
 * 它不包含 {@code ApprovalHandler} 写下的详细理由。本类因此在 {@code call} 里检查
 * {@link DbApprovalHandler#lastRejectionReason()}，把它拼进错误文本 ——
 * 这样模型才能向用户解释"为什么这笔单子没下成"。这是示例侧的适配，
 * 不是框架能力，已在 README 的「插件问题」里单列。</p>
 */
public final class OrderWriteTools {

    /** 工具名常量：Agent 的 {@code toolNames} 与演示断言都引用它，避免各处写字符串。 */
    public static final String CREATE_ORDER = "create_order";
    public static final String REFUND_ORDER = "refund_order";

    private final OrderWriteService service;
    private final DbApprovalHandler approvalHandler;

    public OrderWriteTools(OrderWriteService service, DbApprovalHandler approvalHandler) {
        this.service = service;
        this.approvalHandler = approvalHandler;
    }    // ------------------------------------------------------------------
    // 影子方法：只用来生成 JSON Schema，永远不会被反射调用
    // ------------------------------------------------------------------

    /** {@code create_order} 的参数形状。 */
    @SuppressWarnings("unused")
    private Object createOrderSchema(
            @com.benxin.llm.core.annotation.LlmToolParam(value = "customerId",
                    description = "客户 id（数字）。张三=1，李四=2，王五=3") Long customerId,
            @com.benxin.llm.core.annotation.LlmToolParam(value = "sku",
                    description = "商品编码，必须唯一，例如 SKU-9001") String sku,
            @com.benxin.llm.core.annotation.LlmToolParam(value = "amount",
                    description = "订单金额，正数") BigDecimal amount) {
        return null;
    }

    /** {@code refund_order} 的参数形状。 */
    @SuppressWarnings("unused")
    private Object refundOrderSchema(
            @com.benxin.llm.core.annotation.LlmToolParam(value = "orderId",
                    description = "要退款的订单 id（数字）") Long orderId,
            @com.benxin.llm.core.annotation.LlmToolParam(value = "amount",
                    description = "退款金额，正数") BigDecimal amount) {
        return null;
    }

    private static Method methodOf(String name) {
        for (Method method : OrderWriteTools.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("找不到影子方法 " + name);
    }

    // ------------------------------------------------------------------
    // 两个工具
    // ------------------------------------------------------------------

    /** 下单工具（需要审批）。具名嵌套类的理由见 {@link CreateOrderTool}。 */
    public ToolCallback createOrder() {
        return new CreateOrderTool(service);
    }

    /** 退款工具（需要审批）。具名嵌套类的理由见 {@link CreateOrderTool}。 */
    public ToolCallback refundOrder() {
        return new RefundOrderTool(service);
    }

    /**
     * 下单工具（需要审批）。
     *
     * <p><b>为什么是内部类而不是匿名类？</b>（第一版就是匿名类，结果是这两个工具
     * 一个都没进 {@code orderAssistant} 的工具表。）{@code LlmToolCatalog} 登记"手写
     * {@code ToolCallback}"时记的键是 {@code bean.getClass()}，而
     * {@code @LlmAgent(tools = {OrderWriteTools.class})} 要按
     * {@code OrderWriteTools.class} 去查。用匿名类的话 {@code getClass()} 是
     * {@code OrderWriteTools$1} / {@code OrderWriteTools$2} —— 查不到，
     * 插件只会打一行 WARN 然后静默跳过，写库演示全部失效。具名的内部类让
     * "工具对象的类型"与"注解里写的来源类"重新对上。</p>
     *
     * <p>它们写成<b>非静态</b>内部类，是为了拿到外层持有的 {@link OrderWriteService}
     * 与 {@link DbApprovalHandler}（补一句可读的拒绝原因要用到后者）。</p>
     */
    public final class CreateOrderTool extends WriteTool {

        private final OrderWriteService service;

        CreateOrderTool(OrderWriteService service) {
            super(new ToolSpec(CREATE_ORDER,
                    "为客户创建一笔新订单并写库。【写操作】执行前会走人工审批："
                            + "金额超过阈值会被拒绝并转人工，请把拒绝原因如实告知用户",
                    JsonSchemaGenerator.forMethod(methodOf("createOrderSchema"))));
            this.service = service;
        }

        @Override
        protected ToolResult execute(Map<String, Object> arguments) {
            long customerId = asLong(arguments.get("customerId"), 1L);
            String sku = asString(arguments.get("sku"));
            BigDecimal amount = asDecimal(arguments.get("amount"));
            WriteResult result = service.createOrder(customerId, sku, amount);
            return ToolResult.ok(Json.writeQuietly(result));
        }
    }

    /** 退款工具（需要审批）。具名内部类的理由同 {@link CreateOrderTool}。 */
    public final class RefundOrderTool extends WriteTool {

        private final OrderWriteService service;

        RefundOrderTool(OrderWriteService service) {
            super(new ToolSpec(REFUND_ORDER,
                    "对一笔已存在的订单发起退款（状态置为 REFUNDED）。【写操作】执行前会走人工审批",
                    JsonSchemaGenerator.forMethod(methodOf("refundOrderSchema"))));
            this.service = service;
        }

        @Override
        protected ToolResult execute(Map<String, Object> arguments) {
            long orderId = asLong(arguments.get("orderId"), 0L);
            BigDecimal amount = asDecimal(arguments.get("amount"));
            WriteResult result = service.refundOrder(orderId, amount);
            return ToolResult.ok(Json.writeQuietly(result));
        }
    }

    // ------------------------------------------------------------------
    // 写工具的骨架：审批要求在基类里表达一次，两个工具各自只写"怎么执行"
    // ------------------------------------------------------------------

    /**
     * 写工具基类。
     *
     * <p>它把三件写工具共有的、最容易写错的事集中起来：</p>
     * <ol>
     *   <li>{@code requiresApproval() = true} —— 默认策略 {@code ON_REQUEST} 下
     *       这就是"必须过审批闸"的唯一开关；</li>
     *   <li>异常兜底 —— 业务异常统一转成 {@code ToolResult.error} 而不是抛出。
     *       抛出会穿透整个 Loop 让这次 Agent 运行以异常收场（审计只留一行 FAILED），
     *       而返回错误文本能让模型自我纠正并向用户解释。这是本心工具实现的一贯口径；</li>
     *   <li>拒绝原因补充 —— 见类注释。</li>
     * </ol>
     */
    private abstract class WriteTool implements ToolCallback {

        private final ToolSpec spec;

        private WriteTool(ToolSpec spec) {
            this.spec = spec;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        /** 真正落库的动作。 */
        protected abstract ToolResult execute(Map<String, Object> arguments);

        @Override
        public boolean requiresApproval() {
            return approvals();
        }

        /**
         * 是否需要人工审批。
         *
         * <p>写成 {@code protected} 的可覆写方法而不是常量：以后要按参数（例如金额）
         * 动态判断时，覆写这一个方法就够了，不用动 {@code requiresApproval()}。</p>
         */
        protected boolean approvals() {
            return true;
        }

        @Override
        public ToolResult call(Map<String, Object> arguments, ToolContext context) {
            // 先清掉上一次的拒绝理由：它是一个跨调用保留的状态，
            // 不清的话"这次因为参数错误失败"会被讲成"上次那次审批拒绝了"，非常误导。
            clearStaleRejection(context);
            try {
                return execute(arguments);
            } catch (DataAccessException e) {
                // 唯一约束冲突 / 连接异常等 SQL 层错误
                return ToolResult.error(spec.name() + " 写库失败（" + rootMessage(e) + "）"
                        + "，本次操作已整体回滚，数据库没有留下半写入的数据");
            } catch (RuntimeException e) {
                return ToolResult.error(spec.name() + " 未执行：" + e.getMessage() + "。" + hintOf(context));
            }
        }

        /**
         * 给模型的那句"为什么没执行"。
         *
         * <p>审批被拒时完整理由只留在 {@code approval_request} 表与
         * {@code ApprovalHandler} 内部，框架回灌的文本是固定的
         * {@code "用户拒绝执行工具 [xxx]"}，因此这里主动补一句可读原因。
         * 这个动作是<b>示例侧的适配</b>，不是框架能力。</p>
         */
        private String hintOf(ToolContext context) {
            String reason = reasonFrom(context);
            return reason == null
                    ? "请检查参数是否正确（客户/订单是否存在、金额是否为正数）"
                    : "审批环节未放行，原因：" + reason;
        }

        private String rootMessage(Throwable error) {
            Throwable current = error;
            while (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            String message = current.getMessage();
            return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        }
    }

    /**
     * 取"这次为什么被拦下来"的完整原因。
     *
     * <p>从 {@link ToolContext} 里拿本次运行真正生效的 {@code ApprovalHandler}，
     * 而不是靠构造期注入的那一个 —— 同一个 Agent 可能配了不同的 handler
     * （例如 {@code refundAssistant} 用的是"永远转人工"的那个），
     * 写工具只能问"当下这个是谁"，问错了就会把别人的理由讲给模型听。</p>
     */
    private String reasonFrom(ToolContext context) {
        if (context != null && context.approvalHandler() instanceof DbApprovalHandler handler) {
            return handler.lastRejectionReason();
        }
        return approvalHandler == null ? null : approvalHandler.lastRejectionReason();
    }

    private void clearStaleRejection(ToolContext context) {
        if (context != null && context.approvalHandler() instanceof DbApprovalHandler handler) {
            handler.clearRejectionReason();
            return;
        }
        if (approvalHandler != null) {
            approvalHandler.clearRejectionReason();
        }
    }

    // ------------------------------------------------------------------
    // 宽松取值：参数直接来自模型的 JSON，什么都可能缺、可能是字符串
    // ------------------------------------------------------------------

    /**
     * 宽松取值：参数直接来自模型的 JSON，什么都可能缺、可能是字符串。
     *
     * <p>但"缺"与"给了垃圾"必须区别对待：<b>缺参数</b>用 {@code fallback} 兜底，
     * <b>给了值却解析不出来</b>直接抛 —— 后者如果也退化成 fallback，
     * 一次"参数写错了"会被伪装成"对客户 1 下单成功"，而这种错最难被发现。
     * 异常会在 {@link WriteTool#call} 里被转成 {@code ToolResult.error} 回灌给模型，
     * 不会穿透整个 Loop。</p>
     */
    private static long asLong(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数必须是数字，收到：[" + raw + "]");
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static BigDecimal asDecimal(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 一个便捷入口，便于配置类一次性拿到两个工具。 */
    public java.util.List<ToolCallback> all() {
        return java.util.List.of(new CreateOrderTool(service), new RefundOrderTool(service));
    }

    /** 供演示打印"写工具的表里到底是什么"。 */
    public String describe() {
        return "create_order / refund_order（都需要审批；来自 " + OrderWriteTools.class.getSimpleName() + "）";
    }
}
