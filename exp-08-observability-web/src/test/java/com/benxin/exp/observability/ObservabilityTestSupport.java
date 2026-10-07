package com.benxin.exp.observability;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.observability.interceptor.GuardInterceptor;
import com.benxin.exp.observability.interceptor.OrderProbeInterceptor;
import com.benxin.exp.observability.interceptor.ShortCircuitInterceptor;
import com.benxin.exp.observability.interceptor.ToolAuditInterceptor;
import com.benxin.exp.observability.listener.MonitorListener;
import com.benxin.exp.observability.listener.ToolActivityListener;
import com.benxin.exp.observability.web.SessionIds;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 所有需要完整容器的测试的公共基类。
 *
 * <p>集中做三件事：</p>
 * <ol>
 *   <li>把 {@code @SpringBootTest} 摆在基类上，让所有子类<b>共享同一个 ApplicationContext</b>
 *       （配置相同 → 命中 Spring 的上下文缓存），避免每个测试类各起一次容器；</li>
 *   <li>在 {@code @BeforeEach} 里清空全部夹具 —— 子类若需额外清理，覆写时<b>必须先调
 *       {@code super.resetFixtures()}</b>；</li>
 *   <li>把"清空会话历史"这件麻烦事做成 {@link #clearHistory()}。</li>
 * </ol>
 *
 * <p><b>为什么监听器要按名字取</b>（{@link #monitorListener} 在 {@code @BeforeEach} 里
 * 用 {@code getBean(name, type)} 拿，而不是 {@code @Autowired} 具体类型）：
 * {@code ObservabilityConfiguration} 里那两个 {@code @Bean} 方法的返回类型刻意声明为
 * {@code AgentListener} —— 因为内置的 {@code llmLoggingListener} 用的是
 * {@code @ConditionalOnMissingBean(AgentListener.class)}，只有容器里真的存在
 * "类型为 AgentListener 的 bean" 时它才会让位。若把返回类型改成具体类，
 * 按类型注入是方便了，但"内置让位"这条装配语义就验证不到了。</p>
 *
 * <p><b>为什么必须清历史：</b>声明式 Agent 默认开着记忆，而 HTTP 端点在"不传 sessionId"时
 * 会生成随机的 {@code http-<uuid>} 会话 —— 测试根本猜不到它的 id。
 * {@code SessionIds} 拦截器把这些 id 登记下来，{@link SessionIds#clearAll} 才能清干净。
 * 同时刻意<b>不使用</b> {@code AgentRegistry.resolved()}：{@code @LlmAgent} 接口在注册表里是
 * 惰性条目，{@code resolved()} 只看得到"已经被解析过"的那些，用它遍历一个都清不掉。</p>
 */
@SpringBootTest
public abstract class ObservabilityTestSupport {

    @Autowired
    protected RecordingModel mockLlmModel;

    @Autowired
    protected CommonTools commonTools;

    @Autowired
    protected AgentRegistry agentRegistry;

    @Autowired
    protected SessionIds sessionIds;

    @Autowired
    protected ShortCircuitInterceptor shortCircuitInterceptor;

    @Autowired
    protected ToolAuditInterceptor toolAuditInterceptor;

    @Autowired
    protected ApplicationContext context;

    /** 录制型监听器：12 个回调全部留档。 */
    protected MonitorListener monitorListener;

    /** 第二个监听器：证明多个自定义监听器会叠加。 */
    protected ToolActivityListener toolActivityListener;

    @BeforeEach
    void resetFixtures() {
        monitorListener = context.getBean("monitorListener", MonitorListener.class);
        toolActivityListener = context.getBean("toolActivityListener", ToolActivityListener.class);

        mockLlmModel.reset();
        commonTools.reset();
        monitorListener.reset();
        toolActivityListener.reset();
        shortCircuitInterceptor.reset();
        toolAuditInterceptor.reset();
        OrderProbeInterceptor.resetTrace();
        GuardInterceptor.resetHooks();
        clearHistory();
    }

    /** 清空所有已知会话的历史（含 HTTP 端点生成的随机会话）。 */
    protected void clearHistory() {
        sessionIds.clearAll(agentRegistry);
    }

    /** 拿到被 {@link RecordingModel} 装饰的脚本化模型，用于查看"模型被调了几次"。 */
    protected ScriptedLlmModel scripted() {
        return (ScriptedLlmModel) mockLlmModel.unwrap();
    }
}
