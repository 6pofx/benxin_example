# exp-08 · 可观测性与 HTTP 端点

> 跑起来之后你能**看见**什么（12 个监听器回调 / 9 个拦截器钩子 / 流式三件套）、
> 能**改**什么（短路、改写、记账），以及 `/llm/**` 到底对外承诺了什么。

本模块只做一件事：把"可观测性 + Web 端点"这条路径拆开揉碎演示一遍。
**默认离线、零 token**：模型来自 `exp-common` 的脚本化模型（`ScriptedLlmModel` + `RecordingModel`），
`mvn spring-boot:run` 立刻能看到完整输出（含一次真实的 HTTP 响应体），
`mvn test` 却能断言到框架内部的口径（78 个用例）。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| `AgentListener` | **12 个回调**全部触发并留档：`onAgentStart` / `onStepStart` / `onTextDelta` / `onThinkingDelta` / `onToolCall` / `onToolResult` / `onStepEnd` / `onSubAgentStart` / `onSubAgentEnd` / `onCustomEvent` / `onAgentEnd` / `onError` |
| 监听器装配 | 多个自定义监听器**叠加**；内置 `llmLoggingListener` 按**类型**让位；`CompositeListener.of(...)` / `AgentListener.noop()`；单个监听器抛异常不影响别人 |
| `AgentInterceptor` | **9 个钩子**全部可达：`order` / `supports` / `beforeAgent` / `afterAgent` / `onError` / `beforeModel` / `afterModel` / `beforeTool`（短路）/ `afterTool` |
| `@LlmGuard` | `agents = {...}` 的范围过滤，以及"为什么消费端必须依赖 `LlmInterceptorChain`" |
| `@LlmAgent(interceptors = {...})` | 注解级拦截器（优先取容器 bean，取不到直接 `new`），天然只作用于本 Agent |
| 流式三件套 | `LlmStreamHandler` 7 回调 / `StreamCollector` 聚合 / `LlmStreamHandler.ofText(...)` |
| HTTP 端点 | `GET /agents`、`GET /loops`、`GET /models`、`POST /agents/{name}/chat`、`GET /agents/{name}/stream`（SSE），以及自定义 `llm.web.base-path` |
| 失败路径 | 模型抛异常 → `onError`；工具抛异常 → `ToolResult(error=true)`；失败运行不产生 `onAgentEnd` |

## 目录结构

```
src/main/java/com/benxin/exp/observability/
├── ObservabilityApplication.java        @SpringBootApplication + @EnableLlmAgents
├── agent/                               9 个 @LlmAgent 接口（见下表）
├── listener/MonitorListener.java        12 个回调全留档的录制型监听器
├── listener/ToolActivityListener.java   第二个监听器（证明"叠加"而不是"互斥"）
├── interceptor/                         Guard / ShortCircuit / Rewrite / ToolAudit / OrderProbe
├── loop/EmittingLoop.java               自定义 Loop（发自定义事件 + 派生子代理）
├── stream/StreamingService.java         流式三件套的演示服务
├── tool/FailingProbeTool.java           "必定失败"的工具（probe_fail）
├── config/                             离线模型 / 工具 / 监听器 / 拦截器装配
├── web/SessionTrackingInterceptor.java  全局拦截器：登记 sessionId，供测试清理历史
└── demo/ObservabilityDemo.java          启动即跑的 7 组演示（ApplicationRunner）
src/test/java/com/benxin/exp/observability/   78 个用例（见文末"断言在哪里"）
```

