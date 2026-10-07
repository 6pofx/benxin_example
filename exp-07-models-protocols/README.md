# exp-07 · 模型装配与协议

> 四种 LLM 协议**并排跑一遍**：抓真实 HTTP 报文，看同一个请求被翻译成四种样子。
> 协议、传输层、模型装配三个切面各自"怎么换"，都在这一个模块里。

本模块只做一件事：把本心的**线级报文**摊开给人看。
**完全离线、零 token**：四条协议通道的 `base-url` 全都指向一个只监听 `127.0.0.1` 随机端口的本地桩服务，
`mvn test` 却能断言到"认证头叫什么、系统提示放在哪一层、工具参数怎么分片拼装"这一级。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| 四种内置协议 | OpenAI Chat Completions / Anthropic Messages / Gemini generateContent / OpenAI Responses API |
| 请求侧 | 端点路径、认证头、`system` 落点、tools 字段名、输出上限字段名、`extra` 透传 |
| 响应侧 | `text()` / `usage()` / `finishReason()` 的归一化，含 `reasoning_content`、缓存命中、`promptFeedback` |
| 流式 | 增量拼接、结束语义（`[DONE]` / `message_stop` / 无标记 / 命名事件）、工具参数分片拼装 |
| `ModelConfig` | `base-url` 末尾斜杠、`extra` 合并、`api-key` 容错、`hasApiKey()`、`resolveModel(ChatRequest)` |
| `ModelFactory` | `create` / `createAll` / `registry`，`max-retries` 自动包 `RetryingLlmModel` |
| `ModelRegistry` | `register` / `registerAll` / `find` / `get` / `names` / `all` / `setDefault` / `defaultName` / `defaultModel` / `clear` |
| 模型三来源 | yml 配置 / `LlmModel` bean（覆盖同名 yml）/ 程序化 `new OpenAiModel(...)` |
| 自定义协议 | `DemoProtocolCodec` 四步走通；同名覆盖内置（注册时 WARN）；未注册协议启动期失败 |
| 自定义传输层 | `RecordingHttpTransport` / `FakeTransport` / `ScriptedStreamTransport` |
| 模型装饰器 | 插件自带 `RetryingLlmModel` + 自写 `CachingLlmModel`，可叠加、可 `unwrap()` 链式取回 |
| 能力声明 | `ModelCapabilities.streaming=false` → Agent 路径改走 `chat()` 同步分支 |

## 四协议差异对照表

同一份 `ChatRequest`（一条 system + 一条 user + 一个 `get_weather` 工具）被四个 Codec 各翻译一遍的真实结果：

| | OpenAI Chat Completions | Anthropic Messages | Google Gemini | OpenAI Responses |
|---|---|---|---|---|
| **端点** | `POST {base}/v1/chat/completions` | `POST {base}/v1/messages` | `POST {base}/v1beta/models/{model}:generateContent` | `POST {base}/v1/responses` |
| **流式端点** | 同左 + `"stream":true` | 同左 + `"stream":true` | `:streamGenerateContent?alt=sse` | 同左 + `"stream":true` |
| **认证头** | `Authorization: Bearer <key>` | `x-api-key: <key>` + `anthropic-version: 2023-06-01` | `x-goog-api-key: <key>`（**URL 里没有 `?key=`**） | `Authorization: Bearer <key>` |
| **模型 id 位置** | 请求体 `model` | 请求体 `model` | **URL 路径上**（请求体里没有 `model`） | 请求体 `model` |
| **系统提示** | `messages[].role="system"` | **顶层 `system` 字符串** | **顶层 `systemInstruction.parts[].text`** | **顶层 `instructions`** |
| **对话载体** | `messages[]` | `messages[]`（content 是内容块数组） | `contents[]`（role 是 `user`/`model`） | `input[]` item 列表 |
| **工具声明** | `tools[].function.{name,description,parameters}` | `tools[].{name,description,input_schema}` | `tools[].functionDeclarations[].{name,description,parameters}` | `tools[].{type,name,description,parameters}`（**扁平，没有 `function` 包装**） |
| **工具结果回灌** | `role:"tool"` 消息 + `tool_call_id` | 紧随 assistant 的 user 消息里 `{type:"tool_result",tool_use_id}` | user 侧 `parts[].functionResponse{name,response.result}` | 独立 `function_call_output` item + `call_id` |
| **工具参数形态** | `arguments` 是 **JSON 文本** | `input` 是 **JSON 对象** | `args` 是 **JSON 对象** | `arguments` 是 **JSON 文本** |
| **输出上限** | `max_tokens` | `max_tokens`（**必填**，插件缺省兜底 4096） | `generationConfig.maxOutputTokens` | `max_output_tokens` |
| **流式结束** | `data: [DONE]` | `event: message_stop` | **没有结束标记，靠连接关闭** | `event: response.completed`（**没有 `[DONE]`**） |
| **流式事件形态** | 匿名 `data:` 帧 | 强类型 `event:` + `data:` 成对 | 匿名 `data:` 帧（结构与整包响应一致） | 命名事件（`response.output_text.delta` …） |
| **工具参数分片** | 按 `delta.tool_calls[].index` 拼 `arguments` | 按 block index 拼 `input_json_delta.partial_json` | **不分片，`functionCall` 一次性给全** | 按 `output_index` 拼，最终以 `output_item.done` 为准 |
| **思维链** | 非标准：`reasoning_content`（DeepSeek 形态） | `thinking` 内容块 + `signature`（**分片下发，必须累积**） | `parts[].thought=true` | `response.reasoning_text.delta` / `response.output_item.added` |
| **用量字段** | `prompt_tokens`/`completion_tokens`/`prompt_tokens_details.cached_tokens` | `input_tokens`/`output_tokens`/`cache_read_input_tokens` | `usageMetadata.promptTokenCount`/`candidatesTokenCount`/`cachedContentTokenCount` | `input_tokens`/`output_tokens`（+ `*_details.cached_tokens`） |
| **用量口径** | 整段响应的累计值（末帧给一次） | `message_delta` 给**累计** `output_tokens` | **逐帧给累计值** | 终态事件给一次 |
| **结束原因** | `finish_reason` | `stop_reason` | `finishReason` | **`status`**（`completed`/`incomplete`/`failed`） |
| **失败标记** | `{"error":{...}}`（HTTP 200 也可能是它） | `{"type":"error","error":{...}}` | `{"error":{code,status,message}}`；被拦截则是 `promptFeedback.blockReason` + **空 `candidates`** | `{"error":{message,type}}`；流里是 `response.failed` 事件 |
| **能力声明（Codec）** | streaming/tool/parallel/vision=true，**thinking=false** | 六项全 true，maxContext=200k | 六项全 true，maxContext=1M | 六项全 true，maxContext=128k |

