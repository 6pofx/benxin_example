package com.benxin.exp.local;

import com.benxin.exp.local.agent.LocalAgents;
import com.benxin.exp.local.config.LocalCallMetrics;
import com.benxin.exp.local.config.LocalModelGate;
import com.benxin.exp.local.tool.LocalTools;
import com.benxin.exp.local.vision.TestImageFactory;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线路径：**永远跑**，不依赖 LM Studio。
 *
 * <p>一个"要连本机服务"的示例如果连离线都跑不起来，那它就不是示例而是环境依赖。
 * 所以这里断言的是"没装 LM Studio 时也能得到的结论"：模型可插拔、端点可选、
 * 测试图可复现。</p>
 *
 * <p>{@code exp.demo.enabled=false}：示例的 {@code ApplicationRunner} 在
 * {@code @SpringBootTest} 里同样会被执行，而它开着真实调用 —— 测试里必须关掉，
 * 否则"跑测试"会顺带跑一遍全部演示。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@DisplayName("示例 12 · 离线路径（不依赖 LM Studio）")
class OfflinePathTest {

    @Autowired
    private LocalAgents.LocalAssistant assistant;

    @Autowired
    private LocalAgents.VisionAssistant visionAssistant;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private LocalModelGate gate;

    @Autowired
    private LocalTools localTools;

    @Autowired
    private LocalCallMetrics metrics;

    @Autowired
    private com.benxin.llm.core.loop.LoopRegistry loopRegistry;

    @Test
    @DisplayName("默认模型是离线的 mock：LM Studio 没开也能跑完整链路")
    void defaultModelIsOffline() {
        assertThat(modelRegistry.defaultName()).isEqualTo("mock");
        assertThat(modelRegistry.names()).contains("mock", "local-lmstudio");

        AgentResult result = agentRegistry.get("localAssistant").call("自我介绍", "offline-1", null);
        assertThat(result.modelName()).isEqualTo("mock");
        assertThat(result.text()).contains("离线脚本模型");
        assertThat(result.steps()).isEqualTo(1);
    }

    @Test
    @DisplayName("四个 Agent 接口都没有写 model 属性：换模型完全靠配置/派生")
    void modelIsNotHardcodedInBusinessCode() {
        assertThat(agentFactory.specOf(LocalAgents.LocalAssistant.class).model()).isNull();
        assertThat(agentFactory.specOf(LocalAgents.ToolAssistant.class).model()).isNull();
        assertThat(agentFactory.specOf(LocalAgents.VisionAssistant.class).model()).isNull();
        assertThat(agentFactory.specOf(LocalAgents.MemoryAssistant.class).model()).isNull();
    }

    @Test
    @DisplayName("本地端点已注册但**不会**被默认调用（注册 ≠ 调用）")
    void localEndpointIsRegisteredButNotUsedByDefault() {
        // 端点是从 yml 装配出来的模型，因此它一定在注册表里
        assertThat(modelRegistry.find("local-lmstudio")).isPresent();
        assertThat(modelRegistry.find("local-lmstudio").orElseThrow().name()).isEqualTo("local-lmstudio");

        // 默认路径打的是 mock：这一次调用的 modelName 就是证据
        AgentResult result = agentRegistry.get("localAssistant").call("自我介绍", "offline-2", null);
        assertThat(result.modelName()).isEqualTo("mock");
    }

