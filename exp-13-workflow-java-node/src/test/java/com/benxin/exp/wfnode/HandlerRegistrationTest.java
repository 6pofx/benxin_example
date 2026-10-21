package com.benxin.exp.wfnode;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表：{@code handler:} 那个字符串唯一的解析依据。
 *
 * <p>这些用例<b>不需要 Spring、不需要模型</b> —— 注册表本身就是纯 core 的一个
 * {@code Map<String, WorkflowHandler>}。把它们放在纯 JUnit 里，是为了说明
 * "信任边界"这件事与框架无关：谁能被点名，只取决于 {@code @LlmNode} 上的名字。</p>
 */
@DisplayName("Java 节点 · 注册表")
class HandlerRegistrationTest {

    @LlmNode("alpha")
    static class AlphaHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("alpha");
        }
    }

    @LlmNode("beta")
    static class BetaHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("beta");
        }
    }

    /** 故意不打注解：注册时必须报错，而不是回落到类名。 */
    static class AnonymousHandler implements WorkflowHandler {
        @Override
        public NodeOutput handle(NodeInput input) {
            return NodeOutput.of("anonymous");
        }
    }

    @Test
    @DisplayName("按 @LlmNode 的名字登记：名字是契约，类名不是")
    void annotationNameIsTheContract() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty().register(new AlphaHandler());

        assertThat(registry.names()).containsExactly("alpha");
        assertThat(registry.contains("alpha")).isTrue();
        // 类名不是名字
        assertThat(registry.contains("AlphaHandler")).isFalse();
        assertThat(registry.find("alpha")).isPresent().get().isInstanceOf(AlphaHandler.class);
    }

    @Test
    @DisplayName("同名后登记者覆盖先前的（与 LoopRegistry 一致）")
    void duplicateNameLastWins() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("same", new AlphaHandler())
                .register("same", new BetaHandler());

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.require("same")).isInstanceOf(BetaHandler.class);
    }

    @Test
    @DisplayName("缺 @LlmNode 直接报错：消息里带类名，而不是静默用类名")
    void missingAnnotationFailsLoudly() {
        assertThatThrownBy(() -> WorkflowHandlerRegistry.empty().register(new AnonymousHandler()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AnonymousHandler")
                .hasMessageContaining("@LlmNode");
    }

    @Test
    @DisplayName("require 未注册的名字：报错并列出已注册清单")
    void unknownHandlerListsWhatIsRegistered() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register(new AlphaHandler())
                .register(new BetaHandler());

        assertThatThrownBy(() -> registry.require("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta");
    }

    @Test
    @DisplayName("空名字 / null 处理器被拒绝")
    void rejectsBlankNameAndNullHandler() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty();

        assertThatThrownBy(() -> registry.register("  ", new AlphaHandler()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.contains(null)).isFalse();
    }

    @Test
    @DisplayName("names() 保持登记顺序且不可变")
    void namesKeepRegistrationOrder() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register(new BetaHandler())
                .register(new AlphaHandler());

        assertThat(registry.names()).containsExactly("beta", "alpha");
        assertThatThrownBy(() -> registry.names().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
