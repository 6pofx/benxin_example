package com.benxin.exp.override.override;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.override.component.DemoAgentRegistry;
import com.benxin.exp.override.component.DemoLlmAgentFactory;
import com.benxin.exp.override.component.DemoLoopRegistry;
import com.benxin.exp.override.component.DemoModelRegistry;
import com.benxin.exp.override.component.DoublingTokenEstimator;
import com.benxin.exp.override.component.OrderFollowerInterceptor;
import com.benxin.exp.override.component.PlatformSystemPromptProvider;
import com.benxin.exp.override.component.RecordingAgentListener;
import com.benxin.exp.override.component.RecordingContextManager;
import com.benxin.exp.override.component.RecordingMemoryStore;
import com.benxin.exp.override.component.RecordingNoopCompactor;
import com.benxin.exp.override.component.StubTransport;
import com.benxin.exp.override.component.ToggleApprovalHandler;
import com.benxin.exp.override.component.ToggleToolSandbox;
import com.benxin.exp.override.component.TraceInterceptor;
import com.benxin.exp.common.wire.StubHttpServer;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.annotation.LlmModelDef;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmInterceptorChain;
import com.benxin.llm.spring.LlmProperties;
import com.benxin.llm.spring.LlmToolCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * <b>本模块的主角</b>：用 14 个自定义 bean 逐个顶掉 {@code LlmAutoConfiguration} 的默认实现。
 *
 * <p>整个类里没有一行开关、没有一个 {@code @Primary}、没有一处 {@code exclude}。
 * 让位的机制只有一条：{@code LlmAutoConfiguration} 里每个 {@code @Bean} 都带
 * {@code @ConditionalOnMissingBean}，于是"容器里已经有同类型的 bean"就等于"用户接管"。
 * 这就是「一切皆可插拔」在 Spring 层面的全部实现手段。</p>
 *
 * <p>三个容易被忽略的细节，都在下面各自的 {@code @Bean} 上写了：</p>
 * <ol>
 *   <li><b>bean 名可以照抄自动配置的</b>（{@code llmModelRegistry} / {@code llmInterceptors} …）。
 *       抄了不会冲突，因为对方压根没注册；但抄了有额外好处：诊断时
 *       {@code beanDefinitionNames} 里对得上号，而 {@code llmInterceptors} 这个切面的条件
 *       本来就是按<b>名字</b>判的（{@code @ConditionalOnMissingBean(name = "llmInterceptors")}）。</li>
 *   <li><b>{@code @ConditionalOnMissingBean} 看的是"用户 bean 定义先于自动配置被注册"</b>。
 *       {@code @Configuration} 类在 {@code ConfigurationClassPostProcessor} 阶段（用户配置）
 *       先被处理，自动配置类在后 —— 所以这里定义的 bean 一定"更早被看到"，
 *       与 bean 的实例化顺序、依赖关系都无关。</li>
 *   <li><b>能继承就继承</b>：{@code ModelRegistry} / {@code LoopRegistry} / {@code AgentRegistry} /
 *       {@code LlmAgentFactory} 都是普通类，子类 is-a 父类，条件照样让位。
 *       "换实现"不等于"从零写一遍"。</li>
 * </ol>
 *
 * <p><b>整类上的 {@code @ConditionalOnProperty} 是必需的</b>：{@code @ConditionalOnMissingBean}
 * 只作用于自动配置，管不到用户自己的 {@code @Configuration}。{@code llm.enabled=false} 时
 * 本心里那些组件一个都不存在，而这个类里的 {@code @Bean} 方法全都在等它们注入 ——
 * 结果会是 {@code UnsatisfiedDependencyException}，整个应用起不来。
 * 加上这一行之后，总开关一关，本类也跟着让位，容器干干净净。
 * 这也是"替换一个组件"附带的义务：<b>你得跟被替换的那个 bean 用同一套开关</b>。</p>
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "llm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultOverrideConfiguration {

    // ==================================================================
    // 切面 1/14 · HttpTransport
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmHttpTransport()}（默认 {@code new JdkHttpTransport()}）。
     *
     * <p>这个实现<b>仍然会发出真实的 HTTP</b>，只是多记了一份流水；
     * 测试里配一个 {@link StubHttpServer} 当端点，就能看到请求真的从这条传输层出去了。</p>
     */
    @Bean
    public StubTransport stubTransport() {
        return new StubTransport();
    }

    // ==================================================================
    // 切面 3/14 · ModelRegistry
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmModelRegistry(...)}（默认在方法体里按 yml + LlmModel bean 现搭一个）。
     *
     * <p>返回类型刻意写成 {@link DemoModelRegistry} 而不是 {@code ModelRegistry}：
     * 这样测试可以 {@code @Autowired ModelRegistry} 之后断言 {@code isInstanceOf(DemoModelRegistry.class)}，
     * 而"容器里只有一个 ModelRegistry"这条不变式也仍然成立。</p>
     *
     * <p>bean 名照抄 {@code llmModelRegistry}：这是官方给出的那个名字，
     * 诊断 {@code /llm/**} 端点或 {@code beanDefinitionNames} 时最容易对上。</p>
     *
     * <p><b>这里也是本模块最值得停一下的一处</b>：换成自定义实现之后，
     * "yml → 模型"这段翻译就<b>不再自动发生</b>了 —— 自动配置整个 bean 方法都没跑。
     * 所以 {@link DemoModelRegistry#seed} 把那段算法原样搬了过来。
     * "接过一个切面"同时意味着"接过它的全部义务"，这一点在 15 个切面里都一样。</p>
     */
    @Bean
    public DemoModelRegistry llmModelRegistry(LlmProperties properties,
                                              HttpTransport transport,
                                              ObjectProvider<LlmModel> modelBeans) {
        return new DemoModelRegistry().seed(properties, transport, modelBeans);
    }

    // ==================================================================
    // 切面 4/14 · LoopRegistry
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmLoopRegistry(...)}（默认注册 8 个内置 Loop +
     * {@code @LlmLoop} bean + {@code llm.workflows.*}）。
     *
     * <p>与切面 3 同理：换掉它就要自己把内置 Loop、工作流与 {@code @LlmLoop} bean 装进去，
     * 见 {@link DemoLoopRegistry#seed}。示例里这么写不为省事，只为让责任边界可见。</p>
     */
    @Bean
    public DemoLoopRegistry llmLoopRegistry(LlmProperties properties,
                                            ObjectProvider<com.benxin.llm.core.loop.AgentLoop> loopBeans,
                                            org.springframework.core.io.ResourceLoader resourceLoader) {
        return new DemoLoopRegistry().seed(properties, loopBeans, resourceLoader);
    }

    // ==================================================================
    // 切面 5/14 · TokenEstimator
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmTokenEstimator()}（默认 {@code TokenEstimator.DEFAULT}）。
     *
     * <p>注意返回值类型要写成<b>接口类型</b>或至少是可注入的类型；这里用 {@link DoublingTokenEstimator}
     * 是为了让测试能拿到 {@code invocations()} 这个计数。</p>
     */
    @Bean
    public DoublingTokenEstimator doublingTokenEstimator() {
        return new DoublingTokenEstimator();
    }

    // ==================================================================
    // 切面 6/14 · ContextCompactor
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmContextCompactor(LlmProperties)}
     * （默认 {@code new SummarizingCompactor()} —— 会调模型做摘要）。
     */
    @Bean
    public RecordingNoopCompactor recordingCompactor() {
        return new RecordingNoopCompactor();
    }

    // ==================================================================
    // 切面 7/14 · ContextManager
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmContextManager(ContextCompactor, TokenEstimator)}
     * （默认 {@code new DefaultContextManager(compactor, estimator, 0.8, 8, 4096)}）。
     *
     * <p>参数里那两个类型就是切面 5 与切面 6 —— <b>换掉它们，这里自动拿到新实现</b>。
     * "可插拔"因此不是 14 个孤立的点，而是一张靠构造器注入连起来的网。</p>
     */
    @Bean
    public RecordingContextManager recordingContextManager(ContextCompactor compactor,
                                                            TokenEstimator estimator) {
        return new RecordingContextManager(compactor, estimator);
    }

    // ==================================================================
    // 切面 8/14 · MemoryStore
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmMemoryStore()}（默认 {@code new InMemoryMemoryStore()}）。
     *
     * <p>想接 Redis 就在这里换：接口只有 {@code load/save/clear} 三个方法，
     * 加上一个可选的 {@code exists}。</p>
     */
    @Bean
    public RecordingMemoryStore recordingMemoryStore() {
        return new RecordingMemoryStore();
    }

    // ==================================================================
    // 切面 9/14 · SystemPromptProvider
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmSystemPromptProvider()}
     * （默认 {@code new TemplateSystemPromptProvider()} —— 做 {@code {变量}} 插值）。
     */
    @Bean
    public PlatformSystemPromptProvider platformSystemPromptProvider() {
        return new PlatformSystemPromptProvider();
    }

    // ==================================================================
    // 切面 10/14 · ApprovalHandler
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmApprovalHandler()}（默认 {@code ApprovalHandler.autoApprove()}）。
     *
     * <p>默认之所以是"自动放行"：无人值守环境下弹窗式审批只会把请求挂死。
     * 真正的守门交给沙箱与 {@code ApprovalPolicy}。</p>
     */
    @Bean
    public ToggleApprovalHandler toggleApprovalHandler() {
        return new ToggleApprovalHandler();
    }

    // ==================================================================
    // 切面 11/14 · ToolSandbox
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmToolSandbox(LlmProperties)}
     * （默认 {@code BuiltinToolkit.sandbox(LlmBuiltinToolRegistrar.toSandboxConfig(tools))}）。
     */
    @Bean
    public ToggleToolSandbox toggleToolSandbox() {
        return new ToggleToolSandbox();
    }

    // ==================================================================
    // 切面 12/14 · AgentListener
    // ==================================================================

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmLoggingListener(LlmProperties)}。
     *
     * <p>这是 14 个切面里唯一一个曾经"按 bean 名判条件"的（{@code name = "llmLoggingListener"}），
     * 结果是自定义监听器跟内置的<b>并存</b>而不是顶掉。现在条件改成按<b>类型</b>判
     * （{@code @ConditionalOnMissingBean(AgentListener.class)}），与本模块演示的其余 13 个切面一致。
     * 测试里那条 {@code hasSingleBean(AgentListener.class)} 就是这条口径的钉子。</p>
     */
    @Bean
    public RecordingAgentListener recordingAgentListener() {
        return new RecordingAgentListener();
    }

    // ==================================================================
    // 切面 13/14 · List<AgentInterceptor>
    // ==================================================================

    /** 链上的第一个拦截器：带 {@code @LlmGuard(agents = {"toolAssistant"})}，只对那个 Agent 生效。 */
    @Bean
    public TraceInterceptor traceInterceptor() {
        return new TraceInterceptor();
    }

    /** 链上的第二个拦截器：没有 {@code @LlmGuard}，对所有 Agent 生效，order 更大。 */
    @Bean
    public OrderFollowerInterceptor orderFollowerInterceptor() {
        return new OrderFollowerInterceptor();
    }

    /**
     * 覆盖 {@code LlmAutoConfiguration#llmInterceptors(ObjectProvider)}。
     *
     * <p><b>这个切面跟别的 13 个不一样</b>：它的条件是
     * {@code @ConditionalOnMissingBean(name = "llmInterceptors")} —— 按 <b>bean 名</b>判，
     * 而且是 {@code List<AgentInterceptor>} 这个"集合类型"。名字与类型都必须对得上，
     * 这也是全模块唯一一个必须照抄 bean 名的切面。</p>
     *
     * <p>不自己定义它也行：那时自动配置会把容器里的 {@code AgentInterceptor} bean 收集起来，
     * 并给带 {@code @LlmGuard} 的套上 {@code SelectiveInterceptor}（范围过滤）后按 order 排序。
     * 本方法刻意把"收集"这一步写出来，是为了让这条链<b>看得见</b>。</p>
     *
     * <p>⚠️ <b>但别用它当消费端</b>：容器里同时还有 {@code LlmInterceptorChain}，
     * 声明式装配（{@code LlmAgentFactory}）消费的是后者。原因是
     * {@code @LlmGuard(agents = {...})} 的范围包装只发生在<b>返回 List 的那个 bean 方法里</b>，
     * 直接注入 {@code List<AgentInterceptor>} 会拿到<b>未包装</b>的原始 bean，
     * 于是范围声明彻底失效。这一点在 {@code LlmInterceptorChain} 的类注释里有完整说明。</p>
     */
    @Bean
    public List<AgentInterceptor> llmInterceptors(TraceInterceptor trace,
                                                  OrderFollowerInterceptor follower) {
        // LlmAutoConfiguration.resolveInterceptors 做的事：包 @LlmGuard + 按 order 排序。
        // 这里手写一遍，等价于"我把那条链自己接管了"。
        List<AgentInterceptor> chain = new java.util.ArrayList<>();
        chain.add(new com.benxin.llm.spring.SelectiveInterceptor(trace, trace.getClass().getAnnotation(
                com.benxin.llm.core.annotation.LlmGuard.class)));
        chain.add(follower);
        chain.sort(java.util.Comparator.comparingInt(AgentInterceptor::order));
        return chain;
    }

    // ==================================================================
    // 切面 14/14 · AgentRegistry
    // ==================================================================

    /** 覆盖 {@code LlmAgentAutoConfiguration#llmAgentRegistry()}（默认 {@code new AgentRegistry()}）。 */
    @Bean
    public DemoAgentRegistry llmAgentRegistry() {
        return new DemoAgentRegistry();
    }

    // ==================================================================
    // 切面 15/14 · LlmAgentFactory
    // ==================================================================

    /**
     * 覆盖 {@code LlmAgentAutoConfiguration#llmAgentFactory(...)}。
     *
     * <p>这个 {@code @Bean} 方法的方法体与自动配置里那一份<b>逐参对应</b> —— 不是复制粘贴的样板，
     * 而是本模块最想让你看到的一件事：<b>15 个切面全部是构造器参数</b>。
     * 换句话说，上面 14 个 {@code @Bean} 不管换了哪一个，都会自动流进这个工厂，
     * 再流进每一个声明式 Agent。这就是"插拔"能一路兑现到调用方的机制。</p>
     *
     * <p>注意最后两个参数的类型：{@link LlmInterceptorChain} 与 {@code ObjectProvider<AgentListener>}。
     * 前者是"已按 {@code @LlmGuard} 包好并排过序"的那份链；
     * 后者是懒解析的（装配那一刻才去取），因此监听器 bean 的创建顺序不会影响这里。</p>
     */
    @Bean
    public DemoLlmAgentFactory llmAgentFactory(LlmProperties properties,
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
        return new DemoLlmAgentFactory(properties, modelRegistry, loopRegistry, toolCatalog,
                contextManager, memoryStore, systemPromptProvider, sandbox, approvalHandler,
                agentRegistry, interceptorChainProvider, listenerProvider);
    }

    // ==================================================================
    // 另外两样东西：工具来源 + 一个"走真实 HTTP"的模型 bean
    // ==================================================================

    /**
     * 工具来源由使用者决定：把 {@link CommonTools} 交给容器，
     * {@code LlmToolCatalog}（BeanPostProcessor）会扫出它里面的 4 个 {@code @LlmTool} 方法。
     *
     * <p>这条路径本身也是"可插拔"的一部分，只不过它走的是<b>类型</b>（{@code LlmToolCatalog}
     * 收集所有带 {@code @LlmTool} 的 bean）而不是 {@code @ConditionalOnMissingBean}。</p>
     */
    @Bean
    public CommonTools commonTools() {
        return new CommonTools();
    }

    /**
     * 一个"走真实 HTTP"的模型 bean，演示 <b>yml 与代码两条路径的优先级</b>。
     *
     * <p>它的类型 {@link StubDemoModel} 上标着 {@code @LlmModelDef("stub")}，
     * 与 yml 里的 {@code llm.models.stub} 同名 —— 而
     * {@code LlmAutoConfiguration.llmModelRegistry()} 的规则是"<b>bean 后登记</b>"，
     * 所以容器里的 bean 覆盖同名配置项。这就是"配置不够用时用代码接管"的那条通道。</p>
     *
     * <p>{@code @LlmModelDef} 必须标在<b>类</b>上，不能标在 {@code @Bean} 方法上：
     * 自动配置读的是 {@code AopUtils.getTargetClass(model).getAnnotation(LlmModelDef.class)}，
     * 也就是"模型对象的实际类型"。所以这里多了一层 {@link StubDemoModel}，
     * 而不是让 {@code @Bean} 方法直接返回 {@code new RecordingModel(...)}。</p>
     *
     * <p>base-url 默认是"不出网"（{@code HttpBackedModel.OFFLINE}）：纯内存里跑脚本决策。
     * 演示进程（{@code SpringOverrideDemo}）与
     * {@code SpringOverrideTest#httpTransportFacetActuallyCarriesTraffic} 会各自起一个
     * {@link StubHttpServer} 并把它指过来，那时才真的发 HTTP。
     * 这样"默认离线、零 token"与"要 HTTP 证据时真的有 HTTP"两件事同时成立。</p>
     */
    @Bean
    public LlmModel stubLlmModel(HttpTransport transport) {
        // 外面套一层 RecordingModel：它把每次真正发给模型的 ChatRequest 留档，
        // 示例与测试因此能断言"模型这一轮看到的系统提示词是什么"。
        return new RecordingModel(new StubDemoModel(transport));
    }

    /**
     * {@code stub} 端点的代码版实现。
     *
     * <p>{@code @LlmModelDef} 标在它是为了演示"容器里的 {@code LlmModel} bean 覆盖同名 yml 配置项"
     * （见上）。行为上它把脚本决策与一次真实的 HTTP 往返缝在一起（见 {@link HttpBackedModel}）。</p>
     */
    @LlmModelDef("stub")
    public static class StubDemoModel extends HttpBackedModel {

        public StubDemoModel(HttpTransport transport) {
            super("stub", transport);
        }
    }

    /**
     * 一个把"脚本决策"与"真实 HTTP"缝在一起的模型：决策由脚本给，但每轮先真的发一次 HTTP。
     *
     * <p>为什么需要它：{@link StubTransport} 只有被真正调用才会留下流水，
     * 而 {@link ScriptedLlmModel} 是纯内存的、永远不会碰网络。这个类让
     * "自定义 HttpTransport 到底有没有被用上"变成一个可以观测的事实。</p>
     *
     * <p><b>默认是"离线模式"</b>（{@link #OFFLINE}）：只有被显式指向一个端点之后才真的发 HTTP。</p>
     *
     * <p>这不是为了省事，而是本模块"默认完全离线、零 token"这条约束的必要条件。
     * yml 里 {@code llm.models.stub.base-url} 指向 {@code https://api.example.invalid/v1}
     * （一个刻意不可解析的域名），而声明式路径<b>恒为流式</b>
     * （{@code DefaultLoopContext.callModel} 直接看 {@code capabilities().streaming()}），
     * 于是一旦默认就发 HTTP，每一次模型调用都会真的去连那个不存在的域名 ——
     * 表现是几秒钟的连接超时之后抛 {@code ModelException}，
     * 而不是"离线跑通"。测试里绝大多数用例并不需要 HTTP 证据，
     * 它们只想确认"调用穿过了我换的那个切面"。</p>
     *
     * <p>需要真实 HTTP 证据的两个地方（演示进程与
     * {@code SpringOverrideTest#httpTransportFacetActuallyCarriesTraffic}）
     * 会各自起一个 {@link StubHttpServer} 并把它指过来 —— 回环地址上的真 socket、
     * 真协议报文，但不出网、不花 token。</p>
     */
    public static class HttpBackedModel implements LlmModel {

        /**
         * "别出网"的哨兵值。
         *
         * <p>刻意不用 {@code null}：{@code null} 是"还没设置"的自然表示，
         * 而这里要表达的是"设置过了，且明确要求不出网"。用哨兵值之后
         * {@link #baseUrl()} 就能如实回答"当前生效的是什么"，
         * 打印到控制台时也不会让人误以为只是忘了配。</p>
         */
        public static final String OFFLINE = "offline:in-memory";

        /** 上游端点前缀（例如 {@code http://127.0.0.1:53124}）；{@link #OFFLINE} 表示"本次不真的发 HTTP"。 */
        private volatile String baseUrl = OFFLINE;

        private final ScriptedLlmModel scripted;
        private final HttpTransport transport;

        HttpBackedModel(ScriptedLlmModel scripted, HttpTransport transport) {
            this.scripted = scripted;
            this.transport = transport;
        }

        /** 按名字造一个开箱可用的脚本决策源（子类用这个构造器）。 */
        HttpBackedModel(String name, HttpTransport transport) {
            this(ScriptedLlmModel.builder(name)
                    .rule("你好", "（stub 模型）你好，我走的是真实 HTTP 而不是纯内存调用。")
                    .fallback("（stub 模型）已收到：{input}")
                    .build(), transport);
        }

        /**
         * 指向 {@code StubHttpServer#baseUrl()}；演示与测试启动桩服务之后调用。
         *
         * <p>指向之后每一轮模型调用都会先真的发一次 HTTP；传 {@link #OFFLINE}（或 null）
         * 则退回纯内存的脚本决策。</p>
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? OFFLINE : baseUrl;
        }

        public String baseUrl() {
            return baseUrl;
        }

        /** 当前是否真的会把请求发到网络上。 */
        public boolean isOnline() {
            return baseUrl != null && !OFFLINE.equals(baseUrl);
        }

        /**
         * 最里面那层脚本决策源。
         *
         * <p>演示要打印"模型这一轮看到的系统提示词"，而那是 {@link ScriptedLlmModel} 记的。
         * 与其让演示去反射私有字段（本类里已经有两处反射了，都是因为"不想为演示扩大可见性"），
         * 不如在这里给一个只读访问器 —— 反射是脆的，编译器不会帮你发现它失效。</p>
         */
        public ScriptedLlmModel scripted() {
            return scripted;
        }

        @Override
        public String name() {
            return scripted.name();
        }

        @Override
        public com.benxin.llm.core.model.ModelCapabilities capabilities() {
            return scripted.capabilities();
        }

        @Override
        public com.benxin.llm.core.chat.ChatResponse chat(com.benxin.llm.core.chat.ChatRequest request) {
            // 决策始终由脚本模型给（离线、可复现）；只有被指到端点上时才额外发一次 HTTP：
            // 那一次请求就是"自定义 HttpTransport 被用上了"的硬证据 ——
            // 换成默认的 JdkHttpTransport 也一样能发，但 StubTransport 会把它记下来。
            if (isOnline()) {
                ModelConfig config = outboundConfig();
                com.benxin.llm.core.chat.ChatRequest outbound = outboundRequest(request);
                transport.post(codec().endpoint(config, outbound), codec().headers(config),
                        codec().encode(outbound, config), java.time.Duration.ofSeconds(5));
            }
            return scripted.chat(request);
        }

        @Override
        public void stream(com.benxin.llm.core.chat.ChatRequest request,
                           com.benxin.llm.core.model.LlmStreamHandler handler) {
            // ⚠️ 这一步很重要：声明式路径（DefaultLoopContext.callModel）看的<b>不是</b>
            // AgentSpec.stream()，而是 capabilities().streaming() —— 它是 true，所以
            // 走的一直是 stream(...) 而不是 chat(...)。因此 HTTP 证据也必须在这里发，
            // 否则"自定义 HttpTransport 被用上了"这句话在声明式路径上根本不成立。
            if (isOnline()) {
                ModelConfig config = outboundConfig();
                com.benxin.llm.core.chat.ChatRequest outbound = outboundRequest(request);
                // 用 postStreaming（而不是 post）：与 HttpLlmModel.stream 走同一条传输方法，
                // 于是自定义传输层的 postStreaming 也被真正覆盖到。
                transport.postStreaming(codec().endpoint(config, outbound), codec().headers(config),
                        codec().encode(outbound, config), java.time.Duration.ofSeconds(5));
            }
            // 决策仍由脚本模型给：本类是"传输层证据"的载体，不是协议实现。
            // 想看真正的 SSE → ChatResponse 解码，请去 CoreFacadeTest#realStreaming
            // 或 exp-07：那里用的是插件自带的 HttpLlmModel + StreamDecoder。
            scripted.stream(request, handler);
        }

        /** 出网时用的配置：base-url 取当前端点，模型 id 固定成桩服务认得的那个。 */
        private ModelConfig outboundConfig() {
            return ModelConfig.builder(name())
                    .baseUrl(baseUrl)
                    .apiKey("exp-09-demo")
                    .model("stub-gpt")
                    .maxRetries(0)
                    .build();
        }

        /** 出网时用的请求：把模型 id 换成桩服务认得的那个（其余字段原样透传）。 */
        private com.benxin.llm.core.chat.ChatRequest outboundRequest(com.benxin.llm.core.chat.ChatRequest request) {
            return request.toBuilder().model("stub-gpt").build();
        }

        /** 报文用内置的 OpenAiCodec 编码：不手搓 JSON，免得示例自己的报文写错。 */
        private com.benxin.llm.core.protocol.openai.OpenAiCodec codec() {
            return new com.benxin.llm.core.protocol.openai.OpenAiCodec();
        }

        @Override
        public String toString() {
            return "HttpBackedModel(" + scripted.name() + ", 端点=" + (isOnline() ? baseUrl : "未接（纯内存）") + ")";
        }
    }
}
