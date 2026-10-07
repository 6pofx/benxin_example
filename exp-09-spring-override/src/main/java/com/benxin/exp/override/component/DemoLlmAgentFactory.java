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
                               ToolSandbox sandbox,
                               ApprovalHandler approvalHandler,
                               AgentRegistry agentRegistry,
                               ObjectProvider<LlmInterceptorChain> interceptorChainProvider,
                               ObjectProvider<AgentListener> listenerProvider) {
        super(properties, modelRegistry, loopRegistry, toolCatalog, contextManager, memoryStore,
                systemPromptProvider, sandbox, approvalHandler, agentRegistry,
                interceptorChainProvider, listenerProvider);
    }

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
