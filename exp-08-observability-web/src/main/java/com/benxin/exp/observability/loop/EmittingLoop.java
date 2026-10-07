package com.benxin.exp.observability.loop;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用自定义 Loop：在标准的"调模型 → 执行工具 → 收尾"骨架上，额外做两件本示例要观测的事。
 *
 * <ol>
 *   <li><b>每轮都发一个自定义事件</b>（{@code ctx.emit("exp-observe.step", payload)}）——
 *       {@code AgentListener.onCustomEvent} 的唯一触发源就是 {@code LoopContext.emit}
 *       与 {@code ToolContext.emit}，标准 Loop 里只有步数耗尽时才会 emit 一次
 *       （{@code max-steps-reached}），所以"自定义事件"这条回调需要一个自己的 Loop 才演示得干净。</li>
 *   <li><b>收尾时派生一个子代理</b>（{@code ctx.spawnSubAgent("task", ...)}）——
 *       触发 {@code AgentListener.onSubAgentStart / onSubAgentEnd} 这一对回调。</li>
 * </ol>
 *
 * <p>写法上刻意继承 {@link AbstractAgentLoop} 而不是直接实现 {@code AgentLoop}：
 * 基类的 {@code run()} 已经是"异常时先广播 onError 再抛出"的样板，
 * 自定义 Loop 不应该把这个语义再实现一遍（漏掉的 Loop 会让失败路径静默）。</p>
 */
@LlmLoop(value = "exp-observe", order = 10)
public class EmittingLoop extends AbstractAgentLoop {

    /** 子代理的名字，必须与 {@code @LlmAgent(subAgents = {...})} 里写的一致。 */
    public static final String SUB_AGENT = "task";

    /** 派生子代理时下发的提示词；离线脚本模型对这个词有固定应答。 */
    public static final String SUB_AGENT_PROMPT = "请汇报任务状态";

    /** 自定义事件类型名，测试按它断言。 */
    public static final String EVENT_STEP = "exp-observe.step";
    public static final String EVENT_SUB_AGENT = "exp-observe.subagent";

    @Override
    public String name() {
        return "exp-observe";
    }

    @Override
    public String description() {
        return "演示用 Loop：每轮发一个自定义事件，收尾派生一个 task 子代理。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        while (true) {
            if (outOfBudget(ctx)) {
                return finishTruncated(ctx);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agent", ctx.agentName());
            payload.put("step", ctx.step() + 1);
            payload.put("session", ctx.sessionId());
            ctx.emit(EVENT_STEP, payload);

            ChatResponse response = ctx.callModel();
            ChatMessage message = response.message();
            if (message == null) {
                return buildResult(ctx, "");
            }
            List<ToolUsePart> toolUses = message.toolUses();
            if (toolUses.isEmpty()) {
                // 模型给出最终答案：先派生子代理拿一份"独立核对"结果，再收尾。
                // 用 emit 出的 Map 而不是裸字符串，演示 payload 可以是任意对象。
                ctx.emit(EVENT_SUB_AGENT, Map.of("delegate", SUB_AGENT, "parent", ctx.agentName()));
                var child = ctx.spawnSubAgent(SUB_AGENT, SUB_AGENT_PROMPT, Map.of("from", ctx.agentName()));
                log.debug("[exp-observe] 子代理 {} 返回 {} 步", SUB_AGENT, child.steps());
                return buildResult(ctx, message.text());
            }
            ctx.executeTools(toolUses).forEach(ctx::append);
        }
    }
}
