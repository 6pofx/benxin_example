# exp-09 · 装配与覆盖

> 「一切皆可插拔」在 **Spring 层面**是怎么落地的：14 个切面逐个用自定义 bean 顶掉，
> 三个基础设施 bean 刻意顶不掉，两个开关（`llm.enabled` / `@EnableLlmAgents`）分头管关停与扫描，
> 另外还有两条完全不经过 Spring 的路（程序化装配、裸 core 门面）。

本模块不演示新的 Agent 能力（那是 exp-01 ~ exp-08 的事）。它演示的是
**这些能力是怎么被装进容器、又怎么被你顶掉**的机制。
**默认完全离线、零 token**：模型来自 `exp-common` 的脚本化模型（`ScriptedLlmModel`），
需要 HTTP 证据时用回环地址上的 `StubHttpServer`——真 socket、真协议报文，但不出网。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| 14 个可替换切面 | 逐个用自定义 bean 顶掉，并断言"容器里生效的是我的实现，而且真的被调用到了" |
| 3 个基础设施 bean | `LlmToolCatalog` / `LlmComponentRegistrar` / `LlmBuiltinToolRegistrar` **刻意不可替换**，附原因与断言 |
| `@ConditionalOnMissingBean` | `ApplicationContextRunner` 做的有 / 无对照实验（同一份断言跑两个容器） |
| `llm.enabled=false` | 整体关停：连 `@EnableLlmAgents` 也不再注册代理 bean |
| `@EnableLlmAgents` | 扫描范围的四种情形：不写参数（推断）/ `basePackages` / `basePackageClasses` / **完全不给它** |
| 程序化装配 | `Agent.builder(...)` / `AgentSpec.builder()` / `toBuilder()` / `maxSteps(0)` 报错 / `hardMaxSteps` 夹取 |
| 裸 core | `Benxin.*` 门面 + `ModelFactory` + `ToolScanner`，跑一次真正的 `Agent.call(...)` |
| 配置绑定 | `LlmProperties` **全字段**对照（`application.yml` 把每个字段都写了一遍） |
| 注解注册 | `@LlmModelDef` / `@LlmLoop` 的注册名与默认选举规则 |
| 惰性注册表 | `AgentRegistry.names()` vs `resolved()` vs `find()` |
| 几处"写了不生效" | `AgentSpec.stream()`、`@LlmRetry`、`@LlmLoop(defaultLoop=true)` —— 断言**真实**行为 |

## 装配顺序表（15 行）

抄自插件文档 `benxin/docs/ARCHITECTURE.md` 第四节。**每一行都是一个可替换点**，
机制只有一条：`LlmAutoConfiguration` 里每个 `@Bean` 都带 `@ConditionalOnMissingBean`。

| 顺序 | Bean | 默认实现 | 覆盖效果 |
|---|---|---|---|
| 1 | `HttpTransport` | `JdkHttpTransport` | 换 OkHttp / WebClient / 测试桩 |
| 2 | `LlmToolCatalog` | 扫描 `@LlmTool` 与 `ToolCallback` | 换工具来源（MCP、数据库） |
| 3 | `ModelRegistry` | yml 配置 + `LlmModel` bean | 自定义模型路由 / 降级 |
| 4 | `LoopRegistry` | 6 个内置 + `@LlmLoop` bean | 换或加 Loop |
| 5 | `TokenEstimator` | 中英混排启发式 | 接真实 tokenizer |
| 6 | `ContextCompactor` | `SummarizingCompactor` | 换 `SlidingWindowCompactor` 或自研 |
| 7 | `ContextManager` | `DefaultContextManager` | 换窗口策略 |
| 8 | `MemoryStore` | `InMemoryMemoryStore` | 换 Redis / JDBC |
| 9 | `SystemPromptProvider` | `TemplateSystemPromptProvider` | 接提示词管理平台 |
| 10 | `ApprovalHandler` | `autoApprove` | 接人工审批 |
| 11 | `ToolSandbox` | 内置工具关闭时 `permissive` | 换容器化隔离 |
| 12 | `AgentListener` | `LoggingListener` | 接可观测性 |
| 13 | `List<AgentInterceptor>` | 空 | 加日志 / 脱敏 / 限流 / 计费 |
| 14 | `AgentRegistry` | 空表 | — |
| 15 | `LlmAgentFactory` | 注解 + 配置 + 容器装配 | 换装配逻辑 |

**第 2 行是唯一的例外**：`LlmToolCatalog` 是 `BeanPostProcessor`，
必须用 **static 的 `@Bean` 方法**注册，才能早于普通 bean 实例化 ——
否则会出现"先创建的 bean 扫不到工具"的时序问题。所以它**不可替换**（理由见下）。

