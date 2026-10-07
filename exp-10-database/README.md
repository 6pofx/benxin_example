# exp-10 · 数据库：一个真的会「落库」的智能订单助手

> 「本心 benxin」插件示例工程里**最复杂**的一个子项目。
> 它用一张真实的数据库，把**记忆落库 + 工具查库 + 运行审计 + 工具审计 + 写库审批 + 工作流驱动的审批 + 事务一致性**
> 串成一个端到端的订单助手 —— 每一节演示结束都会打印六张表的行数，这是「数据真的落库了、不是内存里演了一遍」最直接的证据。

- **默认零配置可跑**：数据源是 H2 文件库（落在本模块 `target/` 下），不需要本机装任何数据库。
- **一键切真库**：加 `--spring.profiles.active=mysql` 就切到本机 MySQL 8，schema / 数据脚本 / 工作流 YAML / Java 代码**一行都不用改**。
- **完全离线、零 token**：模型是 `exp-common` 的脚本化离线模型，不联网、不消耗 token。

---

## 1. 业务背景

我们做的不是一个"能聊天"的助手，而是一个**必须对数据库负责**的助手。区别在于三条硬要求：

| 要求 | 为什么难 | 本示例怎么落地 |
| --- | --- | --- |
| 回答必须来自真实数据，不能编 | 模型天然会"顺着话说" | 只读能力全部做成工具（`query_orders` / `customer` / `statistics`），SQL 走 `JdbcTemplate` 参数绑定，答案里能逐字对上表里的值 |
| **写库**必须有人把关 | 模型不该拥有无条件的写权限 | 写工具 `requiresApproval() = true` + 自定义 `ApprovalHandler`：按金额阈值放行/拒绝；再叠一层风控沙箱（单笔上限） |
| 每一步都要能复盘 | "谁在什么时候、以什么理由动了哪一行" | 两张审计表：`agent_run`（一次运行一行）+ `tool_audit`（一次工具调用一行）；被拒的写操作进 `approval_request` 待办队列 |

再加两件真实系统里绕不开的事：

- **多轮对话要续得上，而且进程重启也要续得上** → 会话记忆不放在内存里，而是用 JDBC `MemoryStore` 落到 `agent_memory` 表。
- **审批流程本身也应该是"可编排"的** → 用一份 YAML 工作流把「模型判定 → 直调查库拿真实金额 → 按阈值分支 → 出审批说明」写成一张图，分支条件用的是**数据库里的真实数字**，不是模型的自述。

---

## 2. 表结构（六张表）

建表脚本：`src/main/resources/schema.sql`（幂等，`CREATE TABLE IF NOT EXISTS`）；种子数据：`data.sql`（先 `DELETE` 再 `INSERT`）。

```
customer ──< order_item            业务数据：客户与订单
agent_memory                       会话记忆：一条 ChatMessage 一行（JDBC MemoryStore）
agent_run                          运行审计：一次 Agent 运行一行（AgentListener）
tool_audit                         工具审计：一次工具调用一行（AgentInterceptor）
approval_request                   审批：被拒/转人工的写操作待办（ApprovalHandler）
```

### customer — 客户

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK 自增 | 张三=1、李四=2、王五=3 |
| `name` | VARCHAR(64) | 客户姓名 |
| `level` | VARCHAR(16) | `VIP` / `普通` |
| `phone` | VARCHAR(32) | 电话 |
| `created_at` | TIMESTAMP | |

### order_item — 订单

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK 自增 | |
| `customer_id` | BIGINT | 指向 `customer.id` |
| `sku` | VARCHAR(64) **UNIQUE** | ★ `uk_order_item_sku` 是「事务一致性」演示的靶子 |
| `amount` | DECIMAL(12,2) | 金额 |
| `status` | VARCHAR(16) | `PAID` / `PENDING` / `REFUNDED` |
| `created_at` | TIMESTAMP | |

种子数据：张三 3 笔（1380 + 1500 + 260 = **3140.00**）、李四 1 笔、王五 1 笔。

### agent_memory — 会话记忆

