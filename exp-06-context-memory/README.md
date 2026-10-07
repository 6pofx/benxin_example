# exp-06 · 记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态

> 一次模型调用真正发出去的消息序列是怎么被组装出来的 —— 记忆存在哪、超窗时谁压缩、
> 系统提示词从哪来，以及"这些在插件里全都是可替换的 bean"。

本模块把插件里那五个"平时看不见、却决定成本与效果"的切面逐个换掉自己的实现：
`MemoryStore` / `ContextManager` / `ContextCompactor` / `TokenEstimator` / `SystemPromptProvider`，
再用 `exp-common` 的 `RecordingModel` 把**真正下发的 `ChatRequest`** 留档并断言。
**默认离线、零 token**：模型是脚本化模型（`ScriptedLlmModel`），
`mvn test` 与 `java -jar` 都不联网、不花钱，却完整跑过了压缩、裁剪与多模态的真实路径。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| `MemoryStore` 三种来源 | 默认 `InMemoryMemoryStore`（含 `new InMemoryMemoryStore(容量)` 的 LRU 淘汰）、自定义 `JdbcMemoryStore`（H2 **文件**库、整份历史序列化成 JSON）、`MemoryStore.disabled()` |
| 会话语义 | `@Memory sessionId` 的装载/写回、多轮线性增长、会话隔离、`clearHistory()`、`AgentSession`、不传 sessionId 时的 `<agentName>-default` |
| 关掉记忆的两条路径 | `@LlmAgent(memory = false)`（注解级，影响该接口所有调用）与 `AgentBuilder.memory(false)`（只影响派生出来的这一个实例），以及它们与 `disabled()` 的差别 |
| 自定义 `ContextManager` | `Exp06ContextManager`：注入带来源标记的系统提示词、按窗口裁剪历史、裁剪时保护 `tool_use`/`tool_result` 配对、用默认方法 `afterStep` 做统计 |
| `DefaultContextManager` 构造参数 | `(compactor, estimator, threshold, keepRecent, reservedOutputTokens)` 的真实语义与**夹取规则**（`threshold<=0→0.8`、`keepRecent<=0→1`、`reserved<0→0`） |
| 三种 `ContextCompactor` | `SummarizingCompactor`（**会额外调一次模型**，用 `RecordingModel` 数出来）、`SlidingWindowCompactor`（零成本、纯丢弃）、`ContextCompactor.none()`，外加自定义的 `HardLimitCompactor`（只按条数硬砍） |
| `TokenEstimator` | 默认实现的实际取值、自定义 `Exp06TokenEstimator`（英文 2 字符 1 token）、以及"只换估算器就让压缩决定出现分叉"的行为级证据 |
| `SystemPromptProvider` | 默认 `TemplateSystemPromptProvider` 的插值规则、自定义 `JdbcPromptPlatform`（表驱动 + 热更新 + 回落）、**方法级 `@SystemPrompt` 会完全盖掉 provider** |
| `PromptTemplates` | 内置分段常量、`claudeCode(PromptContext)` / `codex(...)` 的渲染、按"真实存在的工具"挑选提示词条目 |
| 消息与多模态 | `ChatMessage` 六种静态构造 + `builder`、`ContentPart` 全谱（text / thinking / tool_use / tool_result / image）、`text()` 与 `allText()` 的差别、`ImagePart` 内联 base64 与外链 URL、多模态历史经自定义 `MemoryStore` 的 JSON 往返 |
| 上下文 ≠ 记忆 | `ContextManager` 只改"这一轮下发的副本"，`ClaudeCodeLoop` 才会**就地改写历史**（把较早的工具结果换成占位摘要，条数不变） |

## 五个 Agent 的分工

