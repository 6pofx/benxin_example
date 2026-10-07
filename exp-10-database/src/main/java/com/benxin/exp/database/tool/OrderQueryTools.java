package com.benxin.exp.database.tool;

import com.benxin.exp.database.db.OrderRepository;
import com.benxin.exp.database.domain.Customer;
import com.benxin.exp.database.domain.OrderItem;
import com.benxin.exp.database.domain.OrderStat;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 查库工具（只读）：把"模型决策 → 工具查库 → 结果回灌 → 收尾"这条闭环的中间一环落到 SQL 上。
 *
 * <p>这些方法刻意返回 <b>DTO / record</b> 而不是拼好的字符串：本心会把返回值序列化成
 * JSON 文本回灌给模型，字段名因此是稳定的、可被测试断言的。返回一段自然语言的话，
 * "答案是不是真来自数据库"就只能靠肉眼看了。</p>
 *
 * <h2>两条可读性上的取舍</h2>
 * <ol>
 *   <li><b>参数全部显式声明，不用 {@code Optional<T>}</b>。本心支持 {@code Optional}
 *       参数（缺参绑成 {@code Optional.empty()}），但 JSON Schema 里它仍然是必填项，
 *       模型还是得给值。示例选择"给默认值"这种更朴素的口径：
 *       {@code customerId} 缺省为 1（张三），{@code status} 缺省为"不限"。</li>
 *   <li><b>查不到数据时返回"空结果"而不是抛异常</b>。模型看到
 *       {@code {"found": false}} 会自然地换一个工具或换一个说法；
 *       看到异常栈则往往只会重复同一次调用。</li>
 * </ol>
 */
public class OrderQueryTools {

    private final OrderRepository repository;

    public OrderQueryTools(OrderRepository repository) {
        this.repository = repository;
    }

    /**
     * 查订单。
     *
     * <p>{@code customerId} 用包装类型 {@code Long} 而不是 {@code long}：
     * 模型漏给参数时 {@code long} 会绑定成 0，于是变成一个"查询客户 0"的正常请求，
     * 排错时看不出任何异常；{@code Long} 的 null 则能被显式识别成"参数没给"。</p>
     */
    /**
     * 查订单。
     *
     * <p>参数用 {@code String} 接、再自己解析，而不是直接声明 {@code Long} ——
     * 这是本示例踩过的一个真坑，值得写下来：</p>
     * <ul>
     *   <li>声明成 {@code Long} 时，凡是绑不进去的值（JSON 里给了字符串、工作流把
     *       {@code ${customerId}} 原样透传过来）都会在<b>绑定阶段</b>直接抛
     *       {@code Cannot deserialize value of type java.lang.Long}，
     *       工具体一行都不会执行，错误信息也离"到底哪个参数不对"很远；</li>
     *   <li>自己解析则可以区分两件完全不同的事：<b>参数没给</b>（null → 用默认客户 1，
     *       方便演示）与<b>参数给了但解析不出来</b>（→ 明确报错）。
     *       "解析不出来就悄悄退化成客户 1"是最糟的一种：它会拿着一份<b>看起来正常</b>的
     *       别人的数据继续往下跑，排查时根本看不出哪里错了。</li>
     * </ul>
     */
    @LlmTool(name = "query_orders", description = "查询某个客户的订单列表，可按订单状态过滤。返回订单的 id、sku、金额、状态")
    public ObjectNode queryOrders(
            @LlmToolParam(value = "customerId", description = "客户 id（数字）。张三=1，李四=2，王五=3",
                    required = false) String customerId,
            @LlmToolParam(value = "status", description = "订单状态过滤：PAID / PENDING / REFUNDED；不填表示不限",
                    required = false) String status) {
        ObjectNode result = Json.object();
        Long id = parseId(customerId, 1L);
        if (id == null) {
            result.put("found", false);
            result.put("reason", "customerId 必须是数字，收到：[" + customerId + "]");
            return result;
        }
        Optional<Customer> customer = repository.findCustomerById(id);
        if (customer.isEmpty()) {
            result.put("found", false);
            result.put("reason", "客户 id " + id + " 不存在");
            return result;
        }
        List<OrderItem> orders = repository.findOrders(id, status);
        result.put("found", true);
        result.put("customerId", id);
        result.put("customerName", customer.get().name());
        result.put("statusFilter", status == null || status.isBlank() ? "ALL" : status);
        result.put("count", orders.size());
        ArrayNode items = result.putArray("orders");
        for (OrderItem order : orders) {
            ObjectNode node = items.addObject();
            node.put("orderId", order.id());
            node.put("sku", order.sku());
            node.put("amount", order.amount());
            node.put("status", order.status());
            node.put("createdAt", String.valueOf(order.createdAt()));
        }
        return result;
    }

