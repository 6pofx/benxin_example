package com.benxin.exp.override;

import com.benxin.exp.override.component.DemoAgentRegistry;
import com.benxin.exp.override.component.DemoLlmAgentFactory;
import com.benxin.exp.override.component.DemoLoopRegistry;
import com.benxin.exp.override.component.StubTransport;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.NoSuchAgentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code AgentRegistry} 的惰性语义 —— 一条很实用、也很容易猜错的口径。
 *
 * <p>为什么单独一个测试类：这个类里的断言必须在"<b>刚启动、还没人调用过任何 Agent</b>"
 * 的上下文里做。其它测试类的 {@code @BeforeEach} 会遍历 {@code names()} 去清历史，
 * 而 {@code find()} 会把惰性条目顺手解析成真身 —— 那一刻起 {@code resolved()} 就不再是空的了。</p>
 *
 * <p>三条口径：</p>
 * <ol>
 *   <li>{@code LlmAgentFactoryBean} 是 eager-init 的 {@code SmartFactoryBean}，
 *       所以<b>启动期</b>就会为每个 {@code @LlmAgent} 接口调用一次 {@code getObject()}，
 *       并登记一个惰性条目 —— 于是"从没被调用过"的 Agent 也出现在 {@code names()} 里；</li>
 *   <li>但 {@code resolved()} 只列出"已经解析成真身"的那些，所以启动后它是<b>空</b>的；</li>
 *   <li>{@code find(name)} 才会把惰性条目变成真身，而且这个变化能被 {@code resolved()} 看到。</li>
 * </ol>
 *
 * <p><b>结论</b>：想遍历全部 Agent（清历史、做健康检查、出清单），请用
 * {@code names()} + {@code find()}；{@code resolved()} 只适合"看已经热起来的那些"。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("示例 09 · AgentRegistry 的惰性语义")
class LazyRegistrySemanticsTest {

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private DemoAgentRegistry demoAgentRegistry;

    @Autowired
    private LlmAgentFactory agentFactory;

    @Autowired
    private DemoLlmAgentFactory demoAgentFactory;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private DemoLoopRegistry demoLoopRegistry;

    @Autowired
    private StubTransport stubTransport;

    @Autowired
    private HttpTransport httpTransport;

    /**
     * 第 1 条：只声明了 {@code @LlmAgent} 接口、从没调用过，也出现在 {@code names()} 里。
     *
     * <p>本方法被 {@link Order @Order(1)} 固定为这个类里最先跑的 ——
     * 后面的方法会调用 {@code find()}，从而破坏"全新上下文"这个前提。</p>
     */
    @Test
    @Order(1)
    @DisplayName("启动后：names() 里有全部 @LlmAgent 接口，resolved() 却是空的")
    void lazyEntriesAreListedButNotResolved() {
        assertThat(agentRegistry.names()).contains("supportAssistant", "toolAssistant");
        assertThat(agentRegistry.resolved()).isEmpty();
        // 惰性登记由 LlmAgentFactoryBean（eager-init 的 SmartFactoryBean）在启动期完成。
        assertThat(demoAgentRegistry.lazyRegistrations()).isGreaterThanOrEqualTo(2);
    }

    /** 第 2 条：{@code find()} 把惰性条目变成真身 —— 之后 {@code resolved()} 就能看到它了。 */
    @Test
    @Order(2)
    @DisplayName("find() 会把惰性条目解析成真身，此后 resolved() 里就有了它")
    void findResolvesLazyEntry() {
        assertThat(agentRegistry.resolved()).doesNotContainKey("toolAssistant");

        var found = agentRegistry.find("toolAssistant");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().name()).isEqualTo("toolAssistant");
        assertThat(agentRegistry.resolved()).containsKey("toolAssistant");
        assertThat(agentRegistry.resolved().get("toolAssistant")).isSameAs(found.orElseThrow());
    }

    /** 第 3 条：找不到时的两种口径 —— {@code find} 给 empty，{@code get} 直接抛。 */
    @Test
    @Order(3)
    @DisplayName("找不到时的两种口径：find 给 empty，get 抛 NoSuchAgentException（消息里带已注册清单）")
    void missingAgentSemantics() {
        assertThat(agentRegistry.find("不存在的 Agent")).isEmpty();
        assertThatThrownBy(() -> agentRegistry.get("不存在的 Agent"))
                .isInstanceOf(NoSuchAgentException.class)
                .hasMessageContaining("不存在的 Agent")
                .hasMessageContaining("supportAssistant");
    }

    // ------------------------------------------------------------------
    // 顺手把容器里的切面身份也核一遍（用的是同一个上下文）
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("同一个上下文里的切面身份：AgentRegistry / LlmAgentFactory / HttpTransport / ModelRegistry 都是我的")
    void facetIdentitiesInThisContext() {
        assertThat((Object) agentRegistry).isInstanceOf(DemoAgentRegistry.class);
        assertThat(agentRegistry).isSameAs(demoAgentRegistry);
        assertThat(agentFactory).isSameAs(demoAgentFactory);
        assertThat(httpTransport).isSameAs(stubTransport);
        // 生成式断言：这个类名只在被覆盖过时才存在。
        assertThat(modelRegistry.getClass().getSimpleName()).isEqualTo("DemoModelRegistry");
        assertThat(demoLoopRegistry.marker()).isEqualTo("DemoLoopRegistry");
    }
}