| 接口 | 演示重点 |
|---|---|
| `MemoryAssistant` | `@Memory` 多轮续接 / 隔离 / 历史查询；四种返回类型（`String`、`AgentResult`、`List<ChatMessage>`） |
| `StatelessAssistant` | `@LlmAgent(memory = false)`：传了 sessionId 也不装载、不写回 |
| `CompactAssistant` | `model = "tiny"`（窗口 6000）：把压缩阈值拉到手够得着的高度 |
| `ClaudeCodeAssistant` | `loop = "claude-code"` + `maxSteps = 14`：演示"压缩发生在历史本身" |
| `PromptAssistant` | 系统提示词的三条来源路径：方法级 `@SystemPrompt` / provider（平台）/ 注解兜底 |

三个离线模型也各有分工：`mock`（窗口 64000，日常问答不会触发压缩）、
`tiny`（窗口 6000，压缩演示专用）、`vision`（同一个脚本模型 + 装饰器补上 `vision = true` 的能力位）。

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-06-context-memory
mvn -o spring-boot:run                                   # 启动即打印 11 组演示
mvn -o test                                              # 78 个离线用例，零 token
mvn -o package -DskipTests && java -jar target/exp-06-context-memory-1.0.0.jar
```

H2 文件库固定在 `target/exp06-memory.mv.db`，删掉 `target/` 就等于重置演示数据。
Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8（或用
`java -Dstdout.encoding=UTF-8 -jar ...`）。

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论：

1. **不传 sessionId 时，会话 id 是 `<agentName>-default`**（`DefaultAgent.defaultSessionId()`），
   不是随机 id、也不是空串 —— 所以"连着问两句"会自然续上上下文。
2. **`MemoryStore.disabled()` 与 `memory = false` 效果像，成因不同。**
   前者是"永远返回空、save 不做事"，但 Agent **照样**每轮 `load` / `save` 各一次；
   后者是**这次运行根本不装载、也不写回**（连 `save` 都不调用）。
   测试里用一个"计数版 disabled()"把这件事变成了可断言的数字（2 次 vs 0 次）。
3. **`AgentBuilder.memory(boolean)` 只影响用这个 builder 派生出来的实例**，
   原 Agent 的 `spec.memory()` 不受影响；而 `@LlmAgent(memory = false)` 影响的是该接口的**所有**调用。
4. **`ContextManager.prepare(...)` 只影响这一轮请求，不会缩短 `MemoryStore` 里的历史。**
   窗口裁剪改的是"下发的副本"：记忆里 12 条、这一轮只发 8 条（+1 条系统提示词），
   下一轮那 12 条还会被原样装载回来。想让历史本身收敛，得靠 Loop 层的压缩。
5. **窗口边界要连"本轮输入"一起算。** 跑第 6 轮时，`ContextManager` 手里是
   「记忆 10 条 + 本轮输入 1 条」= 11 条，窗口取最后 8 条 → 被切掉的是**最早 3 条**，
   留下的第一条是第 2 轮的 assistant 回答（不是"从记忆里取最后 8 条"）。
6. **`SummarizingCompactor` 会额外花一次模型调用**：一次 Agent 运行里
   `RecordingModel` 会录到 2 个请求 —— 第 1 个是压缩请求（系统提示词是那段摘要指令），
   第 2 个才是正常请求。`SlidingWindowCompactor` / `none()` 则一次都不会多调。
7. **`threshold` 是"占模型窗口的比例"，且要先把 `reservedOutputTokens` 扣掉**：
   触发线 = `(窗口 − 预留输出) × 阈值`。tiny 窗口 6000、阈值 0.8、预留 4096 → 触发线 1523 token；
   同样一份历史放到 64000 窗口上（触发线 47923）就永远不会压缩 —— 这就是示例里必须有 tiny 模型的原因。
8. **`keepRecent` 是上限不是下限**：它是"压缩时最多留几条"，真正留几条由压缩器自己的预算决定。
9. **自定义 `SystemPromptProvider` 会被方法级 `@SystemPrompt` 完全盖掉**：
   声明式代理把方法级提示词渲染好放进运行属性，`DefaultAgent` 优先取属性值，
   于是容器里的 provider **一次都不会被调用**（平台侧的版本/灰度能力管不到方法级提示词）。
10. **`TokenEstimator` 只被 `ContextManager` 消费，不一定被压缩器消费**：
    插件默认装配的 `new SummarizingCompactor()` 自己拿的是 `TokenEstimator.DEFAULT`，
    只注册一个 `TokenEstimator` bean 并不会换掉它内部的预算口径 ——
    本示例显式把容器里的估算器注入压缩器，才让两者一致。
11. **`ChatMessage.text()` 刻意不含思维链与工具调用**（只拼 `TextPart` 与 `ToolResultPart` 的内容），
    完整调试视图是 `allText()`；工具消息的 `text()` 就是它 `ToolResultPart` 的内容。
12. **本心不做多模态降级**：`capabilities().vision() = false` 的模型照样会收到 `ImagePart`，
    框架不会替你丢图或换成文字描述 —— 该不该带图是使用者的决定。
13. **`toBuilder().model("名字")` 不会换模型**：`toBuilder()` 已经把模型**实例**放进 builder，
    `DefaultAgent` 优先用实例，字符串名字被静默忽略。换模型必须传实例。

## 插件问题（实测发现，未修改插件）

以下都是"文档说的"与"代码做的"不一致，本模块**只改测试断言、不动插件源码**，
并把真实行为钉在测试里。复现环境：`com.benxin:benxin-spring-boot-starter:1.1.0`。

### 1. `SlidingWindowCompactor` 一旦触发压缩，就只保留最后 1 条历史（高危）

- **证据**：`benxin-core/src/main/java/com/benxin/llm/core/context/SlidingWindowCompactor.java:56-63`
  的 `for` 循环把 `start` 同时当成"循环下界"和"结果下标"：
  ```java
  int start = Math.max(0, conversation.size() - keep);
  for (int candidate = conversation.size() - 1; candidate >= start; candidate--) {
      ...
      start = candidate;      // ← 循环体里改写了循环条件用的变量
  }
  ```
  第一轮（`candidate = size - 1`）落下 `start = size - 1` 之后，第二轮就因 `candidate < start` 直接退出，
  于是无论 `keepRecent` 写多少，结果都是"最后 1 条"。
- **复现**：`ContextManagerTest.slidingWindowKeepsOnlyTheLastConversationMessage`
  （6 条 600 字符历史 + 系统提示词，tiny 窗口）。
- **实际 vs 预期**：`keepRecent = 8` → 实际 `[系统, 占位, 最后 1 条]`（3 条），预期 `[系统, 6 条]`；
  `keepRecent = 2` → 实际同样 3 条，预期 `[系统, 占位, 2 条]`；两种配置结果**完全相等**。
- **附带影响**：`SummarizingCompactor` 在"摘要后仍超预算"时会退化为本压缩器兜底
  （`SummarizingCompactor.java:101-103`），此时**连摘要本身都会被丢掉**。实测
  `new SummarizingCompactor(exp, 0.8, null).compact(8 条 900 字符历史, tiny 模型, 4)`
  返回 2 条：一条 `[本心上下文压缩]` 占位 + 一条原文，`[本心历史摘要]` 不见了。
- **文档口径**：`ContextCompactor#compact` 的 javadoc 写的是"**至少**保留的最近消息条数"，
  `SlidingWindowCompactor` 类内注释写的是"同时**不少于** keepRecent 条" —— 两处都与实现不符。

