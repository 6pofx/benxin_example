package com.benxin.exp.override.override;

/**
 * 14 个"可替换切面"的清单：bean 名 → 自动配置里的默认实现。
 *
 * <p>为什么把这份清单做成一个枚举而不是散落在演示代码里：它是 <b>本模块的纲</b> ——
 * 装配顺序表（{@code benxin/docs/ARCHITECTURE.md} 第四节）上的 15 行、
 * {@code LlmAutoConfiguration} 里的每一个 {@code @ConditionalOnMissingBean}、
 * 以及演示里"覆盖前 / 覆盖后"那一列，都应该来自同一个事实来源。</p>
 *
 * <p><b>为什么只有 14 个</b>：第 2 行 {@code LlmToolCatalog} 与那两个"注册器"
 * （{@code LlmComponentRegistrar} / {@code LlmBuiltinToolRegistrar}）是<b>基础设施</b> bean，
 * 刻意<b>不</b>可替换 —— 原因写在下面 {@link #INFRASTRUCTURE_NOTE}。</p>
 */
public enum OverridePoint {

    HTTP_TRANSPORT("llmHttpTransport", "HttpTransport",
            "com.benxin.llm.core.transport.JdkHttpTransport",
            "换 OkHttp / WebClient / 测试桩：换掉它，所有 HTTP 模型（含流式）立刻改走新传输层"),

    MODEL_REGISTRY("llmModelRegistry", "ModelRegistry",
            "com.benxin.llm.core.model.ModelRegistry",
            "自定义模型路由 / 降级：换掉它，所有 Agent 的模型解析都走你的表"),

    LOOP_REGISTRY("llmLoopRegistry", "LoopRegistry",
            "com.benxin.llm.core.loop.LoopRegistry",
            "换或加 Loop：换掉它，@LlmAgent(loop=...) 与默认 Loop 都从你的表里取"),

    TOKEN_ESTIMATOR("llmTokenEstimator", "TokenEstimator",
            "com.benxin.llm.core.util.TokenEstimator.DEFAULT（匿名实现）",
            "接真实 tokenizer：换掉它，上下文压缩的阈值判断立刻按你的算法走"),

    CONTEXT_COMPACTOR("llmContextCompactor", "ContextCompactor",
            "com.benxin.llm.core.context.SummarizingCompactor",
            "换压缩策略：摘要压缩 / 滑窗 / 自研，换掉它即可"),

    CONTEXT_MANAGER("llmContextManager", "ContextManager",
            "com.benxin.llm.core.context.DefaultContextManager",
            "换窗口策略：换掉它，每一轮该准备哪些消息就由你决定"),

    MEMORY_STORE("llmMemoryStore", "MemoryStore",
            "com.benxin.llm.core.memory.InMemoryMemoryStore",
            "换 Redis / JDBC：换掉它，会话历史的落点就换了"),

    SYSTEM_PROMPT_PROVIDER("llmSystemPromptProvider", "SystemPromptProvider",
            "com.benxin.llm.core.prompt.TemplateSystemPromptProvider",
            "接提示词管理平台：换掉它，提示词的版本化与灰度就有了落点"),

    APPROVAL_HANDLER("llmApprovalHandler", "ApprovalHandler",
            "ApprovalHandler.autoApprove()（lambda）",
            "接人工审批：换掉它，决定权就从「自动放行」交回给人或外部系统"),

    TOOL_SANDBOX("llmToolSandbox", "ToolSandbox",
            "com.benxin.llm.core.tool.builtin.BuiltinToolkit#sandbox（按 llm.tools.* 推导）",
            "换容器化隔离 / 收紧张开度：换掉它，工具执行前的唯一闸门就是你的"),

    AGENT_LISTENER("llmLoggingListener", "AgentListener",
            "com.benxin.llm.core.hook.LoggingListener",
            "接可观测性：这是一条容易踩坑的切面 —— 条件按类型判（@ConditionalOnMissingBean(AgentListener.class)），"
                    + "定义任意一个 AgentListener bean 就会顶掉内置日志监听器"),

    INTERCEPTORS("llmInterceptors", "List<AgentInterceptor>",
            "空列表（由容器里的 AgentInterceptor bean 收集而来）",
            "加日志/脱敏/限流/计费：注意 bean 名固定是 llmInterceptors，"
                    + "而且链上同时做了 @LlmGuard 包装与 order 排序"),

    AGENT_REGISTRY("llmAgentRegistry", "AgentRegistry",
            "com.benxin.llm.spring.AgentRegistry",
            "空表：换掉它即可接管「Agent 名 → Agent 实例」的解析"),