| Agent | 演示重点 |
|---|---|
| `chatAssistant` | 普通对话；方法级 `@SystemPrompt` 与 `LlmStreamHandler` 参数的对照 |
| `observability` | 绑定 `exp-observe` Loop：自定义事件 + 子代理（`task`） |
| `task` | 子代理；`toolNames` 收紧到 1 个工具（对照"不写就拿全部"） |
| `thinkingAssistant` | `onThinkingDelta`（模型 `thinking(true)`） |
| `guardedAssistant` | `GuardInterceptor` 的 `@LlmGuard` 点名对象 |
| `toolGuard` | `beforeTool` 短路（`get_weather` 被拦，其余放行） |
| `rewrite` | `@LlmAgent(interceptors = {...})` 注解级拦截器 |
| `toolErrorProbe` | 工具失败路径（`probe_fail` 真的抛异常） |
| `failAssistant` | 模型异常 → `onError`，失败运行不产生 `onAgentEnd` |

## 事件回调表（`AgentListener`，12 个）

顺序即"一次成功运行"里的典型次序；**实测口径**一列是本模块用断言钉住的真实行为。

| # | 回调 | 何时触发 | 实测口径 / 注意 |
|---|---|---|---|
| 1 | `onAgentStart(AgentInvocation)` | 每次运行开始 | 参数带 `agentName` / `sessionId` / `spec` / `input` / 可写 `attributes`；**子代理也会触发一次**（它是另一个 Agent） |
| 2 | `onStepStart(agentName, step)` | 每次模型调用之前 | 步号从 1 开始；`step` 是"本次运行的第几次模型调用" |
| 3 | `onTextDelta(delta)` | 模型**支持流式**就会触发 | 调用方没传 `LlmStreamHandler` 也照样触发；回调里**没有** `agentName`，多代理并发时无法直接区分来源 |
| 4 | `onThinkingDelta(delta)` | 模型真的产出思维链时 | 需要 `capabilities().thinking == true`（本模块用 `ScriptedLlmModel.builder().thinking(true)`） |
| 5 | `onToolCall(ToolInvocation)` | 工具执行**之前** | 与"模型请求了工具"同义；未知工具同样会广播 |
| 6 | `onToolResult(ToolInvocation, ToolResult)` | 工具结果产生**之后** | 在拦截器 `afterTool` **之后**广播；`error=true/false` 两种都在这里 |
| 7 | `onStepEnd(agentName, step, ChatResponse)` | 每次模型调用返回之后 | 此时 `afterModel` 的改写**已经**生效（它拿到的是改写后的响应） |
| 8 | `onSubAgentStart(parent, child, prompt)` | 父代理派生子代理之前 | 只有"自己会 `ctx.spawnSubAgent(...)` 的 Loop"才有（标准 tool-calling 骨架不派生） |
| 9 | `onSubAgentEnd(parent, child, AgentResult)` | 子代理跑完之后 | 子代理**自己**的 `onAgentEnd` 比这条更早（见"容易猜错的口径"第 5 条） |
| 10 | `onCustomEvent(type, payload)` | Loop / 工具调用 `ctx.emit(type, payload)` | 标准 Loop 只在步数耗尽时 `emit("max-steps-reached", …)`；要演示得干净需要一个自定义 Loop（`EmittingLoop`） |
| 11 | `onAgentEnd(AgentResult)` | 成功收尾 | `AgentResult` 带 `steps` / `usage` / `toolCalls` / `maxStepsReached` / `durationMillis` / `attributes`；**失败运行不触发** |
| 12 | `onError(Throwable)` | 运行抛异常 | **会被广播两次**：`AbstractAgentLoop.run()` 一次 + `DefaultAgent.run()` 一次 |

## 拦截器钩子表（`AgentInterceptor`，9 个）