### 三个基础设施 bean：为什么刻意顶不掉

| bean | 为什么不可替换 | 想让它不生效怎么做 |
|---|---|---|
| `LlmToolCatalog` | `BeanPostProcessor`，必须 static 定义、早于普通 bean 就绪 | 无法关；它的"开关"是"你有没有给工具来源 bean" |
| `LlmComponentRegistrar` | `BeanDefinitionRegistryPostProcessor`，要在 `llmLoopRegistry` 之前把 `@LlmLoop`/`@LlmGuard` 补进容器 | `llm.enabled=false` |
| `LlmBuiltinToolRegistrar` | 条件是 `@ConditionalOnProperty`（**配置**开关），不是 `@ConditionalOnMissingBean`（类型判断） | `llm.tools.builtin-enabled=false` |

一句话：它们是"把别的切面组装起来"的机器本身。
**正确做法是关开关，而不是拿一个同类型 bean 去顶它们** ——
顶不掉，而且会让人以为顶掉了。

## 每个切面"怎么覆盖"

整个模块里没有一行开关、没有一个 `@Primary`、没有一处 `exclude`。
让位的机制只有一条：**容器里已经有同类型的 bean，自动配置那份就不注册**。

### 1 / `HttpTransport`

```java
@Bean
public StubTransport stubTransport() {          // 返回子类型也可以，条件只看"有没有"
    return new StubTransport();                 // 它仍然真的发 HTTP，只是多记一份流水
}
```

### 3 / `ModelRegistry`（能继承就继承）

```java
@Bean
public DemoModelRegistry llmModelRegistry(LlmProperties properties,
                                          HttpTransport transport,
                                          ObjectProvider<LlmModel> modelBeans) {
    // ⚠️ 换掉它，就接过了它的<b>全部义务</b>：
    // 自动配置那份方法体里做的事（yml → 模型、LlmModel bean 登记、定默认）现在全归你。
    return new DemoModelRegistry().seed(properties, transport, modelBeans);
}
```

### 6 + 7 / 切面之间靠构造器注入连成一张网

```java
@Bean
public RecordingContextManager recordingContextManager(ContextCompactor compactor,   // ← 切面 6
                                                      TokenEstimator estimator) {   // ← 切面 5
    return new RecordingContextManager(compactor, estimator);
}
```

我没换 `ContextManager` 之外的任何东西，但它已经用上了我换的压缩器与估算器。
`ConditionalOnMissingBeanMechanismTest.contextManagerPicksUpTheCustomCompactor` 就是这条的钉子。

### 12 / `AgentListener`（条件按**类型**判）

```java
@Bean
public RecordingAgentListener recordingAgentListener() {   // 任意一个 AgentListener bean
    return new RecordingAgentListener();                   // 内置 LoggingListener 整个让位
}
```

这是 14 个切面里唯一一个**曾经**按 bean 名判条件的（`name = "llmLoggingListener"`），
后果是自定义监听器跟内置的**并存**而不是顶掉。现在改成按类型判，与其余 13 个一致。

### 13 / `List<AgentInterceptor>`（唯一一个必须照抄 bean 名的切面）

```java
@Bean
public List<AgentInterceptor> llmInterceptors(TraceInterceptor trace,               // bean 名固定
                                              OrderFollowerInterceptor follower) {
    List<AgentInterceptor> chain = new ArrayList<>();
    chain.add(new SelectiveInterceptor(trace, /* @LlmGuard */ ...));   // 范围包装
    chain.add(follower);
    chain.sort(Comparator.comparingInt(AgentInterceptor::order));      // order 排序
    return chain;
}
```

⚠️ **别拿它当消费端**。容器里同时还有 `LlmInterceptorChain`，
声明式装配（`LlmAgentFactory`）消费的是后者 —— 因为
`@LlmGuard(agents = {...})` 的范围包装只发生在"返回 List 的那个 bean 方法"里，
直接注入 `List<AgentInterceptor>` 拿到的是容器里**未包装**的原始 bean。

### 15 / `LlmAgentFactory`（最重的一个）

它的方法体与自动配置里那一份**逐参对应** —— 13 个构造器参数就是上面 14 个切面。
**换掉任意一个，都会自动流进这个工厂，再流进每一个声明式 Agent**。
这就是"插拔"能一路兑现到调用方的机制。

### 附：不走 `@ConditionalOnMissingBean` 的一条通道 —— 工具来源

```java
@Bean
public CommonTools commonTools() {
    return new CommonTools();     // LlmToolCatalog（BeanPostProcessor）会扫出它里面的 @LlmTool 方法
}
```

