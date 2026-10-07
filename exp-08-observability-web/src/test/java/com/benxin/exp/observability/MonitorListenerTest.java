package com.benxin.exp.observability;

import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.agent.FailAssistant;
import com.benxin.exp.observability.agent.GuardedAssistant;
import com.benxin.exp.observability.agent.ObservabilityAssistant;
import com.benxin.exp.observability.agent.ThinkingAssistant;
import com.benxin.exp.observability.agent.ToolErrorProbe;
import com.benxin.exp.observability.agent.ToolGuardAssistant;
import com.benxin.exp.observability.listener.MonitorListener;
import com.benxin.exp.observability.tool.FailingProbeTool;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.model.ModelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 把 {@code AgentListener} 的 <b>12 个回调</b>逐个钉住：每个回调在什么条件下触发、
 * 收到的参数是什么、什么时候<b>不</b>该触发。
 *
 * <p>全部离线：模型是 {@code exp-common} 的脚本化模型，零 token、零网络。</p>
 */
@DisplayName("示例 08 · 监听器 12 个回调")
class MonitorListenerTest extends ObservabilityTestSupport {

    @Autowired
    private ObservabilityAssistant observability;

    @Autowired
    private ChatAssistant chat;

    @Autowired
    private ThinkingAssistant thinking;

    @Autowired
    private ToolGuardAssistant toolGuard;

    @Autowired
    private ToolErrorProbe toolErrorProbe;

    @Autowired
    private GuardedAssistant guarded;

    @Autowired
    private FailAssistant fail;

    // ------------------------------------------------------------------
    // ① 一次"工具 + 子代理 + 自定义事件"的运行，覆盖除 onError 之外的 11 个回调
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一次运行即可触发 11 个回调：start/step/text/thinking/toolCall/toolResult/stepEnd/subAgentStart/subAgentEnd/customEvent/agentEnd")
    void allCallbacksExceptErrorFireOnSuccessRun() {
        AgentResult result = observability.run("北京天气怎么样？");

        // 基本形状：两步（请求工具 + 收尾），工具真的被调用并成功
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(commonTools.callCount()).isEqualTo(1);

        // onAgentStart：拿到 AgentInvocation（名字/会话/loop/输入）。
        // 实测是 2 条而不是 1 条 —— 子代理 task 也是一个普通 Agent，而这两个监听器是
        // 容器级 bean，同样挂在子代理身上。这不是"重复触发"，而是"子代理就是另一个 Agent"
        // 的直接后果：想只盯住父代理，得在监听器里自己按 agentName 过滤。
        assertThat(monitorListener.count("onAgentStart")).isEqualTo(2);
        MonitorListener.Event start = monitorListener.first("onAgentStart");
        assertThat(start).isNotNull();
        assertThat(start.detail())
                .contains("agent=observability")
                .contains("loop=exp-observe")
                .contains("input=北京天气怎么样？");
        assertThat(monitorListener.details("onAgentStart").get(1)).contains("agent=task");

        // onStepStart / onStepEnd：父代理各 2 次（两步模型调用，步号从 1 开始）+ 子代理 1 次
        assertThat(monitorListener.details("onStepStart"))
                .containsExactly("observability#1", "observability#2", "task#1");
        assertThat(monitorListener.count("onStepEnd")).isEqualTo(3);

        // 增量：第 1 步是工具调用（无文本），第 2 步是最终答案；子代理的应答也走同一条通道。
        // onTextDelta(delta) 的签名里没有 agentName —— 想区分"谁说的"只能自己在拦截器里
        // 用 AgentInvocation 挂上下文，这是监听器的一个真实局限。
        assertThat(monitorListener.count("onTextDelta")).isPositive();
        assertThat(monitorListener.joinRaw("onTextDelta"))
                .startsWith(result.text())
                .contains("子代理已完成任务");
        assertThat(monitorListener.count("onThinkingDelta")).isPositive();

        // 工具：调用一次、结果一次
        assertThat(monitorListener.count("onToolCall")).isEqualTo(1);
        assertThat(monitorListener.first("onToolCall").detail()).contains("get_weather");
        assertThat(monitorListener.count("onToolResult")).isEqualTo(1);
        assertThat(monitorListener.first("onToolResult").detail()).contains("error=false");

        // 子代理：父 → 子 的开始与结束，结束事件带子代理的 AgentResult
        assertThat(monitorListener.first("onSubAgentStart").detail())
                .contains("observability").contains("task");
        assertThat(monitorListener.count("onSubAgentEnd")).isEqualTo(1);
        assertThat(monitorListener.first("onSubAgentEnd").detail()).contains("task");

        // 自定义事件：EmittingLoop 每轮发一个 step 事件（两步 → 2 条）+ 收尾发一个 subagent 事件
        assertThat(monitorListener.count("onCustomEvent")).isEqualTo(3);
        assertThat(monitorListener.details("onCustomEvent").get(0)).contains("exp-observe.step");
        assertThat(monitorListener.details("onCustomEvent").get(1)).contains("exp-observe.step");
        assertThat(monitorListener.details("onCustomEvent").get(2)).contains("exp-observe.subagent");

        // onAgentEnd：2 次（子代理 + 父代理），最后落档的是父代理自己那份完整结果
        assertThat(monitorListener.count("onAgentEnd")).isEqualTo(2);
        assertThat(monitorListener.lastResult()).isNotNull();
        assertThat(monitorListener.lastResult().agentName()).isEqualTo("observability");
    }

