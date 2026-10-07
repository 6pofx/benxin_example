# benxin_exp · 本心插件示例工程

> 用一个**可运行、可断言、默认不花钱**的聚合 Maven 工程，把「本心（benxin）」LLM Agent 插件
> **1.1.0** 的每一个功能都演示一遍。

本工程是**使用者视角**的示例：只从本地 Maven 仓库消费 `com.benxin:benxin-spring-boot-starter:1.1.0`，
不把插件当源码依赖 —— 所以这里写出来的每一行代码，第三方使用者都能原样照抄。

**当前状态**：14 个 Maven 项目全部 `BUILD SUCCESS`，**789 个用例全绿**
（3 个真实云端调用用例默认 skip；exp-12 的 7 个本机模型用例在 LM Studio 不可达时自动 skip），
每个模块都有独立 README、可执行 jar 与启动即跑的演示。
实测发现的插件问题汇总在 [`FINDINGS.md`](FINDINGS.md)（31 条）。

---

## 一、工程结构

```
benxin_exp/                          聚合 POM（Spring Boot 3.5.5 / Java 17）
├── exp-common/            共享离线基础设施（非 Spring Boot 应用）
│   ├── model/ScriptedLlmModel       脚本化模型：零 token 驱动完整 Agent 循环
│   ├── model/RecordingModel         录制型装饰器：把"真正发出去的请求"留档
│   ├── tool/CommonTools             多模块共用工具集（天气/时间/计算/上下文）
│   ├── wire/StubHttpServer          本地 HTTP 桩：抓真实报文 + 回放 JSON/SSE
│   ├── wire/{OpenAi,Anthropic,Gemini,Responses}Wire   四协议报文与 SSE 帧构造器
│   ├── db/{SimpleDataSource,Db}     纯 JDBC 小工具（建库/建表/查询）
│   └── demo/Demo                    统一演示输出
│
├── exp-01-declarative-agent/   20 用例  声明式 Agent：注解全属性 / 参数语义 / 返回值全谱 / 记忆
├── exp-02-tools/               38 用例  工具：@LlmTool 全谱 / ToolContext / 程序化 ToolCallback / 动态登记
├── exp-03-builtin-sandbox/     71 用例  8 个内置工具 + 三重开关 + PathSandbox/CommandSandbox + 四种审批
├── exp-04-loops/               37 用例  8 个内置 Loop + 自定义 @LlmLoop + 子代理派生
├── exp-05-workflow/           175 用例  声明式工作流：六种节点 / 条件表达式 / 模板 / 容错 / 三道防线
├── exp-06-context-memory/      78 用例  记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态
├── exp-07-models-protocols/    77 用例  四协议线级报文 + 自定义协议/传输/模型装饰器
├── exp-08-observability-web/   78 用例  监听器全回调 + 拦截器全钩子 + 流式 + HTTP 端点
├── exp-09-spring-override/    104 用例  逐个切面的 bean 覆盖 / llm.enabled / 程序化装配 / 裸 core 门面
├── exp-10-database/            72 用例  MySQL+H2 复杂示例：JDBC 记忆 + 查库工具 + 运行审计 + 工作流入库
├── exp-11-real-deepseek/       19 用例  真实 DeepSeek 联调（默认 skip，显式开关才联网）
└── exp-12-local-lmstudio/      20 用例  本机小模型（LM Studio / Qwen3-VL-8B）：真实对话/工具/流式/**视觉**
```

每个子项目都是**完整的 Spring Boot 应用**：有 `@SpringBootApplication`、有 `application.yml`、
可 `mvn spring-boot:run`、可打成可执行 jar、有 `@SpringBootTest` 测试与中文 README。

---

## 二、怎么跑

```bash
# ① 在工程根目录装一次（多模块工程的标准做法）
cd benxin_exp
mvn -o install -DskipTests

# ② 跑任意一个子项目的演示（默认离线、零 token）
cd exp-01-declarative-agent
mvn -o spring-boot:run

# ③ 跑该子项目的离线测试（断言演示里的每条结论）
mvn -o test

# ④ 或者一次跑完全部 13 个模块的测试
cd ..
mvn -o test
```

用打好的可执行包跑（Windows 控制台建议加 `-Dstdout.encoding=UTF-8`，或先 `chcp 65001`）：

```bash
mvn -o package -DskipTests
java -Dstdout.encoding=UTF-8 -jar exp-01-declarative-agent/target/exp-01-declarative-agent-1.0.0.jar

# Web 模块（exp-08）跑完演示会自己退出；也可以一直跑着用 curl 试端点
java -Dstdout.encoding=UTF-8 -Dexp.demo.exit-after-run=true \
     -jar exp-08-observability-web/target/exp-08-observability-web-1.0.0.jar
```

数据库模块（exp-10）默认用 H2 文件库，零配置即可跑；换本机 MySQL 加一个 profile：

```bash
java -jar exp-10-database/target/exp-10-database-1.0.0.jar --spring.profiles.active=mysql
```

---

