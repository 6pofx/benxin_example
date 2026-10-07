package com.benxin.exp.context;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配与多模态的断言：容器里六个可插拔点装的到底是谁，
 * 以及"图片会不会被自动降级"这条最容易猜错的口径。
 */
@DisplayName("示例 06 · 装配与多模态")
class AgentWiringTest extends AbstractContextMemoryTest {

    @Autowired
    private MemoryStore memoryStoreBean;

    @Autowired
    private SystemPromptProvider systemPromptProvider;

    @Autowired
    private TokenEstimator tokenEstimatorBean;

    @Autowired
    private ModelRegistry modelRegistry;

    @Test
    @DisplayName("可插拔点都被自定义 bean 接管了（@ConditionalOnMissingBean 让位）")
    void allCutoverBeansAreOurs() {
        assertThat(memoryStoreBean).isInstanceOf(com.benxin.exp.context.memory.JdbcMemoryStore.class);
        assertThat(contextManager).isInstanceOf(com.benxin.exp.context.context.Exp06ContextManager.class);
        assertThat(contextCompactor).isInstanceOf(com.benxin.llm.core.context.SummarizingCompactor.class);
        assertThat(tokenEstimatorBean).isInstanceOf(com.benxin.exp.context.context.Exp06TokenEstimator.class);
        assertThat(systemPromptProvider).isInstanceOf(com.benxin.exp.context.prompt.JdbcPromptPlatform.class);
        assertThat(systemPromptProvider).isSameAs(promptPlatform);
        assertThat(tokenEstimatorBean).isSameAs(tokenEstimator);
        // Agent 用到的就是这几个实例
        assertThat(((com.benxin.llm.core.agent.DefaultAgent) agent("memoryAssistant")).memoryStore())
                .isSameAs(memoryStoreBean);
        assertThat(((com.benxin.llm.core.agent.DefaultAgent) agent("memoryAssistant")).systemPromptProvider())
                .isSameAs(systemPromptProvider);
    }

