# exp-05 · 工作流模式

> 控制流由我们决定，而不是模型自述。两条路线：**声明式工作流**（`workflow` Loop，图写在 YAML 里）
> 与**结构化编排**（`staged` Loop，阶段机写在代码里）。

本模块把「工作流」这个切面拆开揉碎演示一遍：六种节点、三类边、条件表达式小语言、`${}` 模板、
回边循环、节点级容错、三道防线、加载期校验、`WorkflowIo` 读写，以及一条与之对照的 `staged` 路线。
**默认离线、零 token**：模型来自 `exp-common` 的脚本化模型（`ScriptedLlmModel`），
`java -jar` 启动即打印全部演示，`mvn test` 却能断言到引擎内部的口径。

## 覆盖的功能

| 切面 | 本模块演示的内容 |
|---|---|
| 六种节点 | `start`（透传/包装）、`agent`（一次完整子任务）、`tool`（直调工具，不过模型）、`branch`（纯路由）、`set`（写黑板变量）、`end`（渲染最终答案）。<br>第七种 `java`（Java 节点）在 [exp-13](../exp-13-workflow-java-node/README.md) 单独演示 —— 它需要本机模型，且要讲的东西（注册制、两条输出通道、失败语义）值得单独一个模块 |
| 边的三种形态 | 无条件边（默认边）、`when` 条件边、回边（指向已访问过的节点即构成循环） |
| 三条装配路径 | ① `llm.workflows.<key>.location` ② `llm.workflows.<key>.inline` ③ 调用时 `Map.of(WorkflowLoop.ATTRIBUTE, yaml/json/对象)` 逐次传入 |
| 条件表达式 | `==` `!=` `>` `>=` `<` `<=` `contains` `matches` `is empty` `is not empty` `exists` `missing`，`not` `and` `or` 与括号，中文别名 `非` `且` `或` `为空` `不为空` `非空`，`!` `&&` `\|\|` |
| 模板 `${}` | 保留名 `${input}` `${task}` `${last}` `${error}` `${current}`、`${节点id}`、`${set 变量}`、`${a.b}` 点路径下钻、`${x:-兜底}`、取不到保留原文 |
| 节点级容错 | `retry`、`continueOnError`、`maxSteps`（节点自己的工具轮次上限） |
| 三道防线 | ① 步数预算 → `truncated` ② `maxVisitsPerNode` 死循环护栏 ③ 节点级 `retry` / `continueOnError` |
| 纯引擎 | `WorkflowEngine` + `WorkflowState` + 假 `WorkflowRuntime`：无 Spring、无模型即可精确断言 |
| 加载期校验 | 缺 `entry`、边指向不存在的节点、节点 id 与保留名重名、条件语法错误、缺 `prompt`/`tool`/`set`、id 带点号、`maxVisitsPerNode` 非正 |
| `WorkflowRun` 口径 | `visited()` / `distinctVisited()` / `executions()` / `truncated()` / `completed()` / `output()`，以及 8 类事件 |
| `staged` 路线 | 规划 → 执行 → 验收 → 修复 → 汇总；验收通过与不通过两条路；`new StagedLoop(false, n)` 关闸门 |

## 两份 YAML 之外的装配与代码分工

| 文件 | 用途 |
|---|---|
| `workflows/triage.yml` | **主演示图**：六种节点 + 紧急/默认两条分支 + 回边回炉。注册成 `workflow:triage` |
| `workflows/loop-workflow.yml` | 回边循环：`branch` ⇄ `tool` + `set`，转 3 圈后由条件退出；全程不调模型 ⇒ 步数预算对它无效。注册成 `workflow:loop-workflow` |
| `workflows/flaky-tools.yml` | 节点级容错：`stamp` 用 `retry: 3` 重试成功，`charge` 用 `continueOnError: true` 走补偿分支。注册成 `workflow:flaky-tools` |
| `workflows/budget-probe.yml` | 第一道防线的靶子：四个串联 `agent` 节点，`maxSteps: 2` 必然中途耗尽。注册成 `workflow:budget-probe` |
| `workflows/deadloop.yml` | 第二道防线的靶子：`spin(branch)` ⇄ `again(set)` 的**不调模型死循环**，`maxVisitsPerNode: 5`。**刻意不注册**，演示时用 `WorkflowIo.read(...)` 现读现用 |
| `application.yml` 的 `llm.workflows.inline-demo.inline` | 装配路径②：定义直接写在配置里（块标量），磁盘上没有对应文件。注册成 `workflow:inline-demo` |

