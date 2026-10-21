# 示例 13 · 工作流的 Java 节点

> 七种节点里的第七种：`type: java` + `@LlmNode` —— 把一段**本地逻辑**放进图里。
> 本模块的模型用**本机 LM Studio**（Qwen3-VL-8B）真实调用，**不提供离线 mock 模型**。

```bash
# 前置：根目录装一次
cd benxin_exp && mvn -o install -DskipTests
# 跑演示（需要本机 LM Studio 开着，端点 http://127.0.0.1:1234）
cd exp-13-workflow-java-node && mvn -o spring-boot:run
```

---

## 一、它补的是哪个洞

| 节点 | 能做什么 | 不能做什么 |
|---|---|---|
| `set` | 把模板求值后写进变量 | 算不了任何东西（`${}` 只做插值，不做运算） |
| `branch` | 按条件表达式选一条出边 | 自己不产生内容 |
| `tool` | 调一个**给模型用**的能力（进 Schema、过沙箱与审批） | 不该拿来跑"每次都一样的业务规则" |
| **`java`** | **读一批值 → 按业务规则算 → 吐出结构化结果** | 不能调模型、不能调工具、不能决定下一条边 |

三条边界都是刻意的：

1. **不能调模型** —— 需要模型就写 `agent` 节点。图的可见性正来自"模型只在被点名的地方出现"。
2. **不能调工具** —— 需要工具就写 `tool` 节点。
3. **不能决定下一条边** —— 处理器只把结论写进变量，**路由仍由出边的 `when` 表达决定**。
   整张图只有一种路由机制，因此"图长什么样"永远是读 YAML 就能看全的事。

信任边界用**注册制**而不是类名反射：定义里只能写 `handler: <名字>`，名字只能解析到
`@LlmNode` 注册进来的实例 —— 所以不存在"一份 YAML 变成任意代码执行入口"的路径。
处理器等同于应用自己的 service，因此**不走** `ToolSandbox` 与审批。

---

## 二、本模块的三张图

### ① `content-release.yml` —— 真实模型 + 三个 Java 节点

```
begin(start) → summarize(agent, 真实模型)
             → stats(java)          数出字数、判篇幅
             → route
                  ├─ ${level} == 偏长 → condense(agent, 真实模型) → restats(java)
                  └─ 默认 ─────────────────────────────────────┐
             → screen(java)        合规校验（敏感词 + 字数上限）
             → decide
                  ├─ ${verdict} == 通过 → pack(java) ─→ done
                  └─ 默认 ─────────────→ reject(set) ─→ done
```

这张图要回答一个问题：**什么该交给模型，什么不该**。

- "概括成一句话" 交给模型 —— 这是它擅长的；
- "这段有多少字" 交给 Java 节点 —— 这是一个有唯一答案的问题，模型给的是高概率的答案；
- "有没有敏感词、有没有超长" 交给 Java 节点 —— 业务规则必须每次都一样。

演示里能看到这个分工的直接后果：**字数是本地数出来的**，模型在摘要里怎么写都不影响它。

### ② `java-fault.yml` —— 失败语义（零模型调用）

```
begin → audit(java, retry: 3) → charge(java, retry: 1, continueOnError: true)
                                    ├─ ${error} 非空 → compensate(set) ┐
                                    └─ 默认 ────────→ ok(set) ─────────┴→ done
```

`audit` 前两次抛异常、第三次成功（`retry: 3` 够用）；`charge` 次次失败，
但 `continueOnError: true` 让它不拖垮整张图，出边读 `${error}` 走补偿分支。

**这张图里一个 agent 节点都没有**，所以 `token 0/0`、2 毫秒跑完、LM Studio 关着也能跑。
这不是巧合：容错本来就是"与模型无关"的能力，应该能被单独验证。

### ③ `compliance-gate.yml` —— 同一段逻辑复用到另一张图（同样零模型调用）

三个节点：`screen(java) → decide → pass/reject`。它证明两件事：

- 同一个 `compliance` 处理器可以挂在多张图上，行为完全一致；
- 因为图里没有概率成分，"放行"与"拦截"两条分支都能被**确定性**地跑到
  （见 `LocalOnlyWorkflowsTest`）。

---

## 三、五个容易猜错的口径

这五条都是本模块实测出来的，注释与断言都写在对应位置：

1. **节点的输出只存在自己 id 名下。** `pack` 节点产出的是 `${pack}`，不是 `${packed}`。
   终点节点两条分支要共用一句话，就得额外写一个 `packed` 变量（"通过"由 `pack` 写、
   "不通过"由 `reject` 的 `set` 写）。第一版忘了这件事，终点原样打印了 `${packed}`。

2. **`${error}` 会被下一个成功节点清除。** 失败原因要在**紧接着**的补偿节点里就读走存进变量；
   晚一步再引用，拿到的是字面量 `${error}` —— 因为……