| 列 | 说明 |
| --- | --- |
| `session_id` | 会话 id（复合主键之一） |
| `seq` | 消息在会话内的顺序号（复合主键之二）—— `load()` 靠它还原顺序，**不依赖自增主键的插入顺序** |
| `role` | `USER` / `ASSISTANT` / `TOOL` / `SYSTEM` |
| `payload` | 整条消息的 JSON 快照（由 `ChatMessageCodec` 显式编解码） |
| `created_at` | |

### agent_run — 运行审计

| 列 | 说明 |
| --- | --- |
| `agent_name` / `session_id` / `loop_name` / `model_name` | 这次是谁、在哪个会话、用哪个 Loop 与模型跑的 |
| `steps` / `input_tokens` / `output_tokens` / `duration_ms` | 用量与耗时 |
| `finish_reason` / `max_steps_reached` | 结束原因；`max_steps_reached=1` 表示"没跑完、被步数预算截断" |
| `status` | `OK`（`onAgentEnd` 写）/ `FAILED`（`onError` 补偿写） |
| `error_message` / `output_summary` | 失败原因 / 输出摘要（没有模型文本时退化成输入摘要） |

### tool_audit — 工具调用审计

| 列 | 说明 |
| --- | --- |
| `tool_use_id` | 与 `AgentResult.toolCalls()` 里的 id 一一对应，能追到具体哪一次调用 |
| `tool_name` / `agent_name` / `session_id` / `step` | 谁在第几步调了什么 |
| `arguments` / `result_digest` | 参数与结果摘要（截断到 500 字） |
| `error` | 这次调用是不是错误结果 |
| `short_circuit` | ★ 被 `beforeTool` 短路（工具**没有**执行）时为 1 —— 这张表"能证明工具没被执行"的地方 |
| `duration_ms` | 耗时 |

### approval_request — 写操作审批

| 列 | 说明 |
| --- | --- |
| `tool_name` / `agent_name` / `session_id` / `arguments` | 谁请求做什么 |
| `reason` | 拒绝/放行的可读理由 |
| `decided_by` | `DbApprovalHandler`（**拒绝**时）或 `OrderWriteService`（**放行留痕**时） |
| `approved` | 0 = 未放行，1 = 已放行 |
| `status` | `PENDING_MANUAL` / `AUTO_APPROVED` |

---

## 3. 功能覆盖表

