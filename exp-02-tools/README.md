# exp-02 · 工具切面

> 一个普通 Java 方法 + 一个注解 = 模型能调用的工具。本模块只做这一件事，把它做穿。

本模块把「本心 benxin 1.1.0」的工具（Tool）切面从上到下演示一遍：
**声明（注解 / 程序化）→ JSON Schema 自动生成 → 装配（工具来源）→ 执行（上下文 / 并行 / 直达 / 异常）→ 结果回灌**。
**默认离线、零 token**：模型来自 `exp-common` 的脚本化模型（`ScriptedLlmModel`）与本模块的
`support/ScriptedToolCallModel`，`mvn spring-boot:run` 立刻能看到 14 组输出，`mvn test` 把每条结论都钉成用例。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| `@LlmTool` 属性 | `name` / `value`（等价，`name` 优先）/ `description` / `returnDirect` / `enabled = false` / 名字留空回落到方法名 / **类级注解**（所有 public 方法都是工具） |
| `@LlmToolParam` 属性 | `value` / `name` / `description` / `required` / `defaultValue`，以及"不写注解用真实参数名"（依赖 `-parameters`） |
| 参数类型全谱 | `int`/`long`/`double`/`boolean` + 包装类型 + `String` + 枚举 + `List<String>`/`Set<Integer>`/`Map<String,Object>` + 数组 + `Optional<String>` + **嵌套 POJO（含 `List<POJO>`、内层枚举）** + `defaultValue` 参数 + 无注解参数（16 个参数一个方法） |
| 返回值全谱 | `String` / DTO（自动 JSON）/ `ObjectNode` / `Map` / `ToolResult.ok(content, meta)` / `ToolResult.error(...)` / `null` / 数字 / 布尔 |
| 工具异常 | 方法里抛 `RuntimeException` → 框架转成**错误类型的 `ToolResult`** 回灌模型（`ToolCallRecord.error() == true`，模型看得到 `error` 标记），链路不断 |
| `ToolContext` 注入 | `agentName()` / `sessionId()` / `step()` / `attributes()`（可读可写）/ `model()` / `sandbox()` / `approvalHandler()` / `canSpawnSubAgent()` / `emit(事件名, 负载)`；`AgentSession` 注入（实测为 `null`，见「插件问题」） |
| `returnDirect = true` | 工具结果**直达**最终答案，模型只被调用 **1** 次（对照：不写它的同类工具是 2 次） |
| `parallelSafe` | 同一轮多个工具：**全真才并行**，一个 `false` 就整轮串行；并行时**完成顺序乱、记账顺序按声明顺序**（`AgentResult.toolCalls()` 稳定） |
| 程序化 `ToolCallback` | `@Bean ToolCallback` 手写 `spec()` + `call()` + `parallelSafe()` + `requiresApproval()`：**"实现接口"与"打注解"等价**，且能表达注解表达不了的语义 |
| `LlmToolCatalog` | `register()` 运行期登记、`global()` / `forClasses()` / `forNames()` / `snapshot()`，以及"创建时解析"这条边界 |
| 三种工具来源 | `@LlmAgent(tools = {类})` 整包拿、`toolNames = {...}` 按名精确拿、**两者都不写 → 继承容器全局工具表** |
| `ToolScanner` / `JsonSchemaGenerator` | `scan` / `scan(对象,类型)` / `scanAll` / `hasTools`；`forMethod` / `forType` / `isInjected` / `resolveName`，并把生成的 JSON Schema **美化打印** |
| `ToolRegistry` | `DefaultToolRegistry` 的 `register` / `find` / `all` / `names` / `specs` / `size` / `isEmpty` / `filtered`、`composite(...)`（只读叠加视图）、`of(...)`、`empty()` |

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-02-tools
mvn -o spring-boot:run            # 启动即打印 14 组演示
mvn -o test                       # 38 个离线用例，零 token
```

Windows 控制台如果显示乱码，先执行 `chcp 65001`；用 jar 跑时加 `-Dstdout.encoding=UTF-8`：

```bash
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-02-tools-1.0.0.jar
```

## 目录

```
src/main/java/com/benxin/exp/tools/
├── ToolsApplication.java                    @SpringBootApplication + @EnableLlmAgents
├── agent/                                   三种工具来源（本模块的主角之一）
│   ├── ByClassAssistant.java                tools = {CommonTools.class}
│   ├── ByNameAssistant.java                 toolNames = {get_current_time, calculate, explode}
│   └── InheritingAssistant.java             什么都不写 → 继承全局工具表
├── config/
│   ├── OfflineModelConfiguration.java       两个离线模型 + 全部工具 bean
│   └── ProgrammaticToolConfiguration.java   @Bean ToolCallback（serial_probe / manual_report）
├── dto/                                     Ticket（DTO 返回）、OrderRequest（嵌套参数）、TreeNode（自引用）、枚举
├── tool/                                    11 个工具类，每个只演示一件事
│   ├── AnnotationSpectrumTools.java         @LlmTool 五个属性
│   ├── ClassLevelToolSet.java               类级 @LlmTool
│   ├── ParamAnnotationTools.java            @LlmToolParam 五个属性 + 缺参/默认值
│   ├── ParameterSpectrumTools.java          参数类型全谱（16 个参数）+ 自引用类型
│   ├── ReturnSpectrumTools.java             返回值全谱（9 个工具）
│   ├── FaultyTools.java                     抛异常
│   ├── ContextAwareTools.java               ToolContext / AgentSession 注入 + emit
│   ├── DirectAnswerTools.java               returnDirect 对照
│   ├── ParallelProbeTools.java              并行探针（注解路径）
│   └── NoteTools.java                       带 path 参数 → 观察沙箱关卡
├── support/                                 测试夹具（都不是"业务代码"）
│   ├── ScriptedToolCallModel.java           能给嵌套 JSON、能一轮发多个工具调用的 LlmModel
│   ├── ToolEventCollector.java              AgentListener：自定义事件 + ToolResult.meta
│   ├── RecordingApprovalHandler.java        记录"审批器被问过没有"
│   ├── ConcurrencyRecorder.java             并发峰值 / 完成顺序 / 执行线程
│   ├── GateOrderProbe.java                  AgentInterceptor：记录 beforeTool 见过谁
│   └── ToolFixtures.java                    反射找方法、美化 JSON、造最简 ToolCallback
└── demo/ToolsDemo.java                      14 组演示（ApplicationRunner，启动即跑）
src/test/java/com/benxin/exp/tools/
├── AbstractToolTest.java                    公共底座（注入夹具 + 每个用例前清历史）
├── ToolDeclarationTest.java                 14 例：注解 / 参数 / Schema / 扫描器 / 注册表
├── ToolExecutionTest.java                   16 例：返回值 / 异常 / 上下文 / 直达 / 并行 / 沙箱关卡
└── ToolSourcesAndCatalogTest.java           8 例：三种来源 / 目录查询 / 动态登记
```

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论：

1. **`required = true` 不强制。** 它只决定 JSON Schema 的 `required` 数组，框架**不做运行期校验**：
   缺参时 `String` 收到 `null`、`int` 收到 `0`、`Optional` 收到 `empty`，都不会报错（测试
   `requiredOnlyAffectsSchema` 打印了这份"实际收到"）。想拦住请在方法体里自己校验并返回
   `ToolResult.error(...)`。
2. **`defaultValue` 只在"参数根本没给"时生效**，而且写了它就自动不算必填（不再进 `required`）。
   另外 schema 里的 `default` 永远是**字符串**：`int` 参数会同时出现 `"type":"integer"` 与
   `"default":"200"`（见「插件问题」第 3 条）。
3. **注解工具永远是 `parallelSafe() == true`。** `@LlmTool` 没有这个属性、`MethodToolCallback`
   也没覆写它。要声明"我不能并行"必须写程序化 `ToolCallback`（本模块的 `serial_probe`）。
4. **并行是"全真才并行"，不是"逐个决定"。** 同一轮里只要有一个工具说 `parallelSafe = false`，
   **整轮**退化成串行（`DefaultLoopContext.executeTools`）。并行时完成顺序会被打乱，但
   `AgentResult.toolCalls()` 永远按**模型声明顺序**记账。
5. **工具跑在别的线程上。** 即使串行执行，工具方法体也在 `benxin-tool-N` 线程池里跑
   （`executeWithTimeout` 包了一层 `CompletableFuture`），不是调用方线程 —— 工具里用
   `ThreadLocal` 传上下文会失效。
6. **`ToolContext` 参数不进 schema，`AgentSession` 参数也不进 —— 但它拿到的是 `null`。**
   别写 `session.id()`，用 `ctx.sessionId()`（见「插件问题」第 1 条）。
7. **`ToolResult.meta()` 是"旁路"信息。** 它不进模型上下文，也**不进** `AgentResult.toolCalls()`
   （`ToolCallRecord` 只有 `toolUseId / name / arguments / result / error / durationMillis`）。
   想要 meta 只能挂 `AgentListener.onToolResult` 或拦截器。
8. **不写 `tools` / `toolNames` ≠ 没有工具**，而是**继承容器的全局工具表**：本模块 33 个工具会
   全部装进去，每次请求都把它们**全部的 schema** 发给模型。这也是 token 烧得最快的一种配置。
9. **类级 `@LlmTool` 不要写 `name`。** 类级模式下所有 public 方法都是工具，如果类级注解还写了
   名字，所有方法会共用一个工具名，进注册表时**后一个覆盖前一个**（`Map.put`），最后只剩一个。
   类级注解只写 `description`；方法级 `@LlmTool(enabled = false)` 仍可单独摘掉某个方法。
10. **`enabled = false` 是"扫描阶段就消失"**，不是"调用时被拒绝"：模型连它的 schema 都收不到。
    同理，`tools = {类}` 之外的工具有多"看不见"——测试里用 `#tool:param_default` 点名一个不在工具表
    里的工具，结果是**一次工具调用都没发生**（模型直接兜底回答）。