这条走的是**类型**（`LlmToolCatalog` 收集所有带 `@LlmTool` 的 bean），不是条件判断。
`@LlmAgent(tools = {X.class})` / `@LlmAgent(toolNames = {...})` 是它的两个精确取用入口。

## 两个开关

| 开关 | 管什么 | 关掉之后 |
|---|---|---|
| `llm.enabled=false` | `LlmAutoConfiguration` + `LlmAgentAutoConfiguration` 的整体关停 | 本心的组件一个都不在；`@EnableLlmAgents` **仍然"在"**，但一个代理 bean 都不注册 |
| `@EnableLlmAgents` | 扫哪些包里的 `@LlmAgent` 接口 | **必写**。不写它，一个 `@LlmAgent` 接口都不会注册 |

`llm.enabled=false` 时"连代理 bean 也不注册"是刻意的：那些代理 bean 依赖的
`LlmAgentFactory` 来自已被关停的自动配置，照旧注册的话容器里会留下一批**一用就炸**的 bean，
失败被推迟到第一次调用而不是启动期暴露 —— 那是最难排查的一类延迟爆炸。

**替换一个组件附带的义务**：`DefaultOverrideConfiguration` 整类上也必须写同一套
`@ConditionalOnProperty`。总开关一关，本心里那些组件一个都不存在，而这个类里的 `@Bean`
方法全都在等它们注入 —— 少这一行就是 `UnsatisfiedDependencyException`，整个应用起不来。

## 运行

```bash
cd exp-09-spring-override
mvn -o test                        # 104 个离线用例，零 token
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-09-spring-override-1.0.0.jar
```

演示会打印五节：覆盖清单（14 行"覆盖前 → 覆盖后"）、一次真实调用留下的切面证据、
`AgentRegistry` 的惰性语义、`yml → LlmProperties` 的全字段对照、程序化装配。
第 2 节会自己起一个 `StubHttpServer`，所以演示进程里 HTTP 是真的（回环地址，不出网）。

Windows 控制台如果显示乱码，先 `chcp 65001`，或者认准上面那句 `-Dstdout.encoding=UTF-8`。

## 几条"按直觉猜会猜错"的口径

这些是本模块实测出来、并写进测试的结论：

1. **`llm.agent.stream` / `@LlmAgent(stream = true)` 是死配置。**
   它们确实会被写进 `AgentSpec.stream()`（`LlmAgentFactory:120`），但**插件里没有任何一处读它**。
   真正的流式开关是 `ModelCapabilities.streaming()` —— `DefaultLoopContext.callModel`
   现场看的是模型能力，因此 HTTP 模型在声明式路径上**恒为流式**，
   与那个布尔值无关。见 `DeadConfigurationTest`。
2. **`@LlmRetry` 是死注解。** 它定义在 `benxin-core` 里、README 的注解表里也列着，
   但整个插件（含 starter）**没有任何一处引用它** —— 打了不报错，也不生效。
   真正能配重试的地方是 `llm.models.*.max-retries`（`ModelConfig.maxRetries`），
   由 `ModelFactory.create` 自动包一层 `RetryingLlmModel`。见 `DeadConfigurationTest`。
3. **`@LlmLoop(defaultLoop = true)` 几乎永远轮不到。**
   `llmLoopRegistry` 的选举顺序是
   `llm.agent.default-loop`（且必须在表里）→ `@LlmLoop(defaultLoop = true)` → 字面量 `"dsh-minimal"`。
   而 `AgentProperties.defaultLoop` 的字段默认值**就是** `"dsh-minimal"`，它又必然在表里 ——
   于是第一步永远成立，第二步是死代码。只有显式把 `default-loop` 写成一个**没注册**的名字，
   注解才轮得到。见 `AnnotationRegistrationTest`。
4. **`AgentRegistry.resolved()` 只包含"已经被解析过"的 Agent。**
   `names()` 才包含全部（含惰性条目）。想遍历全部 Agent（清历史、做健康检查、出清单），
   请用 `names()` + `find()`；`resolved()` 只适合"看已经热起来的那些"。
5. **`getBeanNamesForType(LlmAgentFactoryBean.class)` 返回的名字带 `&` 前缀。**
   Spring 用 `&name` 表示"工厂 bean 本身"、`name` 表示"它产出的对象"。
   想拿代理对象的名字就直接看 `getBeanNamesForType(SupportAssistant.class)`。
6. **`@EnableLlmAgents` 的扫描范围不只作用于 `@LlmAgent`。**
   同一批包里的 `@LlmLoop` / `@LlmGuard` 也会被一并注册成 bean
   （`LlmAgentRegistrar` 末尾那句 `componentScanner.registerAnnotatedComponents`）。
   而且另一条路 `LlmComponentRegistrar` 还会按 `@SpringBootApplication` 所在包再扫一遍。