| # | 功能 | 实现 | 默认演示（`DatabaseDemo`） | 用例（`OrderAssistantTest`，H2/MySQL 同一套断言） |
| --- | --- | --- | --- | --- |
| ① | **JDBC MemoryStore** | `memory/JdbcMemoryStore` + `memory/ChatMessageCodec` | 第 1 节：多轮历史落库、会话隔离、`clear` 删行、**换一个全新的 MemoryStore 实例读同一个库**（跨重启） | `memoryIsPersistedToDatabase`、`memoryStoreIsTheJdbcOne`、`memoryIsIsolatedPerSessionAndClearDeletesRows`、`memorySurvivesANewApplicationContext`；`JdbcMemoryStoreStandaloneTest`（**不启动 Spring**，真的 new 两个 Agent 实例共享一个文件库） |
| ② | **工具查库** | `tool/OrderQueryTools`（`@LlmTool`）+ `db/OrderRepository` | 第 2 节：查订单 → 结果回灌 → **改一行数据，答案随之变化** | `toolQueriesTheDatabaseAndTheAnswerComesFromIt`、`changingARowChangesTheToolAnswer`、`queryCustomerReturnsRealRow`、`statisticsAreComputedInSql`、`dtoReturnTypeIsDeserialized` |
| ③ | **运行审计** | `audit/AgentRunAuditListener`（`AgentListener`） | 第 3 节：成功落 `OK`；失败时框架只调 `onError`，那行 `FAILED` 是**监听器补偿**写入的 | `successfulRunIsAudited`、`failedRunLeavesNoFrameworkRowAndListenerCompensates`、`auditRowsAreScopedByAgentAndSession` |
| ④ | **工具调用审计** | `audit/ToolAuditInterceptor`（`AgentInterceptor`）+ `guard/WriteToolShortCircuitInterceptor` | 第 4 节：正常调用留痕；**`beforeTool` 短路时工具没执行、审计仍留痕** | `toolCallsAreAudited`、`shortCircuitedToolIsStillAudited`、`failedToolCallIsAuditedAsError` |
| ⑤ | **写库工具的审批** | `tool/OrderWriteTools`（手写 `ToolCallback`，`requiresApproval()=true`）+ `approval/DbApprovalHandler` + `guard/RefundAmountSandbox` | 第 5 节：三种结局 —— 小额放行 / 大额拒绝转人工 / 沙箱先拒绝（审批一次都不问） | `smallAmountIsAutoApprovedAndOrderIsWritten`、`largeAmountIsRejectedAndQueuedForManualReview`、`manualReviewSessionAlwaysRejected`、`alwaysPolicyAsksForEveryToolCall`、`sandboxDenialShortCircuitsTheApprovalGate`、`refundWithinLimitsSucceeds` |
| ⑥ | **工作流驱动的入库审批** | `resources/workflows/order-compliance.yml` + `agent/ComplianceAssistant` | 第 6 节：真实总额 3140 > 500 走大额分支；把金额改成 100 就走小额分支；输入不是数字则走**补偿分支** | `workflowTakesHighAmountBranch`、`workflowBranchFollowsTheRealDatabaseValue`、`workflowCompensatesWhenToolNodeFails`、`workflowToolNodeDoesNotCallTheModel` |
| ⑦ | **事务一致性** | `tool/OrderWriteService`（`TransactionTemplate` 显式边界 + 两个反例） | 第 7 节：有事务 → 回滚得干干净净；没事务 → 半写入 | `explicitTransactionRollsBackBothWrites`、`withoutTransactionTheFirstWriteRemains`、`writeToolIsTransactionalViaTemplate`、`validationFailureLeavesNothingBehind` |
| ⑧ | **多轮追问** | `@LlmAgent(memory=true)` + `agent_memory` | 第 8 节：第二轮依赖第一轮的实体与工具结果，历史全在库里 | `multiTurnFollowUpUsesPreviousContext`、`withoutMemoryThereIsNoFollowUpContext` |
| — | 表结构与种子数据 | `schema.sql` / `data.sql` / `demo/DatabaseReset` | 收尾：六张表最终行数 | `schemaAndSeedDataAreComplete`、`dataSourceMatchesTheExpectedProduct` |

**用例总数：72 条，全绿。**

| 测试类 | 条数 | 跑在哪 |
| --- | --- | --- |
| `JdbcMemoryStoreStandaloneTest` | 6 | H2 文件库，**不启动 Spring**（真的 new 两个 Agent 实例共享一个库） |
| `OrderAssistantTest` | 33 | H2 内存库 |
| `OrderAssistantMysqlTest` | 33 | **真 MySQL 8.0.43**（继承上一类的全部断言 + 一条"跑在哪儿"的自证） |

> `dataSourceMatchesTheExpectedProduct` 这条不是凑数的：它断言"MySQL 那一份真的跑在 MySQL 上"。
> 加它之前，`OrderAssistantMysqlTest` 因为 `@SpringBootTest(properties=…)` 会与父类**合并**（而不是替换）
> 而**静默跑在 H2 上、照样全绿** —— 已修正，详见 `OrderAssistantMysqlTest` 的类注释。

---

## 4. 怎么跑

### 4.1 默认：H2 文件库（零配置）

```bash
# 跑测试（默认 profile 就是 H2 内存库；独立测试用 H2 文件库）
mvn -o -B test

# 打包并启动演示
mvn -o -B package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-10-database-1.0.0.jar
```

库文件落在 `./target/exp10-db.mv.db`。**删掉它就等于清库** —— 也正因为它是文件库，
"记忆跨进程重启还在"这件事在演示里是真的：第二次启动时 `agent_memory` 里的老会话仍然读得到。

> 只想跑 H2 那一份：`mvn -o test -Dtest=OrderAssistantTest`

### 4.2 mysql profile：切到本机 MySQL 8

```bash
# 建库（只需要一次；连接串上也带了 createDatabaseIfNotExist=true）
mysql -u root -p123456 -e "CREATE DATABASE IF NOT EXISTS benxin_exp_demo DEFAULT CHARACTER SET utf8mb4"

mvn -o -B package -DskipTests
java -Dstdout.encoding=UTF-8 -jar target/exp-10-database-1.0.0.jar --spring.profiles.active=mysql
```

