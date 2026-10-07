# exp-04 · Agent Loop

> 8 个内置 Loop + 自定义 Loop + 子代理：每个 Loop 都真跑一遍，并钉住它「实际做了什么」。

本模块只做一件事：把「一次 Agent 运行到底是怎么被推进的」拆开揉碎演示一遍。
**默认离线、零 token**：模型来自 `exp-common` 的 `ScriptedLlmModel`（少数几处见下文），
`mvn -o spring-boot:run` 立刻能看到完整输出，`mvn -o test` 却能断言到框架内部的口径。

## 8 个内置 Loop 对照表

| Loop | 触发条件 | 特征 | 本模块断言了什么 |
|---|---|---|---|
| `dsh-minimal` | 名字点名或回落默认 | 最小骨架：调模型 → 执行工具 → 回灌，直到模型不再请求工具。零内置提示词、零压缩 | 工具闭环成立（`toolCalls` 有记录、`steps ≥ 2`、token 为正），且**没有** `benxin.claude-code.*` / `benxin.codex.*` 这类属性 |
| `react` | 同上（`loop = "react"`） | 模型支持 function calling 时走结构化工具调用；**不支持时自动降级**为 `Action / Action Input / Final Answer` 文本协议 | 原生路径：工具被执行 1 次且 `error = false`；降级路径：历史里出现完整的格式说明（断言原文）、最终答案取自 `Final Answer:` 行、工具结果以 `Observation:` 回灌 |
| `claude-code` | 同上 | 单一主循环 + 待办清单 + 上下文压缩 + 子代理 + **连续工具错误熔断（提醒而非停机）** | 首轮写 `benxin.claude-code.initialized/.cwd`；`todo_write` 真的把清单写进 `benxin.todos`；连续 3 次工具失败后历史里出现「停下来重新评估」而进程不中断 |
| `codex` | 同上 | turn 制：输入 > 80 字符先出计划 → 最小改动 → 必须验证；权限边界如实告知 | `benxin.codex.sandboxWriteEnabled/execEnabled/verified` 与构造参数一致；`verificationNote` 如实写明「工具集中没有命令类工具」；`apply_patch` 补丁**真的落盘** |
| `plan-execute` | 同上 | 先规划后执行：第一步产出编号计划，逐步推进并回显进度，最后追加一轮汇总 | `benxin.plan` 被解析成 3 步；历史里有「已完成 N/M」进度回显与汇总提示词；`maxStepsReached = false` |
| `reflexion` | 同上 | 执行 → 自省评分 → 不满意就带批评重做，最多 N 轮 | 自省提示词（「严格按下面两行输出」）与「低于达标线」重做提示都在历史里；`attempts/score/exhausted` 三个属性齐全；**收敛形态是 EXHAUSTED，不是「第二轮达标」**（见下文口径 6） |
| `staged` | 同上 | 阶段机：PLAN → EXECUTE → **VERIFY** → （REPAIR）→ SYNTHESIZE，汇总前强制验收 | 一次通过：`stage = synthesize`、`verdict = pass`、`repair-rounds` 键不存在；验收失败：`verdict = fail`、`repair-rounds = 1`，且「作为验收者」的验收请求被发出**两次** |
| `workflow` | 需要 `llm.workflows.*` 定义 | 声明式工作流：图由外部 YAML/JSON 定义，分支条件由表达式决定 | **本模块只点名一行**（`loopRegistry.get("workflow")` 能取到、description 正确、名字在 `BuiltinLoops.NAMES` 里），节点编排由 exp-05 专门演示 |

除内置的 8 个之外，本模块还注册了 4 个**变体 Loop**（同一个 Loop 类 + 不同构造参数、换个注册名并存）：
`exp-reflexion-strict`（阈值 0.9）、`exp-staged-no-verify`（关掉验收闸门）、
`exp-staged-lenient`（允许修 3 轮）、`exp-codex-unrestricted`（写/执行全开、审批 never），
以及 1 个自定义 Loop `exp-decorate`（`@LlmLoop` + `LoopContext` 全能力 + `SubAgentObserver`）。

## 子代理的三种装配方式

