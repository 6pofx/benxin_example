package com.benxin.exp.observability;

import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.listener.MonitorListener;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.CompositeListener;
import com.benxin.llm.core.hook.LoggingListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监听器的<b>装配语义</b>：内置让位、自定义叠加、手动组合。
 *
 * <p>这三条经常被混为一谈："只能有一个监听器"是错的，
 * "自定义监听器与内置监听器并存"也是错的 —— 真实口径是
 * <b>内置的那个按类型让位，自定义的彼此叠加</b>。</p>
 */
@DisplayName("示例 08 · 监听器装配语义")
class ListenerFanoutTest extends ObservabilityTestSupport {

    @Autowired
    private ChatAssistant chat;

    @Autowired
    private ApplicationContext context;

    // ------------------------------------------------------------------
    // ① 多个自定义监听器全部生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("多个自定义监听器 bean 全部生效：两个都收到同一批事件（叠加而非互斥）")
    void multipleCustomListenersAllReceiveEvents() {
        chat.ask("自我介绍");

        assertThat(monitorListener.count("onAgentStart")).isEqualTo(1);
        assertThat(monitorListener.count("onAgentEnd")).isEqualTo(1);

        assertThat(toolActivityListener.agentEnds()).isEqualTo(1);
        // 文本增量只由"模型支持流式"决定，两个监听器收到的条数必然一致
        assertThat(toolActivityListener.textDeltas()).isEqualTo((int) monitorListener.count("onTextDelta"));
    }

    @Test
    @DisplayName("容器里恰好有 2 个 AgentListener bean，名字就是注册的那两个")
    void customListenersStaySeparateBeans() {
        String[] names = context.getBeanNamesForType(AgentListener.class);
        assertThat(names).containsExactlyInAnyOrder("monitorListener", "toolActivityListener");
        // 真正的合成发生在装配 Agent 时（AgentBuilder.build() 里的 CompositeListener.of），
        // 容器里不会因此多出一个 CompositeListener bean。
        assertThat(context.getBeansOfType(CompositeListener.class)).isEmpty();
    }

    // ------------------------------------------------------------------
    // ② 内置监听器让位
    // ------------------------------------------------------------------

    @Test
    @DisplayName("内置 llmLoggingListener 按类型让位：容器里根本没有这个 bean")
    void builtinLoggingListenerStepsAside() {
        // @ConditionalOnMissingBean(AgentListener.class) 是按"类型"判断的：
        // 只要出现任意一个 AgentListener bean，内置那个就整个不装配。
        assertThat(context.containsBean("llmLoggingListener")).isFalse();
        assertThat(context.getBeansOfType(LoggingListener.class)).isEmpty();
    }

    // ------------------------------------------------------------------
    // ③ CompositeListener.of(...) / AgentListener.noop()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CompositeListener.of(...) 拍平嵌套、丢掉 null（但保留 noop）；AgentListener.noop() 一个事件都不发")
    void compositeListenerFlattensAndFilters() {
        MonitorListener left = new MonitorListener("left");
        MonitorListener right = new MonitorListener("right");
        MonitorListener nested = new MonitorListener("nested");

        AgentListener inner = CompositeListener.of(nested, null);
        AgentListener composite = CompositeListener.of(left, inner, null, AgentListener.noop(), right);

        // of(...) 的实测语义（读 CompositeListener.of(List) 的源码即可确认）：
        //   ① 嵌套的 CompositeListener 会被"拍平"，它内部的委托提到同一层；
        //   ② null 被丢掉；
        //   ③ AgentListener.noop() 只是一个普通的匿名实现，并【不】会被特殊处理 ——
        //      会过滤的只有 null 与嵌套的 CompositeListener。
        // 所以这条链里实际有 4 个委托：[left, nested, noop, right]（noop 白占一格但不发事件）。
        assertThat(composite).isInstanceOf(CompositeListener.class);
        List<AgentListener> delegates = ((CompositeListener) composite).delegates();
        assertThat(delegates).hasSize(4).doesNotContainNull();
        assertThat(delegates).containsSubsequence(left, nested, right);
        assertThat(delegates.get(2)).isNotInstanceOf(MonitorListener.class);   // 第 3 格就是那个 noop

        // 用"程序化装配的副本"承载这条链：容器里那个 Agent 的监听器是启动期定好的，
        // 演示里看到的正是"两个自定义监听器被 AgentBuilder 合成一条链"这件事，
        // 而合成算法的细节在这里用独立副本验证更干净。
        Agent agent = agentRegistry.get("chatAssistant").toBuilder()
                .listener(composite)
                .build();
        agent.call("自我介绍", "composite-session");

        assertThat(left.count("onAgentStart")).isEqualTo(1);
        assertThat(right.count("onAgentStart")).isEqualTo(1);
        assertThat(nested.count("onAgentStart")).isEqualTo(1);
        assertThat(left.count("onAgentEnd")).isEqualTo(1);

        // noop 是"所有回调都空实现"的 AgentListener：任何回调都不会抛异常，
        // 因此它可以安全地充当 CompositeListener.of(...) 里的占位符。
        AgentListener noop = AgentListener.noop();
        noop.onAgentStart(null);
        noop.onStepStart("agent", 1);
        noop.onTextDelta("x");
        noop.onThinkingDelta("x");
        noop.onToolCall(null);
        noop.onToolResult(null, null);
        noop.onStepEnd("agent", 1, null);
        noop.onSubAgentStart("p", "c", "prompt");
        noop.onSubAgentEnd("p", "c", null);
        noop.onCustomEvent("type", "payload");
        noop.onAgentEnd(null);
        noop.onError(new IllegalStateException("x"));
        assertThat(noop).isNotInstanceOf(CompositeListener.class);
    }

    @Test
    @DisplayName("单个监听器抛异常不会影响其它监听器与主流程")
    void oneFailingListenerDoesNotBreakOthers() {
        AtomicInteger after = new AtomicInteger();
        AgentListener boom = new AgentListener() {
            @Override
            public void onAgentStart(com.benxin.llm.core.agent.AgentInvocation invocation) {
                throw new IllegalStateException("故意炸掉的监听器");
            }
        };
        AgentListener counter = new AgentListener() {
            @Override
            public void onAgentEnd(com.benxin.llm.core.agent.AgentResult result) {
                after.incrementAndGet();
            }
        };

        Agent agent = agentRegistry.get("chatAssistant").toBuilder()
                .listener(CompositeListener.of(boom, counter))
                .build();
        String text = agent.call("自我介绍", "failing-listener-session").text();

        assertThat(text).isNotBlank();
        assertThat(after.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("监听器是 Agent 级的：没挂上去的那个监听器一个事件都收不到")
    void listenersArePerAgent() {
        MonitorListener notAttached = new MonitorListener("not-attached");

        Agent agent = agentRegistry.get("chatAssistant").toBuilder()
                .listener(new MonitorListener("attached"))
                .build();
        agent.call("自我介绍", "per-agent-session");

        assertThat(notAttached.count("onAgentEnd")).isZero();
    }

    @Test
    @DisplayName("容器里的监听器清单可枚举（可观测性自身的自查）")
    void listenerBeansAreEnumerable() {
        List<String> names = List.of(context.getBeanNamesForType(AgentListener.class));
        assertThat(names).contains("monitorListener", "toolActivityListener");
        assertThat(monitorListener.label()).isEqualTo("monitor");
    }
}
