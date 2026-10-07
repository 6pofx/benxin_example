package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.LlmProperties;
import com.benxin.llm.spring.LlmToolCatalog;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * 切面 15 的自定义实现：{@code LlmAgentFactory}。
 *
 * <p>这是最"重"的一个切面 —— 它是"注解 + 配置 + 容器里的组件"三者的交汇点，
 * 也是声明式路径唯一的装配器。换掉它意味着你接管了"注解怎么变成 Agent"这件事：
 * 想加一层命名规范、想强制注入某个拦截器、想把 {@code maxSteps} 换一套算法，改这里就行。</p>
 *
 * <p>本类只做减法：<b>不</b>在装配时打印日志、并把每次装配的 Agent 名字记下来。
 * 之所以能这么省事，是因为 {@code LlmAgentFactory} 的构造器已经把 12 个协作组件
 * 全部收成了参数 —— 换句话说，<b>15 个切面里任何一个被替换，都会自动流进这个工厂</b>。
 * 这句话就是整个模块的纲。</p>
 *
 * <p>注意构造器参数里那两个 {@code ObjectProvider} 不是随手写的：
 * 拦截器必须走 {@link LlmInterceptorChain}（已按 {@code @LlmGuard} 包好并排过序），
 * 直接用 {@code ObjectProvider<AgentInterceptor>} 会拿到<b>未包装</b>的原始 bean，
 * 于是 {@code @LlmGuard(agents = {...})} 的范围声明会彻底失效。</p>
 *
 * <p><b>1.1.1 的一处构造签名变更</b>：{@code ToolSandbox} 与 {@code ApprovalHandler}
 * 从"裸类型"改成了 {@code ObjectProvider<...>}（本工程上报为 FINDINGS 第 28 条）——
 * 目的是支持"按 Agent 选沙箱/审批器"。本类因此也跟着改：参数类型换成
 * {@code ObjectProvider}，并用 {@link #fixed(Object)} 把已经确定的实例包进去。
 * 这也说明"覆盖 LlmAgentFactory"这个切面会跟着插件的构造签名走，属于预期内的耦合。</p>
 */
public class DemoLlmAgentFactory extends LlmAgentFactory {

    private final java.util.List<String> builtAgents = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final String marker = "DemoLlmAgentFactory";

    public DemoLlmAgentFactory(LlmProperties properties,
                               ModelRegistry modelRegistry,
                               LoopRegistry loopRegistry,
                               LlmToolCatalog toolCatalog,
                               ContextManager contextManager,
                               MemoryStore memoryStore,
                               SystemPromptProvider systemPromptProvider,
                               ObjectProvider<ToolSandbox> sandboxProvider,
                               ObjectProvider<ApprovalHandler> approvalHandlerProvider,
                               AgentRegistry agentRegistry,
                               ObjectProvider<LlmInterceptorChain> interceptorChainProvider,
                               ObjectProvider<AgentListener> listenerProvider) {
        super(properties, modelRegistry, loopRegistry, toolCatalog, contextManager, memoryStore,
                systemPromptProvider, sandboxProvider, approvalHandlerProvider, agentRegistry,
                interceptorChainProvider, listenerProvider);
    }

    /**
     * 把一个已经确定的实例包成 {@link ObjectProvider}。
     *
     * <p>{@code ObjectProvider} 只有 {@code getObject()} 系列是抽象方法，
     * {@code getIfAvailable()} / {@code getIfUnique()} 都有默认实现，所以两个方法就够了。</p>
     */
    public static <T> ObjectProvider<T> fixed(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }
        };
    }

    /**
     * 记录"这个工厂装配了哪些 Agent"，并把装配工作交回给父类。
     *
     * <p><b>为什么覆写的是 {@code createFromInterface} 而不是 {@code create(...)}：</b>
     * 1.1.0 时声明式路径最终会走到公开的
     * {@code create(AgentSpec, ToolRegistry, List)}，覆写它就能插一脚。
     * 1.1.1 为了支持 {@code @LlmRetry}（FINDINGS 第 1 条）新增了一个<b>私有</b>的
     * {@code create(spec, tools, interceptors, retry)} 重载，而 {@code createFromInterface}
     * 现在直接调那个私有的 —— 于是子类覆写的公开重载<b>被静默绕过</b>（本工程已把它记为 FINDINGS 第 33 条）。
     * 公开的重载只剩"外部直接调用"这一条路。
     *
     * <p>因此这里改成覆写同样是 public 的 {@code createFromInterface}：它是声明式路径的入口，
     * 位置更靠前，也就不受内部重载怎么拆分的影响。</p>
     */
    @Override
    public Agent createFromInterface(Class<?> agentInterface) {
        builtAgents.add(specOf(agentInterface).name());
        return super.createFromInterface(agentInterface);
    }

    /**
     * 保留对公开 3 参 {@code create} 的覆写：<b>直接</b>调它的调用方（程序化装配）依然会被记下来。
     *
     * <p>注意它不再承担"记录声明式装配"的职责 —— 那条路已经不走这里了。</p>
     */
    @Override
    public Agent create(AgentSpec spec, com.benxin.llm.core.tool.ToolRegistry tools,
                        List<AgentInterceptor> extraInterceptors) {
        builtAgents.add(spec.name());
        return super.create(spec, tools, extraInterceptors);
    }

    public String marker() {
        return marker;
    }

    /** 依次记录被装配出来的 Agent 名字。 */
    public List<String> builtAgents() {
        return List.copyOf(builtAgents);
    }

    @Override
    public String toString() {
        return marker + "(已装配 " + builtAgents.size() + " 个 Agent)";
    }
}