配置在 `src/main/resources/application-mysql.yml`，用户名/密码走环境变量占位：

```yaml
spring:
  datasource:
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:123456}   # 冒号后是"本机开发默认值"，部署时用环境变量覆盖
```

> `mvn -o test` 会**同时**跑 H2 与 MySQL 两份（`OrderAssistantMysqlTest` 继承 H2 那份的全部断言）。
> 数据库没起来时它会**失败**而不是静默跳过 —— 示例的价值就在于"真的在两种库上都跑通了"。
>
> 实测：两种库上**跑出的行数完全一致**（见下面第 9 节的输出对照），这才是"一套脚本两种库"的真凭据。

### 4.3 两种库上的差异（同一份脚本必须都能跑）

- H2 用 `MODE=MySQL;DATABASE_TO_LOWER=TRUE`，让 `schema.sql` / `data.sql` 说 MySQL 的方言；
- 脚本里刻意**不写二级索引**：H2 认 `CREATE INDEX IF NOT EXISTS`，MySQL 8 至今不支持；示例数据只有几行，索引对查询没有影响；
- 唯一约束两边都认，因此照常保留 —— 它是事务演示的靶子。

---

## 5. 几条「容易猜错」的口径

这些都是**实测**出来的，每一条都有对应的断言或演示输出。

### 5.1 记忆落库

1. **`Json.write(ChatMessage)` 不报错，但会把数据静默写没。** 实测输出是 `{"textEmpty":false}` —— role 与全部内容都丢了。
   而 `Json.convert(..., ChatMessage.class)` 直接失败（没有 Creator）。
   **结论：走 `Json` 直通的路是死的，必须自己写编解码** —— 这就是 `ChatMessageCodec` 存在的原因。
2. **`Role` 的 Jackson 绑定只认枚举名（`USER`），不认 wire name（`user`）**；而插件自己的 `Role.fromWire("user")` 是认的。两处口径不一致，编解码器里必须用 `fromWire`。
3. **`save` 是全量覆盖**：一次 `save` 对应一次运行的完整历史（先 `DELETE` 再批量 `INSERT`），而不是增量 upsert。
4. **`exists` 被覆写成一条 `COUNT(*)`**：接口的默认实现会 `load` 出整段历史再判空，而 `exists` 是每次调用都要问的热路径。

### 5.2 审计

5. **失败路径没有 `AgentResult`。** `DefaultAgent#run` 的 catch 里只调 `listener.onError(Throwable)`，
   不给 `steps` / `usage` / `finishReason`，`onError` 的签名里**连 agentName 与 sessionId 都没有**。
   于是那行 `FAILED` 是监听器自己在 `onAgentStart` 留了草稿才补出来的 —— **这是使用者侧的补偿动作，不是框架行为**。
6. **`short_circuit` 能留痕，但沙箱/审批拒绝留不下痕。**
   `DefaultLoopContext#executeOne` 的顺序是：沙箱 → 审批 → **拦截器循环** → 执行。
   `beforeTool` 短路发生在循环里，所以审计看得见；而沙箱拒绝、审批拒绝在循环**之前**就 return 了，
   连 `beforeTool` 都没被调过，`tool_audit` 里自然一行都没有。
7. **"审计看得见短路"还有一个前提：审计拦截器必须排在短路拦截器之前。**
   反过来写的话，短路会 `break` 掉整个拦截器循环，审计的 `beforeTool` 永远轮不到 —— 表里一行都不会有。
   本示例：`ToolAuditInterceptor.order() = 0` < `WriteToolShortCircuitInterceptor.order() = 500`。
8. **审批被拒时，框架回灌给模型的文本是固定的** `"用户拒绝执行工具 [xxx]"`，**不含** handler 写下的详细理由。
   想给模型一句可读原因，只能在 handler 内部留状态、再由工具层补 —— 但**被审批闸拦下的调用根本进不到工具里**，
   所以那条"补一句"的代码在拒绝路径上跑不到。这是示例侧的一次适配尝试，最终没能在拒绝路径上生效，如实记录在案。

### 5.3 写库与审批

