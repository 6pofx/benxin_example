package com.benxin.exp.override;

import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import com.benxin.llm.spring.LlmAutoConfiguration;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code yml → LlmProperties → ModelConfig} 的全字段对照。
 *
 * <p>{@link LlmProperties} 有 5 个顶层字段、两层嵌套（{@code ModelProperties} /
 * {@code AgentProperties} / {@code ToolProperties} / {@code WorkflowProperties}），
 * 一共几十个叶子字段。逐个写断言不是为了刷覆盖率，而是因为<b>配置绑定失败是静默的</b>：
 * 名字写错（比如 {@code maxToken} 少个 s）不会报错，只会悄悄变成默认值，
 * 然后你在别处花两小时排查"为什么 temperature 没下发"。</p>
 *
 * <p>配置来源是 {@code src/test/resources/application-exp09-binding.yml} ——
 * 与示例的 {@code application.yml} 字段一一对应（那份文件也是"每个字段都写一遍"）。
 * 刻意<b>不</b>复用示例的 {@code application.yml}：{@code ApplicationContextRunner}
 * 默认不加载它，显式指定文件位置比隐式依赖 classpath 更不容易出意外。</p>
 */
@DisplayName("示例 09 · LlmProperties 全字段绑定")
class LlmPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class))
            .withPropertyValues("spring.config.location=classpath:application-exp09-binding.yml");

    // ==================================================================
    // 顶层
    // ==================================================================

    @Test
    @DisplayName("顶层字段：enabled / default-model")
    void topLevelFields() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties properties = context.getBean(LlmProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultModel()).isEqualTo("mock");
        });
    }

    // ==================================================================
    // llm.agent.*
    // ==================================================================

    @Test
    @DisplayName("llm.agent.* 的十个字段全部绑定（含 Duration 型的 hard-max-steps 语义）")
    void agentProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties.AgentProperties agent = context.getBean(LlmProperties.class).getAgent();

            assertThat(agent.getDefaultLoop()).isEqualTo("dsh-minimal");
            assertThat(agent.getMaxSteps()).isEqualTo(8);
            assertThat(agent.isStream()).isFalse();
            assertThat(agent.isMemory()).isTrue();
            assertThat(agent.getTemperature()).isEqualTo(0.25);
            assertThat(agent.getMaxTokens()).isEqualTo(512);
            assertThat(agent.getSystemPrompt()).isEqualTo("（来自 llm.agent.system-prompt 的全局兜底提示词）");
            assertThat(agent.isLoggingListener()).isTrue();
            assertThat(agent.getApprovalPolicy()).isEqualTo("on-request");
            assertThat(agent.getHardMaxSteps()).isEqualTo(32);

            // 落到 LoopRegistry 上就是默认 Loop 名。
            assertThat(context.getBean(LoopRegistry.class).defaultName()).isEqualTo(agent.getDefaultLoop());
        });
    }

    // ==================================================================
    // llm.models.*
    // ==================================================================

    @Test
    @DisplayName("llm.models.mock.* 的十三个字段全部绑定")
    void modelProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties.ModelProperties mock = context.getBean(LlmProperties.class).getModels().get("mock");

            assertThat(mock.getProtocol()).isEqualTo("openai");
            assertThat(mock.getBaseUrl()).isEqualTo("https://api.example.invalid/v1");
            // api-key 是占位符 ${EXP_DEMO_KEY:not-used}：没设环境变量时落到默认值。
            // 用占位符而不是字面量，是为了让示例工程里不出现任何真实密钥。
            assertThat(mock.getApiKey()).isEqualTo("not-used");
            assertThat(mock.getModel()).isEqualTo("exp-09-config-model");
            assertThat(mock.getHeaders())
                    .containsEntry("X-Exp-Tenant", "exp-09")
                    .containsEntry("X-Exp-Trace", "true");
            assertThat(mock.getExtra()).containsEntry("top_p", 0.9);
            assertThat(mock.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(mock.getReadTimeout()).isEqualTo(Duration.ofSeconds(12));
            assertThat(mock.getStreamReadTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(mock.getMaxRetries()).isEqualTo(3);
            assertThat(mock.getTemperature()).isEqualTo(0.4);
            assertThat(mock.getMaxTokens()).isEqualTo(256);
            assertThat(mock.isPrimary()).isTrue();
            assertThat(mock.getOrder()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("最后一跳：ModelProperties → ModelConfig（ModelFactory 真正拿到的那个对象）")
    void modelConfigTranslation() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties.ModelProperties mock = context.getBean(LlmProperties.class).getModels().get("mock");

            ModelConfig config = mock.toModelConfig("mock");
            assertThat(config.name()).isEqualTo("mock");
            assertThat(config.protocol().id()).isEqualTo("openai");
            // baseUrl 会被去掉结尾斜杠（ModelConfig 构造器里做的事）。
            assertThat(config.baseUrl()).isEqualTo("https://api.example.invalid/v1");
            assertThat(config.model()).isEqualTo("exp-09-config-model");
            assertThat(config.hasApiKey()).isTrue();
            assertThat(config.maxRetries()).isEqualTo(3);
            assertThat(config.temperature()).isEqualTo(0.4);
            assertThat(config.maxTokens()).isEqualTo(256);
            assertThat(config.headers()).containsEntry("X-Exp-Tenant", "exp-09");
            assertThat(config.extra()).containsEntry("seed", 20260909);
        });
    }

    @Test
    @DisplayName("default-model 与 primary：llm.default-model 写了就以它为准")
    void defaultModelWinsOverPrimary() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // yml 里 mock 是 primary=true，但 default-model 也写着 mock —— 两者一致。
            assertThat(context.getBean(com.benxin.llm.core.model.ModelRegistry.class).defaultName())
                    .isEqualTo("mock");
        });
    }

    // ==================================================================
    // llm.tools.*
    // ==================================================================

    @Test
    @DisplayName("llm.tools.* 的十个字段全部绑定")
    void toolProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties.ToolProperties tools = context.getBean(LlmProperties.class).getTools();

            assertThat(tools.isBuiltinEnabled()).isFalse();
            assertThat(tools.getWorkdir()).isEqualTo(".");
            assertThat(tools.isAllowWrite()).isFalse();
            assertThat(tools.isAllowExec()).isFalse();
            assertThat(tools.getAllowedCommands()).containsExactly("git status", "mvn -v");
            assertThat(tools.getDeniedCommands()).containsExactly("docker", "kubectl");
            assertThat(tools.getEnabled()).containsExactly("read", "glob");
            assertThat(tools.getExecTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(tools.getMaxOutputChars()).isEqualTo(20000);
            assertThat(tools.getMaxFileBytes()).isEqualTo(524288L);
        });
    }

    @Test
    @DisplayName("builtin-enabled=false 时不会装配 LlmBuiltinToolRegistrar（这是配置开关，不是类型判断）")
    void builtinDisabledByProperty() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(com.benxin.llm.spring.LlmBuiltinToolRegistrar.class));
    }

    // ==================================================================
    // llm.workflows.*
    // ==================================================================

    @Test
    @DisplayName("llm.workflows.* 两种写法都绑定：inline 直接写、location 指向 classpath 资源")
    void workflowProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties.WorkflowProperties quickFix =
                    context.getBean(LlmProperties.class).getWorkflows().get("quick-fix");
            LlmProperties.WorkflowProperties codeReview =
                    context.getBean(LlmProperties.class).getWorkflows().get("code-review");

            assertThat(quickFix.getInline()).contains("name: quick-fix").contains("type: agent");
            assertThat(quickFix.getLocation()).isNull();
            assertThat(codeReview.getLocation()).isEqualTo("classpath:workflows/exp09-review.yml");
            assertThat(codeReview.getInline()).isNull();
        });
    }

    @Test
    @DisplayName("每个 workflows.<key> 都被注册成一个 workflow:<key> 的 Loop（一行配置换一个 Loop）")
    void workflowsBecomeLoops() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LoopRegistry loops = context.getBean(LoopRegistry.class);

            assertThat(loops.contains("workflow:quick-fix")).isTrue();
            assertThat(loops.contains("workflow:code-review")).isTrue();
            // Loop 名与注册名必须一致，否则 /llm/agents 会显示一个查不到的名字。
            assertThat(loops.get("workflow:code-review").name()).isEqualTo("workflow:code-review");
            assertThat(loops.get("workflow:code-review").description()).contains("exp-09");
        });
    }
}
