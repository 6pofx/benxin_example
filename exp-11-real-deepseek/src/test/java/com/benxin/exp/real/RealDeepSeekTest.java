package com.benxin.exp.real;

import com.benxin.exp.real.agent.TinyAssistant;
import com.benxin.exp.real.config.ApiKeyResolver;
import com.benxin.exp.real.config.RealCallGate;
import com.benxin.exp.real.config.RealCallMetrics;
import com.benxin.exp.real.wire.WireLog;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实端点上的回归用例：<b>默认整类 skip</b>，只有显式打开开关才会跑。
 *
 * <pre>{@code
 * mvn -o test -Dexp.real.enabled=true          # 只跑本类时会真的联网、真的花钱
 * }</pre>
 *
 * <p>为什么默认 skip 而不是 fail：本模块的离线用例已经覆盖了同一条代码路径，
 * 真实调用只是"再多一个证据"。让 CI 上的 {@code mvn -o test} 因为"没有 key / 没有网"
 * 而变红，是最容易让人放弃写测试的做法。</p>
 *
 * <p>本类刻意<b>只做两次调用</b>（一次最小对话 + 一次注定被拒的 401），
 * 把大部分真实调用预算留给 {@code java -jar} 的完整演示。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@ActiveProfiles("real")
@EnabledIfSystemProperty(named = "exp.real.enabled", matches = "true")
@DisplayName("示例 11 · 真实 DeepSeek（默认 skip，需 -Dexp.real.enabled=true）")
class RealDeepSeekTest {

    @Autowired
    private TinyAssistant tiny;

    @Autowired
    private RealCallGate gate;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private WireLog wireLog;

    @Autowired
    private RealCallMetrics metrics;

    @Test
    @DisplayName("闸门在 real profile 下打开，且 mock 与 deepseek 都在注册表里")
    void gateIsOpenInRealProfile() {
        assertThat(gate.enabled()).isTrue();
        assertThat(modelRegistry.names())
                .contains("mock", "deepseek", "deepseek-responses", "deepseek-badkey");
        assertThat(modelRegistry.defaultName()).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("最小真实对话：一次调用拿到非空回答与真实用量")
    void minimalRealCall() {
        Assumptions.assumeTrue(ApiKeyResolver.available(),
                "取不到 API Key，跳过真实调用（这不是失败）");

        metrics.reset();
        wireLog.reset();
        AgentResult result = tiny.result("real-test-minimal", "1+1=?");

        assertThat(result.text()).isNotBlank();
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.modelName()).isEqualTo("deepseek");
        assertThat(result.usage().totalTokens()).isPositive();
        // max-tokens: 48 生效：输出不会被放大
        assertThat(result.usage().outputTokens()).isLessThanOrEqualTo(48);
        // 一次调用 = 一笔真实请求（max-retries: 0）
        assertThat(wireLog.realRequests()).isEqualTo(1);
        assertThat(metrics.modelCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("假 key → ModelException，statusCode() == 401，且不消耗 token")
    void invalidKeyYieldsReadable401() {
        LlmModel bad = modelRegistry.get("deepseek-badkey");

        assertThatThrownBy(() -> bad.chat(ChatRequest.builder()
                .message(ChatMessage.user("hi"))
                .maxTokens(8)
                .build()))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException exception = (ModelException) error;
                    assertThat(exception.statusCode()).isEqualTo(401);
                    assertThat(exception.getMessage()).isNotBlank();
                });
    }
}