7. **`LlmComponentRegistrar` 连 `target/test-classes` 一起扫。**
   它按 `AutoConfigurationPackages`（主应用包）做 `ClassPathScanningCandidateComponentProvider`，
   而那东西不看"这个类来自 src/main 还是 src/test"。所以**测试夹具不能放在主应用包下**：
   `@LlmLoop` / `@LlmGuard` 夹具会溜进主上下文，让"链上有几个拦截器"这类断言凭空多出东西。
   本模块把夹具统一放在 `com.benxin.exp.overridefixture`（主包之外）。
8. **`LLM` 的"默认模型"有三个来源，优先级容易记反。**
   `llm.default-model`（写了就以它为准）> `llm.models.*` 里 `primary: true` 的（按 `order` 取最小）
   > `@LlmModelDef(defaultModel = true)` 的 bean > 第一个 bean。
   而**容器里的 `LlmModel` bean 后登记**，所以它能覆盖同名的 yml 配置项 ——
   本模块的 `stub` 端点就是这么被代码接管的。
9. **`toBuilder()` 的隔离性只对 spec 成立，工具表是共享引用。**
   `AgentSpec.toBuilder()` 对 `subAgents` / `toolNames` / `metadata` 都是拷贝；
   但 `DefaultAgent.toBuilder()` 把原来那张 `ToolRegistry` **原样传下去**，
   而 `tool(...)` 是往同一个注册表里追加 —— 在副本上加工具，原对象也会看到。
   想真正隔离就整表替换：`.toolRegistry(ToolRegistry.of(...))`。见 `ProgrammaticAssemblyTest`。
10. **`maxSteps(0)` 直接报错，不是静默回落。**
    v1.1.0 的行为变更：以前 `if (maxSteps > 0)` 的写法会让"零预算"被悄悄换成 24。
    现在 `AgentSpec.Builder.maxSteps(<= 0)` 抛 `IllegalArgumentException`。
11. **`hardMaxSteps` 默认不夹取（0）。**
    程序化装配里 `maxSteps` 是代码里的显式意图，不该被一个隐含默认上限改掉；
    需要防呆的装配方自己调（Spring 侧由 `LlmAgentFactory` 从 `llm.agent.hard-max-steps` 传入）。
12. **`ChatRequest.systemText()` 在没有 system 消息时返回空串，不是 `null`。**
    想判"有没有系统提示词"请用 `isBlank()`。同理，`load(...)` / `save(...)` 记流水时
    带的是"记了什么"，断言别按下标 0 写死。
13. **`StubHttpServer.Reply.sseFrames` 与 `sseData` 不是同义词。**
    `OpenAiWire.streamingText(...)` 返回的**已经是**一条条完整的 SSE 帧（自带 `data: ` 与空行），
    再套 `sseData` 会把整条流塞进**一个**事件里，解码器解析失败后逐帧跳过，
    表现是"HTTP 200 但回答为空"。
14. **`ModelFactory.create` 在 `maxRetries > 0` 时会套一层 `RetryingLlmModel`。**
    `ModelConfig.Builder` 的 `maxRetries` **默认是 2** —— 所以 `Benxin.model(config, transport)`
    拿到的不是 `HttpLlmModel`。想直接看 `HttpLlmModel` 就把重试关掉，或者先 `unwrap()`。

## 目录

```
src/main/java/com/benxin/exp/override/
├── SpringOverrideApplication.java          @SpringBootApplication + @EnableLlmAgents（刻意不写参数）
├── agent/
│   ├── SupportAssistant.java               什么都不声明：模型/Loop 全回落
│   └── ToolAssistant.java                  显式 model = "stub" + toolNames（bean 覆盖同名配置项的观测点）
├── component/                              14 个自定义切面的实现（每个都能被 @Autowired 到具体类型）
├── override/
│   ├── DefaultOverrideConfiguration.java   本模块的主角：14 个 @Bean 逐个顶掉自动配置
│   └── OverridePoint.java                  14 个切面的清单（枚举）+ 基础设施 bean 的例外说明
└── demo/
    ├── SpringOverrideDemo.java             启动即跑的五节演示（ApplicationRunner）
    └── LlmPropertiesPrinter.java           递归打印 LlmProperties 全字段（走 JavaBeans 约定）

src/main/resources/
├── application.yml                         llm.* 每个字段都写了一遍（用于绑定对照）
└── workflows/exp09-review.yml              classpath 工作流定义 → 注册成 workflow:code-review

src/test/java/com/benxin/exp/override/      11 个测试类，104 个用例
src/test/java/com/benxin/exp/overridefixture/   测试夹具（刻意放在主应用包之外，见口径 7）
```
