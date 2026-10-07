package com.benxin.exp.database.guard;

import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.exp.database.audit.ToolAuditInterceptor;

/**
 * 写操作短路拦截器：命中"测试 SKU 前缀"时<b>不让工具执行</b>，直接返回一个可读的结果。
 *
 * <p>它演示 {@link AgentInterceptor#beforeTool} 的短路语义：
 * 返回非 null 即"不用执行了，就用这个结果"。三个要点值得钉住：</p>
 *
 * <ol>
 *   <li><b>短路发生在沙箱与审批之后</b>。看 {@code DefaultLoopContext#executeOne} 的顺序：
 *       沙箱 → 审批 → <b>拦截器 beforeTool</b> → 真正执行。
 *       也就是说"拦截器"是最后一道可编程的闸，前面的闸都放行了它才有机会说话。</li>
 *   <li><b>短路之后 {@code afterTool} 仍然会被调用</b>，因此审计照样留痕
 *       （{@code tool_audit.short_circuit = 1}）—— 这是"工具没执行但审计仍在"的证据。
 *       <b>但这条承诺有一个前提：审计拦截器必须排在短路拦截器之前</b>，
 *       否则"短路即跳出拦截器循环"会让审计的 {@code beforeTool} 永远轮不到，
 *       表里一行都不会有。本类因此把 {@code order()} 让到审计之后
 *       （{@code ToolAuditInterceptor.order() = 0}，本类是 500），
 *       这一条是实测撞出来的，已写进 README 的「容易猜错的口径」。</li>
 *   <li><b>审计自己判断不出"这是短路"</b>：{@code ToolResult} 里没有任何字段标明来源，
 *       所以由本拦截器在短路时主动向审计声明一次
 *       （{@link ToolAuditInterceptor#markShortCircuit}）。
 *       让"做决定的那个"负责声明，比让审计去猜可靠得多。</li>
 * </ol>
 *
 * <p>为什么用"SKU 前缀"当触发条件，而不是一个环境变量或开关？
 * 因为演示与测试需要一个<b>确定性的、写在数据里的</b>触发点：
 * 只要模型（或测试）生成了以 {@value #BLOCKED_SKU_PREFIX} 开头的 sku，
 * 这次调用就一定被短路，与运行顺序、并发都无关。</p>
 */
public class WriteToolShortCircuitInterceptor implements AgentInterceptor {

    /** 命中这个前缀的 sku 会被短路，工具不会真的执行。 */
    public static final String BLOCKED_SKU_PREFIX = "SKU-BLOCKED";

    private static final String CREATE_ORDER = "create_order";

    private final ToolAuditInterceptor audit;

    public WriteToolShortCircuitInterceptor(ToolAuditInterceptor audit) {
        this.audit = audit;
    }

    @Override
    public int order() {
        // 排在审计（0）后面：审计先把"试图调什么"落库，本拦截器再决定要不要短路。
        // 反过来的话，被短路的调用在审计表里会是空白 —— 见类注释里那条实测结论。
        return 500;
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        if (!CREATE_ORDER.equals(invocation.toolName())) {
            return null;
        }
        Object sku = invocation.arguments().get("sku");
        if (sku == null || !String.valueOf(sku).startsWith(BLOCKED_SKU_PREFIX)) {
            return null;
        }
        // 先向审计声明"这次不执行"，再返回短路结果
        audit.markShortCircuit(invocation);
        String message = "[写操作护栏] sku 以 " + BLOCKED_SKU_PREFIX + " 开头，"
                + "按预演规则拒绝真实入库（工具未被执行）；如需真实下单请换一个 sku。";
        return ToolResult.ok(message);
    }
}