### 2. `ChatMessage.withText(...)` 用在工具消息上不会"替换"，反而让 `text()` 变长（中危）

- **证据**：`benxin-core/src/main/java/com/benxin/llm/core/message/ChatMessage.java:143-150`
  把新 `TextPart` **插到最前**，再原样保留其余内容块（含 `ToolResultPart`）；
  而 `text()`（同文件 `101-111`）会把 `TextPart` 与 `ToolResultPart` 的内容**首尾拼接**。
- **复现**：`MessageApiTest.withTextKeepsOtherParts`。
- **实际 vs 预期**：`ChatMessage.toolResult("call_1","read_file","很长的结果",false).withText("压缩后的占位")`
  → `parts()` 变成 2 个（`TextPart` + 原 `ToolResultPart`）、`text()` = `"压缩后的占位很长的结果"`；
  按 javadoc "仅替换文本内容，其余结构保持不变"预期的是 1 个 `ToolResultPart`、`text()` = 新文本。
  想真正改短工具结果必须用 `ChatMessage.toolResult(...)` 重建（`ClaudeCodeLoop` 正是这么做的）。

### 3. `TokenEstimator.DEFAULT` 对纯中日韩文本会多算 1 token（低危）

- **证据**：`benxin-core/src/main/java/com/benxin/llm/core/util/TokenEstimator.java:33`
  `return cjk + Math.max(1, other / 4);` —— `other == 0`（全是 CJK）时仍会补 1。