> 上表每一行都有对应断言，见 `ProtocolWireTest`。表里加粗的地方是最容易猜错、也最值得先看的位置。

## 加一种协议：完整步骤

以本模块的 `demo` 协议（`DemoProtocolCodec`）为例，四步：

```java
// 第 1 步：实现 ProtocolCodec —— 协议差异全部关在这一个类里
public class DemoProtocolCodec implements ProtocolCodec {
    public static final Protocol PROTOCOL = Protocol.of("demo");   // 名字随便取

    @Override public Protocol protocol() { return PROTOCOL; }

    // 能力声明不是装饰品：LoopContext 读 streaming() 决定走流式还是同步分支
    @Override public ModelCapabilities capabilities() {
        return ModelCapabilities.builder()
                .streaming(true).toolCalling(true).parallelToolCalls(false)
                .vision(false).thinking(true).maxContextTokens(64_000).build();
    }

    @Override public String endpoint(ModelConfig c, ChatRequest r) { return c.baseUrl() + "/demo/v2/invoke"; }
    @Override public Map<String,String> headers(ModelConfig c) { /* X-Demo-Key 等 */ }
    @Override public String encode(ChatRequest r, ModelConfig c) { /* → directive/turns/skills */ }
    @Override public ChatResponse decode(String body, ModelConfig c) { /* ← out[]/spend/outcome */ }
    @Override public StreamDecoder newStreamDecoder(ModelConfig c, LlmStreamHandler h) {
        return new DemoStreamDecoder(h);
    }
}
```

```java
// 第 2 步：注册成 bean。starter 会收集容器里所有 ProtocolCodec，
//         内置四协议先登记、用户 bean 后登记 → 同名即覆盖（并打一条 WARN）
@Bean
public ProtocolCodec demoProtocolCodec() {
    return new DemoProtocolCodec();
}
```

```yaml
# 第 3 步：yml 里写协议名 + base-url
llm:
  models:
    demo:
      protocol: demo          # ← 就是 Protocol.of("demo") 里的那个 id
      base-url: ${exp.stub.base-url}
      api-key: sk-demo
      model: demo-chat
```

```java
// 第 4 步：调用方代码一行不改 —— 下面的接口不知道 demo 协议的存在
@LlmAgent(name = "demoAssistant", model = "demo", systemPrompt = "…")
public interface DemoAssistant {
    String ask(@User String question);
}
```

**自写流式解码器只要守住四条约定**（`StreamDecoder`）：
① `onStart` 最多一次；② 工具调用参数拼装完整后才回调 `onToolCall`，且一次调用只回调一次；
③ `finish()` 幂等，且是发出 `onComplete` 的唯一位置；④ 出错后不再补 `onComplete`。