| Java | 分工 |
|---|---|
| `agent/WorkflowAgents.java` | 6 个 `@LlmAgent` 接口：`triageAgent` / `inlineAgent` / `loopAgent` / `flakyAgent` / `budgetAgent` / `stagedAgent`，换一个 `loop` 就换一种编排 |
| `config/MockModelConfiguration.java` | 离线脚本化模型 + `CommonTools` + `FlakyTools` 的工具来源 |
| `tool/FlakyTools.java` | 「会按脚本失败」的工具（`flaky_stamp` / `flaky_charge`）+ 计数器工具 `next_attempt` |
| `hook/WorkflowEventCollector.java` | 自定义 `AgentListener`：把 8 类工作流事件收下来（顺带示范"自定义监听器顶掉内置日志监听器"） |
| `engine/FakeWorkflowRuntime.java` | 假运行时：不调模型、不调工具，按脚本返回/抛异常 —— 「引擎与框架解耦」最直接的证据 |
| `demo/*.java` | 启动即跑的演示：`WorkflowDemo`（主流程）、`PureEngineDemo`（纯引擎）、`ConditionsDemo`（条件表）、`TemplatesDemo`（模板）、`WorkflowIoDemo`（读写与校验） |

## 运行

```bash
# 在工程根目录先装一次（多模块工程的标准做法）
cd ..            # 到 benxin_exp/
mvn -o install -DskipTests

cd exp-05-workflow
mvn -o spring-boot:run                       # 启动即打印全部演示
mvn -o test                                  # 175 个离线用例，零 token
mvn -o package -DskipTests                   # 打成一个 fat jar
java -Dstdout.encoding=UTF-8 -jar target/exp-05-workflow-1.0.0.jar
```

Windows 控制台如果显示乱码，先执行 `chcp 65001` 切到 UTF-8，或者用上面的 `-Dstdout.encoding=UTF-8`。

演示输出里每一节都会打印 `WorkflowRun.visited()` 画出来的路径（`begin → classify → route → …`）——
这是「**控制流在跑之前就能从 YAML 里读出来、跑完之后又被如实记下来**」最直观的证据。

## 几条"按直觉猜会猜错"的口径

这些都是本模块实测出来、并写进测试的结论（每条都有对应用例，失败时会直接指出是哪一条口径）。

1. **`exists` 不是"非空"，是"≠ null"。** 空串也存在 —— 要判断"有内容"必须写 `is not empty`。
   `${blank} exists` 与 `${blank} is empty` **同时**为真。
2. **`matches` 的右操作数按原文截取**，从下一个 token 一直读到顶层的 `and` / `or` / `)` 为止。
   所以 `${log} matches (?i)error and ${count} > 10` 里的正则只是 `(?i)error`；
   正则里出现 `and` / `or` / `)` 时必须加引号：`matches "(?i)error and"`。
   （好处：`(?i)` 这种最常见的忽略大小写写法可以直接写。）
3. **`branch` 与 `set` 不产生输出，因此不覆盖 `${last}`。** 只有 `start` / `agent` / `tool` / `end`
   才有输出。想在中途确认这一点，让下游节点读 `${last}` 就行（`WorkflowEngineTest.setAndBranchDoNotOverwriteLast`）。
   ⚠️ 但 **`end` 自己会产生输出**：它就是最终答案，所以跑完之后 `state.last()` 等于 END 的渲染结果。
4. **`${}` 取不到就保留原文，而不是变成空串。** 于是 `${anaylze}` 这种拼写错误在提示词与轨迹里一眼可见，
   shell 片段 `echo ${HOME}/bin` 也不会被误伤。注意 `set` 节点的**id**取不到值 ——
   能取的是它 `set:` 里声明的**变量名**（`${mark}` 无值，`${note}` 才有）。
5. **默认边必须放在最后一条。** 引擎按声明顺序取第一条条件成立的边，**顺序即优先级**；
   默认边写在前面的图不会报错，只是它后面的条件边永远不可达（加载期只给一条 WARN）。
6. **`visited` 是"尝试过"哪些节点，不只是"成功走过"哪些节点。** 硬失败（重试用尽而抛异常）的节点
   也会进 `visited`、也会发一条 `failed=true` 的 `workflow.node` 事件；只有被死循环护栏拦下的那一次
   不进访问表（它压根没有执行）。
7. **`not contains` 是支持的**，别把它当成"没实现"而绕远路去写 `not (${x} contains y)`（两者等价）。
   `not` 是**前缀否定**，作用在整个比较上。