11. **`AgentSession` 之外，`Optional<T>` 参数在 schema 里会被"拆掉"**：`Optional<String>` 就是
    `{"type":"string"}`，看不出可空；但**缺参时绑的是 `Optional.empty()` 而不是 `null`**，
    所以 `note.orElse("兜底")` 这种自然写法不会 NPE。
12. **工具参数 JSON 解析失败会静默降级为空参数**（`ToolInvocation.of` → `Json.parseQuietly` 吞异常）。
    模型写错 JSON 时它自己毫不知情，你的工具会带着 `null` 执行（见「插件问题」第 8 条）。
13. **沙箱在拦截器之前跑。** 被沙箱拒绝的调用**不会**触发 `AgentInterceptor.beforeTool`，
    和 `docs/ARCHITECTURE.md`「五道关卡」画的顺序相反（见「插件问题」第 6 条）。
14. **`LlmToolCatalog.register()` 之后新建的 Agent 才"确定"看得到新工具**，但要注意：
    继承全局表的 Agent 持有的就是那个注册表对象，所以它"顺带"也看得见新工具；而它的
    `spec().toolNames()` 却是创建时快照 —— 同一份工具表有两个真相来源（见「插件问题」第 7 条）。

## 与 exp-01 的差别

* exp-01 演示"接口 + 注解 = Agent"；本模块**只**演示工具，Agent 只作为"工具的消费者"出现
  （三个接口专门用来对照工具来源）。
