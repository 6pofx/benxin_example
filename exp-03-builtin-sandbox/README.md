# exp-03 · 内置工具 + 沙箱 + 审批

> 模型说"帮我读一下 ../.env"的时候，到底有几道闸会拦住它？这个模块把每一道都摆出来数一遍。

被演示的能力只有一个切面：**本心的 8 个内置工具、两道机器级围栏（`PathSandbox` / `CommandSandbox`）、
以及人的点头（`ApprovalPolicy` + `ApprovalHandler`）**。
**默认离线、零 token**：模型来自 `exp-common` 的脚本化模型，`mvn spring-boot:run` 立刻能看到全部结论。

所有文件操作都落在本模块的 `target/sandbox` 之内，`bash` 只跑 `echo`；
`rm -rf /`、`mkfs`、fork bomb 这些只用 `CommandSandbox.denyReason(...)` 做**静态判定**演示，**一条都不会真的执行**。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| 8 个内置工具 | `read`（offset/limit）、`write`（覆盖写 + 自动建父目录）、`edit`（多处匹配即拒绝 / `replace_all`）、`glob`（`**/*.txt`）、`grep`（include / ignore_case / max_results）、`bash`（`echo`）、`todo_write`（运行属性 + 事件）、`task`（派生子代理） |
| 三重开关 | `builtin-enabled` / `allow-write` / `allow-exec` / `enabled`（增量）/ `allowed-commands`（替换）/ `denied-commands`（**追加**） |
| `PathSandbox` | 目录穿越、`.git/`、`.env`、`id_rsa`、`*.pem`、`*.key`、`credentials*` 的**中文拒绝原因**；静态 API `resolve` / `isInside` / `isDeniedPath` / `firstDeniedPattern` / `globLiteralPrefix` / `realOrNearest` |
| `CommandSandbox` | 三道闸：`allow-exec` 总闸 → 内置 **29 条**黑名单 → 白名单前缀；`denyReason` / `deniedCommands` |
| `ApprovalPolicy` | `NEVER`（不问、放行）/ `ON_REQUEST`（默认）/ `ALWAYS` / `ON_FAILURE` 四态对照表 |
| `ApprovalHandler` | `autoApprove()` / `denyAll()` / 自定义（按工具名 + 参数 + sessionId 决定） |
| 硬约束 | 沙箱拒绝**不可被审批覆盖**（`ON_FAILURE` + 永远批准也照样拒绝），且顺序是「沙箱 → 审批」 |
| 可替换守门人 | 自定义 `ToolSandbox` 实现 `check()` 整体接管；`CompositeSandbox.of(...)` 取最严结论 |
| 纵深防御 | 把沙箱换成 `ToolSandbox.permissive()`，内置 `read` 越界读**仍然**被拒绝 |
| 开关无关 | `builtin-enabled=false` 时工具表为空、但沙箱依旧是真正的 `PathSandbox + CommandSandbox`，自写 `@LlmTool` 同样受围栏约束 |
| `SandboxConfig` | 全字段、`defaults()`、`DEFAULT_DENIED_COMMANDS`（29）/ `DEFAULT_DENIED_PATH_PATTERNS`（19）、`workdirAbsolute` / `workdirReal` / `fence()`、非法值报错 |

## 谁是"模型"，谁是"工具"

```
RecordingModel            ← 留档每次真正下发的请求（exp-common 提供）
 └─ OnceForcedToolModel   ← 给 #tool: 语法补两个工程化处理（见下）
    └─ ScriptedLlmModel   ← exp-common 的脚本模型，真正做决策
```

`OnceForcedToolModel` 只补两件 `ScriptedLlmModel` 做不到的事，其余决策全部原样交给它：

1. **花括号配平**：`ScriptedLlmModel` 用 `#tool:(\w+)\s*(\{.*?})?` 抓参数，非贪婪的 `.*?` 会在**第一个**
   `}` 处停下，于是任何"对象数组"参数（`todo_write` 的 `{"todos":[{"content":"x","status":"pending"}]}`）
   都会在到达工具之前被截断成非法 JSON，表现为莫名其妙的「参数 todos 必填」。这里改成深度配平扫描。
2. **一次运行只强制一次**：原规则在**每一轮**都会命中同一条 user 消息，于是"强制一次工具"实际是
   "每轮都调这个工具"，一路撞到步数上限、模型永远没机会收尾。这里约定拿到工具结果后不再强制，
   把收尾交回脚本模型 —— 演示因此能完整跑通「模型决策 → 工具执行 → 结果回灌 → 收尾」。