    /**
     * 把"要么没给、要么给了一个数字、要么给了一段垃圾"的客户 id 解析成 {@code Long}。
     *
     * @param fallback 参数<b>完全没给</b>时用的默认值
     * @return 解析结果；<b>给了值却解析不出来时返回 {@code null}</b>，由调用方给出明确错误
     */
    private static Long parseId(String raw, Long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按姓名查客户 —— 这是"两步查询"里的第一步（先拿到 id，再查订单）。 */
    @LlmTool(name = "query_customer", description = "按姓名精确查询客户，返回客户 id、等级与电话")
    public ObjectNode queryCustomer(
            @LlmToolParam(value = "name", description = "客户姓名，例如：张三") String name) {
        ObjectNode result = Json.object();
        Optional<Customer> customer = repository.findCustomerByName(name);
        if (customer.isEmpty()) {
            result.put("found", false);
            result.put("reason", "没有名为 [" + name + "] 的客户");
            return result;
        }
        Customer found = customer.get();
        result.put("found", true);
        result.put("customerId", found.id());
        result.put("name", found.name());
        result.put("level", found.level());
        result.put("phone", found.phone());
        return result;
    }

    /**
     * 聚合统计。
     *
     * <p>返回值里显式带上 {@code "ALL"} 这一行合计 —— 不让模型自己把几行加起来：
     * 让模型做算术是"看起来能用、实际经常错"的典型，能在 SQL 里算的就别交给模型。</p>
     */
    @LlmTool(name = "order_statistics", description = "按状态聚合某个客户的订单笔数与金额合计，额外返回一行 ALL 总计")
    public ObjectNode orderStatistics(
            @LlmToolParam(value = "customerId", description = "客户 id（数字）；不填默认 1（张三）",
                    required = false) String customerId,
            @LlmToolParam(value = "status", description = "只统计某个状态；不填表示全部",
                    required = false) String status) {
        Long parsed = parseId(customerId, 1L);
        long id = parsed == null ? -1L : parsed;
        ObjectNode result = Json.object();
        result.put("customerId", id);
        // id = -1 表示"参数给了但不是数字"：统计结果为空，模型能一眼看出是参数问题
        // （口径与 queryOrders 一致：不悄悄退化成客户 1）
        List<OrderStat> stats = id < 0 ? List.of() : repository.statistics(id, status);
        ArrayNode rows = result.putArray("statistics");
        for (OrderStat stat : stats) {
            ObjectNode node = rows.addObject();
            node.put("status", stat.status());
            node.put("count", stat.count());
            node.put("amount", stat.amount());
        }
        return result;
    }

    /**
     * 只返回订单总额的标量。
     *
     * <p>它是给<b>工作流</b>准备的：{@code Conditions} 支持 {@code ${amount} > 500} 这种
     * 数字比较，但比较的前提是黑板上的值本身是个数字（或能 {@code Double.parseDouble} 的字符串）。
     * 返回一大坨 JSON 的话，条件表达式就没法直接比大小。示例因此把它单独拆成一个工具 ——
     * <b>"编排归图、能力归工具"这条取舍的具体体现</b>。</p>
     *
     * <p>参数解析不出来的情况在工作流里是<b>常态</b>：{@code args.customerId: "${customerId}"}
     * 在黑板上没有该变量时会被 Templates 原样透传成字符串。这时候必须抛异常让节点失败，
     * 而不是返回一个 "0" —— 后者会让 {@code ${amount} > 500} 静默判假、走进"自动通过"分支，
     * 于是一次取数失败被伪装成了"合规通过"。{@code callTool} 会把异常升级成节点级失败，
     * 再由 {@code continueOnError} + {@code ${error}} 出边走补偿分支。</p>
     */
    @LlmTool(name = "order_amount_of", description = "只返回某个客户的订单金额合计（一个纯数字），供工作流条件比较使用")
    public String orderAmountOf(
            @LlmToolParam(value = "customerId", description = "客户 id（数字）；不填默认 1（张三）",
                    required = false) String customerId) {
        Long id = parseId(customerId, 1L);
        if (id == null) {
            throw new IllegalArgumentException("customerId 必须是数字，收到：[" + customerId + "]");
        }
        BigDecimal total = repository.totalAmount(id);
        return total.toPlainString();
    }

    /**
     * 演示 {@code ToolContext} 注入：工具能拿到"谁在调、哪一步、哪个会话"。
     *
     * <p>它本身也是"工具里可以顺手写一行审计"的示范 —— 不过示例的主线审计走的是
     * {@code AgentInterceptor}（那样连"没执行"的调用都能留痕），
     * 这条工具的作用是让演示能打印出运行上下文，证明工具侧确实拿得到这些信息。</p>
     */
    @LlmTool(name = "describe_order_context", description = "描述当前这次工具调用所处的运行上下文（Agent / 会话 / 步数）")
    public Map<String, Object> describeContext(ToolContext ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("agent", ctx.agentName());
        info.put("sessionId", ctx.sessionId());
        info.put("step", ctx.step());
        // 反射调用 getClass().getName() 而不是 getSimpleName()：这里的对象可能是
        // CGLIB / JDK 代理（工具 bean 一旦被 AOP 增强就会），拿简单名会得到空串。
        info.put("approvalHandler", ctx.approvalHandler() == null
                ? "none" : ctx.approvalHandler().getClass().getName());
        info.put("sandbox", ctx.sandbox() == null ? "none" : ctx.sandbox().getClass().getName());
        return info;
    }
}