## 三、Token 消耗：默认为零

| 模块 | 默认是否联网 | 说明 |
|---|---|---|
| exp-01 ~ exp-10 | **否** | 全部由 `exp-common` 的 `ScriptedLlmModel` 驱动：它按规则模拟"模型决策"，能完整跑通「模型决策 → 工具调用 → 结果回灌 → 收尾」，包括流式增量与工具参数分片 |
| exp-11 | **否（默认）** | 只有显式打开开关（`-Dexp.real.enabled=true` + 传 key）才会调用 DeepSeek |
| exp-12 | **否（默认 auto 探测）** | 端点只有 `127.0.0.1:1234`（本机 LM Studio）：可达就跑真实演示，不可达就只跑离线演示；**本地推理不花钱、不出网** |

因此 `mvn -o test` 在离线环境与 CI 里都能稳定通过，**不会烧掉一个 token**
（exp-12 若本机开着 LM Studio 会顺带跑 7 个本地用例，同样零成本）。

exp-11 里做了完整的 token 控制：`max-tokens: 48`、`max-retries: 0`、`temperature: 0`、
每个演示只调一次模型；错误路径用**故意写错的 key**（上游 401 拒绝，**0 token**）。
一次完整真实演示的实测消耗是 **2,210 tokens**（10 次调用），
逐次用量表写在 [`exp-11-real-deepseek/README.md`](exp-11-real-deepseek/README.md) 第二节。

---

## 四、功能覆盖矩阵（对照插件 README 的能力清单）

### 4.1 可插拔切面

| 切面 | 接口 | 演示位置 |
|---|---|---|
| 模型 | `LlmModel` | exp-07（bean / `@LlmModelDef` / 程序化 / 装饰器）、exp-09（覆盖 `ModelRegistry`） |
| 协议 | `ProtocolCodec` | exp-07（四协议线级 + 自定义协议 + 同名覆盖 + 未注册快速失败） |
| 传输 | `HttpTransport` | exp-07（录制装饰器 / 完全假实现 / `postStreaming` 误用报错）、exp-11（报文留档） |
| 循环 | `AgentLoop` | exp-04（8 个内置 + 2 个自定义 + `defaultLoop`）、exp-05（`workflow` / `staged`） |
| 工具 | `ToolCallback` | exp-02（注解 / 程序化 / 动态登记 / 目录 API）、exp-10（写库工具 + 审批） |
| 上下文 | `ContextManager` | exp-06（自定义实现 + `DefaultContextManager` 参数语义） |
| 压缩 | `ContextCompactor` | exp-06（Summarizing / SlidingWindow / none / 自定义） |
| 记忆 | `MemoryStore` | exp-06（内存 / disabled）、exp-10（JDBC 落库 + 跨上下文续接） |
| 提示词 | `SystemPromptProvider` | exp-06（自定义 provider + 与 `@SystemPrompt` 的优先级） |
| 拦截 | `AgentInterceptor` | exp-08（全钩子 + `@LlmGuard` 范围）、exp-10（审计 + 短路 + 顺序即功能） |
| 事件 | `AgentListener` | exp-08（12 个回调 + `CompositeListener`）、exp-10（运行审计落库） |
| 沙箱 | `ToolSandbox` | exp-03（PathSandbox / CommandSandbox / 自定义 / CompositeSandbox / permissive 对照） |
| 审批 | `ApprovalHandler` | exp-03（autoApprove / denyAll / 自定义 + 四种 `ApprovalPolicy`）、exp-10（按金额放行/拒绝 + 落审批表） |
| 分词估算 | `TokenEstimator` | exp-06（自定义实现 + 触发压缩） |
| Agent 装配 | `LlmAgentFactory` | exp-09（bean 覆盖 + `specOf` / `create` / `createFromInterface`） |

### 4.2 注解

| 注解 | 演示位置 |
|---|---|
| `@LlmAgent`（14 个属性） | exp-01（全属性）、exp-04（`subAgents`）、exp-08（`interceptors` / `stream`） |
| `@LlmLoop`（`value` / `defaultLoop` / `order`） | exp-04、exp-09（含两条"属性不生效"的实测断言，见 FINDINGS 20/21） |
| `@LlmModelDef`（`value` / `defaultModel` / `order`） | exp-07、exp-09 |
| `@LlmTool`（含类级、`returnDirect`、`enabled`） | exp-02 |
| `@LlmToolParam`（5 个属性 + 参数类型全谱） | exp-02 |
| `@SystemPrompt`（`value` / `append` / `inherit`） | exp-01 |
| `@User`（`prefix` / `suffix`） / `@Assistant` | exp-01 |
| `@Ctx`（`value` / `attribute`） | exp-01 |
| `@Memory` | exp-01、exp-06、exp-10 |
| `@LlmGuard`（`global` / `agents`） | exp-08 |
| `@EnableLlmAgents`（`basePackages` / `basePackageClasses`） | exp-01、exp-09 |
| `@LlmRetry` | exp-09（**实测无任何消费点**，见 FINDINGS 1） |

