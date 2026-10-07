package com.benxin.exp.override;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.context.SlidingWindowCompactor;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 程序化装配：<b>不用注解、不写 yml</b>，一行行把 Agent 装出来。
 *
 * <p>它与声明式（exp-01）不是两套东西，而是同一条装配链的两个入口：
 * 声明式的 {@code LlmAgentFactory} 最后也在调 {@link Agent#builder(String)}。
 * 本类把那条链的<b>每一个可设置项</b>都点一遍，并断言"设了的就是设了的"。</p>
 *
 * <p>三条容易踩的口径单独拎出来测：</p>
 * <ul>
 *   <li>{@code AgentSpec.Builder.maxSteps(0)} <b>直接报错</b>（v1.1.0 的行为变更）——
 *       以前 {@code if (maxSteps > 0)} 的写法会让"零预算"被悄悄换成 24；</li>
 *   <li>{@code hardMaxSteps(n)} 的夹取发生在 {@code build()} 里，两条装配路径共用同一处逻辑；</li>
 *   <li>程序化装配<b>默认不夹取</b>（{@code hardMaxSteps() == 0}）：代码里的显式意图不该被
 *       一个隐含的默认上限改掉，需要防呆的装配方自己调它（Spring 侧由
 *       {@code LlmAgentFactory} 从 {@code llm.agent.hard-max-steps} 传入）。</li>
 * </ul>
 */
@DisplayName("示例 09 · 程序化装配（Agent.builder / AgentSpec / toBuilder）")
class ProgrammaticAssemblyTest {

    private final RecordingModel model = new RecordingModel(ScriptedLlmModel.builder("prog-model")
            .rule("你好", "你好，我是程序化装配出来的 Agent。")
            .fallback("（脚本模型）{input}")
            .build());

    private final ModelRegistry models = new ModelRegistry();
    private final LoopRegistry loops = com.benxin.llm.core.Benxin.loops();

    private ModelRegistry registryWithModel() {
        models.register(model);
        models.setDefault("prog-model");
        return models;
    }

    // ==================================================================
    // 三个装配入口
    // ==================================================================

    @Test
    @DisplayName("Agent.builder()：什么都不给也能装配，名字回落到默认值 agent")
    void builderWithoutName() {
        Agent agent = Agent.builder()
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .build();

        assertThat(agent.name()).isEqualTo("agent");
        assertThat(agent.spec().maxSteps()).isEqualTo(24);
        assertThat(agent.spec().memory()).isTrue();
    }

    @Test
    @DisplayName("Agent.builder(name)：只给名字，协作组件全都用默认实现")
    void builderWithName() {
        Agent agent = Agent.builder("only-name")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .build();

        assertThat(agent.name()).isEqualTo("only-name");
        assertThat(agent.tools().names()).isEmpty();
        assertThat(agent.spec().systemPrompt()).isNull();
    }

    @Test
    @DisplayName("Agent.builder(name, model, loop)：最省事的一种，一行拿到可跑的 Agent")
    void builderWithModelAndLoop() {
        Agent agent = Agent.builder("three-args", model, loops.get("dsh-minimal")).build();

        assertThat(agent.name()).isEqualTo("three-args");
        assertThat(agent.model()).isSameAs(model);
        assertThat(agent.loop().name()).isEqualTo("dsh-minimal");
        assertThat(agent.call("你好").text()).contains("程序化装配");
    }

    // ==================================================================
    // AgentBuilder 的常用方法：每一项都设一遍，再逐项断言
    // ==================================================================

    @Test
    @DisplayName("AgentBuilder 常用方法全谱：model/loop/tool/contextManager/memoryStore/prompt/maxSteps/... 都生效")
    void allCommonBuilderMethods() {
        CommonTools tools = new CommonTools();
        SlidingWindowCompactor compactor = new SlidingWindowCompactor();
        DefaultContextManager contextManager = new DefaultContextManager(compactor, TokenEstimator.DEFAULT,
                0.8, 8, 4096);
        MemoryStore memory = new InMemoryMemoryStore();
        SystemPromptProvider prompts = new TemplateSystemPromptProvider();
        ToolSandbox sandbox = ToolSandbox.permissive();
        ApprovalHandler approval = ApprovalHandler.autoApprove();
        AtomicInteger listenerEvents = new AtomicInteger();

        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public int order() {
                return 42;
            }

            @Override
            public void beforeAgent(com.benxin.llm.core.agent.AgentInvocation invocation) {
                listenerEvents.incrementAndGet();
            }
        };
        AgentListener listener = AgentListener.noop();

        ToolCallback manualTool = new ToolCallback() {
            @Override
            public com.benxin.llm.core.chat.ToolSpec spec() {
                return new com.benxin.llm.core.chat.ToolSpec("manual_probe", "手工工具",
                        com.benxin.llm.core.util.Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext ctx) {
                return ToolResult.ok("probe");
            }
        };

        AgentBuilder builder = Agent.builder()
                .name("everything")
                .model(model)                                   // 直接给实例
                .modelRegistry(registryWithModel())             // 也可只给名字 + 注册表
                .loop(loops.get("react"))                       // 直接给实例
                .loopRegistry(loops)
                .systemPrompt("你是全参数演示 Agent。")
                .maxSteps(6)
                .hardMaxSteps(4)                                // 会被夹到 4
                .stream(false)
                .memory(true)
                .temperature(0.7)
                .maxTokens(128)
                .metadata("scene", "programmatic")
                .contextManager(contextManager)
                .memoryStore(memory)
                .systemPromptProvider(prompts)
                .sandbox(sandbox)
                .approvalHandler(approval)
                .approvalPolicy(ApprovalPolicy.ON_REQUEST)
                .toolExecutor(Executors.newSingleThreadExecutor())
                .interceptor(interceptor)
                .listener(listener)
                .tool(manualTool)
                .tools(tools);                                  // 扫 @LlmTool 方法

        Agent agent = builder.build();

        assertThat(agent.name()).isEqualTo("everything");
        assertThat(agent.model()).isSameAs(model);
        assertThat(agent.loop().name()).isEqualTo("react");
        assertThat(agent.spec().systemPrompt()).isEqualTo("你是全参数演示 Agent。");
        assertThat(agent.spec().maxSteps()).isEqualTo(4);        // 6 被 hardMaxSteps(4) 夹住
        assertThat(agent.spec().stream()).isFalse();
        assertThat(agent.spec().memory()).isTrue();
        assertThat(agent.spec().temperature()).isEqualTo(0.7);
        assertThat(agent.spec().maxTokens()).isEqualTo(128);
        assertThat(agent.spec().metadata()).containsEntry("scene", "programmatic");
        assertThat(agent.tools().names())
                .contains("manual_probe", "get_weather", "get_current_time", "calculate", "describe_context");

        // 装配结果的直接视图：协作组件确实是刚才那几个实例。
        // 注意这里没有走 Agent.Runtime —— DefaultAgent 自己就把这些访问器暴露出来了。
        com.benxin.llm.core.agent.DefaultAgent defaultAgent = (com.benxin.llm.core.agent.DefaultAgent) agent;
        assertThat(defaultAgent.contextManager()).isSameAs(contextManager);
        assertThat(defaultAgent.memoryStore()).isSameAs(memory);
        assertThat(defaultAgent.systemPromptProvider()).isSameAs(prompts);
        assertThat(defaultAgent.sandbox()).isSameAs(sandbox);
        assertThat(defaultAgent.approvalHandler()).isSameAs(approval);
        assertThat(defaultAgent.approvalPolicy()).isEqualTo(ApprovalPolicy.ON_REQUEST);
        assertThat(defaultAgent.activeInterceptors()).hasSize(1);
        assertThat(defaultAgent.activeInterceptors().get(0).order()).isEqualTo(42);
        assertThat(defaultAgent.toolExecutor()).isNotNull();

        // 真跑一次：拦截器被触发，工具表也在（虽然脚本模型这次不会去调它）。
        AgentResult result = agent.call("你好");
        assertThat(result.text()).isNotBlank();
        assertThat(listenerEvents.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tool / tools / toolRegistry 三种给工具的方式：结果都汇总进同一张工具表")
    void threeWaysToProvideTools() {
        ToolRegistry registry = ToolRegistry.of(com.benxin.llm.core.tool.ToolScanner.scan(new CommonTools()));

        Agent byRegistry = Agent.builder("by-registry").model(model).loop(loops.get("dsh-minimal"))
                .toolRegistry(registry).build();
        Agent byBean = Agent.builder("by-bean").model(model).loop(loops.get("dsh-minimal"))
                .tools(new CommonTools()).build();
        Agent byCallback = Agent.builder("by-callback").model(model).loop(loops.get("dsh-minimal"))
                .tool(new ToolCallback() {
                    @Override
                    public com.benxin.llm.core.chat.ToolSpec spec() {
                        return new com.benxin.llm.core.chat.ToolSpec("only_one", "唯一的工具",
                                com.benxin.llm.core.util.Json.parse("{\"type\":\"object\"}"));
                    }

                    @Override
                    public ToolResult call(Map<String, Object> arguments, ToolContext ctx) {
                        return ToolResult.ok("ok");
                    }
                }).build();

        assertThat(byRegistry.tools().names()).hasSize(4);
        assertThat(byBean.tools().names()).hasSize(4);
        assertThat(byCallback.tools().names()).containsExactly("only_one");
    }

    @Test
    @DisplayName("subAgent / subAgents：写进 spec 的同时，也能带一个真的 Agent 实例")
    void subAgents() {
        Agent child = Agent.builder("child", model, loops.get("dsh-minimal")).build();

        Agent parent = Agent.builder()
                .name("parent")
                .model(model)
                .loop(loops.get("react"))
                .subAgent("child", child)              // 名字 + 实例
                .subAgent("只写名字的兄弟")              // 只写名字（由 AgentRegistry 惰性解析）
                .subAgents(List.of("第三个"))            // 批量
                .build();

        assertThat(parent.spec().subAgents())
                .containsExactly("child", "只写名字的兄弟", "第三个");
    }

    @Test
    @DisplayName("interceptor / interceptors 与 listener / listeners：多条并存，拦截器按 order 排序")
    void interceptorsAndListeners() {
        AgentInterceptor later = new AgentInterceptor() {
            @Override
            public int order() {
                return 200;
            }
        };
        AgentInterceptor earlier = new AgentInterceptor() {
            @Override
            public int order() {
                return 1;
            }
        };

        Agent agent = Agent.builder("ordered")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .interceptor(later)
                .interceptors(List.of(earlier))
                .listener(AgentListener.noop())
                .listeners(List.of(AgentListener.noop()))
                .build();

        // build() 里会按 order 升序排一遍：后加进来的 earlier(1) 排在前面。
        List<AgentInterceptor> chain =
                ((com.benxin.llm.core.agent.DefaultAgent) agent).activeInterceptors();
        assertThat(chain).extracting(AgentInterceptor::order).containsExactly(1, 200);
    }

    // ==================================================================
    // AgentSpec.Builder
    // ==================================================================

    @Test
    @DisplayName("AgentSpec.builder()...build()：所有字段都可设，name 为空时忽略（保持默认）")
    void agentSpecBuilder() {
        AgentSpec spec = AgentSpec.builder()
                .name("spec-name")
                .model("some-model")
                .loop("react")
                .systemPrompt("提示词")
                .maxSteps(7)
                .stream(true)
                .memory(false)
                .temperature(0.9)
                .maxTokens(999)
                .subAgent("child")
                .toolNames(List.of("get_weather", "calculate"))
                .metadata("k", "v")
                .build();

        assertThat(spec.name()).isEqualTo("spec-name");
        assertThat(spec.model()).isEqualTo("some-model");
        assertThat(spec.loop()).isEqualTo("react");
        assertThat(spec.systemPrompt()).isEqualTo("提示词");
        assertThat(spec.maxSteps()).isEqualTo(7);
        assertThat(spec.stream()).isTrue();
        assertThat(spec.memory()).isFalse();
        assertThat(spec.temperature()).isEqualTo(0.9);
        assertThat(spec.maxTokens()).isEqualTo(999);
        assertThat(spec.subAgents()).containsExactly("child");
        assertThat(spec.toolNames()).containsExactly("get_weather", "calculate");
        assertThat(spec.metadata()).containsEntry("k", "v");

        // name 的空白值被忽略 —— 不会把默认的 "agent" 覆盖成空串。
        AgentSpec blank = AgentSpec.builder().name("   ").build();
        assertThat(blank.name()).isEqualTo("agent");

        // 未设 maxSteps 时是 24（AgentSpec.Builder 的默认值，与 AgentBuilder 一致）。
        assertThat(AgentSpec.builder().build().maxSteps()).isEqualTo(24);
        // 未设 memory 时是 true。
        assertThat(AgentSpec.builder().build().memory()).isTrue();
        // toolNames 是"替换"语义而不是"追加"。
        assertThat(AgentSpec.builder().toolNames(List.of("a", "b")).toolNames(List.of("c")).build().toolNames())
                .containsExactly("c");
    }

    @Test
    @DisplayName("AgentSpec.Builder.maxSteps(0) 直接报错 —— v1.1.0 的行为变更，别再依赖静默回落")
    void maxStepsZeroFailsFast() {
        assertThatThrownBy(() -> AgentSpec.builder().maxSteps(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSteps 必须为正数")
                .hasMessageContaining("0");

        assertThatThrownBy(() -> AgentSpec.builder().maxSteps(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-5");

        // 1 是允许的（"零步预算"要表达的是别的意思，请显式决定它的语义）。
        assertThat(AgentSpec.builder().maxSteps(1).build().maxSteps()).isEqualTo(1);
    }

    @Test
    @DisplayName("Agent.builder().spec(spec)：从一份现成的 spec 开始，再叠加自己的设置")
    void builderFromSpec() {
        AgentSpec spec = AgentSpec.builder()
                .name("from-spec")
                .model("prog-model")
                .loop("dsh-minimal")
                .maxSteps(5)
                .metadata("origin", "spec")
                .build();

        Agent agent = Agent.builder()
                .spec(spec)
                .modelRegistry(registryWithModel())
                .loopRegistry(loops)
                .build();

        assertThat(agent.name()).isEqualTo("from-spec");
        assertThat(agent.spec().maxSteps()).isEqualTo(5);
        assertThat(agent.spec().metadata()).containsEntry("origin", "spec");
        assertThat(agent.model()).isSameAs(model);

        // 注意：builder.spec(spec) 是<b>拷贝</b>（内部走 spec.toBuilder()），
        // 所以之后改 builder 不会回头改动那份 spec。
        assertThat(spec.maxSteps()).isEqualTo(5);
    }

    // ==================================================================
    // hardMaxSteps 的夹取
    // ==================================================================

    @Test
    @DisplayName("hardMaxSteps(n)：无论 maxSteps 写多少，最终都不超过 n")
    void hardMaxStepsClamps() {
        Agent clamped = Agent.builder("clamped")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .maxSteps(100)
                .hardMaxSteps(3)
                .build();

        assertThat(clamped.spec().maxSteps()).isEqualTo(3);
    }

    @Test
    @DisplayName("hardMaxSteps 默认不夹取（0）：程序化装配里的 maxSteps 是代码里的显式意图")
    void hardMaxStepsIsOptIn() {
        AgentBuilder builder = Agent.builder("not-clamped").maxSteps(100);
        assertThat(builder.hardMaxSteps()).isZero();
        assertThat(builder.model(model).loop(loops.get("dsh-minimal")).build().spec().maxSteps())
                .isEqualTo(100);

        // 0 / 负数都被归一成"不夹取"。
        assertThat(Agent.builder().hardMaxSteps(-9).hardMaxSteps()).isZero();
        assertThat(Agent.builder().hardMaxSteps(0).hardMaxSteps()).isZero();
    }

    @Test
    @DisplayName("hardMaxSteps 只在 build() 那一刻夹取，且不会把已设的 spec 改坏")
    void hardMaxStepsOnlyClampsAtBuild() {
        AgentSpec spec = AgentSpec.builder().name("s").maxSteps(50).build();
        Agent agent = Agent.builder().spec(spec).model(model).loop(loops.get("dsh-minimal"))
                .hardMaxSteps(10).build();

        assertThat(agent.spec().maxSteps()).isEqualTo(10);
        // 原来的 spec 不受影响 —— 夹取作用在 builder 内部那份拷贝上。
        assertThat(spec.maxSteps()).isEqualTo(50);
    }

    // ==================================================================
    // toBuilder 的隔离性
    // ==================================================================

    @Test
    @DisplayName("toBuilder() 是隔离的：spec 是拷贝（改副本不影响原对象）")
    void toBuilderIsIsolated() {
        Agent original = Agent.builder("original")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .systemPrompt("原提示词")
                .maxSteps(5)
                .temperature(0.1)
                .tools(new CommonTools())
                .metadata("k", "v1")
                .build();

        Agent copy = original.toBuilder()
                .name("copy")
                .systemPrompt("副本提示词")
                .maxSteps(1)
                .temperature(0.9)
                .metadata("k", "v2")
                .build();

        // 副本变了。
        assertThat(copy.name()).isEqualTo("copy");
        assertThat(copy.spec().systemPrompt()).isEqualTo("副本提示词");
        assertThat(copy.spec().maxSteps()).isEqualTo(1);
        assertThat(copy.spec().temperature()).isEqualTo(0.9);
        assertThat(copy.spec().metadata()).containsEntry("k", "v2");

        // 原对象一点没变 —— 名字、spec、元数据都还是老的。
        // 隔离性来自 AgentSpec：AgentBuilder.spec(spec) 内部走 spec.toBuilder()，
        // 而 toBuilder() 对 subAgents / toolNames / metadata 都是<b>拷贝</b>。
        assertThat(original.name()).isEqualTo("original");
        assertThat(original.spec().systemPrompt()).isEqualTo("原提示词");
        assertThat(original.spec().maxSteps()).isEqualTo(5);
        assertThat(original.spec().temperature()).isEqualTo(0.1);
        assertThat(original.spec().metadata()).containsEntry("k", "v1");

        // 两个是不同的实例，但共享同一个模型实例（协作组件是引用传递，不是深拷贝）。
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.model()).isSameAs(original.model());

        // ⚠️ 但<b>工具表不是拷贝</b>：toBuilder() 把原来那张 ToolRegistry 原样传下去
        // （DefaultAgent.toBuilder() 里的 .toolRegistry(tools)），而 tool(...) 是往同一个注册表里
        // 追加的 —— 所以在副本上加工具，原对象也会看到。
        // 这是"深拷贝"与"共享引用"混在同一个 toBuilder 里的真实边界，
        // 想要真正隔离的工具表得自己给一张（toolRegistry(ToolRegistry.of(...)) 整表替换）。
        Agent withExtraTool = original.toBuilder().tool(probeTool("copy_only")).build();
        assertThat(withExtraTool.tools().names()).contains("copy_only");
        assertThat(original.tools().names())
                .as("工具表是共享引用，不是拷贝 —— 这条口径与 spec 的隔离性刚好相反")
                .contains("copy_only");
        assertThat(original.tools().names()).doesNotContain("isolated_only");

        // 显式隔离的写法：toolRegistry(...) 是<b>整表替换</b>，不是追加，
        // 所以副本能拿到一张全新的表，而原对象的表原地不动。
        Agent isolatedCopy = original.toBuilder()
                .toolRegistry(ToolRegistry.of(List.of(probeTool("isolated_only"))))
                .build();
        assertThat(isolatedCopy.tools().names()).containsExactly("isolated_only");
        assertThat(original.tools().names()).doesNotContain("isolated_only");
    }

    /** 一个最小可用的探针工具，名字由调用方给；返回的是"只含这一个工具"的注册表。 */
    private static ToolCallback probeTool(String name) {
        return new ToolCallback() {
            @Override
            public com.benxin.llm.core.chat.ToolSpec spec() {
                return new com.benxin.llm.core.chat.ToolSpec(name, "探针工具",
                        com.benxin.llm.core.util.Json.parse("{\"type\":\"object\"}"));
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext ctx) {
                return ToolResult.ok("ok");
            }
        };
    }

    // ==================================================================
    // Agent.ask(...)：绕过 Loop 的纯模型调用
    // ==================================================================

    @Test
    @DisplayName("Agent.ask(...) 是纯模型调用：不经过 Loop、不碰记忆、不发工具")
    void askBypassesLoop() {
        Agent agent = Agent.builder("asker")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .tools(new CommonTools())
                .build();

        var response = agent.ask("你好");

        assertThat(response.text()).contains("程序化装配");
        assertThat(model.count()).isEqualTo(1);
        // 一次纯模型调用，没有 Loop 介入 ⇒ 历史里什么都没有。
        assertThat(agent.history("asker-default")).isEmpty();
        // 请求里也不带工具声明（走的是 Agent.ask 的默认实现：只有一条 user 消息）。
        ChatRequest request = model.lastRequest();
        assertThat(request.tools()).isEmpty();
        assertThat(request.messages()).hasSize(1);
        // 注意口径：ChatRequest.systemText() 是把所有 system 消息<b>拼起来</b>，
        // 没有 system 消息时返回的是空串而不是 null（ChatRequest.java:106-117）。
        // 想判"有没有系统提示词"请用 isBlank()，别用 isNull()。
        assertThat(request.systemText()).isEmpty();
    }

    @Test
    @DisplayName("对比：agent.call(...) 会经过 Loop，并且按 memory 设置写回历史")
    void callGoesThroughLoopAndMemory() {
        Agent agent = Agent.builder("caller")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .memory(true)
                .build();

        AgentResult result = agent.call("你好");

        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.loopName()).isEqualTo("dsh-minimal");
        // 开了记忆 ⇒ 一问一答都写回去了。
        assertThat(agent.history("caller-default")).hasSize(2);

        agent.clearHistory("caller-default");
        assertThat(agent.history("caller-default")).isEmpty();
    }

    @Test
    @DisplayName("AgentSession 视图：id / history / attributes / chat 一步到位")
    void agentSessionView() {
        Agent agent = Agent.builder("session-agent")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .build();

        var session = agent.session("my-session");
        var result = session.chat("你好");

        assertThat(session.id()).isEqualTo("my-session");
        assertThat(result.text()).isNotBlank();
        assertThat(session.history()).hasSize(2);
        assertThat(session.attributes()).isEmpty();

        // 不传 id 的 session() 会用一个随机 id —— 两个 session 互不干扰。
        assertThat(agent.session().id()).isNotBlank();
        assertThat(agent.session().id()).isNotEqualTo(agent.session().id());
    }

    @Test
    @DisplayName("一个反直觉但很实用的点：toolExecutor 换掉之后，工具真的在另一个线程里跑")
    void toolExecutorIsUsed() {
        Agent agent = Agent.builder("executor-agent")
                .model(model)
                .loop(loops.get("dsh-minimal"))
                .toolExecutor(Executors.newSingleThreadExecutor(r -> new Thread(r, "exp09-tool-thread")))
                .tool(new ToolCallback() {
                    @Override
                    public com.benxin.llm.core.chat.ToolSpec spec() {
                        return new com.benxin.llm.core.chat.ToolSpec("whoami", "看看自己在哪个线程",
                                com.benxin.llm.core.util.Json.parse(
                                        "{\"type\":\"object\",\"properties\":{}}"));
                    }

                    @Override
                    public ToolResult call(Map<String, Object> arguments, ToolContext ctx) {
                        return ToolResult.ok(Thread.currentThread().getName());
                    }
                })
                .build();

        // 用 #tool:whoami 显式点名工具（脚本模型支持这个语法）。
        AgentResult result = agent.call("#tool:whoami {}");

        // ⚠️ 这里<b>不能</b>断言 hasSize(1)：离线脚本模型在没有新的工具结果时会一直重新发同一个
        // 工具调用（ScriptedLlmModel 的规则顺序，"已有工具结果 → 汇总作答"只有在
        // hasToolResult 为真时才命中，而每轮结束后它又会按第 ⑤ 条重新点名工具），
        // 于是一路跑到 maxSteps（=24）才收尾，toolCalls 里就是 24 条同样的记录。
        //
        // 真正要证明的是"工具有没有跑在我指定的那个线程上"，所以断言的是<b>每一条</b>
        // 记录都来自 exp09-tool-thread，而不是"只调了一次"。
        assertThat(result.toolCalls()).isNotEmpty();
        assertThat(result.toolCalls()).allSatisfy(
                call -> assertThat(call.result()).isEqualTo("exp09-tool-thread"));
        assertThat(result.toolCalls().get(0).name()).isEqualTo("whoami");
        // 步数被 maxSteps 夹住，不会无限循环 —— 这一条同时也解释了上面为什么是 24 条。
        assertThat(result.toolCalls().size()).isLessThanOrEqualTo(agent.spec().maxSteps());
    }

    @Test
    @DisplayName("未指定模型时 build() 就报错，而不是等到第一次调用（消息里带「怎么办」）")
    void missingModelFailsAtBuild() {
        // DefaultAgent 的构造器里就解析模型：按名字取不到、退到默认模型也取不到，
        // 于是 build() 那一刻直接抛 —— 不会留下一个"一调用就炸"的 Agent。
        assertThatThrownBy(() -> Agent.builder("no-model").loop(loops.get("dsh-minimal")).build())
                .isInstanceOf(com.benxin.llm.core.model.ModelException.class)
                .hasMessageContaining("尚未注册任何模型")
                .hasMessageContaining("llm.models.*");

        // 注册表里有东西、但名字给错时：报错里带上已注册清单（便于一眼定位拼写错误）。
        assertThatThrownBy(() -> Agent.builder("wrong-name")
                .model("不存在的模型")
                .modelRegistry(registryWithModel())
                .loop(loops.get("dsh-minimal"))
                .build())
                .isInstanceOf(com.benxin.llm.core.model.ModelException.class)
                .hasMessageContaining("不存在的模型")
                .hasMessageContaining("prog-model");
    }

    @Test
    @DisplayName("LlmModel 接口只有四个方法：name / capabilities / chat / stream（自己写一个很容易）")
    void llmModelInterfaceIsSmall() {
        LlmModel custom = new LlmModel() {
            @Override
            public String name() {
                return "hand-written";
            }

            @Override
            public com.benxin.llm.core.model.ModelCapabilities capabilities() {
                return com.benxin.llm.core.model.ModelCapabilities.DEFAULT;
            }

            @Override
            public com.benxin.llm.core.chat.ChatResponse chat(ChatRequest request) {
                return com.benxin.llm.core.chat.ChatResponse.builder()
                        .message(com.benxin.llm.core.message.ChatMessage.assistant("手写模型也能用"))
                        .build();
            }

            @Override
            public void stream(ChatRequest request,
                               com.benxin.llm.core.model.LlmStreamHandler handler) {
                handler.onStart();
                handler.onTextDelta("手写模型也能用");
                handler.onComplete(chat(request));
            }
        };

        Agent agent = Agent.builder("hand-written-agent")
                .model(custom)
                .loop(loops.get("dsh-minimal"))
                .build();

        assertThat(agent.call("随便问点什么").text()).isEqualTo("手写模型也能用");
    }

    /** 只为了在编译期确认这几个类型确实在 core 里可用（import 即断言）。 */
    @Test
    @DisplayName("核心类型清单：AgentLoop / ToolRegistry / MemoryStore / TokenEstimator 都能独立使用")
    void coreTypesAreUsableStandalone() {
        AgentLoop loop = loops.get("dsh-minimal");
        ToolRegistry tools = ToolRegistry.of(com.benxin.llm.core.tool.ToolScanner.scan(new CommonTools()));
        MemoryStore memory = MemoryStore.inMemory();
        TokenEstimator estimator = TokenEstimator.DEFAULT;
        HttpTransport transport = com.benxin.llm.core.Benxin.transport();

        assertThat(loop.name()).isEqualTo("dsh-minimal");
        assertThat(tools.names()).hasSize(4);
        assertThat(memory.load("nothing")).isEmpty();
        assertThat(estimator.estimate("中文abc")).isPositive();
        assertThat(transport).isNotNull();
        // 列表里那个"空注册表"也是可用的：只是没有 Loop 而已。
        assertThat(com.benxin.llm.core.Benxin.emptyLoops().isEmpty()).isTrue();
    }
}
