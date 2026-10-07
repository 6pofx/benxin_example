package com.benxin.exp.database.agent;

import com.benxin.exp.database.dto.BusinessReport;
import com.benxin.exp.database.tool.OrderQueryTools;
import com.benxin.exp.database.tool.OrderWriteTools;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.Memory;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

/**
 * 智能订单助手 —— 本示例的主角，一个接口搞定"查订单 / 查客户 / 做统计 / 下单 / 退款"。
 *
 * <p>它的装配方式与 exp-01 完全一样（接口 + 注解 = 可注入的 Agent），
 * 差别只在工具来源上，而这里恰好把插件支持的两条路都走了一遍：</p>
 * <ul>
 *   <li>{@code tools = {OrderQueryTools.class}} —— <b>按来源类</b>取
 *       {@code @LlmTool} 路径的只读工具；</li>
 *   <li>{@code toolNames = {"create_order", "refund_order"}} —— <b>按工具名</b>取
 *       手写 {@code ToolCallback} 的写工具（{@code requiresApproval() = true}）。</li>
 * </ul>
 *
 * <p><b>为什么写工具只能用 toolNames，不能写进 tools？</b>（这是示例在真实启动里
 * 撞出来的坑，第一版就是写成 {@code tools = {OrderQueryTools.class, OrderWriteTools.class}}，
 * 结果是这两个写工具一个都没进工具表，所有写库演示静默失效。）
 * 插件里 {@code tools = {X.class}} 的解析走的是
 * {@code LlmToolCatalog.forClasses(X)}，而那张表的键是"工具 bean 的
 * {@code getClass()}"：手写 {@code ToolCallback} 的 bean 会被登记成它的<b>实际类</b>
 * （本示例里是 {@code OrderWriteTools$CreateOrderTool}）。
 * {@code forClasses} 只做精确匹配、不认外层类，于是
 * {@code OrderWriteTools.class} 查不到任何东西，插件只打一行 WARN 就跳过 ——
 * 不报错、不中断，最难发现。而 {@code toolNames} 走的是<b>全局工具表</b>，
 * 手写 bean 在 {@code LlmToolCatalog#collect} 里已经无条件进过那张表，
 * 所以按名字一定能取到。这是"手写 ToolCallback"与"@LlmTool"在装配层面
 * 唯一真正不等价的地方，已记入 README 的「插件问题」。</p>
 *
 * <p>{@code memory = true} 是这个示例的关键：它让会话历史走
 * {@code MemoryStore}（本示例是 JDBC 版），于是"多轮追问"才能真的依赖前一轮的内容 ——
 * 而这份历史是<b>存在数据库里</b>的，进程重启也还在。</p>
 */
@LlmAgent(
        name = "orderAssistant",
        model = "mock",
        loop = "dsh-minimal",
        tools = {OrderQueryTools.class},
        toolNames = {OrderWriteTools.CREATE_ORDER, OrderWriteTools.REFUND_ORDER},
        maxSteps = 8,
        memory = true,
        systemPrompt = "你是智能订单助手。需要数据时先调用工具查库，再基于工具返回的真实数据回答；"
                + "不要凭空编造订单。涉及写库的操作（下单 / 退款）会经过审批闸，"
                + "若被拒绝，请把拒绝原因如实告知用户。"
)
public interface OrderAssistant {

    /**
     * 普通问答：不传 sessionId，历史落在 {@code orderAssistant-default} 这个会话上。
     *
     * <p>这是最常用的形态 —— "同一个助手连着问两句"会自然续上上下文。</p>
     */
    String ask(@User String question);

    /**
     * 指定会话问答：{@code sessionId} 决定"和谁续聊"。
     *
     * <p>记忆落的是 {@code agent_memory.session_id}，因此不同会话天然隔离；
     * 测试里用它断言"两个会话的历史互不干扰"。</p>
     */
    @SystemPrompt("本轮只回答用户的原始问题，不要展开无关信息。")
    String askInSession(@User String question, @Memory String sessionId);

    /**
     * 返回强类型 DTO：模型输出的 JSON 会被反序列化成 {@link BusinessReport}。
     *
     * <p>{@code inherit = false} 在这里不是风格问题，而是这条路径<b>能否走通</b>的前提。
     * Agent 级提示词里写着"你是智能订单助手…"，而 exp-common 的脚本化离线模型是
     * <b>按"最后一条 user 消息 + 系统提示词"做关键字匹配</b>的：只要系统提示词里出现
     * "订单"二字，"订单 → 张三共有 3 笔订单…"那条业务规则就会先命中，
     * 模型会回一句中文散文，DTO 反序列化必然失败（报"模型输出无法解析为该类型"）。
     * {@code inherit = false} 让这一轮的提示词只剩"请只输出 JSON…"，
     * 离线模型就能用 {@code systemRule("只输出 JSON", ...)} 精确接住它。</p>
     *
     * <p>换成真实模型时这个开关没有坏处：DTO 方法本来就该有一条干净的、"只要 JSON"的
     * 指令，而不是让"你是订单助手"这类人设混进去。</p>
     */
    @SystemPrompt(value = "请只输出 JSON，字段为 summary / highlights / orderCount，不要有任何多余文字。",
            inherit = false)
    BusinessReport report(@User String question);
}
