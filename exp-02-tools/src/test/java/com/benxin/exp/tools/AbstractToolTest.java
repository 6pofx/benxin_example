package com.benxin.exp.tools;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.tools.support.ConcurrencyRecorder;
import com.benxin.exp.tools.support.GateOrderProbe;
import com.benxin.exp.tools.support.RecordingApprovalHandler;
import com.benxin.exp.tools.support.ScriptedToolCallModel;
import com.benxin.exp.tools.support.ToolEventCollector;
import com.benxin.exp.tools.support.ToolFixtures;
import com.benxin.exp.tools.tool.ParameterSpectrumTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.tool.ToolCallRecord;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三个测试类的共同底座：注入同一批夹具，并在每个用例前把"共享的可变状态"清干净。
 *
 * <p>为什么要清历史：声明式 Agent 默认开着记忆，且不传 sessionId 时所有调用都落在
 * {@code <agentName>-default} 上。测试共享同一个 ApplicationContext，
 * 上一个用例的对话会成为下一个用例的上下文 —— 于是"这一轮模型到底看到了什么"就不可复现了。</p>
 *
 * <p>必须走 {@code names()} + {@code find()}：{@code @LlmAgent} 接口在注册表里是<b>惰性条目</b>
 * （{@code registerLazy}），{@code resolved()} 只列出已经被解析过的那些，用它遍历一个都清不掉。</p>
 */
@SpringBootTest
abstract class AbstractToolTest {

    @Autowired
    protected RecordingModel mockLlmModel;

    @Autowired
    protected ScriptedToolCallModel scriptedModel;

    @Autowired
    protected AgentRegistry agentRegistry;

    @Autowired
    protected LlmAgentFactory agentFactory;

    @Autowired
    protected LlmToolCatalog catalog;

    @Autowired
    protected ToolEventCollector events;

    @Autowired
    protected RecordingApprovalHandler approvals;

    @Autowired
    protected ConcurrencyRecorder recorder;

    @Autowired
    protected GateOrderProbe gateProbe;

    @Autowired
    protected ParameterSpectrumTools paramSpectrum;

    @Autowired
    protected CommonTools commonTools;

    private final Map<String, Agent> scriptedAgents = new ConcurrentHashMap<>();

    /** 本测试类显式用到的会话 id —— 用例之间也要清，否则会互相续上上下文。 */
    protected abstract List<String> sessions();

    @BeforeEach
    void resetSharedState() {
        mockLlmModel.reset();
        scriptedModel.reset();
        events.reset();
        approvals.reset();
        recorder.reset();
        gateProbe.reset();
        commonTools.reset();
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            sessions().forEach(agent::clearHistory);
        }));
    }

    /** 组装一个由"脚本化工具调用模型"驱动的 Agent（工具表 = 容器全局工具表）。 */
    protected Agent scriptedAgent(String name) {
        // 按名字缓存：Agent 无状态（memory = false，会话 id 每次调用传入），
        // 反复 create 只会刷满 LlmAgentFactory 的装配日志。
        return scriptedAgents.computeIfAbsent(name, key -> agentFactory.create(AgentSpec.builder()
                .name(key)
                .model("scripted-tools")
                .memory(false)
                .maxSteps(4)
                .build()));
    }

    /**
     * 让脚本模型发起一次工具调用并跑完整个闭环，返回运行结果。
     *
     * <p>工具调用一律走这条路，而不是 exp-common 的 {@code #tool:名字 {JSON}} 语法：
     * 后者在"工具确实存在"时每轮都会重新命中同一条规则，直到步数预算耗尽
     * （详见 README 的「口径」一节），拿它做确定性断言会被步数上限悄悄影响。</p>
     */
    protected AgentResult runScripted(String toolName, String argumentsJson, String sessionId) {
        scriptedModel.reset();
        scriptedModel.scriptCall(toolName, argumentsJson);
        return scriptedAgent("scriptedAgent").call("调用 " + toolName, sessionId);
    }

    /** 一次运行里的第 index 条工具记录（越界时直接给出可读的失败信息）。 */
    protected static ToolCallRecord call(AgentResult result, int index) {
        if (result.toolCalls().size() <= index) {
            throw new AssertionError("期望至少 " + (index + 1) + " 条工具记录，实际只有 "
                    + result.toolCalls().size() + " 条：" + result.toolCalls());
        }
        return result.toolCalls().get(index);
    }

    protected static String oneLine(String text) {
        return ToolFixtures.oneLine(text, 200);
    }
}