    @Test
    @DisplayName("三个离线模型都注册进了 ModelRegistry，默认是 mock")
    void threeOfflineModelsAreRegistered() {
        assertThat(modelRegistry.names()).contains("mock", "tiny", "vision");
        assertThat(modelRegistry.defaultName()).isEqualTo("mock");
        assertThat(modelRegistry.get("mock").capabilities().maxContextTokens()).isEqualTo(64_000);
        assertThat(modelRegistry.get("tiny").capabilities().maxContextTokens()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("LlmModel bean 是录制装饰器：unwrap() 之后才是脚本模型")
    void modelBeansAreDecorators() {
        LlmModel mock = modelRegistry.get("mock");

        assertThat(mock).isInstanceOf(com.benxin.exp.common.model.RecordingModel.class);
        assertThat(mock.unwrap()).isInstanceOf(com.benxin.exp.common.model.ScriptedLlmModel.class);
        assertThat(modelRegistry.get("vision")).isInstanceOf(com.benxin.exp.common.model.RecordingModel.class);
        assertThat(modelRegistry.get("vision").capabilities().vision()).isTrue();
        assertThat(modelRegistry.get("mock").capabilities().vision()).isFalse();
    }

    @Test
    @DisplayName("模型能力位只是声明：vision 模型与不支持视觉的模型都会照原样收到 ImagePart")
    void imagesAreNeverDowngradedAutomatically() {
        List<ContentPart> parts = List.of(
                new TextPart("看一下这张图"),
                ImagePart.ofBase64("image/png", "iVBORw0KGgo="));

        // ① 声明了 vision = true 的模型
        Agent visionAgent = agent("memoryAssistant").toBuilder().model(visionModel).build();
        visionAgent.call(List.of(ChatMessage.user(parts.toArray(new ContentPart[0]))));

        assertThat(visionModel.capabilities().vision()).isTrue();
        assertThat(visionModel.lastRequest().messages()).anySatisfy(m -> assertThat(m.parts())
                .anySatisfy(p -> assertThat(p).isInstanceOf(ImagePart.class)));

        // ② 同一个 Agent，换成 vision = false 的默认模型：图片依然被原样下发
        Agent plainAgent = agent("memoryAssistant");
        plainAgent.call(List.of(ChatMessage.user(parts.toArray(new ContentPart[0]))));

        ChatRequest plainRequest = mockModel.lastRequest();
        assertThat(mockModel.capabilities().vision()).isFalse();
        ImagePart delivered = (ImagePart) plainRequest.messages().stream()
                .flatMap(m -> m.parts().stream())
                .filter(ImagePart.class::isInstance)
                .findFirst().orElseThrow();
        assertThat(delivered.isInline()).isTrue();
        assertThat(delivered.toDataUri()).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        // 结论：本心不做自动降级，也不会因为 vision=false 就丢掉内容块。
        // 该不该带图、要不要换视觉模型，是使用者的决定。
    }

    @Test
    @DisplayName("外链图片同样原样下发，且多模态消息不进记忆（call(List) 不写回）")
    void urlImagesAreDeliveredAndNotPersisted() {
        List<ContentPart> parts = List.of(new TextPart("外链"), ImagePart.ofUrl("https://e.com/a.png"));
        int before = agent("memoryAssistant").history("memoryAssistant-default").size();

        agent("memoryAssistant").call(List.of(ChatMessage.user(parts.toArray(new ContentPart[0]))));

        ChatMessage first = mockModel.lastRequest().messages().stream()
                .filter(m -> m.role() == Role.USER)
                .findFirst().orElseThrow();
        assertThat(first.parts()).hasSize(2);
        assertThat(first.parts().get(1)).isEqualTo(ImagePart.ofUrl("https://e.com/a.png"));
        // call(List<ChatMessage>) 的语义就是"直接以给定历史运行"，不装载也不写回
        assertThat(agent("memoryAssistant").history("memoryAssistant-default")).hasSize(before);
    }

    @Test
    @DisplayName("toBuilder() 之后再用 model(\"名字\") 不会换模型（builder 里已经带着模型实例）")
    void derivedAgentIgnoresModelName() {
        Agent derived = agent("memoryAssistant").toBuilder().model("vision").build();

        // AgentBuilder.model(String) 只设置"待解析的名字"，而 toBuilder() 已经把模型实例放进去了，
        // DefaultAgent 优先用实例 → 名字被静默忽略，换模型必须传实例
        assertThat(derived.model().name()).isEqualTo("mock");

        Agent switched = agent("memoryAssistant").toBuilder().model(visionModel).build();
        assertThat(switched.model().name()).isEqualTo("vision");
    }

    @Test
    @DisplayName("录制模型：每一次真正下发的请求都被留档，这是本示例所有断言的依据")
    void recordingModelKeepsEveryRequest() {
        mockModel.reset();
        memoryAssistant.chat("wiring-rec", "你好");
        memoryAssistant.chat("wiring-rec", "第二轮");

        assertThat(mockModel.count()).isEqualTo(2);
        assertThat(mockModel.requests()).hasSize(2);
        assertThat(mockModel.requests().get(0).messages()).hasSize(2);   // 系统 + 第一轮输入
        assertThat(mockModel.requests().get(1).messages()).hasSize(4);   // 系统 + 两轮共 3 条
    }

    @Test
    @DisplayName("被替换的是 provider，不是渲染规则：内置渲染器仍然可以单独使用")
    void builtinRendererIsStillAvailable() {
        // 平台 provider 内部就是复用它来渲染模板的，所以平台提示词里 {变量} 一样可用
        TemplateSystemPromptProvider renderer = new TemplateSystemPromptProvider();

        String rendered = renderer.render("你好 {agentName}", AgentSpec.builder().name("x").build(), Map.of());

        assertThat(rendered).isEqualTo("你好 x");
    }
}
