# exp-01 · 声明式 Agent

> 一个接口 + 一个注解 = 可注入的 Agent。调用方看不到任何 LLM API。

本模块只做一件事：把 `@LlmAgent` 这条路径拆开揉碎演示一遍。
**默认离线、零 token**：模型来自 `exp-common` 的脚本化模型（`ScriptedLlmModel`），
`mvn spring-boot:run` 立刻能看到完整输出，`mvn test` 却能断言到框架内部的口径。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| `@EnableLlmAgents` | **必写**的开关；不写 `basePackages` 时扫描范围从主应用包推断 |
| `@LlmAgent` 属性 | `value` / `name` / `model` / `loop` / `tools` / `toolNames` / `systemPrompt` / `maxSteps` / `temperature` / `maxTokens` / `memory` |
| 参数注解 | 无注解（默认 user）、`@User(prefix/suffix)`、`@Assistant`（预填充）、`@Ctx(value, attribute)`、`@Memory`、`LlmStreamHandler` |
| 方法级 `@SystemPrompt` | `value` 插值 `{变量}`、`append`、`inherit = false` 的差别 |
| 返回类型全谱 | `String` / `AgentResult` / `Optional<String>` / `List<ChatMessage>` / `int` / `long` / `boolean` / `void` / 任意 DTO |
| 记忆与会话 | `@Memory` 的 sessionId 语义、`history()` / `clearHistory()` / `AgentSession` |
| 装配可观测 | `AgentRegistry`（含惰性条目）、`LlmAgentFactory.specOf()`、`ModelRegistry`、`LoopRegistry` |

## 三个 Agent 的分工

| 接口 | 演示重点 |
|---|---|
| `BasicAssistant` | 注解全属性 + 五种参数语义 + 全部返回类型（一个接口看完全部声明式能力） |
| `WeatherAssistant` | `tools = {类}` 按来源类整包拿工具 |
| `TimeAssistant` | `toolNames = {名字}` 精确点名 + `memory = false` |
| `PlainAssistant` | **一个属性都不写**：名字从接口名推断，其余回落到 `llm.agent.*` |

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-01-declarative-agent
mvn -o spring-boot:run            # 启动即打印 9 组演示
mvn -o test                       # 20 个离线用例，零 token
```

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8。

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论：

1. **`@EnableLlmAgents` 不能省。** starter 里没有任何自动配置会替你扫描 `@LlmAgent` 接口，
   不写就是一个接口都不会注册，注入直接抛 `NoSuchBeanDefinitionException`。
2. **不写 `tools` / `toolNames` ≠ 没有工具。** 它等于**继承容器的全局工具表** ——
   本模块里 `BasicAssistant` 什么都没声明，却拿到了 `CommonTools` 的全部 4 个工具。
   想收紧请显式列出 `tools` 或 `toolNames`。
3. **`@Ctx` 只做两件事**：渲染 `@SystemPrompt` 里的 `{变量}`，以及（`attribute = true` 时）
   写进运行时属性。它**不会**进消息体 —— 测试里直接断言了这一点。
4. **`memory` 默认就是 `true`**，且不传 sessionId 时所有调用都落在 `<agentName>-default` 这个会话上。
   所以"同一个 Agent 连着问两句"会自然续上上下文；不想要就写 `memory = false`（见 `TimeAssistant`）。
5. **DTO 返回是"反序列化模型输出的 JSON"**，不是"让模型输出 JSON"。
   提示词里没要求 JSON 时，模型回一段散文会让这个方法抛
   `LlmAgentInvocationException`（消息里带模型原始输出），而不是静默返回 `null`。
6. **`Optional<String>` 在"模型什么都没说"时给 `empty`**，因此 `orElse(兜底)` 真的能兜住。
7. **注解里没写的属性回落到 `llm.agent.*`**（`default-loop` / `max-steps` / `memory` / `temperature` …），
   `hard-max-steps` 还会把 `maxSteps` 夹住：`min(注解值, hard-max-steps)`。
8. **声明式接口不支持 `default` 方法**：JDK 动态代理转发 default 方法需要额外的 InvocationHandler 支持，
   框架选择显式报错而不是静默失效。

## 目录

```
src/main/java/com/benxin/exp/declarative/
├── DeclarativeAgentApplication.java     @SpringBootApplication + @EnableLlmAgents
├── agent/                              4 个 @LlmAgent 接口（本模块的主角）
├── config/OfflineModelConfiguration.java  离线模型 + 工具来源
├── dto/ReviewReport.java                DTO 返回值
└── demo/DeclarativeDemo.java            9 组演示（ApplicationRunner，启动即跑）
src/test/java/com/benxin/exp/declarative/
└── DeclarativeAgentTest.java            20 个断言，把演示里的每条结论钉住
```