| # | 钩子 | 何时被调用 | 返回值的意义 / 实测口径 |
|---|---|---|---|
| 1 | `order()` | **装配期** | 越小越靠外（越早进入、也越早退出）。实测只在 `resolveInterceptors(...)` 排序与 `AgentBuilder.build()` 排序时被问，**运行期不再询问** |
| 2 | `supports(agentName)` | 运行期，每次调用**之前** | `false` 的拦截器完全不参与本次运行。`@LlmGuard` 名单不匹配时，包装层 `&&` 短路，**连被包装者的 `supports()` 都不会被问** |
| 3 | `beforeAgent(invocation)` | 运行开始 | 按 `order` **升序** |
| 4 | `afterAgent(invocation, result)` | 成功收尾 | 也是**升序**（不是洋葱式逆序）；失败时不调用（走 `onError`） |
| 5 | `onError(invocation, error)` | 运行抛异常 | 自己抛异常不会掩盖原始异常（`DefaultAgent` 做了保护） |
| 6 | `beforeModel(invocation, request)` | 每次模型调用前 | 返回 `null` = 不改写；返回 `ChatRequest` = 替换（可加 `extra`、改消息） |
| 7 | `afterModel(invocation, response)` | 每次模型调用后 | 返回 `null` = 不改写；返回 `ChatResponse` = 替换（改写会进入最终结果） |
| 8 | `beforeTool(invocation)` | 工具执行前 | `null` = **放行**；返回 `ToolResult` = **短路**，工具根本不执行。注意它排在**沙箱与审批之后**：被沙箱拒绝的调用压根走不到这里 |
| 9 | `afterTool(invocation, result)` | 工具结果产生后 | 短路结果、沙箱拒绝、未知工具**都会**走到；它比监听器的 `onToolResult` 更早 |

## HTTP 端点表（`llm.web.enabled=true`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `{base-path}/agents` | 列出全部 Agent：`name` / `loop` / `model` / `tools` / `maxSteps` / `systemPrompt` |
| `GET` | `{base-path}/loops` | Loop 清单（描述文本），含内置 Loop、自定义 `@LlmLoop` 与 `workflow:<key>` |
| `GET` | `{base-path}/models` | `{"default": "...", "names": [...]}` |
| `POST` | `{base-path}/agents/{name}/chat` | 同步对话，body：`{"message": "...", "sessionId": "可选"}` |
| `GET` | `{base-path}/agents/{name}/stream?message=...&sessionId=可选` | SSE：事件名只有 `delta` / `thinking` / `tool_call` / `done` / `error` |

`{base-path}` 来自 `llm.web.base-path`，默认 `/llm`；它是 `@RequestMapping` 上的**占位符**，
启动期就固定，运行期改不了。未知 Agent 名 → **404**（响应体含 `timestamp`/`status`/`error`/`message`/`path`）。

```bash
# 端口由 server.port 决定；本示例默认写 0（操作系统分配），演示里从 local.server.port 读回来。
# 想用固定端口跑：
java -jar target/exp-08-observability-web-1.0.0.jar --server.port=8080

# ① Agent 清单（重点看 tools —— 没声明 tools 的 Agent 拿到了全局工具表）
curl -s http://localhost:8080/llm/agents

# ② 同步对话（不传 sessionId：每次一个独立的 http-<uuid>）
curl -s -X POST http://localhost:8080/llm/agents/chatAssistant/chat \
     -H "Content-Type: application/json" \
     -d '{"message":"北京天气怎么样？"}'

# ③ 同一个会话续聊（显式传 sessionId 才会续接历史）
curl -s -X POST http://localhost:8080/llm/agents/chatAssistant/chat \
     -H "Content-Type: application/json" \
     -d '{"message":"第二句","sessionId":"demo-session"}'

# ④ 流式（SSE）：-N 关掉 curl 的缓冲，才能看到增量逐条到达
curl -s -N "http://localhost:8080/llm/agents/chatAssistant/stream?message=北京天气怎么样？"

# ⑤ 未知 Agent → 404（不是 500）
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/llm/agents/doesNotExist/chat \
     -H "Content-Type: application/json" -d '{"message":"你好"}'
```

## 运行方式

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-08-observability-web
mvn -o test                                   # 78 个离线用例，零 token
mvn -o spring-boot:run                        # 启动即打印 7 组演示（Web 应用不退出，Ctrl+C 结束）
```

想"跑完就退出"（CI / 一次性验证，含真实的 HTTP 调用）：

```bash
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -Dexp.demo.exit-after-run=true \
     -jar target/exp-08-observability-web-1.0.0.jar > run.log 2>&1