## 运行

```bash
cd exp-03-builtin-sandbox
mvn -o test                       # 71 个离线用例，零 token
mvn -o spring-boot:run            # 启动即打印 19 组演示
```

> **本模块没有写进 `benxin_exp/pom.xml` 的 `<modules>`**（那个文件不在本模块可修改的范围内），
> 所以要在模块目录里单独构建；父 POM 通过 `<relativePath>../pom.xml</relativePath>` 正常解析，
> `exp-common` 从本地仓库取 `com.benxin.exp:exp-common:1.0.0`。

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8，或
`java -Dstdout.encoding=UTF-8 -jar target/exp-03-builtin-sandbox-1.0.0.jar`。

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论：

1. **`ApprovalPolicy.NEVER` 是"不问、直接放行"**，是四态里**最宽松**的一档，不是"不问就拒绝"。
   测试里用一个"永远拒绝"的 handler 证明它照样放行；想一律拒绝请用 `ApprovalHandler.denyAll()`。
2. **`ON_FAILURE` 在 v1.1.0 里与 `NEVER` 完全等价。** 文档（`docs/ARCHITECTURE.md` 六、README 内置工具章节）
   说它是"被沙箱拒绝或执行失败后再问一次"，但 `DefaultLoopContext#needsApproval` 对它直接 `return false`，
   而沙箱拒绝分支压根不进入审批 —— 所以它一次都不会问。（详见下方「插件问题」第 1 条。）
3. **沙箱拒绝是硬约束，审批推翻不了；而且顺序是「沙箱 → 审批」。**
   `ON_FAILURE` + 永远批准的 handler 读 `../../etc/passwd`，依然被拒绝，且审批被问 0 次；
   `ALWAYS` 下同样越界读也还是 0 次 —— 沙箱在审批之前就拦下了。
4. **`enabled` 是增量，不是白名单。** 只读三件套（`read`/`glob`/`grep`）永远是基线：
   `enabled: [read]` 的最终结果是 `read + glob + grep`。
   而 `todo_write` 与 `task` **不在任何默认基线里**，必须显式点名。
5. **`enabled` 里点名写/执行类工具等价于同时授权。** `enabled: [write]` 会得到 `write + edit`，
   并且沙箱同步变成 `allowWrite=true`（不会出现"工具看得见却跑不动"）。
6. **`denied-commands` 是追加，`allowed-commands` 是替换。**
   yml 里加一条 `docker`，内置那 29 条一条都没少（实际生效 30 条）；
   而 `allowed-commands` 一旦非空就是白名单：**每一条子命令**都必须命中，`echo ok && git status` 会被整串拒绝。
7. **`builtin-enabled=false` 只是"不装配内置工具"，不是"没有沙箱"。**
   容器里的 `ToolSandbox` 依然是 `CompositeSandbox(PathSandbox, CommandSandbox)`，
   自己写的 `@LlmTool` 一样会被围栏拦住。
8. **`ApprovalHandler` 的默认 bean 是 `autoApprove`**（`@ConditionalOnMissingBean`）。
   也就是说：不自己配 handler 时，"问了也是白问"。
9. **定义任意一个 `AgentListener` bean，内置的 `LoggingListener` 就会让位**（同样是 `@ConditionalOnMissingBean`），
   `llm.agent.logging-listener: true` 随之失效。本模块的 `ToolAuditListener` 就是这么接管日志的 ——
   想要两者兼得请自己组一个 `CompositeListener`。
10. **`AgentResult.toolCalls()` 里没有 meta。** meta 被明确设计成"不进模型上下文"的旁路数据，
    想看 `exitCode` / `matches` / `subAgent` 这些只能挂在 `AgentListener#onToolResult`（本模块的 `ToolAuditListener`）。
11. **`todo_write` 写的是"运行属性"，生命周期是「一次 `call`」而不是「一个会话」。**
    同一个 Agent 的第二次运行读不到上一次的清单；`AgentResult.attributes()` 能看到本次那一份。
12. **路径围栏只认特定参数键名**：`path / file / filepath / filename / pattern / dir / directory`
    （大小写与 `_`/`-` 无关）。自写工具想被 `PathSandbox` 拦住，参数就得叫这些名字之一 ——
    本模块的 `guarded_echo(@LlmToolParam("path") ...)` 就是靠这个被拦住的。