| 方式 | 怎么用 | 本模块的断言 |
|---|---|---|
| ① 注解 + 内置 `task` 工具 | `@LlmAgent(subAgents = {...})`，配合 `llm.tools.enabled: [task]` | 父侧 `toolCalls` 里有 `task`；子代理的 token 被补记进父的 `usage()`；`AgentListener.onSubAgentStart/End` 各回调一次；父代理拿到子代理结论后收尾（`maxStepsReached = false`） |
| ② 程序化 `AgentBuilder.subAgent(name, agent)` | 代码里直接持有子代理实例 | 父代理能派生；与 ① 的唯一差别是「子代理实例从哪来」 |
| ③ `ToolContext.spawn` | 在自己的工具里派生（`SubAgentProbeTools.delegateTask`），可额外带一份运行属性给子代理 | 工具返回 `error = false`；`spawnLog` 里有父代理名与透传的 trace；`SubAgentObserver` 记到 start + end 两条 |

对照组：工具在、但 Agent **没有**声明 `subAgents` 时，`TaskTool`/`spawn` 返回一条可读的工具错误
（`error = true`，消息里带「未配置子代理能力」），不抛异常、也不假装成功。

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-04-loops
mvn -o spring-boot:run                     # 启动即打印 24 组演示
mvn -o test                                # 37 个离线用例，零 token
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-04-loops-1.0.0.jar
```

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8（或像上面那样给 `java` 加
`-Dstdout.encoding=UTF-8`）。

演示会在 `target/sandbox/` 下创建 `codex-patch-demo.txt`（`llm.tools.workdir` 的围栏目录）；
`mvn clean` 会把它连同 `target/` 一起删掉，重建即可。

## 几条"按直觉猜会猜错"的口径

这些都是本模块**实测**出来、并写进测试的结论：

1. **`@LlmAgent(model = ...)` 匹配的是「模型注册名」，不是「Spring bean 名」。**
   所以本模块的 `mock-todo-aware` 是一个**显式可见的模型别名**，不是「第 11 个模型」：
   它等于 `mock` 外面套了一层只补 `todo_write` 参数的装饰器（原因见口径 7）。
   两者同名会让 `llmModelRegistry` 对同一个名字注册两次，谁胜出取决于 bean 的遍历顺序。

2. **`task` 不在内置工具的默认基线里。** `BuiltinToolkit.READ_ONLY_TOOLS` 只有
   `read / glob / grep`；`todo_write` 与 `task` 都要靠 `llm.tools.enabled` 显式点名。
   不点名的话 Agent 的工具清单里根本没有 `task`，父代理永远派不出子代理 ——
   而失败方式很安静：模型只是「没有工具可用」而已。

3. **`llm.tools.allow-write` 打开的是沙箱闸门，不是「内置 write/edit 工具」。**
   但这两件事共用同一个开关：`BuiltinToolkit.activeToolNames()` 一旦看到 `allowWrite` 为真
   就会**无条件**把 `write / edit` 加进活跃集合（`enabled` 是叠加，不是白名单）。
   本模块为了让它自带的 `apply_patch` 能落盘而必须打开写闸门，代价是工具清单里多了
   `write / edit`；想只开闸门不放工具，只能自己包一层
   `BuiltinToolkit.Builder.disable("write", "edit")` —— **纯配置做不到**。

4. **`SandboxConfig` 不是 bean，按类型注入必然失败。**
   `llm.tools.*` 配出来的那个 `SandboxConfig` 只在 starter 内部现算现用；
   容器里真正装进每个 Agent 的是 `ToolSandbox`。要拿"配置 → 沙箱"请走公开通道
   `LlmBuiltinToolRegistrar.toSandboxConfig(properties.getTools())`。

5. **真正拦住工具执行的是 Agent 的 `ToolSandbox`，不是工具自己持有的 `SandboxConfig`。**
   `apply_patch` 手里那份配置只用来算围栏根目录；写闸门由 Agent 侧把关。
   实测 `allow-write: false` 时报的是「沙箱拒绝执行: 工具 [apply_patch] 会修改本地文件」。

6. **脚本模型每轮自省回同一个分数，所以 `reflexion` 的终点是 `exhausted` 而不是「达标」。**
   `ScriptedLlmModel` 只有一个 `reflexion(score, critique)` 配置项，做不到「两轮给不同分」；
   本模块取 0.85、达标线取 0.9，于是「重做」真的发生了、「达标」永远不会发生。
   同理 `staged` 的第二次验收仍然是 `fail`：验收应答是固定的「结论：不通过」。
   想看「第二轮达标 / 修完通过」，需要模型两轮给出不同结论 —— 脚本模型给不了。

7. **离线脚本模型的 `#tool:` 语法装不下「对象数组」。**
   它的强制工具正则是 `#tool:(名字)\s*(\{.*?})?` —— 末尾那个 `}` 是**普通字符**而不是量词，
   于是整个捕获**必须以一个 `}` 收尾**，而 `.*?` 又是惰性的。两者一夹，
   `#tool:todo_write {"todos":[{...}]}` 会被截成 `{"todos":[{...}`（少一层 `]`），
   `TodoWriteTool` 因此永远只回「参数 todos 必填」、`benxin.todos` 永远写不进去。
   本模块用一个只补这一个工具参数的装饰器（`TodoAwareModel`）让这条链路真跑起来，
   并且**一次只写一项待办** —— 多对象形态丢的是内容，任何"补"都补不回来。
   详见 `TodoAwareModel` 的类注释。

