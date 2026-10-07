# exp-11 · 真实 DeepSeek 联调

> 前面 10 个模块都跑在离线模型上；这个模块把**同一批代码**接到真实的 DeepSeek 端点上，
> 用最小代价证明"换了模型，业务代码一行都不用改"。

**默认不联网**：不开开关时它跑的是 `exp-common` 的脚本模型，
`mvn -o test` 永远离线通过（真实调用类测试默认 **skip**，不是 fail）。

---

## 一、怎么打开真实调用

```bash
cd exp-11-real-deepseek
mvn -o package -DskipTests

# ① 显式传 key（本机推荐：Windows 用户级环境变量不一定被子进程继承）
java -Dstdout.encoding=UTF-8 -Dexp.real.enabled=true ^
     -Ddeepseek.api-key=sk-xxxx ^
     -Dspring.profiles.active=real ^
     -jar target/exp-11-real-deepseek-1.0.0.jar
```

key 的取值是**三级兜底**（`config/ApiKeyResolver`）：

| 顺序 | 来源 | 说明 |
|---|---|---|
| ① | 环境变量 `DEEPSEEK_API_KEY` | 本机就放在这里（Windows 用户级变量） |
| ② | JVM 属性 `-Ddeepseek.api-key=...` | 临时联调最常用；**显式传的应当覆盖环境变量** |
| ③ | 环境变量 `DEEPSEEK_KEY` | CI 里的常见命名 |

取不到 key 时：**不联网、不抛异常**，只打印一句"未取到（不联网）"然后跳过真实演示。
`ApiKeyResolver` 对外只暴露"有没有取到 / 长度 / 来源"，**从不打印 key 内容**；
落盘的报文里 `Authorization` 也会被脱敏成 `Bearer sk-****`（演示里有一条断言专门验证"文件里不含真实 key"）。

---

## 二、Token 消耗：实测数据

抠 token 的四条硬约束（都写在 `application-real.yml` 里）：

* `max-tokens: 48`（每次回复上限）
* `max-retries: 0`（**一次调用 = 一笔请求**，限流时不会悄悄多花钱）
* `temperature: 0`（可复现）
* 每个演示只调一次模型，提示词尽量短

**一次完整演示的实测用量**（`target/wire-log/` 里有对应的报文留档）：

| # | 演示 | 步 | 输入 | 输出 | 合计 |
|---|---|---|---|---|---|
| 1 | 最小真实对话 | 1 | 254 | 5 | **259** |
| 2 | 工具调用闭环（请求工具） | 1 | 272 | 20 | **292** |
| 3 | 工具调用闭环（收尾） | 2 | 309 | 9 | **318** |
| 4 | 真实流式 SSE | 1 | 255 | 1 | **256** |
| 5 | 协议对照（Chat Completions） | 1 | 15 | 1 | **16** |
| 6 | 协议对照（Responses） | 1 | 15 | 1 | **16** |
| 7 | 多轮记忆（第一问） | 1 | 259 | 1 | **260** |
| 8 | 多轮记忆（第二问，含历史） | 1 | 270 | 1 | **271** |
| 9 | Loop 对照（dsh-minimal） | 1 | 260 | 1 | **261** |
| 10 | Loop 对照（react） | 1 | 260 | 1 | **261** |
| — | **合计** | — | **2169** | **41** | **2210 tokens** |

另外还有 1 笔"免费"请求：错误路径用的是**故意写错的 key**（`deepseek-badkey`），
上游直接 401 拒绝 —— **0 token** 就演示完了鉴权失败与 `ModelException.statusCode()`。

> 这个开销只在你**显式打开开关**时才会发生。日常 `mvn -o test` 与其它 10 个模块一样是零消耗。

---

## 三、覆盖的功能

