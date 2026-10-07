package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.ReactNativeAgent;
import com.benxin.exp.loops.agent.ReactTextAgent;
import com.benxin.exp.loops.config.OfflineModelConfiguration;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ②：{@code react} —— 结构化工具调用与**文本协议降级**两条路。
 *
 * <p>ReAct 的价值不在于 "Thought → Action → Observation" 这个格式本身，而在于它对现实环境的让步：
 * 不少 OpenAI 兼容网关不透传 {@code tools} 字段，不少本地模型根本没有 function calling。
 * 只会走结构化路径的 Loop 在这些环境里直接不可用，所以 {@code ReActLoop} 保留了
 * {@code Action / Action Input / Final Answer} 文本协议这条兜底。</p>
 *
 * <p>本演示把两条路都跑一遍：</p>
 * <ol>
 *   <li><b>原生路径</b>：模型能力声明里 {@code toolCalling = true} → 走标准骨架，
 *       工具结果以 {@code role=tool} 回灌；</li>
 *   <li><b>降级路径</b>：模型能力声明里 {@code toolCalling = false} → Loop 把格式说明
 *       塞进**第一条 user 消息**（系统提示词这时已经渲染完成，改不动了），
 *       再把模型回复当文本解析，工具结果以 {@code Observation: ...} 回灌。</li>
 * </ol>
 */
@Component
public class ReactLoopDemo {

    /** 文本协议格式说明里必然会出现的字面量（见 {@code ReActLoop.PROTOCOL_SPEC}）。 */
    public static final String PROTOCOL_MARKER = "Action Input: <该工具参数的 JSON";

    private final ReactNativeAgent reactNativeAgent;
    private final ReactTextAgent reactTextAgent;
    private final ScriptedLlmModel reactTextModel;
    private final CommonTools commonTools;

    public ReactLoopDemo(ReactNativeAgent reactNativeAgent,
                         ReactTextAgent reactTextAgent,
                         @Qualifier(OfflineModelConfiguration.REACT_TEXT) ScriptedLlmModel reactTextModel,
                         CommonTools commonTools) {
        this.reactNativeAgent = reactNativeAgent;
        this.reactTextAgent = reactTextAgent;
        this.reactTextModel = reactTextModel;
        this.commonTools = commonTools;
    }

    void run(Demo demo) {
        demo.section("react ① 原生 function calling 路径");
        nativePath(demo);
        demo.section("react ② 模型不支持 function calling 时降级到文本协议");
        textProtocolPath(demo);
    }

    /**
     * ① 原生路径：工具结果以 {@code role=tool} 回灌。
     *
     * <p>它与 {@code dsh-minimal} 复用同一个骨架 {@code runToolCallingLoop} ——
     * "react 的结构化路径没有额外魔法"，差别只在模型是否声明支持工具调用。</p>
     */
    private void nativePath(Demo demo) {
        commonTools.reset();
        AgentResult result = reactNativeAgent.ask("北京天气怎么样？");
        LoopReport.result(demo, result);
        demo.ok("结构化路径：工具被执行 %d 次，模型把结果收编成最终答案（steps = %d）",
                commonTools.callCount(), result.steps());
        result.messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .forEach(m -> demo.info("   role=tool 回灌: %s", LoopReport.oneLine(m.text(), 140)));
    }

    /** ② 降级路径：模型能力声明里 toolCalling = false。 */
    private void textProtocolPath(Demo demo) {
        demo.info("模型 %s 的声明：toolCalling = %s，streaming = %s",
                reactTextModel.name(),
                reactTextModel.capabilities().toolCalling(),
                reactTextModel.capabilities().streaming());

        commonTools.reset();
        reactTextModel.reset();
        AgentResult result = reactTextAgent.ask("现在几点了？");

        ChatRequest request = reactTextModel.lastRequest();
        demo.ok("模型实际收到过 Action / Action Input / Final Answer 的格式说明 = %s",
                hasProtocolPrimer(request));
        demo.info("注入位置是第一条 user 消息（系统提示词此时已渲染完成，只能靠新消息注入）：");
        if (request != null) {
            request.messages().stream()
                    .filter(m -> m.role() == Role.USER)
                    .findFirst()
                    .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 200)));
        }
        demo.info("工具结果以 Observation 回灌：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().startsWith("Observation:"))
                .forEach(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 160)));

        LoopReport.result(demo, result);
        demo.ok("最终答案就是 Final Answer 那一行的内容（含「文本协议收尾」）= %s",
                result.text().contains("文本协议收尾"));
        demo.info("模型被调用 %d 次：1 次出 Action + 1 次出 Final Answer", reactTextModel.calls());
        demo.info("口径提醒：文本协议下工具结果是 user 消息里的 Observation，**不是** role=tool，"
                + "因此不会被按 role=tool 扫描的机制（如 claude-code 的失败熔断）看见。");
    }

    /** 判断一次请求里有没有注入协议说明（演示与测试共用同一份判据）。 */
    public static boolean hasProtocolPrimer(ChatRequest request) {
        if (request == null || request.messages() == null) {
            return false;
        }
        return request.messages().stream()
                .anyMatch(m -> m.text() != null && m.text().contains(PROTOCOL_MARKER));
    }
}