8. **`ScriptedLlmModel` 的求值顺序里，使用者规则排在「有工具结果就汇总」之前。**
   于是无条件写一条「父侧收到工具结果就收尾」的 `systemRule`，会在**第一轮**就命中、
   直接吐出最终答案 —— 派生永远不会发生，而运行结果看起来还挺正常。
   本模块因此把「先派生、后收尾」的父/子脚本写成了自己的模型
   （`SubAgentScriptModel`），让决策顺序变成「先看历史里有没有工具结果」。

9. **`CodexLoop` 不会从回复正文里解析补丁。** 它只有 `runToolCallingLoop → executeTools`
   一条执行路径，补丁必须以 `apply_patch` **工具调用**的形式出现。
   只吐文本时文件不会被创建，而模型输出看起来完全正常 —— 一种很难发现的假成功。

10. **变体 Loop 与内置 Loop 没有继承关系。** `exp-codex-unrestricted` 类型上是
    `AbstractAgentLoop`，**不是** `CodexLoop`（`LoopVariants` 用「包一层」的方式做变体，
    否则注册名会退化成内置名并顶掉内置版）。按具体类强转会抛 `ClassCastException`。

11. **`@LlmLoop(defaultLoop = true)` 与 `@LlmLoop(order = ...)` 都不可达 / 不被消费。**
    见下节「插件问题」，这是本模块唯一两处需要如实标注的框架不一致。

12. **`AgentResult.attributes()` 里没有 `benxin.systemPrompt`。**
    系统提示词走 `ChatRequest.systemText()`（给模型看的那一份），不写进运行属性 ——
    这一点与 `benxin.sessionId` / `benxin.agentName` 是同一类问题（常量定义了、没有写入点）。
    真实的会话与 Agent 名请用 `AgentResult.sessionId()` / `.agentName()`。

## 插件问题

本模块实测到 3 处「文档说的」与「代码做的」不一致。**没有修改插件**，测试断言的是真实行为。

### 问题 1 · `@LlmLoop(defaultLoop = true)` 这条分支实际不可达

- **文档**：`LlmAutoConfiguration.llmLoopRegistry` 的选举顺序被描述为
  「`llm.agent.default-loop` 命中则用它；否则取第一个 `@LlmLoop(defaultLoop = true)` 的；
  否则回落 `dsh-minimal`」。
- **实际**：`LlmProperties.AgentProperties.defaultLoop` 的**字段初值就是 `"dsh-minimal"`**
  （不是 `null`），而 `dsh-minimal` 又是内置 Loop —— 于是"配置里写了"这个条件**永远成立**，
  第二、三个分支一次都走不到。
- **证据**：
  - `benxin-spring-boot-starter/.../LlmProperties.java:295` —— `private String defaultLoop = "dsh-minimal";`
  - `benxin-spring-boot-starter/.../LlmAutoConfiguration.java:203-210` ——
    `if (configured != null && registry.contains(configured)) { … } else if (annotatedDefault != null) { … }`
  - **复现**：`DefaultLoopAnnotationTest` 用 `ApplicationContextRunner` 只装
    `LlmAutoConfiguration` + 一个 `@LlmLoop(value = "exp-annotated-default", defaultLoop = true)`
    的 bean（**不加载 `application.yml`**），断言 `registry.defaultName()`。
  - **实际 vs 预期**：`dsh-minimal` vs `exp-annotated-default`。
    注解确实被读到了（`registry.names()` 里有它、`targetClass.getAnnotation(LlmLoop.class)` 也拿得到），
    只是选举路径上永远轮不到它。

