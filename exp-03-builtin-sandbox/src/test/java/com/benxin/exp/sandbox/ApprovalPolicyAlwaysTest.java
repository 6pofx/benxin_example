package com.benxin.exp.sandbox;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.sandbox.agent.SandboxProbe;
import com.benxin.exp.sandbox.audit.ToolAuditListener;
import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.agent.DefaultAgent;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code llm.agent.approval-policy} 走 yml 的那条路径：换一个属性值就换一份 ApplicationContext。
 *
 * <p>这是"声明式 Agent 也吃全局审批策略"的直接证据：{@code LlmAgentFactory} 会把
 * {@code ApprovalPolicy.from(defaults.getApprovalPolicy())} 传给每一个
 * {@code @LlmAgent} 装配出来的 Agent，所以改一处 yml 就能让所有 Agent 改变问询行为。</p>
 *
 * <p>为了让"到底问了几次"可观察，这里用一个计数用的 {@link ApprovalHandler} bean
 * <b>顶掉</b>内置的 {@code autoApprove}（同样是"同类型 bean 即接管"那条机制）。</p>
 */
@SpringBootTest(properties = "llm.agent.approval-policy=always")
@DisplayName("示例 03 · llm.agent.approval-policy=always")
class ApprovalPolicyAlwaysTest {

    @TestConfiguration
    static class CountingHandlerConfiguration {

        @Bean
        CountingApprovalHandler countingApprovalHandler() {
            return new CountingApprovalHandler();
        }
    }

    /** 一律批准，但把每次询问记下来。 */
    static class CountingApprovalHandler implements ApprovalHandler {

        private final List<String> asked = new ArrayList<>();

        @Override
        public boolean approve(ApprovalRequest request) {
            synchronized (asked) {
                asked.add(request.toolName());
            }
            return true;
        }

        int count() {
            synchronized (asked) {
                return asked.size();
            }
        }

        List<String> toolNames() {
            synchronized (asked) {
                return List.copyOf(asked);
            }
        }

        void reset() {
            synchronized (asked) {
                asked.clear();
            }
        }
    }

    @Autowired
    private SandboxProbe probe;

    @Autowired
    private RecordingModel mockLlmModel;

    @Autowired
    private ToolAuditListener audit;

    @Autowired
    private SandboxWorkspace workspace;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private CountingApprovalHandler handler;

    @BeforeEach
    void reset() {
        mockLlmModel.reset();
        audit.reset();
        handler.reset();
        workspace.ensure();
        workspace.resetOutputs();
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    @Test
    @DisplayName("yml 里的 approval-policy 会被每一个 @LlmAgent 装配出来的 Agent 读走")
    void ymlPolicyReachesDeclarativeAgents() {
        // approvalPolicy()/approvalHandler() 在 DefaultAgent 上（Agent 接口只承诺 spec/model/loop/tools）
        assertThat(((DefaultAgent) agentRegistry.get("sandboxProbe")).approvalPolicy())
                .isEqualTo(ApprovalPolicy.ALWAYS);
        assertThat(((DefaultAgent) agentRegistry.get("researchAssistant")).approvalPolicy())
                .isEqualTo(ApprovalPolicy.ALWAYS);
    }

    @Test
    @DisplayName("ALWAYS：连只读的 read 也会先问一次，批准之后照常执行")
    void alwaysAsksEvenForReadOnlyTools() {
        audit.reset();
        var result = probe.runForResult("#tool:read {\"path\":\"a.txt\"}");

        assertThat(handler.count()).isEqualTo(1);
        assertThat(handler.toolNames()).containsExactly("read");
        assertThat(audit.latest().error()).isFalse();
        assertThat(result.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("自己定义的 ApprovalHandler bean 顶掉了内置的 autoApprove")
    void customHandlerBeanReplacesTheBuiltin() {
        assertThat(((DefaultAgent) agentRegistry.get("sandboxProbe")).approvalHandler()).isSameAs(handler);
    }
}