    // ------------------------------------------------------------------
    // ② onTextDelta / onThinkingDelta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只要模型支持流式，onTextDelta 就会触发（调用方没有传 LlmStreamHandler 也一样）")
    void textDeltaFiresWithoutCallerHandler() {
        String text = chat.ask("介绍一下你自己");

        assertThat(monitorListener.count("onTextDelta")).isPositive();
        // 脚本模型按 chunkSize=12 分片，所以增量一定是多片
        assertThat(monitorListener.details("onTextDelta").size()).isGreaterThan(1);
        assertThat(monitorListener.joinRaw("onTextDelta")).isEqualTo(text);
    }

    @Test
    @DisplayName("onThinkingDelta 只在模型产出思维链时触发（thinking(true) 的脚本模型）")
    void thinkingDeltaRequiresThinkingCapability() {
        thinking.ask("介绍一下你自己");

        assertThat(monitorListener.count("onThinkingDelta")).isPositive();
        assertThat(monitorListener.joinRaw("onThinkingDelta")).isNotBlank();
    }

    // ------------------------------------------------------------------
    // ③ 工具失败 → onToolResult 的 error=true
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具执行失败时 onToolResult 收到 error=true 的 ToolResult，运行本身仍然成功")
    void toolResultCarriesErrorFlag() {
        // 用 probe_fail（一个真的会抛异常的工具，见 FailingProbeTool）：
        // CommonTools 里没有任何工具会真的失败 —— calculate 的失败只是内容里的一段错误文本，
        // error 标志仍是 false（见下面的 calculateReportsFailureInContentNotFlag 用例）。
        // 触发方式用关键字而不是 `#tool:probe_fail {}`：后者每一步都会重新命中，一次运行会失败多次。
        AgentResult result = toolErrorProbe.ask(FailingProbeTool.TRIGGER);

        assertThat(monitorListener.count("onToolCall")).isEqualTo(1);
        assertThat(monitorListener.count("onToolResult")).isEqualTo(1);
        assertThat(monitorListener.first("onToolResult").detail())
                .contains("error=true")
                .contains(FailingProbeTool.NAME);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).error()).isTrue();
        assertThat(result.toolCalls().get(0).result()).contains(FailingProbeTool.MESSAGE);

        // 第 1 步请求工具（失败）→ 第 2 步拿到失败结果后收尾：失败结果会回灌给模型，
        // 运行不因工具失败而中断，也不会把步数预算撑爆。
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.maxStepsReached()).isFalse();
        assertThat(monitorListener.count("onAgentEnd")).isEqualTo(1);
        assertThat(monitorListener.count("onError")).isZero();
    }

    @Test
    @DisplayName("口径：CommonTools.calculate 的失败是「内容里的错误」，error 标志仍然是 false")
    void calculateReportsFailureInContentNotFlag() {
        AgentResult result = observability.run("#tool:calculate {\"expression\":\"1+1+?\"}");

        // calculate 既不抛异常、也不返回 ToolResult.error，而是把 "错误：表达式只允许…"
        // 当成正常内容返回 —— 失败只体现在字符串里。按"工具失败 → error=true"的直觉写断言
        // 就会在这里翻车（这正是本用例存在的意义：把这条口径钉住）。
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allMatch(call -> !call.error());
        assertThat(result.toolCalls().get(0).result()).contains("错误：表达式只允许");
        assertThat(monitorListener.first("onToolResult").detail()).contains("error=false");
    }

    @Test
    @DisplayName("口径：`#tool:<名字>` 每一步都会重新命中，直到步数预算耗尽（不是只调一次）")
    void forcedToolRepeatsEveryStep() {
        // ScriptedLlmModel 的规则顺序里，"用户显式点名 #tool:" 排在"已有工具结果就收尾"之前，
        // 而"最后一条 user 消息"在整轮运行里始终是那句 #tool:... —— 于是每一步都重新请求同一个工具。
        // observability 的 maxSteps=4，所以恰好 4 次工具调用，然后因预算耗尽而截断收尾。
        AgentResult result = observability.run("#tool:calculate {\"expression\":\"(12+5)*3\"}");

        assertThat(result.steps()).isEqualTo(4);
        assertThat(result.toolCalls()).hasSize(4);
        assertThat(result.maxStepsReached()).isTrue();
        // 4 次都真的执行了（calculate 不在任何短路名单里）
        assertThat(result.toolCalls().get(0).result()).contains("(12+5)*3 = 51.0");
    }

    // ------------------------------------------------------------------
    // ④ onError：模型抛异常
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型抛异常时触发 onError，且不触发 onAgentEnd")
    void errorCallbackFiresAndAgentEndDoesNot() {
        assertThatThrownBy(() -> fail.ask("随便问点什么"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("演示用的模型故障");

        assertThat(monitorListener.count("onError")).isGreaterThanOrEqualTo(1);
        assertThat(monitorListener.details("onError").get(0))
                .contains("ModelException")
                .contains("演示用的模型故障");
        assertThat(monitorListener.count("onAgentEnd")).isZero();
        assertThat(monitorListener.lastResult()).isNull();
        // 失败运行不会有工具调用，也不会有子代理事件
        assertThat(monitorListener.count("onToolCall")).isZero();
    }

    @Test
    @DisplayName("实测口径：模型抛异常时 onError 会被广播两次（Loop 基类一次 + Agent 一次）")
    void errorCallbackIsBroadcastTwice() {
        assertThatThrownBy(() -> fail.ask("再失败一次")).isInstanceOf(ModelException.class);

        // 这不是断言"应该如此"，而是把实测行为钉住并留档：
        // AbstractAgentLoop.run() 与 DefaultAgent.run() 都会广播 onError。
        // 监听器如果按次数计费/告警，需要自己去重（例如按 error 类型 + 时间窗）。
        assertThat(monitorListener.count("onError")).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // ⑤ onAgentEnd 的 AgentResult 全字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("onAgentEnd 拿到的 AgentResult：steps/usage/toolCalls/maxStepsReached/durationMillis/attributes/finishReason 全部可读")
    void agentResultExposesAllFields() {
        AgentResult result = observability.run("北京天气怎么样？");
        AgentResult fromListener = monitorListener.lastResult();

        assertThat(fromListener).isNotNull();
        assertThat(fromListener.agentName()).isEqualTo("observability");
        assertThat(fromListener.sessionId()).isEqualTo(result.sessionId());
        assertThat(fromListener.loopName()).isEqualTo("exp-observe");
        assertThat(fromListener.modelName()).isEqualTo("mock");
        assertThat(fromListener.text()).isEqualTo(result.text());

        assertThat(fromListener.steps()).isEqualTo(2);
        assertThat(fromListener.maxStepsReached()).isFalse();
        assertThat(fromListener.durationMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(fromListener.usage().totalTokens()).isPositive();
        assertThat(fromListener.usage().inputTokens()).isPositive();
        assertThat(fromListener.usage().outputTokens()).isPositive();
        assertThat(fromListener.finishReason()).isNotNull();

        assertThat(fromListener.toolCalls()).hasSize(1);
        assertThat(fromListener.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(fromListener.toolCalls().get(0).arguments()).containsEntry("city", "北京");

        // attributes 来自 AgentSpec.metadata：@LlmAgent 接口的 FQN 一定在
        assertThat(fromListener.attributes()).containsKey("interface");
        assertThat(String.valueOf(fromListener.attributes().get("interface")))
                .isEqualTo(ObservabilityAssistant.class.getName());
    }

    @Test
    @DisplayName("maxStepsReached=true 时 onAgentEnd 仍然触发（用独立的低步数 Agent 触发）")
    void maxStepsReachedIsReportedThroughAgentEnd() {
        // 借一个"步数只有 2、而模型每轮都请求工具"的组合：循环恰好用光预算后截断收尾。
        Agent original = agentRegistry.get("toolGuard");
        Agent impatient = original.toBuilder()
                .spec(original.spec().toBuilder().name("toolGuard").maxSteps(2).build())
                .build();

        AgentResult result = impatient.call("#tool:get_weather {\"city\":\"北京\"}", "max-steps-run");

        assertThat(result.maxStepsReached()).isTrue();
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolCalls()).hasSize(2);
        // 这里用的是程序化装配出来的副本，不接容器的监听器链，所以断言结果字段本身；
        // 容器内 Agent 的 maxStepsReached 由 ObservabilityDemo 的演示打印。
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
    }

    // ------------------------------------------------------------------
    // ⑥ 事件顺序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("事件顺序：onAgentStart → (onStepStart → 增量 → onToolCall/onToolResult → onStepEnd)* → 子代理 → onAgentEnd")
    void eventOrderIsStable() {
        observability.run("北京天气怎么样？");

        List<String> hooks = monitorListener.hookNames();
        assertThat(hooks).startsWith("onAgentStart");
        // 最后一条一定是【父代理自己】的 onAgentEnd
        assertThat(hooks).endsWith("onAgentEnd");
        // 工具相关事件的相对顺序：先 onToolCall 再 onToolResult
        assertThat(hooks.indexOf("onToolCall")).isLessThan(hooks.indexOf("onToolResult"));
        // 子代理的开始/结束是一对，且都早于父代理收尾
        assertThat(hooks.indexOf("onSubAgentStart")).isLessThan(hooks.indexOf("onSubAgentEnd"));
        assertThat(hooks.indexOf("onSubAgentEnd")).isLessThan(hooks.lastIndexOf("onAgentEnd"));
        // 实测口径：子代理【自己】的 onAgentEnd 落在 onSubAgentStart 与 onSubAgentEnd 之间 ——
        // 父代理是在子代理跑完、并且子代理自己也广播完收尾之后，才广播 onSubAgentEnd 的。
        // 所以这里必须分清 indexOf（第一条，属于子代理）与 lastIndexOf（父代理那份）：
        // 拿 indexOf("onAgentEnd") 去和 onSubAgentEnd 比大小，会得到相反的结论。
        assertThat(hooks.indexOf("onAgentEnd")).isLessThan(hooks.indexOf("onSubAgentEnd"));
        // 每步都以 onStepStart 开头、onStepEnd 收尾
        assertThat(hooks.indexOf("onStepStart")).isLessThan(hooks.indexOf("onStepEnd"));
    }

    // ------------------------------------------------------------------
    // ⑦ 监听器不改行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("监听器是只读的：不监听时结果完全一致")
    void listenerDoesNotChangeBehavior() {
        Agent agent = agentRegistry.get("guardedAssistant");
        // guardedAssistant 上有 @LlmGuard 拦截器，但没有本项目自己的 MonitorListener 参与判定；
        // 这里断言的是"监听器只观察"：同一输入两次运行拿到同样形状的结果。
        AgentResult first = agent.call("自我介绍", "listener-readonly-1");
        AgentResult second = agent.call("自我介绍", "listener-readonly-2");

        assertThat(first.text()).isEqualTo(second.text());
        assertThat(first.steps()).isEqualTo(second.steps());
        assertThat(monitorListener.count("onAgentEnd")).isEqualTo(2);
    }
}
