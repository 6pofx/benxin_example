# 实测发现的插件问题（benxin 1.1.0 → 1.1.1）

本文件汇总 `benxin_exp` 各子项目在**真实运行**中发现的插件问题。

> **插件源码未做任何修改。** 每条都给了可复现位置与"实际 vs 预期"，
> 便于插件作者复核。示例工程遇到这些问题时只做"如实断言 + 绕开"，不做"修框架"，
> 并在代码注释里标注这是绕开而不是正确用法。

复现环境：Windows 11 / JDK 24（`--release 17`）/ Spring Boot 3.5.5 /
`com.benxin:benxin-spring-boot-starter`（本地仓库二进制，与 `benxin/` 源码一一对应）。

## 版本演进与当前状态

| 版本 | 情况 |
|---|---|
| **1.1.0** | 本工程实测出下面 **31 条**问题，全部只记录、不修插件 |
| **1.1.1** | 插件侧把其中 **30 条全部修复**（第 23 条不是缺陷，只是"测试夹具别放主包下"的提醒）—— 逐条证据见 [§一.5](#一5-已在-111-修复的问题30-条)。本工程把对应的断言从"钉住旧行为"改成"钉住新行为"，于是它们同时成了**回归测试** |
| **1.1.1 引入的新回归** | 修 30 条的过程中带出 **2 条新问题**（32 未知 Agent 的 SSE 由 404 变 500、33 私有重载绕过了公开重载的覆写）—— 见 [§一.6](#一6-111-新引入的回归2-条) |

> **下面「一、结论速览」那张表记录的是 1.1.0 当时的状态**（保留原样作为历史），
> 因此表里的 ⏳ 只表示"1.1.0 时存在"。
> **升级到 1.1.1 后，14 个模块里需要改断言的只有 exp-02 / exp-04 / exp-05 / exp-06 / exp-07 / exp-08 / exp-09 / exp-12**，
> 其余（含 exp-01、exp-03、exp-10、exp-11）一行未动、直接通过 ——
> 换句话说：这 **793 个用例可以当成插件升级时的兼容性回归套件**用。
> 插件作者改完一条，跑一遍就知道有没有连带影响（本轮就抓出了上面那 2 条新回归）。

---

## 一、结论速览

状态：✅ = 1.1.1 已修复；⏳ = 1.1.1 仍存在。

| # | 状态 | 问题 | 严重度 | 类型 | 证据 |
|---|---|---|---|---|---|
| 1 | ⏳ | `@LlmRetry` 注解**没有任何消费点**，打了等于没打 | 中 | 文档承诺了但未实现 | 全仓库 grep（仅定义处命中） |
| 2 | ⏳ | `@LlmAgent(stream = true)` 与 `llm.agent.stream` 是**死配置** | 中 | 写入后无人读取 | `LlmAgentFactory:120` 写 / 全仓库 0 处读 |
| 3 | ⏳ | `@LlmTool` 方法的 `AgentSession` 参数**恒为 `null`**（却被当作"可注入参数"排除出 schema） | 中 | 半成品注入点 | `JsonSchemaGenerator:74` vs `MethodToolCallback:102` |
| 4 | ⏳ | **工具调用五道关卡的实际顺序与架构文档相反**（沙箱最先、拦截器最后） | 中 | 文档与实现不符 | `ARCHITECTURE.md:194-206` vs `DefaultLoopContext:425+` |
| 5 | ⏳ | `@LlmTool` **无法声明** `requiresApproval` / `parallelSafe` | 中 | 注解表达力缺口 | `MethodToolCallback:87` 恒 `false`，未覆写 `parallelSafe()` |
| 6 | ⏳ | `@LlmToolParam(required = true)` **运行期不强制**：缺参静默回落 | 中 | 校验缺口 | `JsonSchemaGenerator:61` / `MethodToolCallback.bind` |
| 7 | ⏳ | `defaultValue` 生成的 schema `default` **恒为字符串** | 低 | 类型不一致 | `JsonSchemaGenerator:57` |
| 8 | ⏳ | schema 深度截断**先于**类型判断，标量被降级成 `object` | 低 | 边界降级过宽 | `JsonSchemaGenerator.forType` 开头 |
| 9 | ⏳ | `AgentRegistry.resolved()` 只含"已解析过"的 Agent，与"已注册"直觉不符 | 低 | API 语义易误解 | `AgentRegistry.resolved()` 实现 |
| 10 | ⏳ | HTTP SSE 端点**未声明 charset**，Spring 客户端读中文必然乱码 | 中 | 内容协商缺陷 | `LlmAgentController.java:134` |
| 11 | ⏳ | 同一个异常 `onError` 被**广播两次**（Loop 与 Agent 各一次） | 中 | 重复事件 | `AbstractAgentLoop.java:46` + `DefaultAgent.java:278` |
| 12 | ⏳ | `SelectiveInterceptor` 没有 `delegate()` 访问器，被包装者不可达 | 低 | 可观测性缺口 | `SelectiveInterceptor.java:23-24` |
| 13 | **✅** | 条件表达式里的 `${x:-兜底}` **静默失效**（整串被当成变量名） | **高** | 语义不一致且无提示 | 1.1.0：`Conditions` 的 `RefValue` 不拆 `:-` |
| 14 | ⏳ | **END 节点的出边**没有任何防线（加载期不报错、运行期静默丢弃） | 中 | 静默死代码 | `WorkflowEngine` END 分支 + `WorkflowDefinition.validate()` |
| 15 | ⏳ | `WorkflowIo` 自动格式识别的报错信息误导（flow 风格 YAML 被当 JSON） | 低 | 报错方向错误 | `WorkflowIo.java:93-96` |
| 16 | **✅** | **`SlidingWindowCompactor` 一旦触发就只保留最后 1 条**（`keepRecent` 失效、可能丢系统摘要） | **高** | 循环写错导致数据丢失 | 1.1.0：`SlidingWindowCompactor` 的循环下界被改写 |
| 17 | **✅** | `ChatMessage.withText` 对**工具消息**不是替换而是"前置+保留原内容" | 中 | 语义与 javadoc 不符 | 1.1.0：`ChatMessage.withText` 只删 `TextPart` |
| 18 | **✅** | `TokenEstimator.DEFAULT` 对纯中文多算 1（`cjk + max(1, other/4)`） | 低 | 估算偏差 | 1.1.0：`return cjk + Math.max(1, other / 4)` |
| 19 | **✅** | `SlidingWindowCompactor` 内部预算带 `max(1024, …)` 下限，与 `ContextManager` 口径不同 | 低 | 两套预算口径 | 1.1.0：`budgetOf` 的 `Math.max(1024, …)` |
| 20 | ⏳ | `@LlmLoop(defaultLoop = true)` **永远不会生效**（被默认配置恒真的第一分支挡住） | 中 | 注解属性形同虚设 | `LlmAutoConfiguration:203-210` + `LlmProperties:295` |
| 21 | ⏳ | `@LlmLoop(order = ...)` 未被使用（"第一个"而非"order 最小"胜出） | 低 | 注解属性形同虚设 | `LlmAutoConfiguration:192-200` |
| 22 | ⏳ | 注册器重复执行时用 `name#1` 静默产生第二个同类型 bean | 低 | 错误信息隐晦 | `LlmAgentRegistrar` 去重逻辑 |
| 23 | ⏳ | `LlmComponentRegistrar` 会连 `target/test-classes` 一起扫（测试夹具会进生产上下文） | 低 | 扫描范围未区分来源 | exp-09 的实测复现 |
| 24 | ⏳ | `AgentAttributes` 的 6 个常量里 5 个没有被插件自己使用（`SESSION_ID`/`AGENT_NAME` 在 `attributes()` 里查不到） | 低 | 公开常量与真实来源不一致 | `AgentAttributes.java` 全文 |
| 25 | ⏳ | 流式路径收到"200 + 非 SSE 整包响应"时**静默返回空回答** | 中 | 缺失 Content-Type 判定 | `JdkHttpTransport.java:50-70` + `HttpLlmModel.stream` |
| 26 | ⏳ | `beforeTool` 短路会 **`break` 掉整条拦截器链**（排在后面的拦截器看不到这次调用） | 中 | 顺序即功能，且无提示 | `DefaultLoopContext:441-451` |
| 27 | ⏳ | 手写 `ToolCallback` 用 `@LlmAgent(tools = 外层类.class)` **匹配不到**（按实际实例类精确匹配 + 只 WARN 跳过） | 中 | 静默失效 | `LlmToolCatalog.forClasses` |
| 28 | ⏳ | `ToolSandbox` / `ApprovalHandler` 按**裸类型**注入，多实现时直接启动失败 | 中 | 缺 `@Qualifier` 支持 | `LlmAgentFactory` 构造参数 |
| 29 | 审批被拒时的理由是**硬编码**的，`ApprovalHandler` 没有回传理由的出口 | 低 | 可读性缺口 | `DefaultLoopContext` 审批分支 |
| 30 | **✅** | `agent.toBuilder().model("名字")` / `.loop("名字")` **静默无效** —— 实例优先，名字被忽略 | **中高** | 静默用错模型/循环 | 1.1.0：`DefaultAgent` 构造里"实例优先" + `AgentBuilder` 两个重载互不清空 |
| 31 | **✅** | `agent.toBuilder()` **不携带监听器** —— 派生副本的运行不进任何统计/审计 | 中 | 可观测性静默丢失 | 1.1.0：`DefaultAgent.toBuilder()` 缺 `.listener(...)` |

---

## 一.5、已在 1.1.1 修复的问题（30 条）

插件 1.1.1 改动的文件与对应条目：

```
benxin-core/.../workflow/Conditions.java              ← 13  条件里的 ${x:-兜底}
benxin-core/.../workflow/WorkflowIo.java              ← 15  flow YAML 的报错
benxin-core/.../workflow/WorkflowDefinition.java      ← 14  END 出边 WARN
benxin-core/.../context/SlidingWindowCompactor.java   ← 16、19  压缩丢数据 / 预算口径
benxin-core/.../context/SummarizingCompactor.java     ← 16（摘要被丢的连带项）
benxin-core/.../context/ContextCompactor.java         ← 19  统一 inputBudget
benxin-core/.../context/DefaultContextManager.java    ← 19
benxin-core/.../message/ChatMessage.java              ← 17  withText 对工具消息
benxin-core/.../util/TokenEstimator.java              ← 18  纯中文多算 1
benxin-core/.../agent/DefaultAgent.java               ← 24、30、31  运行属性 / 名字重载 / 监听器继承
benxin-core/.../agent/AgentBuilder.java               ← 30  model/loop 两个重载"后来者胜"
benxin-core/.../tool/JsonSchemaGenerator.java         ← 3、5、6、7、8  schema 与注入
benxin-core/.../tool/MethodToolCallback.java          ← 3、5、6     注入 / 审批 / 必填校验
benxin-core/.../tool/RetryingToolCallback.java        ← 1（新文件）
benxin-core/.../annotation/LlmTool.java               ← 5  requiresApproval / parallelSafe
benxin-core/.../loop/AbstractAgentLoop.java           ← 11  onError 只广播一次
benxin-core/.../loop/DefaultLoopContext.java          ← 4、26、29  关卡顺序 / 短路不 break / 审批理由
benxin-core/.../transport/JdkHttpTransport.java       ← 25  非 SSE 整包响应
benxin-core/.../sandbox/ApprovalHandler.java          ← 29  拒绝理由
benxin-spring-boot-starter/.../LlmAgentFactory.java   ← 1、28  @LlmRetry 消费 / ObjectProvider
benxin-spring-boot-starter/.../LlmAutoConfiguration.java ← 20、21  defaultLoop / order
benxin-spring-boot-starter/.../LlmAgentController.java ← 2、10   stream 开关 / SSE charset
benxin-spring-boot-starter/.../LlmToolCatalog.java    ← 27  外层类匹配
benxin-spring-boot-starter/.../SelectiveInterceptor.java ← 12  delegate()
benxin-spring-boot-starter/.../AgentRegistry.java     ← 9   resolved() javadoc + resolveAll()
benxin-spring-boot-starter/.../LlmAgentRegistrar.java ← 22  重复注册检测
benxin/docs/ARCHITECTURE.md                           ← 4   关卡顺序改成与实现一致
```

| # | 1.1.0 的行为 | 1.1.1 的行为 | 本工程的回归断言 |
|---|---|---|---|
| 1 | `@LlmRetry` 无任何消费点 | `resolveRetryPolicy` + `RetryingLlmModel` / `RetryingToolCallback` | exp-09 `DeadConfigurationTest.llmRetryAnnotationIsConsumed` |
| 2 | `@LlmAgent(stream)` / `llm.agent.stream` 是死配置 | `LlmAgentController.chat` 按 `spec.stream()` 选通道（SSE / JSON） | exp-09 `DeadConfigurationTest.streamFlagIsWrittenAndConsumed` |
| 3 | `AgentSession` 参数恒为 `null` | 注入可用的会话视图（`id()` 即 sessionId） | exp-02 `ToolExecutionTest.agentSessionParameterIsInjected` |
| 4 | 关卡顺序与架构文档相反 | 文档改成与实现一致（沙箱 → 审批 → 拦截器） | exp-02 `ToolExecutionTest.sandboxRunsBeforeInterceptors` |
| 5 | `@LlmTool` 无法声明审批/并行 | 注解新增 `requiresApproval` / `parallelSafe` | exp-02 的注解工具对照演示 |
| 6 | `required = true` 运行期不强制 | 缺参返回 `缺少必填参数 [x]，请补齐后重试` | exp-02 `ToolDeclarationTest.requiredOnlyAffectsSchema` |
| 7 | `defaultValue` 的 schema `default` 恒为字符串 | 按 type 转换（int → 数字） | exp-02 `ToolDeclarationTest.defaultValueAppliesOnlyWhenMissing` |
| 8 | 深度截断先于类型判断，标量降级成 object | 标量不受深度限制 | exp-02 `ToolDeclarationTest.nestedPojoIsExpandedRecursivelyAndDepthIsCapped` |
| 9 | `resolved()` 语义易误解 | 补 javadoc + `resolveAll()` | exp-01 / exp-09 的遍历用法 |
| 10 | SSE 未声明 charset | 声明 `text/event-stream;charset=UTF-8` | exp-08 `LlmWebEndpointTest.contractSseCharsetIsDeclared`（**但见新回归 32**） |
| 11 | `onError` 广播两次 | 只广播一次 | exp-08 `MonitorListenerTest.errorCallbackIsBroadcastOnce` |
| 12 | `SelectiveInterceptor` 无 `delegate()` | 已提供 | exp-08 的拦截器诊断断言 |
| 13 | 条件里的 `${x:-兜底}` 静默失效 | 按字面量解析兜底值 | exp-05 `ConditionsTest.fallbackInsideConditionsWorks`、`WorkflowEngineTest.fallbackInsideBranchConditionsWorks` |
| 14 | END 出边静默丢弃 | 加载期 WARN | exp-05 `WorkflowEngineTest`（END 出边用例） |
| 15 | flow YAML 报"非法 JSON" | 点明"其实是 YAML"并给两条出路 | exp-05 `WorkflowIoDetectionTest` |
| 16 | 压缩触发后只剩最后 1 条 | `keepRecent` 是下限，预算内尽量多留 | exp-06 `CompactorTest` / `ContextManagerTest` |
| 17 | `withText` 对工具消息越压越长 | 替换 `ToolResultPart.content` 并保留 error | exp-06 `MessageApiTest.withTextKeepsOtherParts` |
| 18 | 纯中文多算 1 token | 1 字 1 token | exp-06 `TokenEstimatorTest.defaultEstimatorValues` |
| 19 | 压缩预算两套口径 | 共享 `ContextCompactor.inputBudget(...)` | exp-06 `CompactorTest` / `ContextManagerTest` |
| 20 | `@LlmLoop(defaultLoop = true)` 永不生效 | 字段初值改空，注解分支复活 | exp-04 / exp-09 的 `annotatedDefaultBecomesDefaultLoop` |
| 21 | `@LlmLoop(order)` 从未被读 | 按 order 取最小者 | exp-04 `DefaultLoopAnnotationTest.lowestOrderWins` / `orderIsConsumed` |
| 22 | 重复注册静默产生 `name#1` | 检测到重复 bean 定义并给出可读报错 | exp-09 `EnableLlmAgentsScanTest` |
| 24 | `SESSION_ID` / `AGENT_NAME` 无写入点 | 运行伊始播种 | exp-04 `LoopsTest.sessionAndAgentNameKeysAreWritten` |
| 25 | 非 SSE 整包响应 → 静默空回答 | 抛可读 `ModelException`（带响应体） | exp-07 `ProtocolWireTest.nonSseBodyOnStreamingPathFailsReadably` |
| 26 | `beforeTool` 短路 `break` 整条链 | 短路后仍继续通知其余拦截器 | exp-08 `ObservingInterceptorTest` |
| 27 | 手写 `ToolCallback` 匹配不到外层类 | 沿 `getEnclosingClass()` 向上找 | exp-10 的写工具装配 |
| 28 | 沙箱/审批裸类型注入，多实现即启动失败 | 改为 `ObjectProvider<...>`（可 per-Agent 选择） | exp-09 `SpringOverrideTest`（**但见新回归 33**） |
| 29 | 审批拒绝理由硬编码 | `approval.reason()` 一并回灌模型 | exp-03 / exp-10 的审批用例 |
| 30 | `toBuilder().model/loop("名字")` 静默无效 | 两个重载"后来者胜" | exp-06 `AgentWiringTest.derivedAgentHonoursModelName`、exp-12 `OfflinePathTest.nameOverloadsWorkOnDerivedBuilder` |
| 31 | `toBuilder()` 丢监听器 | `toBuilder()` 带上 `.listener(...)` | exp-12 `OfflinePathTest.derivedAgentKeepsListeners` |

> 第 23 条（`LlmComponentRegistrar` 会扫 `target/test-classes`）**不是缺陷** ——
> 真实项目里 `src/main` 本来就该被扫；它只是提醒"测试夹具别放在主应用包下"。

---

## 一.6、1.1.1 新引入的回归（2 条）

这两条是**修上面那 30 条时带出来的**，由本工程的回归套件在升级验证中抓到。

### 回归 32：未知 Agent 的 SSE 端点由 404 变成 500

**现象.** `GET /llm/agents/{不存在的名字}/stream` 现在返回 **500**；1.1.0 与文档承诺的都是 **404**
（同一个 `@ExceptionHandler(NoSuchAgentException.class)`，`/chat` 上仍然是 404，所以只有 `/stream` 受影响）。

**根因.** 修"SSE 未声明 charset"（第 10 条）时，把 `response.setContentType(SSE_CONTENT_TYPE)`
提到了 `agentRegistry.get(name)` **之前**（`LlmAgentController.stream`）。
于是异常处理器要把 `Map` 写成 JSON 时，响应上已经预设了 SSE 的 Content-Type：

```
HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap]
  with preset Content-Type 'text/event-stream;charset=UTF-8'
```

**复现/断言.** exp-08 `LlmWebEndpointTest.contractUnknownAgentOnStreamReturns404`
（先断言 `/chat` 是 404 作为对照，再断言 `/stream` 实测为 500）。

**建议.** 把 `response.setContentType(...)` 挪到 `agentRegistry.get(name)` 之后（确认要流式了再设），
或在异常处理器里先 `response.reset()` / 改回 `application/json`。

### 回归 33：私有的 4 参 `create` 重载绕过了子类覆写的公开 3 参 `create`

**现象.** 子类覆写 `LlmAgentFactory.create(AgentSpec, ToolRegistry, List<AgentInterceptor>)`
再也收不到声明式装配的调用 —— 它现在是个"没人调用的公开方法"。

**根因.** 为支持 `@LlmRetry`（第 1 条），`createFromInterface` 改成调用新增的**私有**
`create(spec, tools, interceptors, retry)`；公开的三个 `create` 重载都委托给它，
但 `createFromInterface` 不再经过它们。于是覆写公开重载的子类**被静默绕过**（不报错）。

**复现/断言.** exp-09 `DemoLlmAgentFactory` 原来覆写 3 参 `create` 来记录"装配了哪些 Agent"，
升级后 `builtAgents()` 一直是空 → `SpringOverrideTest.llmAgentFactoryFacetIsOverridden` 变红。
示例已改为覆写同样是 public 的 `createFromInterface`（位置更靠前，不受内部重载拆分影响）。

**建议.** 让私有的 4 参重载改为调用公开的 3 参重载（把 retry 作为另一个入口传入），
或在 javadoc 里明确"装配扩展点是 `createFromInterface`"。

---

## 二、逐条详情

> **本节保留的是 1.1.0 当时的原始记录**（含复现步骤与影响分析），作为历史对照 ——
> "曾经错成什么样"对后来者仍有价值。
> **除第 23 条（非缺陷）外，其余 30 条均已在 1.1.1 修复**，逐条对应关系与回归断言见 [§一.5](#一5-已在-111-修复的问题30-条)；
> 修复过程中新引入的 2 条回归见 [§一.6](#一6-111-新引入的回归2-条)。

### 问题 1：`@LlmRetry` 注解完全无效

> ✅ **1.1.1 已修复**：`LlmAgentFactory#resolveRetryPolicy` 读取它（接口 + 方法级取 `maxAttempts` 最大者、
> `includeTools` 取或），模型包 `RetryingLlmModel`、工具包 `RetryingToolCallback`。
> 回归断言：exp-09 `DeadConfigurationTest.llmRetryAnnotationIsConsumed`。

**现象.** README 的「注解速查」表写着 `@LlmRetry`「声明重试策略」，
但给工具方法或 Agent 接口打上它（`maxAttempts` / `backoffMillis` / `multiplier` / `includeTools` 任意组合），
**不产生任何行为变化**：既不重试、也不报错、也不警告。

**证据.**
1. 注解定义：`benxin-core/src/main/java/com/benxin/llm/core/annotation/LlmRetry.java`。
2. 在整个 `benxin/` 仓库（core + starter 的 main 与 test、benxin-examples）搜索 `LlmRetry`，**仅命中定义处那一行**：
   ```
   benxin-core\...\annotation\LlmRetry.java:13: public @interface LlmRetry {
   ```
   没有任何 `getAnnotation(LlmRetry.class)`、没有任何 `instanceof LlmRetry`。
3. `LlmAgentInvocationHandler`（声明式路径入口，本该读"方法/类级重试策略"的地方）用 `javap -p` 也看不到任何重试相关成员。

**实际生效的重试通道**（与 `@LlmRetry` 无关）：`llm.models.<key>.max-retries`（默认 **2**）+ `RetryingLlmModel`。

**影响.** 按文档"在方法上声明重试策略"会得到静默失效 —— 这类"写了不生效"比缺功能更贵。

**建议.** ① 在装配期读取 `@LlmRetry` 并包一层 `RetryingLlmModel`；或 ② 从文档/注解表里移除它。

**示例工程处理.** exp-09 把它放进"惰性配置"对照演示：断言"打了注解也不重试"，并注释说明正确用法是 `max-retries`。

---

### 问题 2：`@LlmAgent(stream = true)` / `llm.agent.stream` 是死配置

**现象.** 两个开关写与不写，运行行为完全一致：只有传了 `LlmStreamHandler` 参数的调用才会收到增量。

**证据.**
1. 唯一写入点：`LlmAgentFactory.java:120` 的 `.stream(annotation.stream() || defaults.isStream())`，
   以及 `AgentBuilder.java:170` 的 `spec.stream(stream)`。
2. core + starter 的 main 源码里，读取 `AgentSpec.stream()` 的位置：**0 处**。
3. `DefaultLoopContext.callModel` 决定流式与否用的是另外两个条件：`model().capabilities().streaming()`
   与"调用方是否传了 `LlmStreamHandler`"。

**影响.** 使用者会以为"给 Agent 打开 stream 就会流式"，尤其在只用 HTTP 端点调用的场景里
（误以为 `llm.agent.stream=true` 能让 `/llm/agents/{n}/chat` 变流式）。

**建议.** 让该开关真正生效，或从 `LlmProperties` / `@LlmAgent` 中移除。

**示例工程处理.** exp-01、exp-08 各有一条断言把"它不改变行为"钉住，并在 README 写明正确做法。

---

### 问题 3：`@LlmTool` 方法的 `AgentSession` 参数恒为 `null`

**现象.** `AgentSession` 与 `ToolContext` 一样被认定为"框架注入参数"（不写进 JSON Schema），
但真正调用时它是 `null`：工具方法里写 `session.id()` 会直接 NPE。

**证据.**
```java
// JsonSchemaGenerator.java:74-77 —— 把 AgentSession 当作注入参数，排除出 schema
public static boolean isInjected(Class<?> type) {
    return ToolContext.class.isAssignableFrom(type)
            || com.benxin.llm.core.agent.AgentSession.class.isAssignableFrom(type);
}

// MethodToolCallback.java:102-105 —— 却硬编码成 null
if (AgentSession.class.isAssignableFrom(parameter.getType())) {
    args[i] = null; // 需要会话时改用 ToolContext#sessionId()
}
```
类注释还写着它负责"框架注入参数（`ToolContext` / `AgentSession`）的填充"。

**影响.** 一个"看起来能用、静默为 null"的参数类型 —— 典型的最难排查的一类问题。

**建议.** 要么真正注入一个可用的会话视图（至少 `id()`），要么让 `isInjected` 不再认它（写进 schema 或装配期报错）。

**示例工程处理.** exp-02 显式演示并断言"注入的是 null"，把它钉成一条可回归的口径。

---

### 问题 4：工具调用五道关卡的实际顺序与架构文档相反

**现象.** `docs/ARCHITECTURE.md` 第六节画的顺序是
`① 拦截器 beforeTool → ② ToolSandbox.check → ③ ApprovalPolicy → ④ 执行 → ⑤ 截断/afterTool/监听器`；
而实际实现是 **先沙箱、再审批、最后才走拦截器**。

**证据.** `DefaultLoopContext.executeOne` 的实际顺序：
```java
ToolSandbox.Decision decision = sandbox().check(call);            // ① 沙箱
if (decision.denied()) { result = decision.toToolResult(); }
else if (needsApproval(call, callback) && !approvalHandler().approve(...)) { ... }   // ② 审批
...
for (AgentInterceptor interceptor : interceptors) {               // ③ 拦截器（最后）
    ToolResult shortCircuit = interceptor.beforeTool(call);
```
exp-02 的测试 `sandboxRunsBeforeInterceptors` 实测：被沙箱拒绝的调用，`beforeTool` **一次都没被调到**。

**影响.** 想在拦截器里审计"**全部**工具调用（含被沙箱拒绝的）"会漏掉被拒的那部分；
按文档写代码的人会得到与预期相反的可观测性。

**建议.** 二者取一即可，但**必须一致**：要么改文档，要么把拦截器提到沙箱之前
（安全上沙箱优先更稳，那就改文档并说明"拦截器只能看到通过沙箱的调用"）。

**示例工程处理.** exp-02 用断言固定真实顺序，并注明与文档不符。

---

### 问题 5：`@LlmTool` 无法声明 `requiresApproval` / `parallelSafe`

**现象.** 用注解写的工具：`requiresApproval()` 恒为 `false`、`parallelSafe()` 恒为 `true`（默认值），
注解上没有任何属性可以改这两件事。

**证据.**
```java
// MethodToolCallback.java:86-89
@Override
public boolean requiresApproval() {
    return false;                     // 恒 false
}
// 未覆写 parallelSafe() → 落到 ToolCallback.java 的 default true
```
`@LlmTool` 的属性只有 `name/value/description/returnDirect/enabled`。

**影响.** 任何**有副作用的注解工具**（写库、发消息、调用外部系统）：
① 无法要求人工审批；② 在模型一轮请求多个工具时会被并行执行。
想表达这两件事只能改成手写 `ToolCallback`（示例里 `serial_probe` / `manual_report` 就是这么做的）。

**建议.** 给 `@LlmTool` 补 `requiresApproval` 与 `parallelSafe` 属性
（README 已经承诺"写类工具默认 `requiresApproval() = true`"，但那条只对**内置**工具成立）。

**示例工程处理.** exp-02 用"注解工具并发峰值 2（必然并行）+ 只能手写才能串行"作为对照证据。

---

### 问题 6：`@LlmToolParam(required = true)` 运行期不强制

**现象.** 模型漏传必填参数时，工具**照样被调用**，参数拿到 `null` / `0` / `false` / `Optional.empty()`，
既不报错也不回灌"缺参数"给模型。

**证据.**
```java
// JsonSchemaGenerator.java:61-64 —— required 只影响 schema 文本
boolean isRequired = annotation == null || annotation.required();
if (isRequired && (annotation == null || annotation.defaultValue().isBlank())) {
    required.add(name);
}
```
运行期绑定（`MethodToolCallback.bind`）对缺参一路回落，没有任何 `required` 校验。

**影响.** "必填"只是给模型看的建议；模型漏参时业务代码要自己防 NPE，
而且模型拿不到"你漏了参数"这个信号，无法自我纠正。

**建议.** 在 `bind` 阶段对 `required = true` 且无默认值的缺失参数返回
`ToolResult.error("缺少必填参数 [x]")`，让模型有机会重试。

**示例工程处理.** exp-02 的测试 `requiredOnlyAffectsSchema` 断言真实行为。

---

### 问题 7：`defaultValue` 生成的 schema `default` 恒为字符串

**现象.** `int` 参数配 `@LlmToolParam(defaultValue = "200")` 时，生成的 schema 同时出现
`"type": "integer"` 与 `"default": "200"`（字符串）。

**证据.** `JsonSchemaGenerator.java:57-59`：
```java
propertySchema.put("default", annotation.defaultValue());   // 注解值是 String
```

**影响.** 严格模式（structured outputs / strict schema）的上游会认为类型不匹配；
模型也可能因此把数字当字符串传。

**建议.** 按 `propertySchema` 的 `type` 做一次转换（`integer` → `Long`、`boolean` → `Boolean`、`array` → 解析 JSON）。

**示例工程处理.** exp-02 的演示第 3 节把生成的 schema 原样打印出来，让这个不一致一眼可见。

---

### 问题 8：schema 深度截断先于类型判断，标量被降级成 `object`

**现象.** 嵌套 POJO 展开到第 5 层时，连 `String` 字段也被写成 `{"type": "object"}`。

**证据.** `JsonSchemaGenerator.forType(...)` 开头：
```java
if (depth > MAX_DEPTH) {           // MAX_DEPTH = 4
    node.put("type", "object");
    return node;                    // 在判 String/int/boolean 之前就返回了
}
```

**影响.** 深层字段的类型信息丢失，模型看到的参数说明是错的（虽然影响面很小）。

**建议.** 把深度检查放到"展开 POJO"那一个分支里，标量维持自己的类型。

**示例工程处理.** exp-02 的测试 `nestedPojoIsExpandedRecursivelyAndDepthIsCapped` 断言真实行为。

---

### 问题 9：`AgentRegistry.resolved()` 的语义与"已注册"直觉不符

**现象.** 只声明了 `@LlmAgent` 接口、从未被调用过的 Agent：
`names()` 能看到（符合文档），`resolved()` **看不到**（返回不含它的 map）。

**证据.**
```java
// AgentRegistry.java
public synchronized Map<String, Agent> resolved() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(agents));   // 只有已解析的
}
```
`agents` 只在 `find(name)` / `get(name)` 解析惰性条目时才写入。
实测踩坑：想遍历全部 Agent 清空会话历史，`resolved().values().forEach(...)` 一个都遍历不到；
改成 `names().forEach(n -> find(n).ifPresent(...))` 才对。

**影响.** "看起来像全量、其实是子集"的 API，会让诊断/清理代码静默地什么都不做。

**建议.** 补 Javadoc 说明语义，或提供 `resolveAll()`；`eager()` 已存在但需要调用方自己记得先调。

**示例工程处理.** exp-01、exp-09 在注释里写清口径，并用断言钉住两者差别。

---

### 问题 10：HTTP SSE 端点未声明 charset，中文客户端必然乱码

**现象.** `GET /llm/agents/{name}/stream` 返回的 SSE，用 Spring 的 `RestTemplate` /
`StringHttpMessageConverter` 按 `String` 读回来是乱码（`北京` 变成 `åäº¬`）；
把响应体按 `new String(bytes, UTF_8)` 解就正常 —— 说明**服务端字节是 UTF-8，只是没声明**。

**证据.**
```java
// LlmAgentController.java:134
@GetMapping(value = "/agents/{name}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```
`MediaType.TEXT_EVENT_STREAM_VALUE` = `"text/event-stream"`（无 `charset`）。
Spring 对 `text/*` 的 `StringHttpMessageConverter` 默认按 ISO-8859-1 解码，于是中文必乱。
exp-08 的新增断言：`LlmWebEndpointTest.contractSseCharsetIsNotDeclared`。

**影响.** 任何用 Spring 客户端消费这个端点的调用方都会拿到乱码，
而"服务端明明是对的"会让排查方向完全跑偏。

**建议.** 写成 `produces = "text/event-stream;charset=UTF-8"`（或在 `SseEmitter` 里显式设置）。

**示例工程处理.** exp-08 的演示改成按 `byte[]` 取回并显式 UTF-8 解码，注释说明这是绕开。

---

### 问题 11：同一个异常 `onError` 被广播两次

**现象.** 一次失败的运行，容器级 `AgentListener.onError` 会被调用 **2 次**（同一个 `Throwable`）。

**证据.** 两条广播路径都存在：
```java
// AbstractAgentLoop.java:46 —— Loop 层捕获后广播
context.listener().onError(e);

// DefaultAgent.java:278 —— Agent 层捕获后又广播一次
listener.onError(e);
```
exp-08 的测试 `MonitorListenerTest.errorCallbackIsBroadcastTwice` 实测 `count == 2`，
复现：`failAssistant.ask("随便问点什么")`（模型抛 `ModelException`）。

**影响.** 按"收到 onError 就告警/计费/落库"的监听器会重复计数；
使用者必须自己按异常实例去重，而这条在文档里没有任何提示。

**建议.** 只在最外层（`DefaultAgent.run`）广播，或给 `AgentListener.onError` 补一个"来源层"参数；
至少在文档里写明"同一次失败可能收到两次 onError"。

**示例工程处理.** exp-08 用断言把"两次"钉住，并在 README 的"容易猜错"清单里写明。

---

### 问题 12：`SelectiveInterceptor` 没有暴露 `delegate()`

**现象.** 想在诊断代码里回答"**这个 Agent 身上到底挂了哪些拦截器**"时，拿到的是
`SelectiveInterceptor` 包装层，而被包装的真实拦截器无法访问。

**证据.** `SelectiveInterceptor.java:23-24` 只有两个私有字段，公开成员只有构造器与 9 个钩子方法
（外加一个 `toString()`，示例/测试只能靠 `toString().contains("...")` 辨认被包装者）。

**影响.** 纯诊断性问题：使用者无法在运行期枚举"实际生效的拦截器与它们的 `@LlmGuard` 范围"，
只能读日志字符串。

**建议.** 补 `public AgentInterceptor delegate()`（以及可选的 `Set<String> agents()`）。

**示例工程处理.** exp-08 的测试 `activeInterceptorsArePreFiltered` 断言
"元素是包装层、且 `supports()` 已被过滤"，并用注释说明这是当前唯一可行的观察方式。

---

### 问题 13：条件表达式里的 `${x:-兜底}` 静默失效（本工程实测最贵的一条）

> ✅ **1.1.1 已修复**：`Conditions` 的 `RefValue` 现在按与 `Templates.render` 一致的语义拆分 `:-`，
> 兜底值按字面量解析。回归断言见 exp-05 的
> `ConditionsTest.fallbackInsideConditionsWorks` 与 `WorkflowEngineTest.fallbackInsideBranchConditionsWorks`。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** 模板语法承诺 `${名称:-兜底值}`，但在**边的条件表达式**里这个兜底**不生效**：
`${x:-0}` 不会被拆成"变量 `x`，取不到用 `0`"，而是被当成一个名叫 `x:-0` 的变量去查表，
查到 `null`，于是比较恒为假。**不抛异常、不打警告、那条边永远不成立。**

**证据.**
```java
// Conditions.java:311-313 —— 整串（含 ":-0"）就是变量路径
private record RefValue(String path) implements Value {
    public Object get(Function<String, Object> lookup) {
        return Templates.resolve(path, lookup);      // path = "x:-0"
    }
}
// Templates.java:74-81 —— resolve 只在第一个 '.' 处分段，不处理 ":-"
public static Object resolve(String path, Function<String, Object> lookup) {
    int dot = path.indexOf('.');
    if (dot < 0) {
        return lookup.apply(path);                   // 直接拿 "x:-0" 查表 → null
    }
    ...
```
对照：`Templates.render(...)`（`Templates.java:46-51`）**是**会拆 `:-` 的，
`Templates.placeholders(...)`（`Templates.java:168`）还专门跳过带 `:-` 的占位符 ——
所以"模板里有兜底、条件里没有"这个不一致是明确的实现缺口，而不是设计选择。

**复现（exp-05 的测试已钉住）.**
```java
Conditions.evaluate("${missingValue:-0} <= 2", k -> null);      // 实测 false（按模板语义应为 true）
Conditions.evaluate("${missingValue:-有兜底} is empty", k -> null); // 实测 true
```
测试：`exp-05-workflow` 的 `ConditionsTest.fallbackInsideConditionsDoesNotWork`；
演示：`demo/ConditionsDemo.pitfalls()` 里有对照行。

**影响.** 真实事故形态：`exp-05` 的 `triage.yml` 里原本写
`when: ${grade} == 紧急 and ${rework:-0} <= 2`，本意是"没写过 rework 就当 0"，
实际这条边一圈都没转过 —— 而**运行结果看起来完全正常**（只是少走了几轮），
只有把 `visited()` 打印出来对照才会发现。这是最难排查的一类：语法合法、无报错、语义静默偏移。

**建议.** `RefValue` 也按 `render` 的规则拆 `:-`（或至少在 `Conditions.validate` 阶段
检测到 `:-` 就报错："条件表达式不支持默认值语法，请先用 set 节点播种"）。

**示例工程处理.** exp-05 的 YAML 改成"上游用 `set` 节点播种计数器"，
测试断言真实行为并注释说明，README 第 8 条也写了这条坑。

---

### 问题 14：END 节点的出边没有任何防线

**现象.** 给 `end` 节点再连出边时：定义**能加载、能运行、不报错、不警告**，
那条出边永远不会被求值，后续节点整段变成死代码。

**证据.**
1. `WorkflowEngine` 的 END 分支只负责产出最终文本（`dispatch` 的 `case END ->` 直接 `yield Outcome.of(text)`），
   推进循环在 END 处结束，**从不看 out-edges**。
2. `WorkflowDefinition.validate()`（`:175-203`）只校验结构（entry 存在、边两端存在、节点 id 不与保留名冲突）
   与条件语法；唯一与"死代码"有关的图级检查是 `warnUnreachableEdges()`（`:215-236`），
   而它只管"默认边**之前**的条件边"这一种情况。
3. `WorkflowNode.validate`（`:137`）对 `START` / `END` / `BRANCH` 的类型必填项**不做任何校验**。
4. exp-05 的复现测试：`WorkflowEngineTest.edgesLeavingEndAreSilentlyDropped`
   —— `begin → report(end) → done(end)` 正常 build/加载，跑完 `visited=[begin, report]`，
   那条边从未求值、没有 dead-end 事件、没有任何 WARN。

**影响.** 与问题 13 同类的"静默死代码"：`exp-05` 的 `triage.yml` 最初把中间产物写成 `end`，
导致验收/回炉/收尾整段永远不会执行，而演示输出看上去"跑完了"。

**建议.** 与"默认边之前还有条件边"同口径，加载期 WARN 一句
"END 节点 [x] 还有 N 条出边，永远不会执行"。

**示例工程处理.** exp-05 把该图改成 `set` 节点并补了这条测试与 README 说明。

---

### 问题 15：`WorkflowIo` 自动格式识别的报错信息误导

**现象.** 把 flow 风格 YAML（`{name: x, nodes: [...]}`）写进 `llm.workflows.<key>.inline` 时，
启动失败，但错误信息说"非法 JSON"，让人以为是 JSON 写错了，而不是"这其实是 YAML 的 flow 风格"。

**证据.** `WorkflowIo.java:93-96` 的自动识别只看"首个非空字符是不是 `{`"，
是就当 JSON 交给 `Json.parse`，实测抛的是
`IllegalArgumentException: 非法 JSON: {name: inline-loop, nodes: [...]}`（`Json.java:56` 包装后的文案）。
exp-05 的测试 `WorkflowIoDetectionTest.flowStyleYamlIsMistakenForJson` 钉住了这个边界
（同时断言显式传 `Format.YAML` 可以正常解析）。

**影响.** 只是报错方向问题，但会把排查引向错误的方向（使用者会去检查 JSON 括号，而问题在格式判定）。

**建议.** 报错时把"自动识别的结果 + 建议"写进去
（例如"看起来像 JSON 但解析失败；如果你写的是 YAML，请显式指定格式或改用块状语法"）。

**示例工程处理.** exp-05 按实测断言并在 README 第 11 条写明"flow 风格 YAML 请显式指定格式"。

---

### 问题 16：`SlidingWindowCompactor` 一旦触发就只保留最后 1 条（**高危**）

> ✅ **1.1.1 已修复**：压缩器重写为"`keepRecent` 是下限，预算内尽量多留"，
> 且与 `ContextManager` 共用 `ContextCompactor.inputBudget(...)`。
> 回归断言见 exp-06 的 `ContextManagerTest` / `CompactorTest`
> （含"历史比 keepRecent 还短时一条都不丢、也不调模型"）。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** 用 `SlidingWindowCompactor`（文档推荐它是因为"不调用模型、成本可预测"）压缩上下文时，
**压缩后只剩下最后一条对话消息** —— 不管 `keepRecent` 传的是 8 还是 2，结果完全一样。
更糟的是：`SummarizingCompactor` 的兜底路径也会走它，于是"摘要失败时退化成滑窗"会**把摘要本身也丢掉**。

**证据.** `SlidingWindowCompactor.java:53-63`：
```java
int keep = Math.max(1, keepRecent);
int start = Math.max(0, conversation.size() - keep);          // 期望的下界
for (int candidate = conversation.size() - 1; candidate >= start; candidate--) {
    List<ChatMessage> tail = conversation.subList(candidate, conversation.size());
    if (estimator.estimate(tail) > budget && candidate < conversation.size() - 1) {
        start = candidate + 1;
        break;
    }
    start = candidate;                                        // ← 每轮都在改写循环下界
}
```
`start` 既当**循环下界**又在循环体里被改写：
第一轮 `candidate = size-1` 通常不满足 `estimate(最后一条) > budget`，于是 `start = size-1`；
第二轮的条件 `candidate(size-2) >= start(size-1)` **立即为假**，循环结束。
结论：这个窗口最多只走一轮，`keepRecent` 实际上是死参数。

**复现（exp-06 的测试已钉住）.**
* `ContextManagerTest.slidingWindowKeepsOnlyTheLastConversationMessage`：
  `keepRecent = 8` → 实际 `[系统, 占位, 1 条]`（预期 `[系统, 6 条]`）；`keepRecent = 2` 结果**完全相同**。
* 附带：`compact(8 条 900 字符历史, tiny, 4)` 返回 2 条，`[本心历史摘要]` 那条被丢掉。

**影响.** 与类注释"不少于 `keepRecent` 条"、`ContextCompactor#compact` javadoc 的"至少保留"全部不符。
真实后果是**上下文被砍到只剩最后一句**，长任务里表现为"模型突然失忆"，
而且因为它不报错、不打日志，排查时会怀疑模型而不是压缩器。

**建议.** 把循环改为"从尾部往前的滑动窗口"：只用一个指针向小遍历，累计 token 超预算就停，
或直接删掉 `start` 在循环体里的赋值（保留 `break` 分支的 `start = candidate + 1`）。

**示例工程处理.** exp-06 用两个 `keepRecent` 取值对照断言真实行为，并在 README 里警告"想省 token 请用 `ContextCompactor.none()` 或自己写"。

---

### 问题 17：`ChatMessage.withText` 对工具消息不是替换，而是"前置 + 保留原内容"

> ✅ **1.1.1 已修复**：没有 `TextPart` 时改为替换 `ToolResultPart.content`，
> 并保留 `toolUseId` / `name` / `error`。回归断言见 exp-06 的 `MessageApiTest.withTextKeepsOtherParts`。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** 文档/方法名读起来是"替换文本内容"，但对 **TOOL 消息**（带 `ToolResultPart`）它变成
"把新文本插到最前面，并保留原来的工具结果"，于是 `text()` 变长而不是被替换。

**证据.**
```java
/** 返回一个仅替换文本内容的副本，其余结构保持不变。 */
public ChatMessage withText(String newText) {
    List<ContentPart> rebuilt = new ArrayList<>();
    rebuilt.add(new TextPart(newText));                       // 新的放最前
    parts.stream().filter(p -> !(p instanceof TextPart))      // 只删 TextPart
         .forEach(rebuilt::add);                              // ToolResultPart 被保留
    return new ChatMessage(role, rebuilt, name, toolCallId);
}
```
而 `text()`（`ChatMessage.java:101-111`）是 `TextPart` 与 `ToolResultPart` 内容**拼接**：
```java
toolResult(id, name, "很长的结果", false).withText("压缩后的占位").text()
    == "压缩后的占位很长的结果"        // 变长、parts 由 1 变 2
```

**影响.** claude-code Loop 的"就地改写旧工具结果以压缩上下文"这条路径，
在插件自己的实现里用的是别的方式；但使用者按 javadoc 用 `withText` 做压缩时会**越压越长**。

**建议.** 要么让 `withText` 对工具消息替换 `ToolResultPart.content`，要么把 javadoc 改成如实描述。

**示例工程处理.** exp-06 的 `MessageApiTest.withTextKeepsOtherParts` 断言真实行为。

---

### 问题 18：`TokenEstimator.DEFAULT` 对纯中文多算 1

> ✅ **1.1.1 已修复**：改为分别统计 CJK 与非 CJK，纯中文就是"1 字 1 token"，
> 非 CJK 部分向上取整。回归断言见 exp-06 的 `TokenEstimatorTest.defaultEstimatorValues`（`"你好" → 2`）。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** `TokenEstimator.DEFAULT.estimate("你好")` 返回 **3**，而 javadoc 的口径是"中日韩 1 字 1 token"（应为 2）。

**证据.** `TokenEstimator.java:33`：`return cjk + Math.max(1, other / 4);`
非 CJK 字符数为 0 时也会补 1。影响很小（1 token 的常数偏移，
但会让"刚好卡在阈值上"的压缩判断偏移），列在这里只为口径准确。

---

### 问题 19：`SlidingWindowCompactor` 的内部预算与 `ContextManager` 不同口径

> ✅ **1.1.1 已修复**：抽成共享的 `ContextCompactor.inputBudget(model, threshold, reserved)`，
> 压缩器与 `DefaultContextManager` 从此同一套预算。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** `SlidingWindowCompactor.budgetOf(...)` 是 `max(1024, max*token - reservedOutput)`，
而 `DefaultContextManager` 判断"要不要压缩"用的是 `model窗口 - reservedOutputTokens`（无 1024 下限）。
同一个"预算"在两处定义不同，边界情况下会出现"决定压缩、但压缩器认为不用压"或反之。

**证据.** `SlidingWindowCompactor.java:75-78` 的 `Math.max(1024, ...)`。

**影响.** 只在小窗口模型（`maxContextTokens` 很小）时可见，属于口径一致性问题。

---

### 问题 20：`@LlmLoop(defaultLoop = true)` 永远不会生效

**现象.** 给自定义 Loop 打上 `@LlmLoop(value = "my-loop", defaultLoop = true)`，
期望它成为全局默认 Loop —— **不会**。默认 Loop 仍然是 `dsh-minimal`。

**证据.** `LlmAutoConfiguration.llmLoopRegistry` 的选举顺序：
```java
String configured = properties.getAgent().getDefaultLoop();
if (configured != null && registry.contains(configured)) {
    registry.setDefault(configured);            // ← 恒真
} else if (annotatedDefault != null) {
    registry.setDefault(annotatedDefault);      // ← 永远走不到
} else {
    registry.setDefault("dsh-minimal");
}
```
而 `LlmProperties.AgentProperties.defaultLoop` 的**字段默认值就是 `"dsh-minimal"`**
（`LlmProperties.java:295`），且 `dsh-minimal` 必然在内置 Loop 表里 →
第一个分支恒真，`annotatedDefault` 是死代码。

exp-09 的 `AnnotationRegistrationTest.annotatedDefaultBecomesDefaultLoop` 按真实行为断言
（实测默认值就是 `dsh-minimal`）。

**影响.** 与问题 1 / 2 同一类："注解属性写了不生效、且不报错不警告"。
使用者要在 yml 里显式写 `llm.agent.default-loop: my-loop` 才有效果。

**建议.** 把"字段默认值"与"用户显式配置"区分开（例如 `defaultLoop` 默认留空、
在最后一步才回落 `dsh-minimal`），或在 `specOf` 之外补一条 WARN。

---

### 问题 21：`@LlmLoop(order = ...)` 未被使用

**现象.** `@LlmLoop` 上有 `order` 属性，但当多个自定义 Loop 都声明 `defaultLoop = true` 时，
胜出的是**注册顺序里的第一个**，而不是 `order` 最小的那个。

**证据.** `LlmAutoConfiguration:192-200` 的记录逻辑是
`if (annotation != null && annotation.defaultLoop() && annotatedDefault == null) annotatedDefault = name;`
—— 一旦拿到第一个就不再更新，`order()` 从未参与比较。
（对照：`@LlmModelDef` 的文档描述里 `order` 是有语义的，两者口径不一致。）

**影响.** 低（只在多个自定义 Loop 竞争默认位时可见），但同样属于"注解属性形同虚设"。

---

### 问题 22：注册器重复执行时用 `name#1` 静默产生第二个同类型 bean

**现象.** 当 `@SpringBootApplication`（自带 `@EnableAutoConfiguration`）与显式的
`AutoConfigurations.of(...)` 同时给出自动配置时，`LlmAgentRegistrar` 会跑两遍，
同一个 `@LlmAgent` 接口被登记成 `alphaAssistant` 与 `alphaAssistant#1` 两个 bean，
随后 `getBean(AlphaAssistant.class)` 抛 `NoUniqueBeanDefinitionException`。

**证据.** exp-09 修复前的两个 ERROR 就是这个；错误信息里 `#1` 后缀是唯一线索。

**影响.** 低（多数项目不会触发），但错误信息不指向根因（"注册器跑了两次"），
排查成本高。这与前面 `AgentRegistry.resolved()` 同类：都是"静默产生第二份"的问题。

**建议.** 注册前检查同名 bean 是否已存在并直接跳过（Spring 的 `BeanDefinitionRegistry`
本来就能查），或在重复时打一条 WARN。

---

### 问题 23：`LlmComponentRegistrar` 会连 `target/test-classes` 一起扫

**现象.** 它按 `AutoConfigurationPackages`（主应用包）做类路径扫描，
**不区分** class 来自 `src/main` 还是 `src/test`。于是主包下写在测试里的
`@LlmLoop` / `@LlmGuard` 夹具会**静默进入生产上下文**（`@SpringBootTest` 里表现为"多出来一堆 Loop/拦截器"）。

**证据.** exp-09 的测试夹具因此必须整体挪到主应用包之外（`com.benxin.exp.overridefixture`）。

**影响.** 这**不是插件缺陷**（真实项目里 `src/main` 本来就该被扫），
但值得写进文档：**测试夹具不要放在主应用包下**。exp-09 的 README 已把这条写成"容易猜错的口径"。

---

### 问题 24：`AgentAttributes` 的 6 个常量里只有 1 个被插件自己用

**现象.** 想在运行属性里按这些公开常量取值时，多数取不到：

| 常量 | 值 | 主源码里的引用 | 说明 |
|---|---|---|---|
| `SYSTEM_PROMPT` | `benxin.systemPrompt` | **有**（写：`LlmAgentInvocationHandler:144`；读：`DefaultAgent:254`） | 唯一真正生效的一个 |
| `TODOS` | `benxin.todos` | 常量本身 0 处 | 值以**字面量**重复在 `TodoWriteTool.ATTRIBUTE_KEY` |
| `PLAN` | `benxin.plan` | 常量本身 0 处 | 值以字面量重复在 `PlanExecuteLoop.PLAN_ATTRIBUTE` / `StagedLoop.PLAN_ATTRIBUTE` |
| `SESSION_ID` | `benxin.sessionId` | **0 处** | 真实值在 `AgentResult.sessionId()` |
| `AGENT_NAME` | `benxin.agentName` | **0 处** | 真实值在 `AgentResult.agentName()` |
| `DEFAULT_SUB_AGENT` | `benxin.default-subagent` | **0 处** | 无任何使用 |

**证据.** 在 core + starter 的 main 源码里 grep `AgentAttributes.<常量名>`，只有 `SYSTEM_PROMPT` 命中
（写、读各一处）；其余 5 个常量的值在主源码里除声明外查不到引用来源。
exp-04 的断言：`LoopsTest.sessionAndAgentNameKeysAreNeverWritten`、`agentAttributesAreWritten`。

**影响.** "公开了一个常量、按它取值却永远是 null"是典型的时间陷阱
（使用者会以为会话 id / agent 名会被写进 attributes）。

**建议.** 要么真的写进去（`DefaultAgent.run` 开头 seed 一次），要么把这些常量标成
"仅供内部键名参考，取值请用 `AgentResult.sessionId()/agentName()`"。

---

### 问题 25：流式路径收到"200 + 非 SSE 整包响应"时静默返回空回答

**现象.** 某些网关/自建代理在**流式请求**上返回 **HTTP 200 + 普通 JSON 整包**（而不是 SSE）。
此时调用方拿到的不是报错，而是**空回答**：`text = ""`、`error = null`、`finishReason = UNKNOWN`。

**证据（三处叠加的结果）.**
1. `JdkHttpTransport.postStreaming`（`JdkHttpTransport.java:50-70`）**只按状态码分流**：
   `statusCode >= 400` 才读成普通响应，否则一律 `TransportResponse.streaming(...)`。
2. `HttpLlmModel.stream` 的校验是 `if (!response.isStreaming())` → 有可读 `ModelException`
   （注释还专门解释了"避免 SseParser NPE"）—— 但因为第 1 步把它标成了 streaming，这个校验过不去。
3. `SseParser` 对一个普通 JSON 体解析出 **0 个事件**，解码器 `finish()` 兜底收尾 → 上层拿到空响应。

**对照.** 同一类误用里，"传输层自己返回 `TransportResponse.of(...)`"是**有**可读报错的
（第 2 步的校验就是为了这个），说明缺的只是"按 `Content-Type` 判断是不是 `text/event-stream`"。

**复现（exp-07 的测试已钉住）.**
桩服务对 `POST /v1/chat/completions` 回 `200 + Content-Type: application/json` + 合法的 chat.completion 体，
然后调用 `model.stream(...)` → 断言得到"静默空响应"。
测试：`ProtocolWireTest.nonSseBodyOnStreamingPathIsSilentlyEmpty`。

**影响.** 网关配置错误会表现为"模型不说话了"，而不是"上游返回格式不对"，
排查方向完全不同（这类问题在真实项目里非常常见：反向代理把 SSE 缓冲成了整包）。

**建议.** `JdkHttpTransport.postStreaming` 检查响应 `Content-Type`：
命中 `application/json`（且非 `text/event-stream`）时读成普通响应，
让 `HttpLlmModel` 抛那条已经写好的可读异常。

---

### 问题 26：`beforeTool` 短路会 `break` 掉整条拦截器链

**现象.** 一个拦截器在 `beforeTool` 里返回非 null（短路）后，
**排在它后面的拦截器完全收不到这次调用** —— 既没有 `beforeTool`，也没有 `afterTool`。

**证据.** `DefaultLoopContext` 的拦截器循环：
```java
result = null;
for (AgentInterceptor interceptor : interceptors) {
    ToolResult shortCircuit = interceptor.beforeTool(call);
    if (shortCircuit != null) {
        result = shortCircuit;
        break;                       // ← 整条链就此结束
    }
}
if (result == null) {
    result = executeWithTimeout(callback, call);
}
```

**影响（exp-10 的真实事故形态）.** 一个"审计拦截器"如果 `order()` 排在"短路拦截器"之后，
那么**被短路的工具调用一行审计都不会留下**（`tool_audit` 表里没有记录）。
只有把审计的 `order()` 调到最前面才看得见。

**建议.** 短路只应跳过"执行"，不该跳过"其余拦截器的通知"；
至少把 `afterTool` 也广播给链上所有拦截器（现在 `afterTool` 在循环外的后半段，
被短路的调用是能收到 `afterTool` 的 —— 但 `beforeTool` 收不到，两者不对称，很难猜）。

**示例工程处理.** exp-10 把审计拦截器 `order()` 设为 0、短路拦截器设为 500，
并在 README 与代码注释里写明"顺序即功能"。

---

### 问题 27：手写 `ToolCallback` 用 `@LlmAgent(tools = 外层类.class)` 匹配不到

**现象.** 把写类工具做成 `@Bean ToolCallback`（例如返回 `OrderWriteTools` 里的一个内部类实例），
然后在 Agent 上写 `tools = {OrderWriteTools.class}` —— **一个工具都装不上**，
插件只打一行 WARN 就继续，Agent 静默地只拿到别的工具。

**证据.**
1. `LlmToolCatalog.collect()` 用 `AopUtils.getTargetClass(bean)` 当来源类键 ——
   手写 bean 的实际类型是内部类 `OrderWriteTools$CreateOrderTool`，不是外层类。
2. `forClasses(...)`（`LlmToolCatalog.java`）只做**精确匹配**：
   `bySourceClass.get(type)` → `byDeclaredType.get(type)` → 都没命中就
   `log.warn(...)` + `continue`，不抛异常、不降级。

**影响.** 与问题 1/2/20/21 同一类："配置写了不生效、只有一行 WARN"。
exp-10 的表现是 `orderAssistant` 只有 5 个只读工具，写工具全部消失。

**建议.** 匹配失败时除了 WARN，还应支持"按外层类/声明类型"的回退匹配
（或让 `register(...)` 显式接受"来源类"参数）。

**示例工程处理.** exp-10 改成"内部类 + 在 `tools` 里同时列出 `toolNames`"的双路写法，并注释说明原因。

---

### 问题 28：`ToolSandbox` / `ApprovalHandler` 按裸类型注入，多实现时启动失败

**现象.** 想"给这个 Agent 换一个沙箱/审批器"，于是容器里出现两个同类型 bean →
`LlmAgentFactory` 的构造注入直接抛 `NoUniqueBeanDefinitionException`，**应用启动失败**。

**证据.** `LlmAgentFactory` 的构造参数是 `ToolSandbox` / `ApprovalHandler`（裸类型），
没有 `ObjectProvider` / `@Qualifier` / "按 Agent 名选择"的机制。

**影响.** "按 Agent 换审批策略"这种很自然的需求，只能靠给其中一个加 `@Primary` 绕过，
无法真正做到 per-Agent 区分。

**建议.** 注入改为 `ObjectProvider<ToolSandbox>` + 约定（例如按 bean 名 = Agent 名优先），
或在 `@LlmAgent` 上加 `sandbox` / `approvalHandler` 属性。

**示例工程处理.** exp-10 给自定义实现加 `@Primary` 并在注释里说明这是绕开。

---

### 问题 29：审批被拒时的理由是硬编码的

**现象.** `ApprovalHandler` 只能返回 `boolean`，被拒绝时模型收到的永远是同一句
`用户拒绝执行工具 [xxx]`，使用者无法把"为什么拒绝"告诉模型。

**证据.** `DefaultLoopContext` 审批分支：
```java
result = ToolResult.error("用户拒绝执行工具 [" + call.toolName() + "]");
```
（`ApprovalHandler#approve` 的返回类型是 `boolean`，没有携带理由的位置。）

**影响.** 模型拿不到理由就无法自我调整（例如"金额超限，请先走人工审批"），
而这正是"把工具错误回灌给模型"这个设计的价值所在。

**建议.** 让 `ApprovalHandler` 能返回"拒绝 + 理由"（例如新增 `ApprovalDecision` 记录类型）。

---

### 问题 30：`toBuilder().model("名字")` / `.loop("名字")` 静默无效（**用错模型也不会报错**）

> ✅ **1.1.1 已修复**：`AgentBuilder` 的两个重载现在"后来者胜" ——
> `model(String)` 会把实例置空、`model(LlmModel)` 会把名字置空（`loop` 同理）。
> 回归断言：`exp-06` `AgentWiringTest.derivedAgentHonoursModelName`、
> `exp-12` `OfflinePathTest.nameOverloadsWorkOnDerivedBuilder`。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** README 把 `Agent.toBuilder()` 列为常用 API，`AgentBuilder` 也同时提供
`model(LlmModel)` 与 `model(String)` 两个重载。但在**派生副本**上，字符串重载会被完全忽略：

```java
Agent local = agentRegistry.get("localAssistant").toBuilder()
        .model("local-lmstudio")     // ← 写了，但没用
        .build();
assertThat(local.model().name()).isEqualTo("mock");   // ← 实测：还是原来那个模型
```

**证据.** `DefaultAgent` 的解析顺序是"**实例优先**"：
```java
// DefaultAgent.java:68-81
LlmModel resolvedModel = b.model;                      // toBuilder() 已经把原实例放进来了 → 非 null
if (resolvedModel == null && b.modelName != null) {    // ← 这一支永远进不去
    resolvedModel = this.modelRegistry.get(b.modelName);
}
...
AgentLoop resolvedLoop = b.loop;                       // 循环同理
if (resolvedLoop == null && b.loopName != null) {      // ← 同样进不去
    resolvedLoop = this.loopRegistry.get(b.loopName);
}
// 之后 spec 里的名字被改回实例的名字（:85-86），所以连"名字对不上"这种线索都没有
this.spec = b.spec.model(resolvedModel.name()).loop(resolvedLoop.name());
```
exp-12 的实测：`OfflinePathTest.modelNameOverloadIsIgnoredOnDerivedBuilder`、
`loopNameOverloadIsAlsoIgnored`（两条断言把"字符串重载无效 / 实例重载有效"钉住）。

**影响（这次是真实咬人的）.** 我最初就是按直觉写的 `toBuilder().model("local-lmstudio")`，
测试跑出来"真实模型用例全绿"——**其实一次都没打到 LM Studio，全在离线 mock 上跑的**。
也就是说：一个静默的无效调用，能伪造出一片绿色的假象。

正确写法是传实例：
```java
Agent local = agentRegistry.get("localAssistant").toBuilder()
        .model(modelRegistry.get("local-lmstudio"))
        .loop(loopRegistry.get("react"))
        .build();
```

**建议.** 三选一：① `AgentBuilder.model(String)` 清空 `this.model`（名字与实例本就是同一个字段的两种表达，
后来的应当覆盖先前的）；② 两个重载同时被设置时在 `build()` 抛错；③ 至少在 `model(String)`
的 Javadoc 里写明"派生 builder 上无效"。

---

### 问题 31：`toBuilder()` 不携带监听器

> ✅ **1.1.1 已修复**：`DefaultAgent.toBuilder()` 补上了 `.listener(listener)`，
> 派生多少层都保留监听器。
> 回归断言：`exp-12` `OfflinePathTest.derivedAgentKeepsListeners`。
> 下面保留的是 1.1.0 当时的原始记录。

**现象.** 从已有 Agent 派生副本后，副本的运行**不会**进入任何 `AgentListener`
（统计、审计、指标、子代理记账全部静默丢失）。

**证据.** `DefaultAgent.toBuilder()` 携带了 spec / model / loop / 三个注册表 / contextManager /
memoryStore / systemPromptProvider / sandbox / approvalHandler / approvalPolicy / hardMaxSteps /
toolExecutor / interceptors / subAgents，**唯独没有 `.listeners(...)`**；
而 `DefaultAgent` 构造里 `this.listener = CompositeListener.of(listeners)`，
于是副本拿到的是一个空的组合监听器。

exp-12 的实测：`OfflinePathTest.derivedAgentLosesListeners`
（`toBuilder().build()` 后调用 → `metrics.runCount() == 0`；显式 `.listener(metrics)` 后 → `1`）。

**影响.** "派生一个副本换个模型/循环试试"是很自然的操作，但派生出来的副本会**悄悄绕过**所有可观测性；
更隐蔽的是它还会**层层丢失**：`localCopy(x).toBuilder()...` 会把上一次刚补上的监听器再丢一次
（exp-12 的演示最初统计只记到 2 次运行，就是这个原因）。

**建议.** `toBuilder()` 里补一行 `.listeners(listener.delegates())`
（`CompositeListener` 已经暴露了 `delegates()`，改成携带监听器几乎没有成本）。

---

## 四点五、另外两个"工程坑"（不是插件问题，但同样会让人白干）

| 坑 | 现象 | 规避 |
|---|---|---|
| `@SpringBootTest(properties = …)` 与父类注解是**合并**而非替换 | exp-10 的"MySQL 测试"一直静默跑在 H2 上却全绿，"两种库都验证过了"变成空话 | 用 `@DynamicPropertySource`，并加一条"当前连的是哪个产品"的自证断言 |
| H2（`MODE=MySQL`）的 `ALTER TABLE … AUTO_INCREMENT = 1` 是**无条件打回 1** | `DELETE → 显式插 id 1..5 → ALTER AUTO_INCREMENT=1` 之后，下一次自动取号仍是 1 → 撞主键；真实 MySQL 同顺序会取 6 | 把自增重置放到"重灌数据"之前；或干脆不显式插主键 |

---

## 三、不是缺陷、但按直觉容易猜错的口径

这些是实测确认"行为与文档一致、但不符合直觉"的点，示例里都写成了显式演示 + 断言
（**不是插件问题**，列在这里是给读者的索引）：

| 口径 | 说明 | 演示位置 |
|---|---|---|
| 不写 `tools` / `toolNames` = 拿到**全部**工具 | "什么都不写"不是"最小权限"，而是继承容器全局工具表 | exp-01、exp-02 |
| 不传 sessionId 时都落在 `<agentName>-default` | 所以"同一个 Agent 连着问两句"会自然续上上下文 | exp-01、exp-06 |
| `memory` 默认 **true** | `@LlmAgent(memory = false)` 才能关掉写回 | exp-01 |
| `MemoryStore.disabled()` ≠ `@LlmAgent(memory=false)` | 前者"永远空"，后者"这次不装载也不写回" | exp-06 |
| `ContextManager` 只影响**这一轮请求** | 不会缩短 `MemoryStore` 里的历史 | exp-06 |
| `SummarizingCompactor` 会**额外花一次模型调用** | 压缩本身也要钱，`SlidingWindowCompactor` 不花 | exp-06 |
| 方法级 `@SystemPrompt` 会**盖掉**自定义 `SystemPromptProvider` | provider 只在方法没声明时生效 | exp-06 |
| `@Ctx` 不进消息体 | 只渲染提示词与（可选）写运行属性 | exp-01 |
| `Optional<String>` 在空白输出时给 `empty` | `orElse(兜底)` 因此真的能兜住 | exp-01 |
| DTO 返回是"反序列化 JSON" | 不是"让模型输出 JSON"；模型回散文会抛异常 | exp-01 |
| `exists` 的语义是"≠ null" | `"" exists` 为 true，同时 `"" is empty` 也为 true | exp-05 |
| `matches` 右操作数按原文截取 | 与 `and` / `or` / `)` 相邻时必须加引号 | exp-05 |
| `workflow` 的 `branch` / `set` 不产生输出 | 因此不覆盖 `${last}` | exp-05 |
| `${不存在的变量}` 保留原文 | 刻意的设计，让拼写错误可见 | exp-05 |
| 工作流 `visited()` 证明的是"**尝试过**哪些节点" | 硬失败节点也会进访问表 | exp-05 |
| 沙箱拒绝是硬约束，审批无法推翻 | `ON_FAILURE` + 永远批准的 handler 也救不回来 | exp-03 |
| `NEVER` 是"不问、直接放行" | 不是"不问就拒绝"；想一律拒绝用 `ApprovalHandler.denyAll()` | exp-03 |
| `enabled` 是**增量**不是白名单 | 只读三件套永远是基线；`todo_write`/`task` 必须点名 | exp-03 |
| `denied-commands` 是**追加**不是替换 | 你写的条目叠加在内置 29 条黑名单之上 | exp-03 |
| `llm.models.<key>.max-retries` 默认是 **2** | 想"一次调用 = 一笔请求"必须显式写 0 | exp-07 |
| HTTP 端点只认 Agent 级 `systemPrompt` | 方法级 `@SystemPrompt` / `@Ctx` / `@User` 在端点上完全不生效 | exp-08 |
| SSE 里没有 `tool_result` 事件 | 工具结果只在 `done` 事件的 `toolCalls` 里 | exp-08 |
| `AgentListener.onTextDelta` 与 `LlmStreamHandler` 是两回事 | 前者只要模型支持流式就会被调用；后者只在传参时收到 | exp-08 |
| `AgentRegistry.resolved()` 只是子集 | 遍历全部请用 `names()` + `find()` | exp-01、exp-09 |

---

## 四、怎么复核

```bash
cd benxin_exp

# 每个模块都能独立跑（离线、零 token）
cd exp-02-tools        && mvn -o test    # 工具切面的 7 条发现，见该模块 README「插件问题」
cd ../exp-01-declarative-agent && mvn -o test
# …其余模块同理
```

* 每条发现都在对应模块的 `README.md`「插件问题」小节里有更详细的复现步骤；
* 每个模块的测试类名就是断言点（例如 `requiredOnlyAffectsSchema`、`sandboxRunsBeforeInterceptors`）；
* 插件源码位置都以 `benxin/<模块>/src/main/java/...:<行号>` 的形式给出，便于按行号复核。