**协议 id 写错不会静默**：`ModelFactory` 在**装配期**（Spring 路径下即上下文启动期）就抛
`ModelException`，并列出所有已注册协议与"怎么加"。

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法；exp-07 从本地仓库消费 exp-common）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-07-models-protocols
mvn -o test                            # 77 个离线用例，零 token
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-07-models-protocols-1.0.0.jar
```

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8。

演示分三段（三个 `ApplicationRunner`，启动即跑；执行顺序即下表顺序）：

| 演示类 | 分节 | 看点 |
|---|---|---|
| `ProtocolWireDemo` | 8 节 | 四种协议的抓包 + 工具结果落点 + 多模态编码 + 流式 + 自定义协议 e2e |
| `BuildAndTransportDemo` | 9 节 | `Protocol` 语义、加协议、覆盖内置、未注册协议、传输层三件套、同步分支、错误路径 |
| `ModelAssemblyDemo` | 5 节 | `ModelConfig` 全字段、模型三来源、`ModelFactory`/`ModelRegistry`、重试、能力声明 |

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论：

1. **`max-retries` 默认是 `2`，不是 `0`。** `ModelConfig.Builder` 里写的就是 `2`
   （`llm.models.<key>.max-retries` 也一样）。想"只发一笔报文、靠请求数取证"的场合
   **必须显式写 `max-retries: 0`**，否则一次瞬时错误会变成 3 次请求，数字全部对不上。
   本模块里 `openai-demo` 之所以是 0，是因为它由 `LlmModel` bean 接管，bean 里显式写了 `0`。

2. **`api-key` 可以整段写成 `"Bearer sk-x"`。** OpenAI / Responses 的 Codec 会识别
   `Bearer ` 前缀，不会拼成 `Bearer Bearer sk-x`；而 Anthropic 的 `x-api-key` 要的是裸 key。
   配置里的自定义头放在**最后**叠加，因此可以覆盖内置认证头（Azure 一类网关的常规做法）——
   本模块的 `exp.model.*` 样本就同时演示了这两条。

3. **Gemini 的密钥走 `x-goog-api-key` 请求头，URL 里没有 `?key=`。** 查询串会进网关访问日志与代理缓存，
   所以哪怕官方文档里两种都支持，这里选请求头。测试直接断言了 `queryParam("key") == null`。

4. **Gemini 不吃外链图片。** `inlineData` 只收 base64，`fileData` 只认 Google File API 的 URI，
   因此普通 `http(s)` 图片会被**降级成一句文字说明** `[图片: <url>]`（注意冒号后有空格），
   而不是报错。这是刻意的失败方向：至少让模型知道有这么张图。

5. **Gemini 的 `functionCall` 没有 id，插件合成 `call_<工具名>_<序号>`。** 序号跨分片递增，
   流式与非流式两条路径规则一致，因此 `tool_result` 能对上。回灌时真正起作用的是工具名。

6. **Gemini 流式没有 `[DONE]`，靠连接关闭收尾。** 收尾动作发生在解码器的 `finish()`
   （由 `HttpLlmModel.stream` 在读完整条流之后调用），所以"最后一帧之后必须还能收尾"是硬要求。

7. **Anthropic 的 `message_delta` 给的是累计 `output_tokens`**，而 `StreamCollector` 对标量用量做的是
   **累加**。解码器必须把它换算成增量再回调，否则帧数越多数字越大 —— 用量偏差会直接体现在账单上，
   而且不报错、不影响功能，最难被发现。本模块用 `message_start(output=1)` + `message_delta(23)`
   断言最终恰好是 23（不是 24）。

8. **Gemini 的 `usageMetadata` 更是逐帧给累计值**，同一个坑，同样的换算。

9. **流式里"已经吐出内容之后才失败"不会重试。** 内容一旦交付给调用方就无法撤回，重试只会造成重复投递。
   重试装饰器用"是否已回调 onTextDelta/onThinkingDelta/onToolCall"来判定，本模块用
   `ScriptedStreamTransport` 精确复现"交付完 N 帧之后断流"来钉住这条口径。

10. **`ModelCapabilities.streaming()` 只影响 Agent 路径，不影响 `model.stream()`。**
    分支决策在 `DefaultLoopContext#callModel` 里；直接调 `model.stream(...)` 会绕过它 ——
    `HttpLlmModel.stream()` 恒走 `postStreaming()`，与能力声明无关。

11. **`ModelFactory.create` 的 `max-retries` 与 `RetryingLlmModel` 的构造器是两条路。**
    配置走前者（工厂自动包一层，退避参数用默认值）；需要精细控制退避曲线时自己
    `new RetryingLlmModel(model, n, initialBackoffMillis, multiplier)`。

