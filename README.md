# benxin_example · 本心（benxin）插件示例工程

> 一个聚合 Maven 工程：**14 个可独立运行的 Spring Boot 子项目 + 1 个共享的离线基础设施模块**，
> 把「本心」声明式 LLM Agent 插件的每一个切面，演示到"看得见、跑得动、断言得住"。
> **默认零 token**；**848 个用例**把演示里说的每一句话钉成回归断言。

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-6DB33F)
![benxin](https://img.shields.io/badge/benxin-1.1.1-orange)
![tests](https://img.shields.io/badge/tests-848%20passed-success)
![offline](https://img.shields.io/badge/default-token%20free-brightgreen)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

**目录**

- [1. 这是什么](#1-这是什么)
- [2. 快速开始](#2-快速开始)
- [3. 工程结构](#3-工程结构)
- [4. 怎么跑](#4-怎么跑)
- [5. Token 消耗：默认为零](#5-token-消耗默认为零)
- [6. 运行参数速查](#6-运行参数速查)
- [7. 功能覆盖矩阵](#7-功能覆盖矩阵)
- [8. 它也是一套兼容性回归套件](#8-它也是一套兼容性回归套件)
- [9. 写示例时遵守的约定](#9-写示例时遵守的约定)
- [10. 版本管理](#10-版本管理)
- [11. License](#11-license)

---

## 1. 这是什么

[「本心 · benxin」](https://github.com/6pofx/benxin) 是一个 Spring Boot 插件：**声明式**地调用 LLM，
并且**一切皆可插拔**（模型 / 协议 / 循环 / 工具 / 上下文 / 记忆 / 提示词全都能换掉，而调用方的写法不变）。
写一个接口、打一个注解，剩下的交给容器：

```java
@LlmAgent(model = "deepseek", loop = "claude-code", tools = {WeatherTools.class})
public interface WeatherAssistant {
    String ask(String question);
}

@Autowired WeatherAssistant assistant;   // 注入即用
```

**本仓库是它的示例工程**，回答三个问题：

1. **每个功能具体怎么用？** —— 每个切面一个子项目，`mvn spring-boot:run` 就能看到输出。
2. **我猜的语义对不对？** —— 演示里的每一句话都有 `@SpringBootTest` 断言，`mvn test` 会告诉你答案。
3. **换个版本会不会坏？** —— 848 个用例就是"防腐烂层"；插件升级后跑一遍，就知道哪条口径变了。

本工程是**使用者视角**的：只从 Maven 仓库消费 `com.benxin:benxin-spring-boot-starter` 的二进制，
**不把插件当源码依赖，也不修改插件源码** —— 所以这里写出来的每一行代码，第三方使用者都能原样照抄。

```
https://github.com/6pofx/benxin            插件本体（MIT，独立仓库）—— 本仓库只依赖、不修改
              │  mvn install  →  本地 Maven 仓库
              ▼
https://github.com/6pofx/benxin_example    本仓库：16 个 Maven 项目（聚合 POM + 15 个子项目）/ 848 个用例
```

> 仓库名是 `benxin_example`，工程内目录名与 Maven `artifactId` 仍是 `benxin_exp` / `benxin-exp`。
> 克隆下来的目录名是 `benxin_example`，下文命令请以此为准。

---

## 2. 快速开始

### 2.1 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17+** | 以 `--release 17` 编译；在 JDK 24 上实测通过 |
| Maven | **3.9+** | 多模块工程需要较新的 Maven |
| 系统 | Windows / macOS / Linux | 在 Windows 11 + PowerShell 7 上开发与验证 |
| 数据库 | **可选** | exp-10 默认用 H2 文件库，零配置可跑；只有 `mysql` profile 需要本机 MySQL 8 |
| 本机小模型 | **可选** | exp-12 / exp-13 默认 `auto` 探测 LM Studio：不可达时 exp-12 退回离线演示、exp-13 跳过需要模型的那张图 |

### 2.2 三步跑起来

```bash
# ① 前置：插件本体不在本仓库，先从源码装进本地 Maven 仓库
#    （com.benxin:* 目前没有发布到 Maven Central，这一步跳不过去）
git clone https://github.com/6pofx/benxin.git
cd benxin
git checkout feature/java-flow   # exp-13/exp-14 需要这个分支；只跑 exp-01~12 用 v1.1.1 即可
mvn install -DskipTests
cd ..

# ② 构建本工程
git clone https://github.com/6pofx/benxin_example.git
cd benxin_example
mvn install -DskipTests

# ③ 跑任意一个示例（默认离线、零 token）
cd exp-01-declarative-agent
mvn spring-boot:run
```

一次跑完全部 **848** 个用例（离线可跑，不会烧掉一个 token）：

```bash
cd benxin_example
mvn test
```

> **⚠️ exp-13 / exp-14 需要比 `v1.1.1` 更新的插件。**
> 工作流 Java 节点（`type: java` + `@LlmNode`）与代码编排（`flow` / `JavaFlow`）都是
> 1.1.1 之后新增的能力，**尚未进入任何 tag**，目前挂在插件的
> [`feature/java-flow`](https://github.com/6pofx/benxin/tree/feature/java-flow) 分支上（上面第 ① 步就是切到这个分支）。
> exp-01 ~ exp-12 用 `v1.1.1` 就能全部跑通；exp-13 / exp-14 需要这份包含新功能的构建。
> 手里只有 `v1.1.1` 时，把根 `pom.xml` 里这两个 module 注释掉即可。
> 判断插件是否够新：`jar tf ~/.m2/repository/com/benxin/benxin-core/1.1.1/benxin-core-1.1.1.jar | grep -E "LlmNode|JavaFlow"`。

> **`-o` 离线开关**：本机已缓存全部依赖后，所有命令都可以加 `-o`（如 `mvn -o test`）以完全断网运行。
> 首次构建请不要加，否则会因为缺依赖而失败。

---

## 3. 工程结构

`benxin_example` 是一个聚合 POM（Spring Boot 3.5.5 / Java 17），下挂 15 个模块。

**① `exp-common`：所有子项目共享的离线基础设施**（不是 Spring Boot 应用，本身不带用例）

```
├── model/ScriptedLlmModel       脚本化模型：零 token 驱动完整 Agent 循环
├── model/RecordingModel         录制型装饰器：把"真正发出去的请求"留档
├── tool/CommonTools             多模块共用工具集（天气/时间/计算/上下文）
├── wire/StubHttpServer          本地 HTTP 桩：抓真实报文 + 回放 JSON / SSE
├── wire/{OpenAi,Anthropic,Gemini,Responses}Wire   四协议报文与 SSE 帧构造器
├── db/{SimpleDataSource,Db}     纯 JDBC 小工具（建库 / 建表 / 查询）
└── demo/Demo                    统一演示输出
```

**② 14 个子项目：一个切面一个项目，每个都能独立启动**

| 模块 | 用例 | 演示什么 |
|---|---|---|
| [exp-01-declarative-agent](exp-01-declarative-agent/README.md) | 20 | 声明式 Agent：注解全属性 / 参数语义 / 返回值全谱 / 记忆 |
| [exp-02-tools](exp-02-tools/README.md) | 38 | 工具：`@LlmTool` 全谱 / `ToolContext` / 程序化 `ToolCallback` / 动态登记 |
| [exp-03-builtin-sandbox](exp-03-builtin-sandbox/README.md) | 71 | 8 个内置工具 + 三重开关 + PathSandbox/CommandSandbox + 四种审批 |
| [exp-04-loops](exp-04-loops/README.md) | 38 | 8 个内置 Loop（第 9 个 `flow` 见 exp-14）+ 自定义 `@LlmLoop` + 子代理派生 |
| [exp-05-workflow](exp-05-workflow/README.md) | 179 | 声明式工作流：六种节点 / 条件表达式 / 模板 / 容错 / 三道防线 |
| [exp-06-context-memory](exp-06-context-memory/README.md) | 78 | 记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态 |
| [exp-07-models-protocols](exp-07-models-protocols/README.md) | 77 | 四协议线级报文 + 自定义协议 / 传输 / 模型装饰器 |
| [exp-08-observability-web](exp-08-observability-web/README.md) | 79 | 监听器全回调 + 拦截器全钩子 + 流式 + HTTP 端点 |
| [exp-09-spring-override](exp-09-spring-override/README.md) | 104 | 逐个切面的 bean 覆盖 / `llm.enabled` / 程序化装配 / 裸 core 门面 |
| [exp-10-database](exp-10-database/README.md) | 72 | MySQL + H2 复杂示例：JDBC 记忆 + 查库工具 + 运行审计 + 工作流入库 |
| [exp-11-real-deepseek](exp-11-real-deepseek/README.md) | 19 | 真实 DeepSeek 联调（默认 skip，显式开关才联网） |
| [exp-12-local-lmstudio](exp-12-local-lmstudio/README.md) | 19 | 本机小模型（LM Studio / Qwen3-VL-8B）：对话 / 工具 / 流式 / **视觉** |
| [exp-13-workflow-java-node](exp-13-workflow-java-node/README.md) | 26 | 工作流 **Java 节点**（`type: java` + `@LlmNode`）：注册制 / 两条输出通道 / 失败语义，模型用本机 LM Studio |
| [exp-14-expense-approval-flow](exp-14-expense-approval-flow/README.md) | 28 | **代码编排**（`flow` / `JavaFlow`）跑真实业务：差旅报销审批，模型用本机 LM Studio + 输出契约 |

每个子项目都是**完整的 Spring Boot 应用**：有 `@SpringBootApplication`、有 `application.yml`、
可 `mvn spring-boot:run`、可打成可执行 jar、有 `@SpringBootTest` 测试与**中文 README**。
每个模块的 README 都写了"这个模块演示了什么 / 结论是什么 / 为什么这么写"，建议从模块入口顺着看。

---

## 4. 怎么跑

### 4.1 跑演示

```bash
# 方式一：直接跑（会先编译，最省事）
cd exp-01-declarative-agent
mvn spring-boot:run

# 方式二：跑打好的可执行包
cd ..
mvn package -DskipTests
java -Dstdout.encoding=UTF-8 -jar exp-01-declarative-agent/target/exp-01-declarative-agent-1.0.0.jar
```

> Windows 控制台如果中文乱码，加 `-Dstdout.encoding=UTF-8`，或先执行 `chcp 65001`。

**Web 模块（exp-08）**默认是一个常驻的 Web 应用（`server.port: 0`，由系统分配端口，启动日志里会打印）。
想"跑完演示就退出"（比如在 CI 里一次性验证），加上开关：

```bash
java -Dstdout.encoding=UTF-8 -Dexp.demo.exit-after-run=true \
     -jar exp-08-observability-web/target/exp-08-observability-web-1.0.0.jar
```

**数据库模块（exp-10）**默认用 H2 文件库，零配置即可跑；换本机 MySQL 加一个 profile：

```bash
java -jar exp-10-database/target/exp-10-database-1.0.0.jar --spring.profiles.active=mysql
```

### 4.2 跑测试

```bash
# 全部模块（16 个 reactor 项目）
cd benxin_example
mvn test

# 单个模块
mvn -pl exp-05-workflow test

# 单个用例
mvn -pl exp-05-workflow test -Dtest=WorkflowEngineTest
```

> ⚠️ **换插件版本后请 `mvn clean test`**：增量编译不会重新编译"源码没变"的模块，
> 但它们的依赖 jar 已经换了版本，容易出现 `NoSuchMethodError` 这种假故障。

---

## 5. Token 消耗：默认为零

| 模块 | 默认是否联网 | 说明 |
|---|---|---|
| exp-01 ~ exp-10 | **否** | 全部由 `exp-common` 的 `ScriptedLlmModel` 驱动：它按规则模拟"模型决策"，能完整跑通「模型决策 → 工具调用 → 结果回灌 → 收尾」，包括流式增量与工具参数分片 |
| exp-11 | **否（默认）** | 只有显式打开开关（`-Dexp.real.enabled=true` + 提供 key）才会调用 DeepSeek |
| exp-12 | **否（默认 `auto` 探测）** | 端点只有 `127.0.0.1:1234`（本机 LM Studio）：可达就跑真实演示，不可达就只跑离线演示；**本地推理不花钱、不出网** |
| exp-13 | **否（默认 `auto` 探测）** | 同样只连本机 LM Studio；**没有离线 mock 模型**（要演示"模型会数错、本地不会"），不可达时跳过需要模型的那张图，另两张图（失败语义、合规闸门）零模型调用照跑 |
| exp-14 | **否（默认 `auto` 探测）** | 同样只连本机 LM Studio；一次完整演示（5 个场景）约 1629 tokens，**全部本地推理**；"无法受理"那条路径 0 token（图里到不了模型节点） |

因此 `mvn test` 在离线环境与 CI 里都能稳定通过，**不会烧掉一个 token**
（exp-12 若本机开着 LM Studio 会顺带跑 7 个本地用例、exp-13 跑 1 个、exp-14 跑 3 个，同样零成本）。

exp-11 里做了完整的 token 控制：`max-tokens: 48`、`max-retries: 0`、`temperature: 0`、
每个演示只调一次模型；错误路径用**故意写错的 key**（上游 401 拒绝，**0 token**）。
一次完整真实演示的实测消耗是 **2,210 tokens**（10 次调用），
逐次用量表写在 [`exp-11-real-deepseek/README.md`](exp-11-real-deepseek/README.md)。

---

## 6. 运行参数速查

| 模块 | 开关 | 默认 | 作用 |
|---|---|---|---|
| exp-08 | `exp.demo.exit-after-run=true` | `false` | 演示跑完即退出 JVM（否则保持 Web 应用常驻） |
| exp-10 | `--spring.profiles.active=mysql` | `h2` | 切到本机 MySQL（需要 JDK 侧能连上） |
| exp-10 | 环境变量 `MYSQL_PASSWORD` | `123456` | MySQL 密码占位符的默认值，部署时用环境变量覆盖 |
| exp-11 | `exp.real.enabled=true` | `false` | 真实调用总开关；也可用 `--spring.profiles.active=real` |
| exp-11 | `DEEPSEEK_API_KEY` / `-Ddeepseek.api-key=` | 空 | key 三级兜底，优先环境变量 |
| exp-12 | `exp.local.mode` | `auto` | `auto` 探测 / `on` 强制（不可达即失败）/ `off` 强制离线 |
| exp-12 | `exp.local.base-url` | `http://127.0.0.1:1234` | LM Studio 端点（模型端点侧写 `.../v1`） |
| exp-12 | 环境变量 `LOCAL_MODEL_ID` | `qwen/qwen3-vl-8b` | 换成本机已加载的其它模型 id |
| exp-13 | `exp.local.mode` | `auto` | 同 exp-12；本模块没有离线模型，`on` 时连不上会明确报错 |
| exp-13 | 环境变量 `LOCAL_MODEL_ID` | `qwen/qwen3-vl-8b` | 换成本机已加载的其它模型 id |
| exp-14 | `exp.expense.auto-approve-limit` / `manager-limit` / `single-claim-limit` | `2000` / `10000` / `20000` | 报销分级阈值（业务规则，改配置不改图） |
| exp-14 | `exp.demo.print-graph` | `true` | 演示时是否打印 `Mermaid.toMermaid` 导出的图 |

> 本仓库**不含任何真实密钥**：`application.yml` 里的 `sk-*` 全是 `sk-demo` / `sk-test` 这类占位符，
> 数据库密码走 `${MYSQL_PASSWORD:123456}` 这类环境变量占位。

---

## 7. 功能覆盖矩阵

### 7.1 可插拔切面

| 切面 | 接口 | 演示位置 |
|---|---|---|
| 模型 | `LlmModel` | exp-07（bean / `@LlmModelDef` / 程序化 / 装饰器）、exp-09（覆盖 `ModelRegistry`） |
| 协议 | `ProtocolCodec` | exp-07（四协议线级 + 自定义协议 + 同名覆盖 + 未注册快速失败） |
| 传输 | `HttpTransport` | exp-07（录制装饰器 / 完全假实现 / `postStreaming` 误用报错）、exp-11（报文留档） |
| 循环 | `AgentLoop` | exp-04（8 个离线内置 + 2 个自定义 + `defaultLoop`）、exp-05（`workflow` / `staged`）、**exp-14（`flow` 代码编排）** |
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

### 7.2 注解

| 注解 | 演示位置 |
|---|---|
| `@LlmAgent`（14 个属性） | exp-01（全属性）、exp-04（`subAgents`）、exp-08（`interceptors` / `stream`） |
| `@LlmLoop`（`value` / `defaultLoop` / `order`） | exp-04、exp-09（`defaultLoop` 选举 / `order` 最小者胜出，见 FINDINGS 20/21） |
| `@LlmModelDef`（`value` / `defaultModel` / `order`） | exp-07、exp-09 |
| `@LlmTool`（含类级、`returnDirect`、`enabled`） | exp-02 |
| `@LlmToolParam`（5 个属性 + 参数类型全谱） | exp-02 |
| `@SystemPrompt`（`value` / `append` / `inherit`） | exp-01 |
| `@User`（`prefix` / `suffix`） / `@Assistant` | exp-01 |
| `@Ctx`（`value` / `attribute`） | exp-01 |
| `@Memory` | exp-01、exp-06、exp-10 |
| `@LlmGuard`（`global` / `agents`） | exp-08 |
| `@EnableLlmAgents`（`basePackages` / `basePackageClasses`） | exp-01、exp-09 |
| `@LlmRetry` | exp-09（1.1.1 起真的被消费：模型包一层 `RetryingLlmModel`，`includeTools` 时工具再包 `RetryingToolCallback`） |
| `@LlmNode`（工作流 Java 节点处理器） | exp-13（注册制：名字即契约、缺注解启动失败、未注册启动失败） |

### 7.3 运行期能力

| 能力 | 演示位置 |
|---|---|
| 返回类型自适应（9 种） | exp-01 |
| 流式（`LlmStreamHandler` 7 个回调 + `StreamCollector` + `ofText`） | exp-01、exp-08、exp-11、exp-12 |
| 工具调用五道关卡 | exp-02、exp-03、exp-08、exp-10 |
| 工具参数类型 → JSON Schema 生成 | exp-02（打印生成的 schema，并暴露 schema 相关缺陷） |
| 步数预算与 `hard-max-steps` | exp-04、exp-05 |
| 子代理派生与用量补记 | exp-04（注解 / 编程式 / `ToolContext.spawn` 三种） |
| 工作流六节点 + 条件表达式 + 模板 + 三道防线 | exp-05（含纯引擎 + 假 `WorkflowRuntime` 的离线验证） |
| **工作流 Java 节点**（第七种节点 + 两条输出通道 + 注册制） | **exp-13**（真实模型参与的主图 + 两张零模型调用的图） |
| **代码编排**（`flow` / `JavaFlow`：图写在 Java 里、节点是对象、边是谓词） | **exp-14**（真实业务：差旅报销审批，17 节点 / 23 边 / 4 出口 / 2 回边） |
| **输出契约 `choice`**（模型只许吐枚举值：归一化 + 纠正重试 + 用尽即失败） | **exp-14**（四个模型节点都带契约，下游 `caseOf` 精确匹配） |
| **`Mermaid.toMermaid`**（把代码里的图导回去和设计稿比对） | **exp-14**（演示直接打印导出结果） |
| HTTP 端点（5 个）与 SSE 事件 | exp-08（含"按直觉猜会猜错"的契约表逐条断言） |
| `AgentResult` 全字段 | exp-01、exp-08 |
| 会话 / 历史 / `AgentSession` | exp-01、exp-06、exp-10、exp-12 |
| 多模态消息（`ImagePart` 四协议编码差异） | exp-06、exp-07（报文层）、**exp-12（真实模型看懂图）** |
| 数据库持久化（记忆 / 审计 / 查库 / 审批 / 事务） | exp-06、exp-10 |
| 真实厂商联调（两种协议 + 报文留档 + 用量统计） | exp-11 |
| 真实本机模型（function calling / 流式 / 视觉 / 多轮） | **exp-12** |

---

## 8. 它也是一套兼容性回归套件

插件 **1.1.0** 时期，本工程的各个模块实测发现并上报了 **31 条**问题（每条都有复现位置：
`插件源码:行号` + 本工程的测试名，以及"实际 vs 预期"）。
插件作者在 **1.1.1** 里修掉了其中 **30 条**（第 23 条经复核不是缺陷，只是"测试夹具别放主应用包下"的提醒），
于是本工程把对应断言从"钉住旧行为"**翻转成"钉住新行为"** —— 这 848 个用例因此同时成为这 30 条修复的回归测试。

其中原属"高危 / 高价值"的四条：

| 原编号 | 问题 | 1.1.1 的修法 |
|---|---|---|
| 16（高危） | `SlidingWindowCompactor` 触发压缩后只剩最后 1 条 | 重写为"`keepRecent` 是下限，预算内尽量多留" |
| 13（高） | 条件里的 `${x:-兜底}` 静默失效 | 按与模板一致的语义拆 `:-`，兜底按字面量解析 |
| 30（中高） | `toBuilder().model/loop("名字")` 静默无效 | 两个重载"后来者胜" |
| 26（中） | `beforeTool` 短路 `break` 掉整条拦截器链 | 短路后继续通知其余拦截器 |

验证这次升级时，本套件还抓到 **2 条新引入的回归**：

| # | 回归 | 位置 |
|---|---|---|
| **32** | 未知 Agent 的 **SSE 端点由 404 变成 500**（修 charset 时把 `setContentType` 提到了 `agentRegistry.get()` 之前，异常处理器要写的 Map 没有可用转换器） | `LlmAgentController.stream` |
| **33** | **私有的 4 参 `create` 绕过子类覆写的公开 3 参 `create`**（为支持 `@LlmRetry` 新增私有重载，`createFromInterface` 直接调它，覆写公开重载的子类被静默绕过） | `LlmAgentFactory` |

完整清单、复现方式与建议改法见 **[`FINDINGS.md`](FINDINGS.md)**。

> 本项目**没有修改插件源码的任何一行**，问题只以"断言 + 注释"的形式记录在示例工程里。

---

## 9. 写示例时遵守的约定

1. **离线优先**：默认路径不得联网；需要真实网络的演示必须放在显式开关之后，并且默认 skip 而不是 fail。
2. **演示即文档**：每个模块都有 `ApplicationRunner`，`mvn spring-boot:run` 就能看到结论；
   结论用 `com.benxin.exp.common.demo.Demo` 统一输出。
3. **结论必须被断言**：演示里说的每一句话，都在 `@SpringBootTest` 里有对应断言 ——
   848 个用例就是这个工程的"防腐烂层"（插件升级后跑一遍就知道哪条口径变了）。
4. **注释解释"为什么"**：尤其是"按直觉会猜错"的地方，代码里写清原因。
5. **不修改插件**：发现插件缺陷只记录、不修改源码；示例若必须绕开，
   注释里注明这是绕开而不是正确用法。

---

## 10. 版本管理

本工程是一个独立的 Git 仓库（`main` 分支）：

```bash
git status                  # 提交前先看一眼
mvn -o -B clean test        # 提交前跑一遍（14/14 绿才提交）
git add -A && git commit -m "feat(exp-03): ..."
```

| 约定 | 说明 |
|---|---|
| **只提交源码与文档** | `target/`、`*.log`、H2 文件库（`*.mv.db`）、沙箱目录、报文留档（`wire-log/`）、`.idea/` 全部在 `.gitignore` 里，**不要** `git add -f` 绕过 |
| **换行符** | `.gitattributes` 统一 `eol=lf`（`*.cmd` / `*.bat` 保持 CRLF），避免 Windows 上整批文件显示为"已修改" |
| **提交信息** | `<type>(<scope>): <中文摘要>`，`scope` 用模块名（如 `exp-02`、`common`、`docs`） |
| **不提交密钥** | exp-11 的 key 一律走 `${DEEPSEEK_API_KEY:}` 占位符；提交前可用 `git diff --cached \| findstr /R "sk-[A-Za-z0-9]\{20,\}"` 自查 |
| **插件源码不在本仓库** | 插件是独立仓库 [`6pofx/benxin`](https://github.com/6pofx/benxin)，本仓库只消费它的二进制 |

欢迎提 Issue 讨论示例写法或补充新场景；如果发现的是**插件本身**的问题，请提到
[`6pofx/benxin`](https://github.com/6pofx/benxin) 的 issue 里，示例仓库只负责"报告现象 + 钉住断言"。

---

## 11. License

[MIT](LICENSE) © 2026 6pofx

示例代码可以随意复制、修改、商用；引用的「本心」插件本体同为 MIT 许可。