* 本模块的工具调用**不用** exp-common 的 `#tool:名字 {JSON}` 语法驱动，改用
  `support/ScriptedToolCallModel`：原因见下面「非插件问题」第 1 条。

## 插件问题（本心 1.1.0，实测；示例只如实断言，不修改插件）

每条都有可复现的证据：源码行号 + 本模块的演示/测试。

1. **`AgentSession` 参数被声明为"可注入"，实际注入 `null`。**
   `JsonSchemaGenerator.isInjected`（`JsonSchemaGenerator.java:74-77`）把它和 `ToolContext` 并列，
   排除出 schema；`MethodToolCallback.call`（`MethodToolCallback.java:102-105`）却硬编码
   `args[i] = null`（注释写"需要会话时改用 `ToolContext#sessionId()`"）。
   证据：演示第 7 节、测试 `agentSessionParameterIsInjectedAsNull` —— 工具拿到的是 `null`，
   `session.id()` 会直接 NPE。**期望**：既然认它是注入参数，就该给一个可用的会话视图（至少 `id()`）。
2. **`required = true` 运行期不强制。** `JsonSchemaGenerator.java:61-64` 只把它写进 `required` 数组，
   `MethodToolCallback.bind`（`MethodToolCallback.java:126-141`）对缺参一路回落
   （`String`→`null`、原始类型→`0`/`false`、`Optional`→`empty`），没有任何校验。
   证据：测试 `requiredOnlyAffectsSchema`。**影响**：模型漏参时工具"静默带 null 执行"。
3. **`defaultValue` 生成的 schema `default` 永远是字符串。**
   `JsonSchemaGenerator.java:57-59` 用 `propertySchema.put("default", annotation.defaultValue())`，
   注解值是 `String`，于是 `int` 参数同时出现 `"type":"integer"` 与 `"default":"200"`。
   证据：演示第 3 节、测试 `defaultValueAppliesOnlyWhenMissing`。
   **影响**：严格模式（strict schema）的上游/模型会认为类型不匹配。