3. **模板解析不到的占位符会原样保留，不是替换成空串。** 这是 `Templates` 刻意的设计
   （拼错的 `${anaylze}` 会一眼可见地留在文案里）。所以"变量没生效"时先看文案里有没有 `${}`。

4. **`args` 的渲染结果总是字符串。** 上一步算出的数字 `chars=42`，到下一个节点里
   `input.arg("chars")` 拿到的是 `"42"`，要自己 `parseInt` 回来。

5. **处理器拿到的 `variables` 是只读快照。** 改状态只能通过 `NodeOutput.variables()` 显式交回，
   由引擎在唯一一处统一写入 —— 于是"谁改了哪个变量"仍然可审计。

还有一条不是"猜错"而是"必然"：**定义里引用未注册的处理器，启动期就失败**，并列出已注册清单
（`StartupValidationTest` 用 `ApplicationContextRunner` 钉住了它）。一张坏定义不会等到跑了一半才炸。

---

## 四、运行

### 4.1 前置：本机 LM Studio

本模块**没有离线 mock** —— 要演示的就是"真实模型 + 本地逻辑"的分工，换成脚本模型这个对比就假了。

```bash
# LM Studio 里加载 qwen/qwen3-vl-8b 并启动本地服务器（默认 1234 端口）
# 换别的模型：环境变量 LOCAL_MODEL_ID=... 覆盖 yml 里的 model
```

端点不可达时，演示会**如实说明原因**并只跑两张不依赖模型的图，不会伪造模型输出。

### 4.2 跑

```bash
cd exp-13-workflow-java-node

mvn -o spring-boot:run          # 跑演示
mvn -o test                     # 26 个用例（LM Studio 不可用时真实调用那 1 个自动 skip）

# Windows 控制台中文乱码时：
mvn -o spring-boot:run -Dspring-boot.run.jvmArguments=-Dstdout.encoding=UTF-8
```

### 4.3 参数

| 开关 | 默认 | 作用 |
|---|---|---|
| `exp.local.mode` | `auto` | `auto` 探测 / `on` 强制真实演示（不可达即失败）/ `off` 跳过 |
| 环境变量 `LOCAL_MODEL_ID` | `qwen/qwen3-vl-8b` | 换成本机已加载的其它模型 |
| `exp.demo.enabled` | `true` | 设 `false` 时只启动上下文、不跑演示（测试里用） |

---

## 五、测试覆盖（26 个用例）

| 测试类 | 数量 | 是否要模型 | 钉住什么 |
|---|---|---|---|
| `HandlerRegistrationTest` | 6 | 否 | 注册表：注解名即契约、同名覆盖、缺注解报错、`require` 列出已注册清单 |
| `JavaNodeEngineTest` | 11 | 否 | 纯引擎：args 渲染、两条通道、节点输出优先、`none()` 不覆盖 `${last}`、失败消息、retry、`continueOnError`、`${error}` 清除时机、只读快照 |
| `StartupValidationTest` | 3 | 否 | Spring 启动期：未注册 handler 启动失败、缺 `@LlmNode` 启动失败、正常装配 |
| `LocalOnlyWorkflowsTest` | 5 | 否 | 两张无 agent 节点的图：重试后成功、补偿分支、合规闸门的放行/拦截/超长 |
| `ContentReleaseTest` | 1 | **是** | 真实模型参与：`chars` 必须等于正文码点数、`verdict` 合规、终点渲染发布包 |

"是否要模型"这一列是有意的结构：**只有 1 个用例依赖外部模型**，其余 25 个在
`mvn test` 里离线跑完。真实模型那一个用 `Assumptions.assumeTrue` 门控，
LM Studio 没开时跳过而不是失败。

---

## 六、文件结构

```
exp-13-workflow-java-node/
├── src/main/java/com/benxin/exp/wfnode/
│   ├── WorkflowJavaNodeApplication.java
│   ├── agent/ReleaseAgents.java            三个 @LlmAgent（都只换 loop）
│   ├── config/LocalEndpoint.java           端点探测（auto/on/off）
│   ├── node/TextStatsHandler.java          @LlmNode("text-stats")
│   ├── node/ComplianceHandler.java         @LlmNode("compliance")
│   ├── node/ReleasePackHandler.java        @LlmNode("release-pack")
│   ├── node/FlakyAuditHandler.java         @LlmNode("flaky-audit")   前 N 次必失败
│   ├── node/AlwaysFailHandler.java         @LlmNode("always-fail")   次次失败
│   └── demo/JavaNodeDemo.java              四段演示
├── src/main/resources/
│   ├── application.yml
│   └── workflows/{content-release,java-fault,compliance-gate}.yml
└── src/test/java/com/benxin/exp/wfnode/    26 个用例（见上表）
```