| 演示 | 内容 |
|---|---|
| 1 | **离线兜底**：同一个 Agent 接口在 `exp.real.enabled=false` 时用脚本模型跑，证明"换模型不改业务代码"（`specOf(TinyAssistant).model() == null`，模型完全由 `llm.default-model` 决定） |
| 2 | **最小真实对话**：`AgentResult` 每个字段（steps / usage / duration / finishReason） |
| 3 | **一次调用 = 一笔请求**：`max-retries: 0` 时可观测地一一对应（`HttpLlmModel` 没有被 `RetryingLlmModel` 包裹） |
| 4 | **真实工具调用闭环**：tools 声明 → `tool_calls` 解析 → 本地工具执行 → 结果回灌 → 收尾（断言 `toolCalls()` 非空且 `steps() == 2`） |
| 5 | **真实流式（SSE）**：`onStart` / `onTextDelta` / `onUsage` 的到达时刻与首 token 延迟 |
| 6 | **Responses 协议对照**：同一个问题在两套报文下的请求/响应顶层字段逐一对照（`instructions` vs `messages[].system`、`input[]` vs `messages[]`、扁平 `tools[]`、`max_output_tokens`、`status`） |
| 7 | **多轮记忆**：同一 sessionId 连续两问（第二问不复述前文），合计 2 次调用 |
| 8 | **Loop 对照**：`dsh-minimal` vs `react` 在同一极简任务上的 steps / usage / 回答 |
| 9 | **错误路径**：假 key → `ModelException` + `statusCode() == 401`，**零 token** |
| 10 | **报文留档**：`WireLogTransport`（自定义 `HttpTransport` 装饰器）把 URL / 脱敏头 / 请求体 / 响应体写进 `target/wire-log/` |
| 11 | **用量汇总**：逐次 token 表 + 汇总 + "是否超预算"的自检 |

---

## 四、真实端点上的观察（实测）

1. **`deepseek-chat` 当前解析到 `deepseek-flash`**：请求里下发 `model: deepseek-chat`，
   响应里的 `model` 字段是 `deepseek-flash`。本模块的所有断言都不写死上游模型名。
2. **`protocol: responses` 在 DeepSeek 上可用**：`base-url: https://api.deepseek.com/responses`
   会被 `ResponsesApiCodec#endpoint` **原样使用**（因为它已经以 `/responses` 结尾）；
   写 `https://api.deepseek.com` 也会被补成 `/v1/responses`，两种写法都实测通过。
3. **声明式路径本来就是流式的**：只要模型声明了 `streaming` 能力，`DefaultLoopContext` 调的就是
   `stream()`；方法上多一个 `LlmStreamHandler` 参数只是把增量**接到手上**，
   并不是"额外打开了一个流式模式"。（另见 `FINDINGS.md` 问题 2：`@LlmAgent(stream=true)` 是死配置。）
4. **记忆不是免费的**：第二问的输入 token 比第一问多，多出来的正是历史。
   所以本模块每一步都显式指定 sessionId，绝不共用默认会话。
5. **鉴权失败的报文也值得留档**：401 的响应体里能看到上游的错误信封格式，
   而它**不消耗 token** —— 错误处理演示就该用这种免费方式做。

---

## 五、目录

```
src/main/java/com/benxin/exp/real/
├── RealDeepSeekApplication.java         不联网也能启动（默认 profile 只有离线模型）
├── config/ApiKeyResolver.java           key 三级兜底 + 桥接成系统属性（不打印内容）
├── config/RealCallGate.java             真实调用开关（yml / profile / -D 三处汇聚）
├── config/RealCallMetrics.java          次数与用量统计（自定义 AgentListener）
├── config/OfflineModelConfiguration.java 离线脚本模型（默认路径）
├── agent/                               4 个 @LlmAgent 接口（tiny/time/minimalLoop/reactLoop）
├── tool/TinyTools.java                  极小工具（now）
├── wire/WireLogTransport.java           报文留档装饰器（脱敏 Authorization）
├── wire/WireLog.java                    落盘与摘要
└── demo/RealDeepSeekDemo.java           11 组演示
src/main/resources/
├── application.yml                      默认：只有离线模型，不可能误联网
└── application-real.yml                 real profile：三个 DeepSeek 端点 + 抠 token 的配置
src/test/java/com/benxin/exp/real/
├── OfflinePathTest.java                 离线路径（永远跑）
├── DemoDryRunTest.java                 演示装配（永远跑）
├── WireLogTransportTest.java           留档与脱敏（永远跑）
└── RealDeepSeekTest.java               真实调用（默认 skip，需 -Dexp.real.enabled=true）
```