### 问题 2 · `@LlmLoop(order = ...)` 完全没有被消费

- **文档**：`LlmLoop.order()` 的 Javadoc 写着「同时存在多个时按 order 取最小者」。
- **实际**：实现里 `if (annotation != null && annotation.defaultLoop() && annotatedDefault == null)`
  取的是 `loopBeans.orderedStream()` 里**第一个** `defaultLoop = true` 的 bean，
  `order()` 一个字符都没读。全仓检索 `.order()` 只命中 `SelectiveInterceptor`
  （那是 `AgentInterceptor` 的 order，与 Loop 无关）。
- **证据**：
  - `benxin-core/.../annotation/LlmLoop.java:26-30`（文档）vs
    `benxin-spring-boot-starter/.../LlmAutoConfiguration.java:191-199`（实现）
  - `grep -rn "\.order()" benxin/` → 仅 `SelectiveInterceptor.java:33`
  - **复现**：`DefaultLoopAnnotationTest.orderIsNeverConsumed` 注册两个探针
    （`order = 10` 与 `order = 0`，都是 `defaultLoop = true`），断言默认 Loop 不是 order 更小的那个。
  - **实际 vs 预期**：默认仍是 `dsh-minimal`（受问题 1 影响）；
    把属性显式写成注解名之后也证明 `order` 不参与 —— 写成哪个名字就用哪个，
    与 `order` 的大小无关。

### 问题 3 · `AgentAttributes` 里三个键没有任何写入点

- **文档**：`AgentAttributes` 定义了 6 个"框架级运行属性"常量。
- **实际**：`SYSTEM_PROMPT` / `SESSION_ID` / `AGENT_NAME` 三个键在框架内部**没有任何写入点**，
  `AgentResult.attributes()` 里永远查不到它们。真实值走 `ChatRequest.systemText()` 与
  `AgentResult.sessionId()` / `.agentName()`。
- **证据**：
  - `benxin-core/.../agent/AgentAttributes.java:12/24/27`（常量定义）
  - **复现**：`LoopsTest.sessionAndAgentNameKeysAreNeverWritten` 与
    `LoopsTest.agentAttributesAreWritten`，对 `genericAgent` 的一次真实运行断言这三个键
    `doesNotContainKey`。
  - **实际 vs 预期**：三个键都不存在 vs 文档暗示它们会被写入。

> 说明：本模块**没有**把「`order()` 不生效」当成"猜错了插件行为"。
> 另外几处（`maxStepsReached()`、attributes 键名、`SubAgentObserver` 的事件条数）
> 确实是一开始猜错了语义，已经按实测改正，不算插件问题。

## 目录

```
src/main/java/com/benxin/exp/loops/
├── LoopsApplication.java                @SpringBootApplication + @EnableLlmAgents
├── agent/                               18 个 @LlmAgent 接口（每个 Loop 一个/一组）
├── config/
│   ├── OfflineModelConfiguration.java   模型装配 + 演示用输入常量（含"为什么这么写"的长注释）
│   ├── ToolConfiguration.java           本模块的工具与 AgentListener
│   ├── SubAgentAuditListener.java       AgentListener：记录每一次子代理派生
│   ├── TodoAwareModel.java              装饰器：补 todo_write 被截断的参数（口径 7）
│   ├── SubAgentScriptModel.java         父/子脚本：先派生、后收尾（口径 8）
│   ├── CodexPatchScriptModel.java       把补丁当工具参数交出去（口径 9）
│   └── BudgetProbeModel.java            永不收尾的模型，用来烧完步数预算
├── loop/
│   ├── DecorateLoop.java                自定义 Loop：@LlmLoop + LoopContext 全能力 + SubAgentObserver
│   ├── LoopVariants.java                4 个变体 Loop（包一层换注册名）
│   └── LoopConfiguration.java           变体 Loop 的 @Bean
├── tool/                                apply_patch / delegate_task / flaky_query
└── demo/                                13 个演示器 + LoopReport（24 组演示，启动即跑）
src/test/java/com/benxin/exp/loops/
├── LoopsTest.java                       30 个用例，覆盖全部 Loop / 子代理 / 注册表 / 预算 / 属性
└── DefaultLoopAnnotationTest.java       7 个用例，独立上下文验证默认 Loop 选举（含上面 3 条问题）
```