```

`exp.demo.exit-after-run=true` 是模块自带的开关（见 `ObservabilityDemo.run(...)`），
默认 `false` —— 保持"正常 Web 应用"的形态。

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8，或像上面那样加 `-Dstdout.encoding=UTF-8`。

## 几条"按直觉猜会猜错"的口径

这些都是本模块**实测**出来、并逐条写成断言的结论：

1. **内置 `llmLoggingListener` 是"按类型让位"，不是"按名字并存"。**
   它的条件是 `@ConditionalOnMissingBean(AgentListener.class)`：只要容器里出现任意一个
   `AgentListener` bean，内置那个就**整个不装配**（`context.containsBean("llmLoggingListener")` 为 `false`）。
   而多个**自定义**监听器之间不互斥，会被 `AgentBuilder` 用 `CompositeListener.of(...)` 合成一条广播链。
2. **`onTextDelta` 由"模型支不支持流式"决定，与调用方传不传 `LlmStreamHandler` 无关。**
   传了 handler 只是多一条私有出口（两条通道并列、收到同一批增量）。
   顺带说明一个**曾经**的死配置：`@LlmAgent(stream = true)` 与 `llm.agent.stream`
   在 1.1.1 起有了读取点 —— `LlmAgentController.chat` 会按它决定返回 SSE 还是 JSON
   （1.1.0 时确实没有任何读取点，见根目录 `FINDINGS.md` 第 2 条）。
3. **`onError` 只广播一次**（1.1.1 修复；1.1.0 时 `AbstractAgentLoop.run()` 与
   `DefaultAgent.run()` 各广播一次，监听器按次数计费/告警必须自己去重 —— 见 FINDINGS 第 11 条）。
4. **失败的运行不产生 `onAgentEnd`**（对应"不往 `agent_run` 表里写行"），只有 `onError`。
5. **子代理的回调会广播到同一批监听器上**，而且 `onSubAgentEnd` 之前会先看到子代理**自己**的
   `onAgentEnd`。所以"最后一条 `onAgentEnd` 才是父代理的"——用 `indexOf` 去找会得到相反的结论，
   必须用 `lastIndexOf`（或按 `agentName` 过滤）。`onTextDelta(delta)` 又不带 agentName，
   想区分"谁说的"只能自己在拦截器里用 `AgentInvocation` 挂上下文。
6. **`CompositeListener.of(...)` 拍平嵌套、丢掉 `null`，但不会丢掉 `AgentListener.noop()`。**
   `noop()` 只是一个普通的匿名实现，会实打实占一个委托位（它不发事件，但 `delegates()` 里数得到）。
7. **`beforeTool` 返回 `null` 是"放行"，返回结果才是"短路"**，而且短路结果同样会走 `afterTool`
   （短路也要进审计）。短路只影响"工具是否执行"，不影响对话流程。
8. **`afterAgent` 也是按 `order` 升序**，不是洋葱模型的逆序。
9. **`order()` 是装配期钩子，`supports()` 才是运行期钩子。**
   运行期每次调用只问 `supports()`；`@LlmGuard` 名单不匹配时连被包装者的 `supports()` 都不会被问
   （`SelectiveInterceptor.supports` 的 `&&` 短路）。
10. **`DefaultAgent.activeInterceptors()` 返回的是"已按 `supports()` 过滤、且保留 `SelectiveInterceptor`
    包装层"的那一份**：按 `GuardInterceptor.class::isInstance` 去找是找不到的（包装层不是裸 bean）。
    这也是"消费端必须依赖 `LlmInterceptorChain`"的原因 —— `ObjectProvider<AgentInterceptor>` 取到的是
    容器里**未包装**的原始 bean，`@LlmGuard(agents = {...})` 的范围会彻底失效。
11. **不声明 `tools` / `toolNames` ≠ 没有工具**，而是**继承容器全局工具表**
    （本模块里就是 `get_weather` / `get_current_time` / `calculate` / `describe_context` / `probe_fail`）。
    想收紧必须显式列出（见子代理 `task`，它只留了 `get_current_time`）。
12. **方法级 `@SystemPrompt` / `@Ctx` / `@User` 在 HTTP 端点上完全不生效。**
    端点按 **Agent 名**调用，不经过方法绑定，只认 Agent 级 `systemPrompt`；
    同一段注解在 Java 接口代理调用时是生效的（测试里两种调用各断言了一次）。
13. **不传 `sessionId` 时每次都是新的 `http-<uuid>`**，而不是落到 `<agentName>-default` 上。
    所以"两次 HTTP 调用"默认互不相关，想续接历史必须显式传同一个 `sessionId`。
14. **SSE 的事件名是个封闭集合**：`delta` / `thinking` / `tool_call` / `done` / `error`，
    **没有 `tool_result`** —— `LlmStreamHandler` 上根本没有对应回调，这个事件名发不出来。
    工具结果只出现在 `done` 事件的 `toolCalls` 里。
15. **脚本模型里 `#tool:<名字> {...}` 会在每一步重复命中**（它排在"已有工具结果就收尾"的规则之前，
    而"最后一条 user 消息"整轮不变）。想"恰好调用一次"要用关键字触发，
    否则一次运行会反复调用同一个工具直到步数预算耗尽（`maxStepsReached=true`）。
