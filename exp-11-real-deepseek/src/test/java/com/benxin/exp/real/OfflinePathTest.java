package com.benxin.exp.real;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.real.agent.MinimalLoopAssistant;
import com.benxin.exp.real.agent.ReactLoopAssistant;
import com.benxin.exp.real.agent.TimeAssistant;
import com.benxin.exp.real.agent.TinyAssistant;
import com.benxin.exp.real.config.RealCallGate;
import com.benxin.exp.real.config.RealCallMetrics;
import com.benxin.exp.real.tool.TinyTools;
import com.benxin.exp.real.wire.WireLogTransport;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线回归：把真实演示里<b>与网络无关</b>的那部分代码路径全部覆盖一遍。
 *
 * <p>关键点在于"覆盖同一条路径"：这里用的 Agent 接口、Loop、工具、报文留档传输层，
 * 与真实调用时<b>完全是同一批对象</b>，唯一不同的是模型换成了 {@code exp-common} 的
 * {@code ScriptedLlmModel}。所以这些用例既不联网、不花 token，又能在 CI 里长期防回归。</p>
 *
 * <p>本类刻意关掉演示（{@code exp.demo.enabled=false}）：{@code @SpringBootTest} 会调用
 * {@code SpringApplication.run}，因此 {@code ApplicationRunner} 本来会被执行 ——
 * 在真实 profile 下那意味着一次真实的、要花钱的运行。示例里把这件事显式关掉，
 * "测试"和"演示"的账才分得清。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@DisplayName("示例 11 · 离线路径（零 token）")
class OfflinePathTest {

    @Autowired
    private TinyAssistant tiny;

    @Autowired
    private TimeAssistant time;

    @Autowired
    private MinimalLoopAssistant minimalLoop;

    @Autowired
    private ReactLoopAssistant reactLoop;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private TinyTools tinyTools;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LoopRegistry loopRegistry;

    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private RealCallGate gate;

    @Autowired
    private ObjectProvider<RealCallMetrics> metricsProvider;

