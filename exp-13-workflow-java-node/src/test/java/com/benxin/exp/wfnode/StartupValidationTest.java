package com.benxin.exp.wfnode;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import com.benxin.llm.spring.LlmAutoConfiguration;
import com.benxin.llm.spring.LlmAgentAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期校验：定义里引用了没注册的 Java 处理器，<b>启动就失败</b>，而不是跑到一半才炸。
 *
 * <p>这是"注册制即信任边界"的另一半：如果坏定义能启动成功，那么"图里只能点名宿主已有的东西"
 * 这条约束就只存在于文档里。这里用 {@link ApplicationContextRunner} 把它钉成可回归的断言 ——
 * 而且不需要起整个应用、不需要模型。</p>
 */
@DisplayName("Java 节点 · 启动期校验")
class StartupValidationTest {

    private static final String FINE_INLINE = """
            name: fine
            nodes:
              - id: begin
                type: start
              - id: call
                type: java
                handler: %s
              - id: done
                type: end
                output: "${call}"
            edges:
              - from: begin
                to: call
              - from: call
                to: done
            """;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class, LlmAgentAutoConfiguration.class))
            .withPropertyValues(
                    "llm.default-model=stub",
                    "llm.models.stub.protocol=openai",
                    "llm.models.stub.base-url=http://127.0.0.1:1/v1",
                    "llm.models.stub.model=stub");

    @Test
    @DisplayName("引用未注册的处理器 → 上下文启动失败，消息里列出已注册清单")
    void unknownHandlerFailsStartup() {
        runner.withPropertyValues("llm.workflows.bad.inline=" + FINE_INLINE.formatted("ghost"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("llm.workflows.bad")
                            .hasStackTraceContaining("ghost")
                            .hasStackTraceContaining("已注册");
                });
    }

    @Test
    @DisplayName("处理器已注册 → 正常启动，注册表里能按注释名找到它")
    void registeredHandlerStartsFine() {
        runner.withUserConfiguration(HandlerConfig.class)
                .withPropertyValues("llm.workflows.good.inline=" + FINE_INLINE.formatted("demo-alpha"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowHandler.class);
                    assertThat(context.getBean(com.benxin.llm.core.workflow.WorkflowHandlerRegistry.class).names())
                            .containsExactly("demo-alpha");
                });
    }

    @Test
    @DisplayName("WorkflowHandler bean 缺 @LlmNode → 启动失败，而不是静默跳过")
    void handlerWithoutAnnotationFailsStartup() {
        runner.withUserConfiguration(UnannotatedConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("@LlmNode")
                            .hasStackTraceContaining(UnannotatedHandler.class.getName());
                });
    }

    @LlmNode("demo-alpha")
    static class AnnotatedHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("alpha");
        }
    }

    static class UnannotatedHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("no-annotation");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfig {
        @Bean
        AnnotatedHandler annotatedHandler() {
            return new AnnotatedHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnannotatedConfig {
        @Bean
        UnannotatedHandler unannotatedHandler() {
            return new UnannotatedHandler();
        }
    }
}
