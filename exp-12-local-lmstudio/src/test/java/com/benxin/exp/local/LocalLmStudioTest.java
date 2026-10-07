package com.benxin.exp.local;

import com.benxin.exp.local.agent.LocalAgents;
import com.benxin.exp.local.config.LocalCallMetrics;
import com.benxin.exp.local.config.LocalModelGate;
import com.benxin.exp.local.tool.LocalTools;
import com.benxin.exp.local.vision.TestImageFactory;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实调用：用本机 LM Studio 上的 Qwen3-VL-8B 跑一遍。
 *
 * <p><b>LM Studio 没开时这一整个类会 skip，而不是 fail</b>（{@link Assumptions#assumeTrue}）——
 * 这是"可选依赖"的正确姿态：CI 上没有桌面环境，本地开发者机器上有。</p>
 *
 * <p>断言刻意写得"松"：真实小模型的措辞每次都不同，能稳定断言的是
 * <b>结构化事实</b>（走了几步、有没有发起工具调用、增量拼起来是否等于返回值、
 * 看图时能不能指出图中的数字），而不是它具体怎么措辞。</p>
 */
@SpringBootTest(properties = {"exp.demo.enabled=false", "exp.local.mode=auto"})
@DisplayName("示例 12 · 真实本机模型（LM Studio / Qwen3-VL-8B）")
class LocalLmStudioTest {

    private static final String LOCAL_MODEL = "local-lmstudio";

    @Autowired
    private LocalAgents.LocalAssistant assistant;

    @Autowired
    private LocalAgents.ToolAssistant toolAssistant;

    @Autowired
    private LocalAgents.VisionAssistant visionAssistant;

    @Autowired
    private LocalAgents.MemoryAssistant memoryAssistant;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LocalModelGate gate;

    @Autowired
    private LocalTools localTools;

    @Autowired
    private LocalCallMetrics metrics;

    @Autowired
    private com.benxin.llm.core.model.ModelRegistry modelRegistry;

    @Autowired
    private com.benxin.llm.core.loop.LoopRegistry loopRegistry;

    @BeforeEach
    void requireLocalEndpoint() {
        Assumptions.assumeTrue(gate.available(),
                () -> "本机 LM Studio 端点不可用，跳过真实调用测试：" + gate.describe());
        metrics.reset();
        localTools.reset();
        agentRegistry.get("memoryAssistant").clearHistory("memory-real");
    }

    private Agent local(String agentName) {
        // 传模型**实例**而不是名字，并显式补回监听器：
        //   · model(String) 在派生副本上会被静默忽略（DefaultAgent 的解析顺序是"实例优先"）；
        //   · toBuilder() 不携带监听器，不补则真实调用不会进统计。
        // 两条都是实测行为，见 FINDINGS.md 问题 30 / 31。
        return agentRegistry.get(agentName).toBuilder()
                .model(modelRegistry.get(LOCAL_MODEL))
                .listener(metrics)
                .build();
    }

    @Test
    @DisplayName("真实对话：非空回答 + 用量 + 确实是本地模型")
    void realChat() {
        AgentResult result = local("localAssistant").call("用一句话说明你是什么模型。", "real-chat", null);

        assertThat(result.modelName()).as("走的是本机端点，而不是离线的 mock").isEqualTo(LOCAL_MODEL);
        assertThat(result.text()).isNotBlank();
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.usage().totalTokens()).isPositive();
        assertThat(metrics.runCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("真实 function calling：模型自己发起工具调用，结果回灌后收尾")
    void realToolCall() {
        AgentResult result = local("toolAssistant")
                .call("现在几点了？请调用工具查一下，不要自己编。", "real-tools", null);

        assertThat(result.toolCalls()).as("真实模型应当发起至少一次工具调用").isNotEmpty();
        assertThat(result.toolCalls()).anyMatch(call -> call.name().equals("now"));
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(localTools.callCount()).isPositive();
        assertThat(result.steps()).isGreaterThanOrEqualTo(2);
        assertThat(result.text()).isNotBlank();
    }

    @Test
    @DisplayName("真实流式：增量拼起来等于返回值，且 onStart 恰好一次")
    void realStreaming() {
        StringBuilder collected = new StringBuilder();
        int[] starts = {0};

        AgentResult result = local("localAssistant").call("从 1 数到 5，只用数字和空格。", "real-stream",
                new LlmStreamHandler() {
                    @Override
                    public void onStart() {
                        starts[0]++;
                    }

                    @Override
                    public void onTextDelta(String delta) {
                        collected.append(delta);
                    }

                    @Override
                    public void onUsage(Usage usage) {
                        assertThat(usage.totalTokens()).isPositive();
                    }
                });

        assertThat(starts[0]).isEqualTo(1);
        assertThat(collected.toString()).isEqualTo(result.text());
        assertThat(collected.length()).isPositive();
    }

    @Test
    @DisplayName("真实视觉：把运行时生成的图交给模型，颜色与数字都要读对")
    void realVision() {
        ChatMessage message = ChatMessage.user(
                new TextPart(TestImageFactory.question()),
                ImagePart.ofBase64("image/png", TestImageFactory.base64()));

        AgentResult result = local("visionAssistant").call(List.of(message));

        assertThat(result.text()).as("视觉模型必须给出文字回答").isNotBlank();
        assertThat(result.text())
                .as("图里的数字是 %s。实际回答：%s", TestImageFactory.NUMBER, result.text())
                .contains(TestImageFactory.NUMBER);
        assertThat(result.text())
                .as("图中的圆形是红色。实际回答：%s", result.text())
                .contains(TestImageFactory.COLOR);
    }

    @Test
    @DisplayName("真实多轮记忆：第二问不复述实体也能答对")
    void realMemory() {
        // 声明式接口用的是默认模型（mock），要"用本地模型跑多轮"必须先派生副本
        Agent local = local("memoryAssistant");
        String first = local.call("记住一个代号：青竹。只回复「好」。", "memory-real", null).text();
        String second = local.call("刚才的代号是什么？只回复代号。", "memory-real", null).text();

        assertThat(first).isNotBlank();
        assertThat(second).contains("青竹");
        assertThat(local.history("memory-real")).hasSize(4);
    }

    @Test
    @DisplayName("真实 Loop 对照：两个副本只差 loop，都能跑通")
    void realLoops() {
        AgentResult minimal = local("localAssistant").toBuilder()
                .loop(loopRegistry.get("dsh-minimal")).build()
                .call("回答一个字：好", "real-loop-1", null);
        AgentResult react = local("localAssistant").toBuilder()
                .loop(loopRegistry.get("react")).build()
                .call("回答一个字：好", "real-loop-2", null);

        assertThat(minimal.loopName()).isEqualTo("dsh-minimal");
        assertThat(react.loopName()).isEqualTo("react");
        assertThat(minimal.text()).isNotBlank();
        assertThat(react.text()).isNotBlank();
    }

    @Test
    @DisplayName("本地端点不花钱：用量被统计下来，可用来判断这个模型吃多少上下文")
    void usageIsMeasuredLocally() {
        local("localAssistant").call("只回复：好", "real-usage", null);

        assertThat(metrics.totalTokens()).isPositive();
        assertThat(metrics.runs()).hasSize(1);
        assertThat(metrics.toolErrors()).isZero();
    }
}
