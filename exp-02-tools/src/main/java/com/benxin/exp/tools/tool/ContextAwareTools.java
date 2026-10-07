package com.benxin.exp.tools.tool;

import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行上下文注入：工具方法可以多一个 {@link ToolContext} 参数，它<b>不进 JSON Schema</b>，
 * 由框架在执行时填进来（{@code JsonSchemaGenerator.isInjected} +
 * {@code MethodToolCallback#call} 里的类型判断）。
 *
 * <p>有了它，工具就不再是"纯函数"：能审计（谁在第几步调的）、能读运行属性、
 * 能发自定义事件、能拿到当前模型再调一次、能问沙箱与审批器。
 * 这也是 exp-03（沙箱）与 exp-08（拦截器）能挂在同一条链上的原因。</p>
 */
public class ContextAwareTools {

    /** 读一遍上下文里所有能读的东西，并把"工具也能写"的那两项真的写一次。 */
    @LlmTool(name = "context_probe", description = "读 ToolContext 的全部可见项，并写属性、发自定义事件")
    public Map<String, Object> contextProbe(ToolContext ctx,
                                            @LlmToolParam(value = "tag", description = "自定义事件里带上的标记",
                                                    required = false) String tag) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("agentName", ctx.agentName());
        info.put("sessionId", ctx.sessionId());
        info.put("step", ctx.step());
        info.put("canSpawnSubAgent", ctx.canSpawnSubAgent());
        info.put("attributes(读)", List.copyOf(ctx.attributes().keySet()));
        // model() 给的是"当前 Agent 正在用的那个模型对象"，工具内部因此可以再问一次模型。
        info.put("model().name()", ctx.model().name());
        info.put("sandbox()", ctx.sandbox().getClass().getSimpleName());
        info.put("sandbox().maxResultChars()", ctx.sandbox().maxResultChars());
        info.put("approvalHandler()", ctx.approvalHandler().getClass().getSimpleName());
        info.put("执行线程", Thread.currentThread().getName());

        // 属性表是本次运行的共享黑板：工具写进去的东西会出现在 AgentResult.attributes() 里。
        ctx.attributes().put("probe.tag", tag == null ? "(未提供)" : tag);
        ctx.attributes().put("probe.step", ctx.step());

        // 自定义事件直达 AgentListener.onCustomEvent —— 工具与前端之间的一条旁路。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent", ctx.agentName());
        payload.put("step", ctx.step());
        payload.put("tag", tag);
        payload.put("thread", Thread.currentThread().getName());
        ctx.emit("tool.custom-event", payload);
        return info;
    }

    /**
     * {@link AgentSession} 注入探针。
     *
     * <p>{@code JsonSchemaGenerator.isInjected(AgentSession.class)} 为 {@code true}，
     * 也就是说框架<b>承认</b>它是"注入参数"、不会把它写进 schema；
     * 但 {@code MethodToolCallback} 实际填进去的是 {@code null}
     * （源码注释写着"需要会话时改用 ToolContext#sessionId()"）。
     * 本示例把这个差异如实断言出来，而不是假装它能用 —— 详见 README 的「插件问题」。</p>
     */
    @LlmTool(name = "agent_session_probe", description = "AgentSession 注入探针：它不会进 schema，但注入的值是 null")
    public String agentSessionProbe(AgentSession session, ToolContext ctx) {
        return "AgentSession 参数 = " + (session == null ? "null" : session.id())
                + "；要会话请用 ToolContext.sessionId() = " + ctx.sessionId();
    }
}
