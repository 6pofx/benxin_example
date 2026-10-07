package com.benxin.exp.context;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.prompt.PromptTemplates;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SystemPromptProvider} 的断言：模板渲染规则、表驱动平台，
 * 以及最容易被猜错的那条 —— <b>方法上有 {@code @SystemPrompt} 时自定义 provider 根本不会被调用</b>。
 */
@DisplayName("示例 06 · SystemPromptProvider")
class PromptProviderTest extends AbstractContextMemoryTest {

    @Test
    @DisplayName("默认 TemplateSystemPromptProvider：{变量} 插值 + 内置变量 + 未定义占位符原样保留")
    void templateProviderRendersBuiltins() {
        AgentSpec spec = AgentSpec.builder()
                .name("demoAgent")
                .loop("dsh-minimal")
                .toolNames(List.of("read_document", "get_weather"))
                .systemPrompt("你是 {agentName}，跑在 {loop} 上，工具 [{tools}]，今天 {today}。"
                        + "参数 question={question}，拼错的 {notDefined} 应保留。")
                .build();

        String rendered = new TemplateSystemPromptProvider()
                .systemPrompt(spec, Map.of("question", "示例问题"));

        assertThat(rendered)
                .contains("你是 demoAgent")
                .contains("跑在 dsh-minimal 上")
                .contains("read_document, get_weather")
                .contains("示例问题")
                // 取不到的占位符保持原样：避免"渲染成空串"这种静默失败
                .contains("{notDefined}");
    }

    @Test
    @DisplayName("模板里没有提示词时返回 null（表示「不设置」），而不是空串")
    void templateProviderReturnsNullForBlankPrompt() {
        TemplateSystemPromptProvider provider = new TemplateSystemPromptProvider();

        assertThat(provider.systemPrompt(AgentSpec.builder().name("x").build(), Map.of())).isNull();
        assertThat(provider.systemPrompt(null, Map.of())).isNull();
        assertThat(provider.systemPrompt(
                AgentSpec.builder().name("x").systemPrompt("   ").build(), Map.of())).isNull();
    }

    @Test
    @DisplayName("平台里配了提示词：方法没有 @SystemPrompt 时用平台那一份")
    void platformPromptWinsWhenMethodHasNoSystemPrompt() {
        promptPlatform.put("promptAssistant",
                "你是提示词管理平台下发的助手（agent={agentName}，loop={loop}）。只回答一句话。");
        promptPlatform.resetStats();

        promptAssistant.fromProvider("打个招呼");

        ChatRequest request = mockModel.lastRequest();
        assertThat(request.systemText())
                .contains("提示词管理平台下发的助手")
                .contains("agent=promptAssistant")
                .contains("loop=dsh-minimal");
        assertThat(promptPlatform.invocations()).isEqualTo(1);
        assertThat(promptPlatform.lastAgent()).isEqualTo("promptAssistant");
    }

    @Test
    @DisplayName("方法上写了 @SystemPrompt：直接覆盖 provider，provider 一次都不会被调用")
    void methodLevelSystemPromptOverridesTheProvider() {
        promptPlatform.put("promptAssistant", "你是提示词管理平台下发的助手。");
        promptPlatform.resetStats();

        promptAssistant.methodOverride("打个招呼");

        ChatRequest request = mockModel.lastRequest();
        assertThat(request.systemText())
                .contains("方法级 · inherit=false")
                .doesNotContain("提示词管理平台下发的助手");
        // 这是本示例最想钉住的一条口径：声明式代理把方法级提示词渲染好放进运行属性，
        // DefaultAgent 优先取属性值，容器里的 provider 完全不会参与
        assertThat(promptPlatform.invocations()).isZero();
    }

    @Test
    @DisplayName("append 与 inherit：方法级 + append 拼成一段；inherit 决定要不要再拼 Agent 级")
    void appendAndInheritSemantics() {
        promptAssistant.withAppend("打个招呼");
        String system = mockModel.lastRequest().systemText();

        // 三段拼接规则：方法级 + append + （inherit 且 Agent 级非空时的 Agent 级），用空行分隔
        assertThat(system).endsWith("（方法级 · append）先给结论。\n\n（append 段）再补一句依据。");
    }

    @Test
    @DisplayName("方法级提示词里的 {变量}：普通参数名 + @Ctx + 内置变量都会被渲染")
    void methodLevelTemplateVars() {
        promptAssistant.withVars("讲一下压缩", "严谨");

        String system = mockModel.lastRequest().systemText();
        assertThat(system)
                .contains("请用 严谨 的风格回答")
                .contains("讲一下压缩")
                .contains("本次由 promptAssistant 处理")
                .contains("循环 dsh-minimal");
        // @Ctx 只参与渲染，不进消息体
        assertThat(nonSystem(mockModel.lastRequest())).extracting(ChatMessage::text)
                .containsExactly("讲一下压缩");
    }

