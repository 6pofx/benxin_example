package com.benxin.exp.declarative;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.declarative.agent.BasicAssistant;
import com.benxin.exp.declarative.agent.PlainAssistant;
import com.benxin.exp.declarative.agent.TimeAssistant;
import com.benxin.exp.declarative.agent.WeatherAssistant;
import com.benxin.exp.declarative.dto.ReviewReport;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 {@code DeclarativeDemo} 里展示的每一条行为都断言成可回归的用例。
 *
 * <p>全部离线：模型是 {@code exp-common} 的脚本化模型，因此这个测试类
 * 既不联网也不消耗 token，却覆盖了"注解 → 代理 → 运行时 → 模型 → 返回值"的完整链路。</p>
 */
@SpringBootTest
@DisplayName("示例 01 · 声明式 Agent")
class DeclarativeAgentTest {

    @Autowired
    private BasicAssistant basic;

    @Autowired
    private WeatherAssistant weather;

    @Autowired
    private TimeAssistant time;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private CommonTools commonTools;

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        commonTools.reset();
        // 声明式 Agent 默认开着记忆，且不传 sessionId 时都落在 "<agentName>-default" 这个会话上。
        // 测试之间共享同一个 ApplicationContext，因此必须显式清空历史 ——
        // 否则前一个用例的对话会成为后一个用例的上下文（这本身也是"记忆生效"的证据）。
        //
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出"已经被解析过"的那些，用它对遍历会一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            agent.clearHistory("tools-execution-session");
        }));
    }

    @Test
    @DisplayName("接口 + 注解即可注入使用，无需实现类")
    void declarativeInterfaceIsInjectable() {
        assertThat(basic).isNotNull();
        assertThat(basic.toString()).contains("@LlmAgent");
        assertThat(basic.ask("你好")).contains("离线模型");
    }

    @Test
    @DisplayName("无注解参数默认作为 user 消息；@User 的 prefix/suffix 包在值外侧")
    void userParameterSemantics() {
        basic.askWithPrefix("我在准备出差", "请把这句话改写得更礼貌");

        List<ChatMessage> mine = nonSystemMessages(mockLlmModel.lastRequest());
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).role()).isEqualTo(Role.USER);
        assertThat(mine.get(0).text()).isEqualTo("背景：我在准备出差");
        assertThat(mine.get(1).text()).isEqualTo("请把这句话改写得更礼貌（请用一句话回答）");
    }

    @Test
    @DisplayName("@Assistant 参数会作为 assistant 角色进入历史（少样本引导）")
    void assistantParameterBecomesAssistantMessage() {
        basic.guided("好的，我先用一句话概括。", "帮我总结这次改动");

        List<ChatMessage> mine = nonSystemMessages(mockLlmModel.lastRequest());
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).role()).isEqualTo(Role.ASSISTANT);
        assertThat(mine.get(0).text()).isEqualTo("好的，我先用一句话概括。");
        assertThat(mine.get(1).role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("@Ctx 只参与提示词渲染，绝不进入消息体")
    void ctxNeverLeaksIntoMessages() {
        basic.explain("public class A { void f() {} }", "严谨", "初学者");

        ChatRequest request = mockLlmModel.lastRequest();
        assertThat(request.systemText()).contains("严谨").contains("初学者");
        assertThat(nonSystemMessages(request)).hasSize(1);
        assertThat(nonSystemMessages(request).get(0).text()).doesNotContain("严谨");
    }

    @Test
    @DisplayName("方法级 @SystemPrompt(inherit = false) 不继承 Agent 级提示词")
    void systemPromptInheritFalseDropsAgentPrompt() {
        basic.reviewStrict("public class A { void f() {} }");

        String system = mockLlmModel.lastRequest().systemText();
        assertThat(system).contains("只输出 JSON");
        assertThat(system).doesNotContain("不要客套");
    }

    @Test
    @DisplayName("方法级 @SystemPrompt(append) 默认继承，并按 方法级 + append + Agent 级 拼接")
    void systemPromptAppendKeepsAgentPrompt() {
        basic.reviewStrictWithAppend("public class A { void f() {} }");

        String system = mockLlmModel.lastRequest().systemText();
        assertThat(system).contains("评分取值范围 0-100。");
        assertThat(system).contains("不要客套");
    }

    @Test
    @DisplayName("返回类型为 DTO 时，模型输出的 JSON 被自动反序列化")
    void dtoReturnTypeIsDeserialized() {
        ReviewReport report = basic.review("public class A { void f() { } }");

        assertThat(report).isNotNull();
        assertThat(report.score()).isEqualTo(78);
        assertThat(report.issues()).containsExactly("缺少对空入参的校验");
        assertThat(report.summary()).isNotBlank();
    }

    @Test
    @DisplayName("Optional<String> 在模型输出空白时落到 empty，让 orElse 兜底生效")
    void optionalReturnIsEmptyForBlankOutput() {
        Optional<String> silent = basic.maybe("保持沉默");

        assertThat(silent).isEmpty();
        assertThat(silent.orElse("兜底")).isEqualTo("兜底");
    }

    @Test
    @DisplayName("int / long / boolean / List<ChatMessage> / AgentResult 各自返回对应的东西")
    void scalarAndRichReturnTypes() {
        assertThat(basic.steps("介绍一下你自己")).isPositive();
        assertThat(basic.elapsedMillis("介绍一下你自己")).isNotNegative();
        assertThat(basic.answered("介绍一下你自己")).isTrue();

        List<ChatMessage> transcript = basic.transcript("介绍一下你自己");
        assertThat(transcript).isNotEmpty();
        assertThat(transcript.get(transcript.size() - 1).role()).isEqualTo(Role.ASSISTANT);

        AgentResult result = basic.result("介绍一下你自己");
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
        assertThat(result.modelName()).isEqualTo("mock");
        assertThat(result.usage().totalTokens()).isPositive();
        assertThat(result.steps()).isEqualTo(1);
    }

    @Test
    @DisplayName("void 返回类型不影响运行本身")
    void voidReturnTypeStillRuns() {
        basic.fireAndForget("介绍一下你自己");

        assertThat(mockLlmModel.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("流式：LlmStreamHandler 参数把增量送到调用方，且与最终文本一致")
    void streamingHandlerReceivesDeltas() {
        StringBuilder collected = new StringBuilder();
        boolean[] started = {false};

        String text = basic.stream("介绍一下你自己", new LlmStreamHandler() {
            @Override
            public void onStart() {
                started[0] = true;
            }

            @Override
            public void onTextDelta(String delta) {
                collected.append(delta);
            }
        });

        assertThat(started[0]).isTrue();
        assertThat(collected.length()).isGreaterThan(12);   // 脚本模型按 12 字符分片，至少两片
        assertThat(text).isEqualTo(collected.toString());
    }

    @Test
    @DisplayName("tools = {类} 是按来源类整包拿；不写 tools/toolNames 则继承容器全局工具表")
    void toolsAttributeSelectsByClass() {
        Agent weatherAgent = agentRegistry.get("weatherAssistant");
        // CommonTools 里有 4 个 @LlmTool 方法，按类取就是 4 个全拿
        assertThat(weatherAgent.tools().names())
                .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate", "describe_context");

        // 这是最容易被猜反的一条口径："什么都不写"等于"拿到所有工具"
        Agent plainAgent = agentRegistry.get("plainAssistant");
        assertThat(plainAgent.tools().names())
                .contains("get_weather", "get_current_time", "calculate", "describe_context");
    }

    @Test
    @DisplayName("toolNames = {...} 按名字挑选工具，工具真的会被调用")
    void toolNamesAttributeSelectsByName() {
        Agent timeAgent = agentRegistry.get("timeAssistant");
        assertThat(timeAgent.tools().names())
                .containsExactlyInAnyOrder("get_current_time", "calculate");

        time.ask("现在几点了？");
        assertThat(commonTools.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("tools 属性生效：模型请求的工具会被真实执行，结果回灌后再收尾")
    void toolsAreActuallyExecuted() {
        // 用一个全新的 sessionId：开了记忆时，"同一会话里已经取过天气"会让模型直接答而不再调工具
        AgentResult result = agentRegistry.get("weatherAssistant")
                .call("北京天气怎么样？", "tools-execution-session");

        assertThat(commonTools.callCount()).isEqualTo(1);
        assertThat(result.text()).contains("根据工具返回的结果");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(result.steps()).isEqualTo(2);   // 一次请求工具 + 一次收尾
    }

    @Test
    @DisplayName("@Memory 参数按 sessionId 隔离历史，clearHistory 能清空")
    void memoryIsIsolatedPerSession() {
        basic.chatWithMemory("s-1", "你好");
        basic.chatWithMemory("s-1", "介绍一下你自己");
        basic.chatWithMemory("s-2", "你好");

        Agent agent = agentRegistry.get("basicAssistant");
        assertThat(agent.history("s-1")).hasSize(4);   // 两轮对话，各一问一答
        assertThat(agent.history("s-2")).hasSize(2);

        agent.clearHistory("s-2");
        assertThat(agent.history("s-2")).isEmpty();
    }

    @Test
    @DisplayName("@LlmAgent(memory = false) 时不写回会话历史")
    void memoryCanBeDisabledPerAgent() {
        time.ask("现在几点了？");

        assertThat(agentRegistry.get("timeAssistant").history("任意会话")).isEmpty();
    }

    @Test
    @DisplayName("注解 → AgentSpec：显式值生效，空缺项回落到 llm.agent.*")
    void specReflectsAnnotationAndGlobalDefaults() {
        var spec = agentFactory.specOf(BasicAssistant.class);
        assertThat(spec.name()).isEqualTo("basicAssistant");
        assertThat(spec.model()).isEqualTo("mock");
        assertThat(spec.loop()).isEqualTo("dsh-minimal");
        assertThat(spec.maxSteps()).isEqualTo(6);
        assertThat(spec.temperature()).isEqualTo(0.2);
        assertThat(spec.maxTokens()).isEqualTo(512);
        assertThat(spec.memory()).isTrue();

        // WeatherAssistant 没写 temperature / maxTokens，回落到 llm.agent.*（示例里是 -1 = 不下发）
        var weatherSpec = agentFactory.specOf(WeatherAssistant.class);
        assertThat(weatherSpec.maxSteps()).isEqualTo(5);         // 注解显式写的值优先
        assertThat(weatherSpec.temperature()).isEqualTo(-1);     // 未配置 = 不下发
        assertThat(weatherSpec.maxTokens()).isEqualTo(-1);

        // PlainAssistant 一个属性都不写：名字从接口名推断，其余全部回落到 llm.agent.*
        var plainSpec = agentFactory.specOf(PlainAssistant.class);
        assertThat(plainSpec.name()).isEqualTo("plainAssistant");
        assertThat(plainSpec.loop()).isEqualTo("dsh-minimal");   // llm.agent.default-loop
        assertThat(plainSpec.maxSteps()).isEqualTo(8);           // llm.agent.max-steps
        assertThat(plainSpec.memory()).isTrue();                 // llm.agent.memory
        assertThat(plainSpec.systemPrompt()).isNull();           // 没配 llm.agent.system-prompt
    }

    @Test
    @DisplayName("注解里的 temperature / maxTokens 会随请求一起下发")
    void temperatureAndMaxTokensReachTheRequest() {
        basic.ask("你好");

        ChatRequest request = mockLlmModel.lastRequest();
        assertThat(request.temperature()).isEqualTo(0.2);
        assertThat(request.maxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("AgentRegistry 里能看到全部 Agent，包括从未被调用过的")
    void registryListsAllAgentsIncludingLazyOnes() {
        Set<String> names = agentRegistry.names();

        assertThat(names).contains("basicAssistant", "weatherAssistant", "timeAssistant");
        assertThat(agentRegistry.find("basicAssistant")).isPresent();
    }

    @Test
    @DisplayName("AgentSession 视图：id / 历史 / 属性")
    void agentSessionView() {
        var session = agentRegistry.get("basicAssistant").session("session-view");
        session.chat("你好");

        assertThat(session.id()).isEqualTo("session-view");
        assertThat(session.history()).hasSize(2);
        assertThat(session.attributes()).isEmpty();
    }

    private static List<ChatMessage> nonSystemMessages(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() != Role.SYSTEM).toList();
    }
}