8. **★ 条件表达式里的 `${x:-兜底}`：1.1.1 起生效（1.1.0 不生效，已在 1.1.1 修复）。**
   1.1.0 时 `Conditions` 会把 `x:-兜底` 当成**变量名**去查表，查不到就是 `null` ⇒ 数值比较判 `false`、
   `is empty` 判 `true` —— 那一格是**静的**：写错了不报错，只让那条边永远不成立。
   1.1.1 起 `:-` 与模板渲染同一套语义，**兜底值按字面量解析**（数字 / `true` / `false` / `null` / 其余当字符串），
   所以 `${retryCount:-0} <= 2` 是数值比较。
   本模块把这次变更钉成了两条回归断言：`ConditionsTest.fallbackInsideConditionsWorks`（语法层）
   与 `WorkflowEngineTest.fallbackInsideBranchConditionsWorks`（真的走通那条分支）；
   `triage.yml` 的回边条件也改回了 `${rounds:-0}` 的自然写法。
9. **`tool` 节点的返回值不会自动写回变量表。** 引擎只把节点输出记在**节点 id** 名下（`${bump}`），
   而循环条件读的往往是你自己起的变量名（`${count}`）。少了那个 `set` 节点，
   `${count}` 永远是初值、条件永远成立，图会一直转到 `maxVisitsPerNode` 才被拦下 ——
   `loop-workflow.yml` 与 `triage.yml` 里各有一个 `tally` / `rework` 节点专门做这件事。
10. **中间产物用 `set`，别用 `end`。** 引擎执行到 `end` 节点就收工，写在它后面的 `check` / `rework`
    会整段变成死代码 —— 回边一次都转不了。`triage.yml` 的 `report` 因此是 `set`，最终答案留给 `done`。
    ⚠️ 更麻烦的是：**`end` 节点的出边在加载期既不报错也不警告**（对照：默认边顺序有 WARN），
    所以画错的回边循环会安安静静地一圈都不转，跑出来的结果看着还挺正常
    （`WorkflowEngineTest.edgesLeavingEndAreSilentlyDropped` 把这条边界钉住了）。
11. **`inline` 里不要写 flow 写法的 YAML。** `WorkflowIo` 的自动识别只看首个非空字符是不是 `{`，
    于是 `{name: x, nodes: [...]}` 会被当 JSON 送进 Jackson，报一句与真实原因无关的
    `非法 JSON: {name: x, nodes: [...]}`。用块写法，或者显式 `WorkflowIo.read(text, Format.YAML)`。
12. **`llm.workflows.<key>` 的注册名是配置键**（`workflow:<key>`），不是 YAML 里的 `name`；
    而且 `location` 与 `inline` **只能二选一**，都不写也会在启动期报错（三条 fail-fast 校验都测了）。
13. **测试写法上的两个坑（与插件无关，但一定会撞上）**：
    - `@SpringBootTest(properties = …)` 里每个元素都被当成一份 properties 文件解析，
      **多行值只有第一行生效** —— 内联 YAML 定义要用 `@DynamicPropertySource` 送进去；
    - 手工 `Agent.builder(...)` 造出来的实例**不会**自动挂上容器里的
      `WorkflowEventCollector`，要显式 `.listener(collector)`，否则事件表是空的。

## 目录

```
src/main/java/com/benxin/exp/workflow/
├── WorkflowApplication.java            @SpringBootApplication + @EnableLlmAgents
├── agent/WorkflowAgents.java           6 个 @LlmAgent 接口（换 loop 就换编排）
├── config/MockModelConfiguration.java  离线模型 + 工具来源
├── engine/FakeWorkflowRuntime.java     假运行时（纯引擎演示用）
├── hook/WorkflowEventCollector.java    自定义监听器：8 类工作流事件
├── tool/FlakyTools.java                会按脚本失败的工具 + next_attempt
└── demo/                               WorkflowDemo / PureEngineDemo / ConditionsDemo
                                        / TemplatesDemo / WorkflowIoDemo
src/main/resources/
├── application.yml                     三条装配路径里的 location 与 inline
└── workflows/                          triage / loop-workflow / flaky-tools
                                        / budget-probe / deadloop 五张图
src/test/java/com/benxin/exp/workflow/
├── ConditionsTest.java                 条件表达式全谱 + 4 处易错口径
├── TemplatesTest.java                  ${} 模板与 renderArgs / placeholders
├── WorkflowEngineTest.java             纯引擎：分支、循环、重试、预算、护栏
├── WorkflowIoTest.java                 读写、加载期校验、五份 YAML 的结构与真跑
├── WorkflowIoDetectionTest.java        已知坑：flow 写法的 YAML 会被当 JSON
├── WorkflowPropertiesTest.java         llm.workflows.* 装配 + 三条 fail-fast
└── WorkflowRoutesTest.java             端到端：三条装配路径、六种节点、三道防线、staged 两路
```