### 4.3 运行期能力

| 能力 | 演示位置 |
|---|---|
| 返回类型自适应（9 种） | exp-01 |
| 流式（`LlmStreamHandler` 7 个回调 + `StreamCollector` + `ofText`） | exp-01、exp-08、exp-11、exp-12 |
| 工具调用五道关卡 | exp-02、exp-03、exp-08、exp-10 |
| 工具参数类型 → JSON Schema 生成 | exp-02（打印生成的 schema，并暴露 3 条 schema 缺陷） |
| 步数预算与 `hard-max-steps` | exp-04、exp-05 |
| 子代理派生与用量补记 | exp-04（注解 / 编程式 / `ToolContext.spawn` 三种） |
| 工作流六节点 + 条件表达式 + 模板 + 三道防线 | exp-05（含纯引擎 + 假 `WorkflowRuntime` 的离线验证） |
| HTTP 端点（5 个）与 SSE 事件 | exp-08（含"按直觉猜会猜错"的契约表逐条断言） |
| `AgentResult` 全字段 | exp-01、exp-08 |
| 会话 / 历史 / `AgentSession` | exp-01、exp-06、exp-10、exp-12 |
| 多模态消息（`ImagePart` 四协议编码差异） | exp-06、exp-07（报文层）、**exp-12（真实模型看懂图）** |
| 数据库持久化（记忆 / 审计 / 查库 / 审批 / 事务） | exp-06、exp-10 |
| 真实厂商联调（两种协议 + 报文留档 + 用量统计） | exp-11 |
| 真实本机模型（function calling / 流式 / 视觉 / 多轮） | **exp-12** |

---

## 五、写示例时遵守的约定

1. **离线优先**：默认路径不得联网；需要真实网络的演示必须放在显式开关之后，并且默认 skip 而不是 fail。
2. **演示即文档**：每个模块都有 `ApplicationRunner`，`mvn spring-boot:run` 就能看到结论；
   结论用 `com.benxin.exp.common.demo.Demo` 统一输出。
3. **结论必须被断言**：演示里说的每一句话，都在 `@SpringBootTest` 里有对应断言 ——
   769 个用例就是这个工程的"防腐烂层"（插件升级后跑一遍就知道哪条口径变了）。
4. **注释解释"为什么"**：尤其是"按直觉会猜错"的地方，代码里写清原因。
5. **不修改插件**：发现插件缺陷只记录、不修改源码；示例若必须绕开，
   注释里注明这是绕开而不是正确用法。

---

## 六、实测发现的插件问题

见 [`FINDINGS.md`](FINDINGS.md)：**31 条**由本工程的 14 个模块实测得到，
每条都给了复现位置（`插件源码:行号` + 本工程的测试名）与"实际 vs 预期"。
其中值得优先看的四条：

* **问题 16（高危）**：`SlidingWindowCompactor` 一旦触发就只保留最后 1 条 —— `keepRecent` 失效，
  且 `SummarizingCompactor` 的兜底会走它，可能连摘要一起丢。
* **问题 13（高）**：条件表达式里的 `${x:-兜底}` 静默失效 —— 那条边永远不成立，却不报错不警告。
* **问题 30（中高）**：`agent.toBuilder().model("名字")` / `.loop("名字")` **静默无效** ——
  派生副本继续用原模型/原 Loop。（写 exp-12 时踩到：一度"真实模型用例全绿"，其实一次都没打过去。）
* **问题 26（中）**：`beforeTool` 短路会 `break` 掉整条拦截器链 —— 审计拦截器只要顺序靠后就什么都看不到。

---

## 七、版本管理

本工程是一个独立的 Git 仓库（`main` 分支），约定如下：

```bash
git status                  # 提交前先看一眼
mvn -o -B test              # 提交前跑一遍（14/14 绿才提交）
git add -A && git commit -m "feat(exp-03): ..."
```

| 约定 | 说明 |
|---|---|
| **只提交源码与文档** | `target/`、`*.log`、H2 文件库（`*.mv.db`）、沙箱目录（`sandbox/`）、报文留档（`wire-log/`）、`.idea/` 全部在 `.gitignore` 里，**不要** `git add -f` 绕过 |
| **换行符** | `.gitattributes` 统一 `eol=lf`（`*.cmd` / `*.bat` 保持 CRLF），避免 Windows 上整批文件显示为"已修改" |
| **提交信息** | `<type>(<scope>): <中文摘要>`，`scope` 用模块名（如 `exp-02`、`common`、`docs`） |
| **不提交密钥** | exp-11 的 key 一律走 `${DEEPSEEK_API_KEY:}` 占位符；提交前可用 `git diff --cached \| findstr /R "sk-[A-Za-z0-9]\{20,\}"` 自查 |
| **插件源码不在本仓库** | `../benxin/` 是只读的插件源码，本仓库只从本地 Maven 仓库消费它的二进制 |

> **插件源码未做任何修改。** 所有问题都只以"断言 + 注释"的形式记录在示例工程里。