13. **`glob` / `grep` 会静默过滤敏感路径**（连文件名都不列出来），只在结尾补一句"另有 N 个被沙箱规则过滤"。
14. **`read` 的输出是 `%6d\t` 行号 + 制表符**，写断言时别用空格去匹配。
15. **`task` 需要 `@LlmAgent(subAgents = {...})`**，否则 `ToolContext.canSpawnSubAgent()` 为 false，
    工具会返回"当前 Agent 未配置子代理能力"；点名一个没声明的子代理则报"未注册子代理"。
16. **`edit` 在 CRLF 文件上会把 LF 目标重试成 CRLF**，避免"换行符不一致"造成的假性找不到；
    但 `old_string` 命中**多处**且 `replace_all=false` 时一定拒绝，绝不"随手改第一处"。

## 插件问题（示例工程观察到的行为与文档不一致 / 缺陷）

> **状态（1.1.1）**：本节记录的是 **1.1.0** 当时的观察。插件 1.1.1 已修复本工程上报的 31 条中的 30 条
> （逐条对应与回归断言见根目录 `FINDINGS.md` 第一节.5）；本模块的断言已同步为"钉住新行为"。

完整证据见最终汇报，这里留三条最值得注意的：

1. `ApprovalPolicy.ON_FAILURE` 的实际行为是"从不询问"（= `NEVER`），与
   `docs/ARCHITECTURE.md` 六、`README.md` 内置工具章节的描述不一致。
2. `@LlmAgent(subAgents = {...})` 装配后 `AgentSpec.subAgents()` 会出现**重复条目**
   （`[researchAssistant, researchAssistant]`）：`LlmAgentFactory#create` 先按 `specOf()` 拿到含子代理名的 spec，
   再对每个名字调用 `AgentBuilder#subAgent(name, agent)`，而后者会往 `spec.subAgents` 里**追加**一次。
   当前无功能影响，但会让 `/llm/agents` 之类的展示与任何按列表长度判断的逻辑失真。
3. `PathSandbox.globLiteralPrefix` 在 Windows 上会把 POSIX 风格的绝对表达式 `/etc/**` 解析成
   **围栏内**的 `<workdir>/etc`（而 `/**` 却会正确判成盘根）。
   由于 `GlobTool` 自己也会把前导 `/` 去掉按相对路径处理，目前不会造成真实越界，
   但围栏判定给出的结论本身是错的。

## 目录

```
src/main/java/com/benxin/exp/sandbox/
├── BuiltinSandboxApplication.java          @SpringBootApplication + @EnableLlmAgents
├── model/OnceForcedToolModel.java          #tool: 的花括号配平 + 一次运行只强制一次
├── agent/
│   ├── SandboxProbe.java                   所有 #tool: 指令的执行体（subAgents 在这里声明）
│   └── ResearchAssistant.java              被 task 工具派生的子代理
├── audit/ToolAuditListener.java            工具结果（含 meta）与子代理派生留痕
├── tool/SandboxGuardTools.java             自写 @LlmTool：证明它也受围栏约束、能读运行属性
├── sandbox/
│   ├── SandboxAgentFactory.java            程序化装配（四态审批靠它换 approvalPolicy）
│   ├── RecordingApprovalHandler.java       会记账的 autoApprove / denyAll
│   └── WorkdirScopedApprovalHandler.java   自定义审批：按工具名 + 参数 + sessionId 决定
├── workspace/SandboxWorkspace.java         建 target/sandbox 这棵树（含敏感路径靶子）
├── support/BuiltinToolTable.java           三重开关的离线演算台
├── config/                                离线模型链、自写工具、SandboxConfig 视图
└── demo/
    ├── BuiltinSandboxDemo.java             19 组演示的编排 + 8 个工具的逐个调用
    ├── SandboxFenceDemo.java               PathSandbox / CommandSandbox / SandboxConfig
    └── ApprovalGateDemo.java               审批四态 / handler / 自定义沙箱 / 纵深防御
src/main/resources/application.yml          三重开关全开 + allowed-commands=[echo,cd] + denied-commands=[docker]
src/test/java/com/benxin/exp/sandbox/
├── BuiltinToolTest.java                    22 个用例：8 个内置工具逐条断言
├── SandboxFenceTest.java                   17 个用例：围栏、命令门禁、SandboxConfig
├── ApprovalGateTest.java                   15 个用例：四态、handler、硬约束、自定义沙箱、纵深防御
├── BuiltinToolSwitchTest.java              14 个用例：ApplicationContextRunner 多容器对照三重开关
└── ApprovalPolicyAlwaysTest.java            3 个用例：llm.agent.approval-policy=always 这条 yml 路径
```