9. **`@LlmTool` 声明的工具永远不会触发审批。** 注解上没有 `requiresApproval` 属性，注解路径落到的
   `MethodToolCallback#requiresApproval()` 是**写死的 `false`**。想让它触发审批只有两条路：
   把全局策略改成 `ALWAYS`（那连只读工具也要挨个问），或者手写 `ToolCallback` —— 本示例的写工具走的是后者。
10. **沙箱拒绝不可被审批覆盖。** 被沙箱拒绝的调用，审批处理器**一次都不会被调用**（直觉上容易猜反）。
11. **"批准"不落库。** `DbApprovalHandler` 在放行这条路径上只记内存；`approval_request` 里那行
    `status=AUTO_APPROVED` 是 `OrderWriteService` 写的（它把"订单行 + 放行留痕"绑在同一个事务里）。
    这样这张表的语义才单一：**由审批处理器写入的行才是"等人处理的待办"**。
12. **多实现类型必须有一个 `@Primary`。** starter 的 `llmAgentFactory(...)` 是按**类型**注入
    `ToolSandbox` / `ApprovalHandler` 的（参数上既没有 `@Qualifier` 也不按 bean 名匹配），
    同类型有两个 bean 时容器会直接启动失败。本示例给"默认沙箱"与"默认审批器"都标了 `@Primary`。

### 5.4 工作流

13. **tool 节点的参数只从黑板取值，没有默认值兜底。** 黑板上没有的名字会被 `Templates` **原样保留**成
    `"${xxx}"` 字符串（这是它刻意的设计：拼错的占位符要肉眼可见）。参数绑不进 `Long` 会直接让节点失败。
    **所以"把输入翻译成参数"必须是图里显式的一步**（本图的 `resolve` 节点）。
14. **`${...}` 只支持"取变量 + 可选默认值"两种形态**（`${x}` / `${x:-默认}`），没有正则或函数调用。
    想从自由文本里抽参数，要么在前面加一个 agent 节点让模型吐结构化结果，要么把输入约定成结构化字段。
15. **tool 节点一次都不调模型**，所以也不消耗步数预算 —— 它调用的就是其它 Loop 用的同一批 `ctx` 方法，
    照常经过沙箱、审批、拦截器、超时与审计。**工作流是既有能力的重新编排，不是绕过安全模型的旁路。**
16. **`${error}` 只在节点失败时才有值。** 而"返回空结果"（例如 `{"found":false}`）**不算节点失败** ——
    它是正常输出。想让一个节点真的失败，工具必须抛异常（`callTool` 会把异常的 `ToolResult` 升级成节点级失败）。
17. **分支节点没有默认边时会静默结束。** 引擎按声明顺序取第一条成立的边，所以**默认边必须放最后**。

### 5.5 事务

18. **工具方法上的 `@Transactional` 指望不上。** 工具是在框架的工作线程里执行的
    （`DefaultAgent` 的 cached thread pool），而 Spring 的事务上下文是 `ThreadLocal` ——
    事务边界落在了工作线程里，与调用方无关。**工具里的多语句写操作用 `TransactionTemplate` 把边界画在方法体内部。**
19. **H2 与 MySQL 在"显式插入 id 之后自增计数器推到哪"上行为不同。**
    MySQL 的 `ALTER TABLE ... AUTO_INCREMENT = 1` 会把计数器设为 `max(id)+1` 与 1 中的较大者；
    H2 则**无条件打回 1**。所以"删库 → 重灌（显式 id） → 重置自增"这个顺序在 H2 上会让下一次自动取号撞主键。
    **重置必须夹在"删除"与"重灌"之间**（见 `demo/DatabaseReset#all` 的注释）。
20. **`approval_request` 表上没有 sku 唯一约束** —— `uk_order_item_sku` 在 `order_item` 上。
    想稳定制造"第二条写失败"，要么把第二条写也打到 `order_item`，要么在应用层显式模拟那道约束
    （本示例的 `insertApprovalTrace` 选了后者）。

### 5.6 离线脚本模型

21. **`#tool:<名字>` 每一步都会重新命中**，而"最后一条 user 消息"整轮不变，
    于是工具会被反复调用**直到步数预算耗尽**，并且**永远走不到"收尾"那一步** ——
    `AgentResult.text()` 会是空串，想知道答案只能看工具结果。
    （`exp-08` 也把这条单独断言成口径；示例不改脚本模型，只把真实结果记下来。）