    @Test
    @DisplayName("平台里没有该 Agent 时回落到注解：memoryAssistant 用的还是注解提示词")
    void platformFallsBackToAnnotationPrompt() {
        assertThat(promptPlatform.find("memoryAssistant")).isEmpty();
        promptPlatform.resetStats();

        memoryAssistant.chat("prompt-fallback", "你好");

        assertThat(mockModel.lastRequest().systemText())
                .contains("会话记忆")                       // 注解里的提示词
                .contains("memoryAssistant");               // {agentName} 被渲染
        assertThat(promptPlatform.invocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("平台行优先于注解：同一个 Agent 配了平台行之后，注解提示词被顶掉")
    void platformRowOverridesAnnotationPrompt() {
        promptPlatform.put("memoryAssistant", "你是平台接管的记忆助手（v1）。");
        promptPlatform.resetStats();

        memoryAssistant.chat("prompt-override", "你好");

        String system = mockModel.lastRequest().systemText();
        assertThat(system).contains("平台接管的记忆助手").doesNotContain("会话记忆");
    }

    @Test
    @DisplayName("平台改一行、删一行都会在下一次请求立刻生效（每次都查库，不做本地缓存）")
    void platformHotUpdateAndDelete() {
        promptPlatform.put("memoryAssistant", "第一版提示词（v1）。");
        memoryAssistant.chat("prompt-hot", "你好");
        assertThat(mockModel.lastRequest().systemText()).contains("第一版提示词");
        assertThat(promptPlatform.find("memoryAssistant").orElseThrow().version()).isEqualTo(1);

        int version = promptPlatform.put("memoryAssistant", "第二版提示词（v2）。");
        memoryAssistant.chat("prompt-hot", "你好");
        assertThat(version).isEqualTo(2);
        assertThat(mockModel.lastRequest().systemText()).contains("第二版提示词");

        promptPlatform.delete("memoryAssistant");
        memoryAssistant.chat("prompt-hot", "你好");
        // 表里没了 → 回落注解提示词
        assertThat(mockModel.lastRequest().systemText()).contains("会话记忆");
    }

    @Test
    @DisplayName("平台 provider 的 {@code @Ctx} 变量同样可用（渲染规则与内置模板一致）")
    void platformRendersContextVariablesToo() {
        promptPlatform.put("memoryAssistant", "平台提示词：{agentName} / {loop} / {tools} / 变量 {style}");
        mockModel.reset();

        agent("memoryAssistant").call("session-x", List.of(ChatMessage.user("你好")),
                Map.of("style", "严谨"), null);

        assertThat(mockModel.lastRequest().systemText())
                .contains("平台提示词：memoryAssistant / dsh-minimal")
                .contains("变量 严谨");
    }

    // ------------------------------------------------------------------
    // PromptTemplates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PromptTemplates 的常量与工厂方法：提示词只描述真实存在的工具")
    void promptTemplatesDescribeRealCapabilities() {
        assertThat(PromptTemplates.MINIMAL).isNotBlank().contains("本心");
        assertThat(PromptTemplates.REACT_TEXT_PROTOCOL).contains("Action Input");
        assertThat(PromptTemplates.CLAUDE_CODE_IDENTITY).contains("Claude Code");
        assertThat(PromptTemplates.CODEX_WORK_LOOP).isNotBlank();

        List<String> full = List.of("read_file", "write_file", "apply_patch", "bash", "todo_write");
        List<String> readOnly = List.of("read_file", "grep_search");

        assertThat(PromptTemplates.hasCommandTool(full)).isTrue();
        assertThat(PromptTemplates.hasWriteTool(full)).isTrue();
        assertThat(PromptTemplates.hasReadTool(full)).isTrue();
        assertThat(PromptTemplates.hasSearchTool(readOnly)).isTrue();
        assertThat(PromptTemplates.hasTodoTool(full)).isTrue();
        assertThat(PromptTemplates.hasCommandTool(readOnly)).isFalse();
        assertThat(PromptTemplates.hasWriteTool(readOnly)).isFalse();
        // apply_patch 比 write_file 更"精确"，优先推荐它
        assertThat(PromptTemplates.preferredPatchTool(full)).isEqualTo("apply_patch");
        assertThat(PromptTemplates.preferredPatchTool(readOnly)).isNull();
        assertThat(PromptTemplates.describeTools(full)).contains("bash");
        assertThat(PromptTemplates.describeTools(List.of())).contains("无");

        String withCommand = PromptTemplates.claudeCode(PromptTemplates.PromptContext.of(full, true));
        String withoutCommand = PromptTemplates.claudeCode(PromptTemplates.PromptContext.of(readOnly, false));
        assertThat(withCommand).contains("bash").contains("todo_write");
        assertThat(withoutCommand)
                .doesNotContain("bash")
                .contains("当前工具集中没有命令执行类工具");

        assertThat(PromptTemplates.codex(PromptTemplates.PromptContext.of(readOnly, false), false, false,
                com.benxin.llm.core.sandbox.ApprovalPolicy.ON_REQUEST)).isNotBlank();
    }

    @Test
    @DisplayName("Loop 只在系统提示词为空时才注入内置兜底提示词（非空时一字不改）")
    void claudeCodeKeepsTheConfiguredSystemPrompt() {
        Agent claudeCode = agent("claudeCodeAssistant");
        assertThat(claudeCode.spec().systemPrompt()).isNotBlank();

        claudeCode.call("你好", "prompt-loop-demo");

        // 自定义 ContextManager 注入的系统提示词带来源标记，且没有被内置兜底提示词覆盖
        String system = tinyModel.lastRequest().systemText();
        assertThat(system).contains("claude-code 循环示例助手");
        assertThat(tinyModel.requests()).noneSatisfy(r ->
                assertThat(r.systemText()).contains("【本心内置系统提示｜claude-code】"));
    }

    @Test
    @DisplayName("role 与用户消息的关系：provider 只影响系统提示词，绝不改消息体")
    void providerOnlyTouchesSystemPrompt() {
        promptPlatform.put("memoryAssistant", "平台提示词。");

        memoryAssistant.chat("prompt-body", "你好");

        List<ChatMessage> conversation = nonSystem(mockModel.lastRequest());
        assertThat(conversation).hasSize(1);
        assertThat(conversation.get(0).role()).isEqualTo(Role.USER);
        assertThat(conversation.get(0).text()).isEqualTo("你好");
    }
}
