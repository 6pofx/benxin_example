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
    @DisplayName("toBuilder().model(\"名字\") 与 loop(\"名字\") 都能换（1.1.1 修复：两个重载后来者胜）")
    void nameOverloadsWorkOnDerivedBuilder() {
        // 1.1.0 时两者都会被静默忽略（toBuilder() 已经把实例放进 builder，DefaultAgent 实例优先）——
        // 本工程上报为 FINDINGS 第 30 条；当时"真实模型用例全绿"其实一次都没打到 LM Studio。
        // 1.1.1 起 model(String)/loop(String) 会把实例置空，后来者胜。
        com.benxin.llm.core.agent.Agent byName = agentRegistry.get("localAssistant")
                .toBuilder().model("local-lmstudio").loop("react").build();
        assertThat(byName.model().name())
                .as("按名字换模型在派生副本上生效")
                .isEqualTo("local-lmstudio");
        assertThat(byName.loop().name())
                .as("按名字换 Loop 在派生副本上生效")
                .isEqualTo("react");

        // 传实例同样可行 —— 两种写法等价，按喜好选
        com.benxin.llm.core.agent.Agent byInstance = agentRegistry.get("localAssistant")
                .toBuilder().model(modelRegistry.get("local-lmstudio"))
                .loop(loopRegistry.get("react")).build();
        assertThat(byInstance.model().name()).isEqualTo("local-lmstudio");
        assertThat(byInstance.loop().name()).isEqualTo("react");
    }

    @Test
    @DisplayName("toBuilder() 会带上监听器（1.1.1 修复）：派生副本的运行照样进统计")
    void derivedAgentKeepsListeners() {
        // 1.1.0 时 toBuilder() 漏了 .listener(...)，派生副本的可观测性静默消失
        // （本工程上报为 FINDINGS 第 31 条）；1.1.1 补齐后这里应当是 1。
        metrics.reset();
        com.benxin.llm.core.agent.Agent derived = agentRegistry.get("localAssistant").toBuilder().build();
        derived.call("自我介绍", "derived-listener", null);
        assertThat(metrics.runCount())
                .as("派生副本继承了原 Agent 的监听器")
                .isEqualTo(1);

        // 再派生一层也不会丢
        metrics.reset();
        com.benxin.llm.core.agent.Agent twice = derived.toBuilder().model("mock").build();
        twice.call("自我介绍", "derived-twice", null);
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