22. **脚本模型的关键字规则排在"已有工具结果就收尾"之前。** 所以
    "查一下张三的订单"这种自然提问会被 `订单` 那条规则直接回掉，**工具一次都不会被调用**。
    要确定性地调工具，就用 `#tool:`。
23. **Agent 级系统提示词也参与关键字匹配。** `OrderAssistant` 的提示词里有"订单"二字，
    于是 DTO 方法 `report()` 会先命中"订单"规则、回一句中文，反序列化必然失败 ——
    解法是给这个方法加 `@SystemPrompt(inherit = false)`。

---

## 6. 插件问题清单

> **状态（1.1.1）**：本节记录的是 **1.1.0** 的实测结果。插件 1.1.1 已修复本工程上报的 31 条中的 30 条，
> 其中与本模块直接相关的有：拦截器短路不再 `break` 整条链（第 26 条）、
> 手写 `ToolCallback` 会沿外层类匹配（第 27 条）、沙箱/审批改为 `ObjectProvider` 注入（第 28 条）、
> 审批拒绝理由可回灌（第 29 条）。逐条对应见根目录 `FINDINGS.md` 第一节.5。

> 下面每一条都是在本示例里**实测撞到**的，附证据与规避方式。

| # | 问题 | 证据 | 影响 / 规避 |
| --- | --- | --- | --- |
| 1 | **`@LlmAgent(interceptors=…)` 之外的拦截器顺序由使用者自己保证，而"审计拦截器"天然会写错位置** | 把 `ToolAuditInterceptor.order()` 写成 100（排在短路的 10 之后）时，`tool_audit` 表对短路调用**一行都没有**：`beforeTool` 被 `break` 跳过，`afterTool` 的 `UPDATE` 也只更新 0 行 | "工具没执行但审计仍在"这条承诺静默失效。规避：审计 `order()` 取最小值 |
| 2 | **`tool_audit` 覆盖不到沙箱/审批拒绝** | `DefaultLoopContext#executeOne` 字节码：`sandbox().check()` → `approvalHandler().approve()` → 拦截器循环；前两条路径只走 `afterTool`，没有 `beforeTool` | 想审计"被沙箱/审批拦下的调用"，只能自己在沙箱/审批处理器里埋点（本示例的 `approval_request` 表就是这个角色） |
| 3 | **审批被拒时模型拿不到可读理由** | `executeOne` 里 `ToolResult.error("用户拒绝执行工具 [" + call.toolName() + "]")` 是硬编码文案；`ApprovalHandler` 没有"回传理由"的出口 | 模型只能向用户复述那句固定文本。示例的 `OrderWriteTools#hintOf` 想在工具里补一句，但被审批闸拦下的调用根本没进工具，**补不上** |
| 4 | **手写 `ToolCallback` 无法用 `@LlmAgent(tools = X.class)` 精确取到** | `LlmToolCatalog#collect` 按 `bean.getClass()` 记来源类；`OrderWriteTools` 是**工厂 bean**（方法返回 `ToolCallback`），且返回的是内部类 → 表里键是 `OrderWriteTools$CreateOrderTool`，而 `forClasses(OrderWriteTools.class)` 只做精确匹配 | 注解里写 `OrderWriteTools.class` 会打一行 WARN 然后**静默跳过**，写工具一个都不进工具表。规避：写工具按名字取 —— `@LlmAgent(toolNames = {"create_order","refund_order"})`，同时把它们做成独立 `@Bean ToolCallback` 以进全局工具表 |
| 5 | **`LlmAgentFactory` 按类型注入 `ToolSandbox` / `ApprovalHandler`，没有 `@Qualifier`** | `LlmAgentAutoConfiguration#llmAgentFactory` 的第 7/8 个参数是裸类型；容器里出现两个同类型 bean 时启动直接失败：`No qualifying bean of type 'ToolSandbox' available: expected single matching bean but found 2` | "按 Agent 换沙箱/审批器"这条路必须自带一个 `@Primary`。本示例的 `refundAssistant` 用 `@Qualifier("refundAssistantSandbox")` 取专属沙箱，同时给默认那两个标 `@Primary` |
| 6 | **tool 节点参数没有兜底值** | `Templates.renderArgs` 对取不到的占位符**原样保留** `"${xxx}"`；`args.customerId: "${customerId}"` 在黑板上没有该变量时把字符串透传给工具，`Long` 绑定直接抛 `Cannot deserialize value of type java.lang.Long from String "${customerId}"` | 图里必须显式写一步 `set` 来落参数。可取的设计（让错误可见），但**必须知道**，否则症状是"工作流莫名走了补偿分支" |
| 7 | **`MethodToolCallback#requiresApproval()` 写死 `false`** | 源码第 87–88 行直接 `return false;`；`@LlmTool` 注解上也没有 `requiresApproval` 属性 | 注解路径的工具**永远不触发审批**。想要审批只能手写 `ToolCallback` 或把全局策略改成 `ALWAYS` |
| 8 | **失败路径不给 `AgentResult`** | `DefaultAgent#run` 的 `catch (RuntimeException \| Error e)` 里只有 `listener.onError(e)`；`AgentListener#onError(Throwable)` 签名里没有 agentName/sessionId | 失败审计要靠使用者在 `onAgentStart` 留草稿再补写。示例的 `AgentRunAuditListener` 就是这么做的，并在 `error_message` 里注明"steps/usage/finishReason 不可得" |
| 9 | **工具在框架的线程池里执行，`@Transactional` 不生效** | `executeWithTimeout` 用 `CompletableFuture.runAsync(..., agent.toolExecutor())`；Spring 事务上下文是 `ThreadLocal` | 工具里的多语句写必须用 `TransactionTemplate`。示例把 `createOrderWithAnnotation` 留着，专门被实测 |
| 10 | **H2（`MODE=MySQL`）的 `ALTER TABLE … AUTO_INCREMENT = 1` 是无条件打回 1** | 实测：`DELETE` → 显式插入 id 1..5 → `ALTER … AUTO_INCREMENT = 1` → 下一次自动取号 = **1** → 撞主键。真实 MySQL 同样顺序下会取 6 | "同一套脚本跑两种库"在这里并不等价。规避：把重置放在重灌**之前**，或干脆用显式 id |
| 11 | **`@SpringBootTest(properties = …)` 与父类注解是"合并"而不是"替换"** | 子类只加 `@ActiveProfiles("mysql")` 时，父类那几行 `spring.datasource.url=jdbc:h2:…` 照样生效 —— 实测"MySQL 那一份"一直**静默跑在 H2 上并全绿** | 这不是插件的问题，是 Spring Test 的既有语义，但它是**示例里最危险的一个坑**：它让"两种库都验证过了"变成一句空话。规避：子类用 `@DynamicPropertySource`（优先级高于注解里的 `properties`）显式覆盖，并补一条"跑在哪个库上"的自证断言 |
| 12 | **（口径而非缺陷）离线脚本模型的 `#tool:` 每一步都会重新命中** | `ScriptedLlmModel#chat` 把"用户显式点名 `#tool:`"排在"已有工具结果就收尾"**之前**，而"最后一条 user 消息"整轮不变 | `AgentResult.text()` 会是空串、`maxStepsReached()=true`；想知道答案只能看工具结果。写断言时必须知道这一条，否则会写出一批"看起来该过却过不了"的用例 |

