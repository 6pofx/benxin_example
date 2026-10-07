package com.benxin.exp.overridefixture.cb;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.TransportResponse;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.LlmProperties;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code @ConditionalOnMissingBean} 对照实验里那一批"用户自定义 bean"。
 *
 * <p>它们原先嵌套在 {@code ConditionalOnMissingBeanMechanismTest.Experiment} 里，
 * 后来搬到了这个包 —— 原因很实在：{@code @Nested} 内部类会跟着外部测试类一起被
 * {@code ConfigurationClassPostProcessor} 注册成配置类，于是这些
 * {@code @Bean} 方法（{@code myTransport} 之类）会溜进<b>别的</b>测试类的上下文。
 * 表现是 {@code AnnotationRegistrationTest} / {@code EnableLlmAgentsScanTest} 里那些
 * {@code ApplicationContextRunner} 实验突然报
 * "expected single matching bean but found 2: myTransport,stubTransport"。</p>
 *
 * <p>搬出来之后，它们只被显式 {@code withUserConfiguration(...)} 引用时才生效。</p>
 */
public final class CustomBeans {

    private CustomBeans() {
    }

    // ---------------------------------------------------------------- 实现类

    /** 一个不做任何真事的传输层：只要类型对得上就够了。 */
    public static class MyTransport implements HttpTransport {
        @Override
        public TransportResponse post(String url, Map<String, String> headers, String body, Duration timeout) {
            return TransportResponse.of(200, Map.of(), "{}");
        }

        @Override
        public TransportResponse postStreaming(String url, Map<String, String> headers, String body,
                                               Duration timeout) {
            return TransportResponse.streaming(200, Map.of(), new ByteArrayInputStream(new byte[0]));
        }
    }

    public static class MyMemoryStore implements MemoryStore {
        @Override
        public List<com.benxin.llm.core.message.ChatMessage> load(String sessionId) {
            return List.of();
        }

        @Override
        public void save(String sessionId, List<com.benxin.llm.core.message.ChatMessage> history) {
        }

        @Override
        public void clear(String sessionId) {
        }
    }

    public static class MyPromptProvider implements SystemPromptProvider {
        @Override
        public String systemPrompt(com.benxin.llm.core.agent.AgentSpec spec, Map<String, Object> vars) {
            return "我的提示词";
        }
    }

    public static class MyCompactor implements ContextCompactor {
        @Override
        public List<com.benxin.llm.core.message.ChatMessage> compact(
                List<com.benxin.llm.core.message.ChatMessage> history,
                LlmModel model, int keepRecent) {
            return history;
        }
    }

    public static class MyListener implements AgentListener {
    }

    public static class MyAgentRegistry extends AgentRegistry {
    }

    public static class MyModelRegistry extends ModelRegistry {
    }

    /** 子类 is-a 父类，{@code @ConditionalOnMissingBean} 照样让位 —— "换实现"不等于"从零写"。 */
    public static class MyAgentFactory extends LlmAgentFactory {
        public MyAgentFactory(LlmProperties properties,
                              ModelRegistry modelRegistry,
                              LoopRegistry loopRegistry,
                              LlmToolCatalog toolCatalog,
                              com.benxin.llm.core.context.ContextManager contextManager,
                              MemoryStore memoryStore,
                              SystemPromptProvider systemPromptProvider,
                              // 1.1.1 起这两个切面是 ObjectProvider（支持按 Agent 选沙箱/审批器）
                              ObjectProvider<ToolSandbox> sandbox,
                              ObjectProvider<ApprovalHandler> approvalHandler,
                              AgentRegistry agentRegistry,
                              ObjectProvider<LlmInterceptorChain> chain,
                              ObjectProvider<AgentListener> listeners) {
            super(properties, modelRegistry, loopRegistry, toolCatalog, contextManager, memoryStore,
                    systemPromptProvider, sandbox, approvalHandler, agentRegistry, chain, listeners);
        }
    }

    // ---------------------------------------------------------------- 用户配置

    /** 自定义传输层：定义同类型 bean 即接管，无需开关 / exclude / @Primary。 */
    @Configuration
    public static class CustomTransport {
        @Bean
        public HttpTransport myTransport() {
            return new MyTransport();
        }
    }

    @Configuration
    public static class CustomMemoryStore {
        @Bean
        public MemoryStore myMemoryStore() {
            return new MyMemoryStore();
        }
    }

    @Configuration
    public static class CustomPromptProvider {
        @Bean
        public SystemPromptProvider myPromptProvider() {
            return new MyPromptProvider();
        }
    }

    @Configuration
    public static class CustomCompactor {
        @Bean
        public ContextCompactor myCompactor() {
            return new MyCompactor();
        }
    }

    @Configuration
    public static class CustomListener {
        @Bean
        public AgentListener myListener() {
            return new MyListener();
        }
    }

    @Configuration
    public static class CustomRegistryAndFactory {
        @Bean
        public AgentRegistry myAgentRegistry() {
            return new MyAgentRegistry();
        }

        @Bean
        public LlmAgentFactory myAgentFactory(LlmProperties properties,
                                              ModelRegistry modelRegistry,
                                              LoopRegistry loopRegistry,
                                              LlmToolCatalog toolCatalog,
                                              com.benxin.llm.core.context.ContextManager contextManager,
                                              MemoryStore memoryStore,
                                              SystemPromptProvider systemPromptProvider,
                                              ToolSandbox sandbox,
                                              ApprovalHandler approvalHandler,
                                              AgentRegistry agentRegistry,
                                              ObjectProvider<LlmInterceptorChain> chain,
                                              ObjectProvider<AgentListener> listeners) {
            return new MyAgentFactory(properties, modelRegistry, loopRegistry, toolCatalog, contextManager,
                    memoryStore, systemPromptProvider,
                    com.benxin.exp.override.component.DemoLlmAgentFactory.fixed(sandbox),
                    com.benxin.exp.override.component.DemoLlmAgentFactory.fixed(approvalHandler),
                    agentRegistry, chain, listeners);
        }
    }

    @Configuration
    public static class CustomModelRegistry {
        @Bean
        public ModelRegistry myModelRegistry() {
            return new MyModelRegistry();
        }
    }

    /** 给"工厂拿到的注册表是我的"那条断言准备一个可用的模型，避免工厂解析模型时炸掉。 */
    @Configuration
    public static class WithOneModel {
        @Bean
        public LlmModel probeModel() {
            return ScriptedLlmModel.of("probe");
        }
    }

}