    LLM_AGENT_FACTORY("llmAgentFactory", "LlmAgentFactory",
            "com.benxin.llm.spring.LlmAgentFactory",
            "换装配逻辑：最重的一个切面 —— 注解 + 配置 + 容器组件三者的交汇点");

    /**
     * 为什么 {@code LlmToolCatalog} / {@code LlmComponentRegistrar} / {@code LlmBuiltinToolRegistrar}
     * <b>刻意不在这张表里</b>。
     *
     * <p>前两个在 {@code LlmAutoConfiguration} 里是 <b>static {@code @Bean} 方法</b>，
     * 而且上面<b>没有</b> {@code @ConditionalOnMissingBean}：</p>
     *
     * <ul>
     *   <li>{@code LlmToolCatalog} 同时是 {@code BeanPostProcessor}，必须在普通 bean
     *       实例化<b>之前</b>就绪，否则"先创建的 bean 扫不到工具"；</li>
     *   <li>{@code LlmComponentRegistrar} 是 {@code BeanDefinitionRegistryPostProcessor}，
     *       要在 {@code llmLoopRegistry} 这类普通 bean 之前把 {@code @LlmLoop}/{@code @LlmGuard}
     *       的 bean 定义补进去，否则 {@code ObjectProvider<AgentLoop>} 看不到它们；</li>
     *   <li>{@code LlmBuiltinToolRegistrar} 用 {@code @ConditionalOnProperty} 而不是
     *       {@code @ConditionalOnMissingBean} —— 它的开关是配置，不是"有没有同名 bean"。</li>
     * </ul>
     *
     * <p>所以这三条是<b>有意的例外</b>：它们是"把别的切面组装起来"的机器本身。
     * 想让它们不生效，正确做法是关开关（{@code llm.enabled=false} /
     * {@code llm.tools.builtin-enabled=false}），而不是拿一个同类型 bean 去顶它们。</p>
     */
    public static final String INFRASTRUCTURE_NOTE =
            "基础设施 bean（LlmToolCatalog / LlmComponentRegistrar / LlmBuiltinToolRegistrar）"
                    + "刻意不可替换：它们必须是 static 定义、必须早于普通 bean 就绪（BeanPostProcessor / "
                    + "BeanDefinitionRegistryPostProcessor），或者是被 @ConditionalOnProperty 而非 "
                    + "@ConditionalOnMissingBean 控制的。它们是把别的切面组装起来的机器本身。";

    private final String beanName;
    private final String type;
    private final String defaultImplementation;
    private final String overrideEffect;

    OverridePoint(String beanName, String type, String defaultImplementation, String overrideEffect) {
        this.beanName = beanName;
        this.type = type;
        this.defaultImplementation = defaultImplementation;
        this.overrideEffect = overrideEffect;
    }

    /** 自动配置里的 {@code @Bean} 方法名，也就是 bean 名。 */
    public String beanName() {
        return beanName;
    }

    /** 切面的类型名（人能读的那个）。 */
    public String type() {
        return type;
    }

    /** 默认实现 —— 也就是"覆盖前"容器里会生效的那一个。 */
    public String defaultImplementation() {
        return defaultImplementation;
    }

    /** 覆盖它到底换来了什么。 */
    public String overrideEffect() {
        return overrideEffect;
    }

    /** 本模块里用来顶掉它的自定义 bean 名。 */
    public String myBeanName() {
        return switch (this) {
            case HTTP_TRANSPORT -> "stubTransport";
            case MODEL_REGISTRY -> "llmModelRegistry";
            case LOOP_REGISTRY -> "llmLoopRegistry";
            case TOKEN_ESTIMATOR -> "doublingTokenEstimator";
            case CONTEXT_COMPACTOR -> "recordingCompactor";
            case CONTEXT_MANAGER -> "recordingContextManager";
            case MEMORY_STORE -> "recordingMemoryStore";
            case SYSTEM_PROMPT_PROVIDER -> "platformSystemPromptProvider";
            case APPROVAL_HANDLER -> "toggleApprovalHandler";
            case TOOL_SANDBOX -> "toggleToolSandbox";
            case AGENT_LISTENER -> "recordingAgentListener";
            case INTERCEPTORS -> "llmInterceptors";
            case AGENT_REGISTRY -> "llmAgentRegistry";
            case LLM_AGENT_FACTORY -> "llmAgentFactory";
        };
    }
}
