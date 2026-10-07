# exp-12 · 本地小模型（LM Studio / Qwen3-VL-8B）

> 前面 11 个模块要么跑在离线脚本模型上，要么跑在云端。这个模块把同一批 Agent 代码接到
> **本机的 LM Studio** 上：**不花钱、不出网**，却能验证真实对话、真实 function calling、
> 真实流式，以及 exp-07 只能在报文层验证的**真实视觉（多模态）**。

---

## 一、前置条件

1. 装好 [LM Studio](https://lmstudio.ai/)，加载 **`qwen/qwen3-vl-8b`**（视觉模型）；
2. 打开 **Local Server**（默认 `http://127.0.0.1:1234`）；
3. 确认端口可访问：

```bash
curl http://127.0.0.1:1234/v1/models
```

没装 / 没开也**不影响**本模块：`exp.local.mode=auto`（默认）会探测端点，
不可达时只跑离线演示并打印原因；测试里的真实调用用例会 **skip 而不是 fail**。

---

## 二、怎么跑

```bash
cd exp-12-local-lmstudio
mvn -o test                        # 20 个用例：13 个离线 + 7 个真实（端点不可达时自动 skip）
mvn -o spring-boot:run             # 启动即跑 9 组演示
```

**三种开关**（`exp.local.mode`）：

| 模式 | 行为 | 用在哪 |
|---|---|---|
| `auto`（默认） | 探测 `/v1/models`：可达 → 跑真实演示；不可达 → 只跑离线演示并打印原因 | 日常 |
| `on` | 强制真实演示；端点不可达时**明确报错**（不静默跳过） | "我就是要测它" |
| `off` | 强制离线，连探测都不做 | CI |

```bash
# 换模型（例如用 4B 版做速度对照）
java -DLOCAL_MODEL_ID=qwen/qwen3-vl-4b -jar target/exp-12-local-lmstudio-1.0.0.jar

# 强制模式
java -jar target/exp-12-local-lmstudio-1.0.0.jar --exp.local.mode=off
```

---

## 三、覆盖的功能

| # | 演示 | 断言了什么 |
|---|---|---|
| 1 | **离线兜底** | 同一批 Agent 接口在 `mock` 上跑通；四个接口都**没有写 `model` 属性**（`specOf(...).model() == null`） |
| 2 | **真实对话** | 非空回答 + 用量 + `modelName() == "local-lmstudio"`（证明确实走了本机端点而不是 mock） |
| 3 | **真实 function calling** | `toolCalls()` 里出现 `now`、`steps() >= 2`、工具被真实执行、结果回灌后收尾 |
| 4 | **真实流式** | 增量拼起来**等于**返回值；`onStart` 恰好一次；首 token 延迟（实测 ≈230 ms） |
| 5 | **真实视觉** | 运行时生成的图：模型输出必须同时包含颜色「红」与数字「42」 |
| 6 | **真实多轮记忆** | 第二问不复述代号也能答出「青竹」；历史 4 条 |
| 7 | **真实 Loop 对照** | 两个副本只差 `loop`，`dsh-minimal` 与 `react` 都能跑通 |
| 8 | **装配视图** | 端点来自 yml、模型来自 bean、业务代码零改动的三段对照 |
| 9 | **用量统计** | 逐次运行表 + 工具调用次数 + 合计 token（本地推理免费，但值得量） |

> 真实调用的用量实测（`qwen/qwen3-vl-8b`，temperature=0）：
> 一次短问答 ≈ 250–280 tokens；带两轮工具调用 ≈ 600 tokens；
> 视觉提问 ≈ 470 tokens（图片会被切成视觉 token，所以输入明显更大）。

---

## 四、这个模块独有的三个价值

### 1. 真实视觉（exp-07 只能在报文层验证的那条）

`ImagePart` 在 exp-07 里只被断言到"报文里确实有图"，**图能不能被看懂**那里测不了。
这里用一张**运行时生成、内容完全已知**的图（`TestImageFactory`：640×320、白底 + 红圆 + 黑字 42），
于是"模型答对了"是可判定的：

```java
ChatMessage message = ChatMessage.user(
        new TextPart(TestImageFactory.question()),
        ImagePart.ofBase64("image/png", TestImageFactory.base64()));
AgentResult result = agent.call(List.of(message));
assertThat(result.text()).contains("42").contains("红");
```

顺带说明一条口径：**多模态要走 Agent API**。声明式接口（`@LlmAgent`）的参数只支持
"文本 / 上下文变量"（见 `LlmAgentInvocationHandler` 的参数绑定），发图必须自己组装
`ChatMessage`。这不是缺陷，是声明式接口的边界，写在这里免得白找。

### 2. "可选依赖"的正确姿态

本机端点再近也是**外部依赖**。所以：`auto` 探测 + `on` 强制 + `off` 关闭；
演示打印"这次为什么不跑真实调用"；测试用 `Assumptions.assumeTrue` 跳过而不是失败。
CI 上没有桌面环境，本地开发者机器上有 —— 同一套代码必须能同时服务这两种场景。

### 3. 顺带挖出两个 `toBuilder()` 的坑（**1.1.1 已修复**，见 `FINDINGS.md` 问题 30 / 31）

写这个模块时踩到的，两个都不显而易见 —— 而且第一个曾经**伪造出一片绿色**：

* 1.1.0：`agent.toBuilder().model("名字")` / `.loop("名字")` **静默无效**，
  副本继续用原模型/原 Loop。（我最初就是按直觉这么写的，测试跑出来"真实模型用例全绿"，
  其实**一次都没打到 LM Studio**，全在离线 mock 上跑的。）
* 1.1.0：`agent.toBuilder()` **不携带监听器** —— 派生副本的运行不进任何统计，而且会**层层丢失**。

**在 1.1.1 上这两件事都修好了**，所以现在的写法就是最自然的那种：

```java
Agent local = agentRegistry.get("localAssistant").toBuilder()
        .model("local-lmstudio")     // 1.1.1 起按名字即可（实例重载同样可用，后来者胜）
        .loop("react")               // 同上
        .build();                    // 监听器自动继承 —— 不要再补 .listener(...)，会重复计数
```

本模块把"修好"这件事也钉成了断言：`OfflinePathTest.nameOverloadsWorkOnDerivedBuilder`
与 `derivedAgentKeepsListeners`（含"再派生一层也不丢"）。
在 1.1.0 上这两条会失败、旧的两条（断言"静默无效"）会通过 —— 一个模块同时记录了修复前后两种行为。

---

## 五、目录

```
src/main/java/com/benxin/exp/local/
├── LocalLmStudioApplication.java        默认离线也能启动
├── config/LocalModelGate.java           模式（auto/on/off）+ 端点探测 + 可读判定说明
├── config/OfflineModelConfiguration.java 离线兜底模型 + 工具 bean
├── config/LocalCallMetrics.java         用量与工具调用统计（AgentListener）
├── agent/LocalAgents.java               4 个 @LlmAgent 接口（都没写 model 属性）
├── tool/LocalTools.java                 now / add（本地演示用的极小工具）
├── vision/TestImageFactory.java         运行时生成"内容已知"的测试图
└── demo/LocalLmStudioDemo.java          9 组演示
src/main/resources/application.yml       离线默认 + 本机端点注册（注册 ≠ 调用）
src/test/java/com/benxin/exp/local/
├── OfflinePathTest.java                 13 个离线用例（含 3 个陷阱的钉子）
└── LocalLmStudioTest.java               7 个真实用例（端点不可达时 skip）
```

---

## 六、实测观察（都写成了断言或注释）

| 观察 | 说明 |
|---|---|
| `qwen3-vl-8b` 支持 **function calling** | 用 `tools` 声明后会返回标准 `tool_calls`，`finish_reason=tool_calls`；工具结果回灌后能正确收尾 |
| **小模型的分辨率下限** | 320×160 / 64px 时它偶发把 `42` 读成 `12`；放大到 640×320 / 150px 后连测多次全对。换模型时先怀疑分辨率，再怀疑插件 |
| 提问方式影响遵从度 | 把"看颜色"和"读数字"合在一句里问（"只回答「颜色, 数字」"）比开放式"图里有什么"稳定得多 |
| 首 token 延迟 ≈ 230 ms | 本地 8B 在消费级显卡上的典型值；模型**首次加载**会额外等几秒到几十秒（本模块读超时给到 5 分钟） |
| 本地不花钱，但用量仍要量 | 一次短问答 ≈ 260 tokens；视觉提问 ≈ 470 tokens —— 这是判断"这个模型能不能胜任"的第一手数据 |