---

## 7. 演示输出对照（H2 vs MySQL）

`java -jar` 启动演示会跑九节，每节结束打印一次六张表的行数。**两种库上的输出完全一致**：

| 节 | customer | order_item | agent_run | tool_audit | agent_memory | approval_request |
| --- | --- | --- | --- | --- | --- | --- |
| ① JDBC 记忆 | 3 | 5 | 3 | 0 | 4 | 0 |
| ② 工具查库 | 3 | 5 | 2 | 16 | 34 | 0 |
| ③ 运行审计 | 3 | 5 | 3 | 0 | 2 | 0 |
| ④ 工具审计 | 3 | 5 | 2 | 16 | 34 | 0 |
| ⑤ 写库审批 | 3 | 5 | 1 | 0 | 17 | 0 |
| ⑥ 工作流审批 | 3 | 5 | 3 | 6 | 0 | 0 |
| ⑦ 事务一致性 | 3 | 5 | 1 | 8 | 17 | 0 |
| ⑧ 多轮追问 | 3 | 5 | 2 | 8 | 19 | 0 |
| 收尾 | 3 | 5 | 2 | 8 | 19 | 0 |

> 表尾的 `approval_request=0` 是**对的**：走完流程的写操作要么被拒（落在第 ⑤ 节中间被打断的查询里），
> 要么被放行（那时表里是 1 行 `AUTO_APPROVED`，而第 ⑤ 节最后又做了一次 `resetEverything`）。
> 想看到待办行，看第 ⑤ 节中间那段输出即可（`[{tool_name=create_order, status=PENDING_MANUAL, reason=金额 900.0 超过自动审批阈值 500}]`）。