    @Test
    @DisplayName("门控说明永远可读：无论端点开没开，都能一句话说清这次会/不会跑真实演示")
    void gateAlwaysExplainsItself() {
        String description = gate.describe();
        assertThat(description).contains("mode=", "端点", "真实演示");
        assertThat(gate.baseUrl()).isEqualTo("http://127.0.0.1:1234");
        assertThat(gate.chatCompletionsUrl()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
    }

    @Test
    @DisplayName("测试图是确定性生成的：白底 + 红圆 + 黑字，尺寸固定")
    void testImageIsDeterministic() throws Exception {
        byte[] png = TestImageFactory.pngBytes();
        assertThat(png).isNotEmpty();

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(TestImageFactory.WIDTH);
        assertThat(image.getHeight()).isEqualTo(TestImageFactory.HEIGHT);

        // 圆心附近应当是红的（红圆画在 6% 宽 / 12% 高处，直径 75% 高）
        int center = image.getRGB(140, 150) & 0xFFFFFF;
        int red = center >> 16 & 0xFF;
        assertThat(red).as("圆心像素应当是红色").isGreaterThan(200);

        // 右下角是白底
        int corner = image.getRGB(620, 300) & 0xFFFFFF;
        assertThat(corner).as("右下角应当是白底").isEqualTo(0xFFFFFF);
    }

    @Test
    @DisplayName("模态：ImagePart 能构造出 data URI 形态的内联图")
    void imagePartCarriesInlineData() {
        var part = com.benxin.llm.core.message.ImagePart.ofBase64("image/png", TestImageFactory.base64());
        assertThat(part.isInline()).isTrue();
        assertThat(part.mediaType()).isEqualTo("image/png");
        assertThat(part.toDataUri()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("工具与统计 bean 在离线路径上也可用（真实调用时靠它们取证）")
    void toolsAndMetricsAreWired() {
        assertThat(localTools).isNotNull();
        metrics.reset();
        assertThat(metrics.runCount()).isZero();
        assertThat(agentRegistry.names())
                .contains("localAssistant", "toolAssistant", "visionAssistant", "memoryAssistant");
    }

    @Test
    @DisplayName("模型 id 解析：从 /v1/models 的响应里取出 data[].id")
    void parsesModelIdsFromModelsEndpoint() {
        String body = """
                {"data":[{"id":"qwen/qwen3-vl-8b","object":"model"},
                         {"id":"text-embedding-bge-m3","object":"model"}],"object":"list"}""";
        List<String> ids = LocalModelGate.parseModelIds(body);
        assertThat(ids).containsExactly("qwen/qwen3-vl-8b", "text-embedding-bge-m3");
        assertThat(LocalModelGate.parseModelIds("not json")).isEmpty();
    }

    @Test
    @DisplayName("陷阱：toBuilder().model(\"名字\") 会被静默忽略 —— 派生副本必须传模型实例")
    void modelNameOverloadIsIgnoredOnDerivedBuilder() {
        // ① 按直觉的写法：结果是**继续用原模型**（这里就是离线的 mock）。
        //    原因：toBuilder() 已经把原模型实例放进了 builder，而 DefaultAgent 的解析顺序是
        //    "有实例就用实例，没实例才按名字查注册表"，于是字符串重载永远不会被读到。
        com.benxin.llm.core.agent.Agent byName = agentRegistry.get("localAssistant")
                .toBuilder().model("local-lmstudio").build();
        assertThat(byName.model().name())
                .as("字符串重载在派生副本上无效（这是实测行为，见 FINDINGS.md 问题 30）")
                .isEqualTo("mock");

        // ② 正确写法：传模型实例
        com.benxin.llm.core.agent.Agent byInstance = agentRegistry.get("localAssistant")
                .toBuilder().model(modelRegistry.get("local-lmstudio")).build();
        assertThat(byInstance.model().name()).isEqualTo("local-lmstudio");
    }

    @Test
    @DisplayName("陷阱：loop(\"名字\") 在派生副本上同样被忽略（与 model 同一个根因）")
    void loopNameOverloadIsAlsoIgnored() {
        com.benxin.llm.core.agent.Agent byName = agentRegistry.get("localAssistant")
                .toBuilder().loop("react").build();
        assertThat(byName.loop().name())
                .as("loop(String) 在派生副本上无效（实测行为，见 FINDINGS.md 问题 30）")
                .isEqualTo("dsh-minimal");

        com.benxin.llm.core.agent.Agent byInstance = agentRegistry.get("localAssistant")
                .toBuilder().loop(loopRegistry.get("react")).build();
        assertThat(byInstance.loop().name()).isEqualTo("react");
    }

    @Test
    @DisplayName("陷阱：toBuilder() 不携带监听器 —— 派生副本的真实调用不会进统计，必须显式补 listener")
    void derivedAgentLosesListeners() {
        metrics.reset();
        com.benxin.llm.core.agent.Agent without = agentRegistry.get("localAssistant").toBuilder().build();
        without.call("自我介绍", "derived-no-listener", null);
        assertThat(metrics.runCount())
                .as("派生副本默认没有监听器（实测行为，见 FINDINGS.md 问题 31）")
                .isZero();

        metrics.reset();
        com.benxin.llm.core.agent.Agent with = agentRegistry.get("localAssistant").toBuilder()
                .listener(metrics).build();
        with.call("自我介绍", "derived-with-listener", null);
        assertThat(metrics.runCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("mode=off 时即使端点可达也不跑真实演示")
    void modeOffForcesOffline() {
        LocalModelGate off = new LocalModelGate("off", "http://127.0.0.1:1234", "qwen/qwen3-vl-8b");
        assertThat(off.enabled()).isFalse();
        assertThat(off.describe()).contains("mode=off", "跳过");
    }

    @Test
    @DisplayName("mode=on 但端点不可达时，requireEnabled 明确报错而不是静默跳过")
    void modeOnFailsLoudlyWhenUnreachable() {
        // 用一个几乎不可能有人监听的端口，确保探测必然失败
        LocalModelGate on = new LocalModelGate("on", "http://127.0.0.1:59321", "whatever");
        assertThat(on.enabled()).as("mode=on 表示「我就是要测它」，因此 enabled 恒为真").isTrue();
        assertThat(on.available()).isFalse();
        assertThat(on.describe()).contains("不可达", "配置错误");
        org.assertj.core.api.Assertions.assertThatThrownBy(on::requireEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可达");
    }
}