4. **深度截断先于类型判断，标量也被降级成 `object`。**
   `JsonSchemaGenerator.forType`（`JsonSchemaGenerator.java:106-111`）先判 `depth > MAX_DEPTH` 就返回
   `{"type":"object"}`，之后才走 `String`/`int` 等分支。
   证据：`forType(TreeNode.class, 0)` 里第 5 层的 `name`（声明为 `String`）是 `{"type":"object"}`；
   演示第 12 节、测试 `nestedPojoIsExpandedRecursivelyAndDepthIsCapped`。
   **期望**：截断只应作用在 POJO 展开上，标量应保持 `string`。
5. **`@LlmTool` 无法表达 `parallelSafe` / `requiresApproval`。**
   `LlmTool.java` 只有 `name/value/description/returnDirect/enabled`；
   `MethodToolCallback.java:86-89` 的 `requiresApproval()` 恒为 `false`，也没有覆写 `parallelSafe()`，
   于是落到 `ToolCallback.java:34-36` 的默认 `true`。
   证据：演示第 9 节（注解工具并发峰值 2，必然并行；`serial_probe` 只能手写）。
   **影响**：任何有副作用的注解工具都无法声明"别并行执行"。
6. **沙箱/审批与拦截器的先后与文档相反。** `docs/ARCHITECTURE.md:194-206` 画的是
   `① 拦截器 beforeTool ② ToolSandbox.check ③ ApprovalPolicy`，而
   `DefaultLoopContext.java:425-451` 的实现是**先沙箱、再审批、最后才遍历拦截器**。
   证据：演示第 14 节、测试 `sandboxRunsBeforeInterceptors` —— 被沙箱拒绝的调用，
   `beforeTool` 一次都没被调到。**影响**：想在拦截器里审计"全部工具调用（含被拒的）"会漏。
7. **运行期 `register()` 与 `spec().toolNames()` 快照不一致。**
   `LlmToolCatalog.register`（`LlmToolCatalog.java:142-145`）只写 `global`；
   `DefaultAgent.tools()` 返回创建时传入的那个注册表**实例**（`DefaultAgent.java:91,128-130`），
   而 `spec.toolNames` 在构造时快照（`DefaultAgent.java:84-88`）；每次调模型用的又是
   `tools().specs()`（`DefaultLoopContext.java:196`）。
   证据：演示第 11 节、测试 `existingAgentHoldsLiveRegistryWhileSpecIsASnapshot` ——
   登记后，已存在的继承型 Agent `tools().names()` 含新工具（true），
   `spec().toolNames()` 不含（false）；而 `tools = {类}` 的 Agent 两份都不含。
   **影响**：同一份工具表有两个真相来源，`/llm/agents` 之类按 spec 展示的视图会与实际下发不一致。
8. **工具参数 JSON 非法时静默降级为空参数。**
   `ToolInvocation.of`（`ToolInvocation.java:23-27`）用 `Json.parseQuietly`（`Json.java:60-69` 吞掉异常
   返回 `null`），`Json.toMap(null)` → `Map.of()`。
   证据：演示第 6 节最后一条、测试 `brokenArgumentJsonDegradesToEmptyArguments` ——
   传 `{"reason": }` 时工具以空参数执行，模型收到的是"工具内部炸了：null"，
   **拿不到任何"你的 JSON 语法错了"的信号**，自我纠正无从谈起。
9. **（次要）`ToolResult.meta` 不进审计轨迹。** `ToolCallRecord.of`（`ToolCallRecord.java:21-24`）
   只搬运 `content/error/durationMillis`，meta 只有监听器/拦截器拿得到 ——
   与 "meta 用于耗时、退出码、文件路径等审计信息" 的注释相比，落点少了一半。

## 非插件问题（exp-common 提供物，本模块只绕开、不修改）

1. **`ScriptedLlmModel` 的 `#tool:名字 {JSON}` 会无限重复工具调用。**
   规则①（`ScriptedLlmModel.java:179-183`）只判断"用户输入里点名了工具、且这个工具在工具表里"，
   **没有** `hasToolResult` 判断，于是每一轮都会重新命中同一条规则，一路调到步数预算耗尽
   （默认 8 步），最终 `AgentResult.text()` 是**空串**、`maxStepsReached = true`。
   本模块因此改用 `ScriptedToolCallModel`（自己写的夹具：只在"还没有工具结果"的那一轮发工具调用）。
   建议修法：规则①补一个 `&& !hasToolResult`。
2. **`#tool:` 的参数正则不支持嵌套 JSON。** `FORCED_TOOL` 用的是非贪婪的 `(\{.*?})?`
   （`ScriptedLlmModel.java:50-51`），`{"order":{"id":"A"}}` 会在第一个 `}` 处提前收尾 ——
   "嵌套 POJO 参数"这类演示喂不进去。这也是本模块自备 `ScriptedToolCallModel` 的原因之一。