数据源自述（`DatabaseReport#describe`）也一眼可辨：

```
H2   : H2 2.3.232 (2024-08-11) @ jdbc:h2:file:./target/exp10-db
MySQL: MySQL 8.0.43 @ jdbc:mysql://127.0.0.1:3306/benxin_exp_demo?createDatabaseIfNotExist=true&...
```

---

## 8. 代码地图

```
exp-10-database
├── src/main/java/com/benxin/exp/database
│   ├── DatabaseApplication.java            @EnableLlmAgents 入口
│   ├── agent/                              OrderAssistant（主角）/ ComplianceAssistant（工作流）/ FailAssistant（失败演示）
│   ├── approval/DbApprovalHandler.java     按金额阈值 + 会话标记放行/拒绝，拒绝落 approval_request
│   ├── audit/AgentRunAuditListener.java    运行审计（onError 补偿写 FAILED）
│   ├── audit/ToolAuditInterceptor.java     工具审计（beforeTool INSERT / afterTool UPDATE，order()=0 必须最前）
│   ├── config/DatabaseConfiguration.java   全部可插拔组件的"接线板"（六个默认实现被顶替）
│   ├── db/OrderRepository.java             查询侧仓储（参数绑定，不拼 SQL）
│   ├── demo/DatabaseDemo.java              启动即跑的九节演示
│   ├── demo/DatabaseReport.java            打印六张表行数 + 当前连的是哪种库
│   ├── demo/DatabaseReset.java             演示与测试共用的"恢复初始状态"
│   ├── domain/ dto/                        Customer / OrderItem / OrderStat / WriteResult / BusinessReport
│   ├── guard/                              RefundAmountSandbox（沙箱闸）/ ManualReviewApprovalHandler /
│   │                                       WriteToolShortCircuitInterceptor（短路 + 审计留痕，order()=500）
│   ├── memory/ChatMessageCodec.java        ChatMessage ↔ JSON 的显式往返编解码
│   ├── memory/JdbcMemoryStore.java         JDBC 版 MemoryStore（save 全量覆盖）
│   └── tool/                               OrderQueryTools（只读）/ OrderWriteTools（写，需审批）/ OrderWriteService（事务）
├── src/main/resources
│   ├── application.yml                     默认 H2 文件库 + llm.* + exp.*
│   ├── application-mysql.yml               mysql profile（密码走 ${MYSQL_PASSWORD:123456}）
│   ├── schema.sql / data.sql               幂等建表 + 种子数据（两种库共用）
│   └── workflows/order-compliance.yml      工作流图：start → set → agent → tool → branch → set → agent → end
└── src/test/java/com/benxin/exp/database
    ├── OrderAssistantTest.java             33 条主用例（H2 内存库）
    ├── OrderAssistantMysqlTest.java        继承上一类 + @DynamicPropertySource 钉到 MySQL（同一套断言）
    └── JdbcMemoryStoreStandaloneTest.java  6 条，不启动 Spring（真的 new 两个 Agent 共享一个文件库）
```

---

## 9. 一句话总结

这个示例想证明的不是"本心能连数据库"，而是：
**把六个可插拔点接到同一张真实的库上之后，它们能互相印证** ——
记忆表里的历史解释了审计表里那次运行为什么带着工具结果；
审计表里的 `short_circuit=1` 解释了业务表里为什么没有那一行订单；
`approval_request` 里的待办解释了模型为什么没能完成用户的要求。
**"每一张表都能被另一张表解释"，这才是把 Agent 接进真实系统的样子。**