12. **`ModelRegistry.defaultName()` 会回退，`configuredDefaultName()` 不会。**
    配置里写了一个取不到的名字时，前者给"实际生效的那个"（注册顺序第一个），后者保留原样写的字符串。
    拿 `defaultName()` 去和 `names()` 比对永远比不出问题 —— 诊断要用后者。

## 容易踩的坑（本模块开发过程中真实踩过的）

- **桩服务的路由是共享且按注册顺序匹配的。** 一条"任何路径都回 503"的宽泛路由会留在表里，
  把之后所有场景的请求都截胡 —— 表现为"流式增量全是空的"这种和根因毫无关系的症状。
  因此 `WireScenario` 每个场景开头都会 `resetStub()`（连路由一起清），
  错误路径也改成只挂在对应协议自己的端点上，并且有一个专门的用例守着这条（`errorRoutesDoNotPoisonOtherScenarios`）。
- **桩服务必须早于 Spring 绑定配置启动。** yml 里的 `${exp.stub.base-url}` 在上下文 refresh 时解析，
  而端口只有桩服务起来才知道。测试里放在**静态初始化块**（不是 `@BeforeAll`），
  与 `main()` 里"先起桩服务再 `SpringApplication.run`"的时序一致。
- **`java -jar` 跑完不会自己退出。** 桩服务用的 `com.sun.net.httpserver` 有一个**非 daemon** 的
  `HTTP-Dispatcher` 线程，演示跑完之后 JVM 会一直挂着。`main()` 末尾显式 `System.exit(0)`，
  顺带触发 shutdown hook 关掉桩服务。
- **`StreamCollector.response()` 在出错时也会合成一个响应。** 它的兜底逻辑（"流正常结束但解码器没回调
  `onComplete`"时用已收到的增量合成）在**已经出错**的场合会让半截内容看起来像成功结果。
  演示与测试统一走 `WireScenario.successfulResponse(...)`：有错就是错，没有响应。

## 插件行为里值得注意的一处

（不改插件，只把真实行为记下来；详见测试与报告）

**流式路径收到"非 SSE 的整包响应"时是静默的空回答。** `JdkHttpTransport` 只按状态码判断成功，
于是一个 `Content-Type: application/json` 的 200 响应会被当成"一条事件都没有的 SSE 流"：
SseParser 一个事件都解不出来 → 解码器的 `finish()` 兜底收尾 → 调用方拿到
`text=""`、`error=null`、`finishReason=UNKNOWN`。对照之下，`postStreaming()` 返回
`TransportResponse.of(...)`（非流式）是有可读报错的 —— 说明缺的只是"按 Content-Type 判断"这一步。
断言见 `ProtocolWireTest#nonSseBodyOnStreamingPathIsSilentlyEmpty`。

## 目录

```
src/main/java/com/benxin/exp/models/
├── ModelsProtocolsApplication.java        @SpringBootApplication；main 里先起桩服务再 run
├── wire/
│   ├── WireScenario.java                  所有线级场景的编排（演示与测试共用同一段代码）
│   ├── WireConstants.java                 四协议共用的请求构造与桩模型 id
│   └── ProtocolProbes.java                Protocol / ProtocolRegistry 行为的"观测快照"
├── transport/
│   ├── RecordingHttpTransport.java        录制型装饰器（落盘到 target/wire-log/）
│   ├── FakeTransport.java                 完全离线、可编程的传输实现
│   └── ScriptedStreamTransport.java       精确控制"第 N 帧之后断流"
├── custom/
│   ├── DemoProtocolCodec.java             自造协议的完整示范
│   ├── DemoStreamDecoder.java             它的流式解码器（守住 StreamDecoder 四条约定）
│   ├── DemoWire.java                      它的报文构造器（契约侧）
│   └── OverridingOpenAiCodec.java         同名覆盖内置协议（只在测试上下文注册）
├── decorator/CachingLlmModel.java         自写模型装饰器（可叠加、可 unwrap）
├── config/
│   ├── ModelDemoConfiguration.java        接线：传输层 / Codec bean / 同名 LlmModel bean
│   ├── ModelSettings.java                 exp.model.* 的全字段样本（record 绑定）
│   └── BaseUrlSupplier.java               ${exp.stub.base-url} 的解释者 + 两条通道的转换器
├── support/                               PrettyJson / WireDump / StubRegistry
├── demo/                                  三个 ApplicationRunner（启动即打印结论）
└── agent/DemoAssistant.java               @LlmAgent(model = "demo") 的声明式接口

src/test/java/com/benxin/exp/models/
├── wire/ProtocolWireTest.java             51 个用例：四协议请求/响应/流式 + 自定义协议 + 传输层 + 错误路径
└── ModelAssemblyTest.java                 26 个用例：ModelConfig/Factory/Registry + Spring 接线 + 启动期失败
```