16. **`CommonTools.calculate` 的"失败"只是内容里的一段错误文本**（`错误：表达式只允许…`），
    `ToolResult.error()` 仍是 `false`。想演示 `error=true` 必须让工具**真的抛异常** ——
    这就是 `probe_fail`（`FailingProbeTool`）存在的原因。
17. **未知 Agent → 404，失败的运行 → 500**：两条不同的路径，前者由
    `@ExceptionHandler(NoSuchAgentException.class)` 映射，后者按服务端错误返回。
18. **SSE 的 `Content-Type` 是 `text/event-stream`，不带 `charset`。**
    字节本身是合法的 UTF-8（按规范显式解码就能看到中文），
    但 Spring 的 `StringHttpMessageConverter` 对 `text/*` 默认按 **ISO-8859-1** 解 ——
    用 `restTemplate.getForEntity(url, String.class)` 拿 SSE 会得到乱码。
    客户端请按字节取回后显式 `new String(bytes, UTF_8)`（`ObservabilityDemo` 与
    `LlmWebEndpointTest.contractSseCharsetIsNotDeclared` 都是这么做的）；
    若希望服务端声明清楚，需要把端点的 `produces` 改成 `text/event-stream;charset=UTF-8`。

## 断言在哪里

| 测试类 | 用例数 | 钉住什么 |
|---|---|---|
| `MonitorListenerTest` | 12 | 12 个回调、事件顺序、`AgentResult` 全字段、工具失败的 `error=true` |
| `ObservingInterceptorTest` | 23 | 9 个钩子、`order` 顺序、`beforeTool` 短路/放行、模型改写、`onError`、`@LlmGuard` 范围 |
| `ListenerFanoutTest` | 7 | 监听器叠加、内置让位、`CompositeListener.of` / `noop()`、异常隔离 |
| `StreamingTest` | 14 | `LlmStreamHandler` 7 回调、`StreamCollector` 聚合、`ofText`、监听器 vs handler 对照 |
| `LlmWebEndpointTest` | 20 | 五个端点 + 契约表逐条断言（404 / sessionId / 工具继承 / 方法级注解 / SSE 事件集 / charset）|
| `LlmWebBasePathTest` | 2 | `llm.web.base-path` 自定义（新路径可用、旧路径 404）|