- **复现**：`TokenEstimatorTest.defaultEstimatorValues`。
- **实际 vs 预期**：`estimate("你好")` = **3**，按类 javadoc "中日韩字符 1 token / 1 字"预期 2。
  压缩阈值判断因此对纯中文历史略微偏保守（每条消息多 1 token）。

### 4. `SlidingWindowCompactor` 的内部预算另有 1024 的下限（低危）

- **证据**：`SlidingWindowCompactor.java:75-78` 的
  `Math.max(1024, max * threshold - reservedOutputTokens)`。
- **影响**：当 `窗口 × 阈值 − 预留输出 < 1024` 时，压缩器认为"1024 token 以内都算合格"，
  而这个数可能已经超过 `ContextManager` 算出来的输入预算，于是压缩结果仍然可能偏大。
  与 `ContextManager` 的预算不是同一个口径，排查"压完还是超窗"时需要留意。

## 目录

```
src/main/java/com/benxin/exp/context/
├── ContextMemoryApplication.java          @SpringBootApplication + @EnableLlmAgents
├── agent/                                 5 个 @LlmAgent 接口（记忆 / 无记忆 / 压缩 / claude-code / 提示词）
├── config/ContextPipelineConfiguration.java   估算器 → 压缩器 → 上下文管理器 的装配
├── config/MemoryStoreConfiguration.java       H2 文件库 + JdbcMemoryStore
├── config/OfflineModelConfiguration.java      三个离线模型（mock / tiny / vision）+ 长文本工具
├── config/PromptPlatformConfiguration.java    表驱动提示词平台
├── context/Exp06ContextManager.java           自定义 ContextManager（窗口裁剪 + 提示词标记）
├── context/Exp06TokenEstimator.java           自定义分词估算器（英文 2 字符 1 token）
├── context/HardLimitCompactor.java            自定义压缩器（只按条数硬砍）
├── memory/JdbcMemoryStore.java                JDBC 记忆后端（整份重写）
├── memory/MessageJson.java                    ChatMessage ⇄ JSON 的编解码
├── model/VisionCapableModel.java              给任意模型补上 vision 能力位的装饰器
├── prompt/JdbcPromptPlatform.java             自定义 SystemPromptProvider（热更新 + 回落）
├── tool/LongDocTools.java                     固定返回 1800 中文字符的工具（撑大上下文用）
└── demo/ContextMemoryDemo.java                11 组演示（ApplicationRunner，启动即跑）
src/test/java/com/benxin/exp/context/
├── AbstractContextMemoryTest.java         共用脚手架：注入 bean + 每个用例前清状态
├── MemoryStoreTest.java                   三种记忆来源、多轮续接与隔离、关掉记忆的两条路径
├── ContextManagerTest.java                自定义实现、构造参数语义、窗口裁剪的边界
├── CompactorTest.java                     三种压缩器 + Loop 层压缩的就地改写
├── TokenEstimatorTest.java                两个估算器的取值与"阈值分叉"
├── PromptProviderTest.java                模板渲染、平台 provider、方法级覆盖、PromptTemplates
├── MessageApiTest.java                    ChatMessage / ContentPart / ImagePart 全谱（不起 Spring）
└── AgentWiringTest.java                   可插拔点装配、模型注册、多模态不降级
```