    @Autowired
    private HttpTransport transport;

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        tinyTools.reset();
        // 记忆是按 sessionId 存的，测试之间共享同一个 ApplicationContext，
        // 因此必须显式清空 —— 否则前一个用例的对话会成为后一个用例的上下文。
        // 注意要遍历 names() + find()：@LlmAgent 接口在注册表里是惰性条目，
        // resolved() 只列出"已被解析过"的那些。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(
                agent -> agent.clearHistory(agent.name() + "-default")));
    }

    @Test
    @DisplayName("默认 profile 里没有 deepseek 端点，真实调用闸门是关的")
    void offlineByDefault() {
        assertThat(gate.enabled()).isFalse();
        assertThat(modelRegistry.names()).containsExactly("mock");
        assertThat(modelRegistry.find("deepseek")).isEmpty();
        assertThat(modelRegistry.find("deepseek-responses")).isEmpty();
        // RealCallMetrics 只在 real 模式装配 —— 离线模式的"估算用量"不该被算进真实消耗
        assertThat(metricsProvider.getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("@LlmAgent 上没有写 model 属性：真实模型由 llm.default-model 决定")
    void modelComesFromConfigurationNotFromCode() {
        assertThat(agentFactory.specOf(TinyAssistant.class).model()).isNull();
        assertThat(agentFactory.specOf(TimeAssistant.class).model()).isNull();
        assertThat(agentRegistry.get("tinyAssistant").model().name()).isEqualTo("mock");
    }

    @Test
    @DisplayName("最小对话：同一段代码在离线模型上跑通并返回回答")
    void minimalCallOnOfflineModel() {
        AgentResult result = tiny.result("offline-minimal", "1+1=?");

        assertThat(result.text()).isEqualTo("2");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.modelName()).isEqualTo("mock");
        assertThat(result.usage().totalTokens()).isPositive();
        assertThat(mockLlmModel.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("工具闭环：模型请求工具 → 工具被真实执行 → 结果回灌后收尾（steps == 2）")
    void toolLoopOnOfflineModel() {
        AgentResult result = time.ask("现在几点？");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("now");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(result.steps()).isEqualTo(2);
        assertThat(tinyTools.callCount()).isEqualTo(1);
        // 工具真的跑了：回答里带着脚本模型对工具结果的转述
        assertThat(result.text()).contains("根据工具返回的结果");
    }

    @Test
    @DisplayName("流式：LlmStreamHandler 收到增量，且拼接结果与返回值一致")
    void streamingOnOfflineModel() {
        StringBuilder collected = new StringBuilder();
        AtomicInteger deltas = new AtomicInteger();
        boolean[] started = {false};

        String text = tiny.stream("offline-stream", "只回复两个字：收到", new LlmStreamHandler() {
            @Override
            public void onStart() {
                started[0] = true;
            }

            @Override
            public void onTextDelta(String delta) {
                deltas.incrementAndGet();
                collected.append(delta);
            }
        });

        assertThat(started[0]).isTrue();
        assertThat(deltas.get()).isPositive();
        assertThat(text).isEqualTo(collected.toString());
    }

    @Test
    @DisplayName("多轮记忆：同一个 sessionId 连续两问，历史续接（4 条）")
    void memoryOnOfflineModel() {
        tiny.chatWithMemory("offline-mem", "记住代号：青龙。只回复：好");
        String second = tiny.chatWithMemory("offline-mem", "代号是什么？只回复代号");

        assertThat(second).isEqualTo("青龙");
        assertThat(agentRegistry.get("tinyAssistant").history("offline-mem")).hasSize(4);
    }

    @Test
    @DisplayName("Loop 对照的两个接口都能在离线模型上跑通")
    void bothLoopsRunOffline() {
        assertThat(loopRegistry.names()).contains("dsh-minimal", "react");

        AgentResult minimal = minimalLoop.run("不要调用任何工具，只回复两个字：完成");
        AgentResult react = reactLoop.run("不要调用任何工具，只回复两个字：完成");

        assertThat(minimal.loopName()).isEqualTo("dsh-minimal");
        assertThat(react.loopName()).isEqualTo("react");
        assertThat(minimal.text()).isNotBlank();
        assertThat(react.text()).isNotBlank();
    }

    @Test
    @DisplayName("报文留档传输层已经接管 HttpTransport（真正的装饰发生在真实调用之前）")
    void wireLogTransportTakesOver() {
        assertThat(transport).isInstanceOf(WireLogTransport.class);
        assertThat(((WireLogTransport) transport).delegate()).isNotNull();
    }

    @Test
    @DisplayName("换模型不改业务代码：同一个 Agent 换掉 model 即可复用全部装配")
    void swapModelWithoutTouchingBusinessCode() {
        Agent agent = agentRegistry.get("tinyAssistant");
        Agent offline = agent.toBuilder().model(mockLlmModel).build();

        // 业务侧的一切（名字 / Loop / 工具表 / 规格）原样保留，只有模型被换掉
        assertThat(offline.spec().name()).isEqualTo(agent.spec().name());
        assertThat(offline.loop().name()).isEqualTo(agent.loop().name());
        assertThat(offline.tools().names()).isEqualTo(agent.tools().names());
        assertThat(offline.model()).isSameAs(mockLlmModel);
        assertThat(offline.model().name()).isEqualTo("mock");
        assertThat(offline.call("1+1=?", "swap-model-session").text()).isEqualTo("2");
    }

    @Test
    @DisplayName("全局工具表就是这一次演示的全部工具（token 成本是可数的）")
    void globalToolCatalogIsDeliberatelyTiny() {
        assertThat(agentRegistry.get("tinyAssistant").tools().names()).containsExactly("now");
        assertThat(agentRegistry.get("timeAssistant").tools().names()).containsExactly("now");
        assertThat(modelRegistry.defaultModel()).isInstanceOf(LlmModel.class);
    }
}
