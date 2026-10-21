package com.benxin.exp.wfnode;

import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.NodeType;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowEngine;
import com.benxin.llm.core.workflow.WorkflowException;
import com.benxin.llm.core.workflow.WorkflowHandler;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import com.benxin.llm.core.workflow.WorkflowNode;
import com.benxin.llm.core.workflow.WorkflowRun;
import com.benxin.llm.core.workflow.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java 节点在<b>纯引擎</b>里的行为：没有 Spring、没有 Agent、没有模型。
 *
 * <p>这是"Java 节点是引擎能力"的直接证据：引擎只认 {@code WorkflowRuntime.callHandler}
 * 这一个方法，参数渲染、两条输出通道、失败传播、retry 与 continueOnError
 * 全部在这里被断言，一次网络请求都不需要。</p>
 */
@DisplayName("Java 节点 · 纯引擎")
class JavaNodeEngineTest {

    // ------------------------------------------------------------------
    // 测试用的处理器（直接 register，不依赖 @LlmNode / 组件扫描）
    // ------------------------------------------------------------------

    /** 把收到的 input 原样记下来，方便断言"args 已经被渲染过"。 */
    static final class CapturingHandler implements WorkflowHandler {

        private NodeInput last;
        private final String text;
        private final Map<String, Object> vars;

        CapturingHandler(String text, Map<String, Object> vars) {
            this.text = text;
            this.vars = vars;
        }

        @Override
        public NodeOutput handle(NodeInput input) {
            this.last = input;
            return NodeOutput.of(text, vars);
        }
    }

    /** 前 N 次抛异常，之后成功 —— 用来验证 retry。 */
    static final class FlakyHandler implements WorkflowHandler {

        private final int failures;
        private final AtomicInteger attempts = new AtomicInteger();

        FlakyHandler(int failures) {
            this.failures = failures;
        }

        @Override
        public NodeOutput handle(NodeInput input) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failures) {
                throw new IllegalStateException("第 " + attempt + " 次尝试失败");
            }
            return NodeOutput.of("成功于第 " + attempt + " 次尝试");
        }
    }

    private static WorkflowRun run(WorkflowDefinition definition, String input, FakeNodeRuntime runtime) {
        return new WorkflowEngine().run(definition, new WorkflowState(input), runtime);
    }

    private static FakeNodeRuntime runtime(WorkflowHandlerRegistry registry) {
        return new FakeNodeRuntime(registry);
    }

    private static WorkflowNode javaNode(String id, String handler, Map<String, Object> args) {
        return WorkflowNode.builder(id).type(NodeType.JAVA).handler(handler).args(args).build();
    }

    // ------------------------------------------------------------------
    // 参数与两条通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("args 在派发前被渲染：处理器拿到的是最终值，不是 ${...}")
    void argsAreRenderedBeforeDispatch() {
        CapturingHandler handler = new CapturingHandler("已处理", Map.of());
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty().register("probe", handler);

        WorkflowDefinition definition = WorkflowDefinition.builder("render")
                .node(WorkflowNode.builder("seed").type(NodeType.SET)
                        .set(Map.of("topic", "工作流 Java 节点")).build())
                .node(javaNode("call", "probe", Map.of("text", "${topic}", "limit", "40")))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${call}").build())
                .edge("seed", "call")
                .edge("call", "done")
                .build();

        run(definition, "ignored", runtime(registry));

        assertThat(handler.last.args()).containsEntry("text", "工作流 Java 节点");
        assertThat(handler.last.args()).containsEntry("limit", "40");
        assertThat(handler.last.nodeId()).isEqualTo("call");
    }

    @Test
    @DisplayName("两条通道：文本进 ${节点 id}，变量给下游与条件表达式按名字读")
    void textChannelAndVariableChannel() {
        CapturingHandler handler = new CapturingHandler("字数=42",
                Map.of("level", "偏长", "chars", 42));
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty().register("probe", handler);

        WorkflowDefinition definition = WorkflowDefinition.builder("channels")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(javaNode("stats", "probe", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("${stats} / ${level} / ${chars}").build())
                .edge("begin", "stats")
                .edge("stats", "done")
                .build();

        WorkflowRun run = run(definition, "x", runtime(registry));

        assertThat(run.output()).isEqualTo("字数=42 / 偏长 / 42");
    }

    @Test
    @DisplayName("节点输出优先：返回与节点 id 同名的变量不会顶掉自己的输出")
    void nodeOutputWinsOverSameNamedVariable() {
        CapturingHandler handler = new CapturingHandler("真正的输出",
                Map.of("stats", "想冒名顶替的变量"));
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty().register("probe", handler);

        WorkflowDefinition definition = WorkflowDefinition.builder("shadow")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(javaNode("stats", "probe", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${stats}").build())
                .edge("begin", "stats")
                .edge("stats", "done")
                .build();

        assertThat(run(definition, "x", runtime(registry)).output()).isEqualTo("真正的输出");
    }

    @Test
    @DisplayName("NodeOutput.none() 不覆盖 ${last}（像 branch / set 那样）")
    void noneDoesNotOverwriteLast() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("speak", new CapturingHandler("第一段输出", Map.of()))
                .register("quiet", (WorkflowHandler) input -> NodeOutput.none());

        WorkflowDefinition definition = WorkflowDefinition.builder("silent")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(javaNode("speak", "speak", Map.of()))
                .node(javaNode("quiet", "quiet", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${last}").build())
                .edge("begin", "speak")
                .edge("speak", "quiet")
                .edge("quiet", "done")
                .build();

        assertThat(run(definition, "x", runtime(registry)).output()).isEqualTo("第一段输出");
    }

    @Test
    @DisplayName("处理器返回 null：按「无输出」处理，不 NPE")
    void nullReturnIsTreatedAsNoOutput() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("broken", (WorkflowHandler) input -> null);

        WorkflowDefinition definition = WorkflowDefinition.builder("nullish")
                .node(WorkflowNode.builder("begin").type(NodeType.START).prompt("原文").build())
                .node(javaNode("nothing", "broken", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${last}").build())
                .edge("begin", "nothing")
                .edge("nothing", "done")
                .build();

        assertThat(run(definition, "x", runtime(registry)).output()).isEqualTo("原文");
    }

    // ------------------------------------------------------------------
    // 失败语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("处理器抛异常 → 节点失败：消息里带节点 id 与节点类型")
    void handlerFailureCarriesNodeIdAndType() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("boom", (WorkflowHandler) input -> {
                    throw new IllegalStateException("数据库连不上");
                });

        WorkflowDefinition definition = WorkflowDefinition.builder("fail")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(javaNode("explode", "boom", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("x").build())
                .edge("begin", "explode")
                .edge("explode", "done")
                .build();

        assertThatThrownBy(() -> run(definition, "x", runtime(registry)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("explode")
                .hasMessageContaining("java")
                .hasMessageContaining("数据库连不上");
    }

    @Test
    @DisplayName("retry 对 Java 节点同样生效：失败 2 次、第 3 次成功")
    void retryRetriesJavaNode() {
        FlakyHandler flaky = new FlakyHandler(2);
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty().register("flaky", flaky);

        WorkflowDefinition definition = WorkflowDefinition.builder("retry")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(WorkflowNode.builder("audit").type(NodeType.JAVA).handler("flaky").retry(3).build())
                .node(WorkflowNode.builder("done").type(NodeType.END).output("${audit}").build())
                .edge("begin", "audit")
                .edge("audit", "done")
                .build();

        WorkflowRun run = run(definition, "x", runtime(registry));

        assertThat(run.output()).isEqualTo("成功于第 3 次尝试");
        assertThat(run.completed()).isTrue();
    }

    @Test
    @DisplayName("continueOnError：失败不中断，出边读 ${error} 走补偿分支")
    void continueOnErrorRoutesToCompensation() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("always", (WorkflowHandler) input -> {
                    throw new IllegalStateException("配额不足");
                });

        WorkflowDefinition definition = WorkflowDefinition.builder("compensate")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(WorkflowNode.builder("charge").type(NodeType.JAVA).handler("always")
                        .continueOnError(true).build())
                // 关键：失败原因要在**紧接着的**补偿节点里就读走存进变量。
                // 一旦有节点成功执行，引擎就会清掉 ${error}；到那时再引用它，
                // 模板会把 ${error} 原样留在文案里（"解析不到就保留原文"是 Templates 的刻意设计）。
                .node(WorkflowNode.builder("compensate").type(NodeType.SET)
                        .set(Map.of("outcome", "已补偿", "detail", "${error}")).build())
                .node(WorkflowNode.builder("ok").type(NodeType.SET)
                        .set(Map.of("outcome", "正常")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("${outcome}｜${detail}").build())
                .edge("begin", "charge")
                .edge("charge", "compensate", "${error} is not empty")
                .edge("charge", "ok")
                .edge("compensate", "done")
                .edge("ok", "done")
                .build();

        WorkflowRun run = run(definition, "x", runtime(registry));

        assertThat(run.output()).startsWith("已补偿");
        assertThat(run.output()).contains("配额不足");
        // 失败的那次也在访问表里："尝试过"与"成功走过"是两回事
        assertThat(run.visited()).contains("charge", "compensate");
    }

    @Test
    @DisplayName("${error} 在下一个成功节点之后被清除：晚读只能读到字面量")
    void errorIsClearedAfterASuccessfulNode() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("always", (WorkflowHandler) input -> {
                    throw new IllegalStateException("配额不足");
                });

        WorkflowDefinition definition = WorkflowDefinition.builder("late-read")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(WorkflowNode.builder("charge").type(NodeType.JAVA).handler("always")
                        .continueOnError(true).build())
                .node(WorkflowNode.builder("compensate").type(NodeType.SET)
                        .set(Map.of("outcome", "已补偿")).build())
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("${outcome}｜${error}").build())
                .edge("begin", "charge")
                .edge("charge", "compensate")
                .edge("compensate", "done")
                .build();

        // 补偿节点成功执行后 error 已被清掉，模板又"解析不到就保留原文"，
        // 于是这里看到的不是空串，而是原封不动的 ${error}
        assertThat(run(definition, "x", runtime(registry)).output()).isEqualTo("已补偿｜${error}");
    }

    @Test
    @DisplayName("未注册的 handler：报错在派发时就发生，并列出已注册清单")
    void unknownHandlerFailsAtDispatch() {
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("known", new CapturingHandler("ok", Map.of()));

        WorkflowDefinition definition = WorkflowDefinition.builder("ghost")
                .node(WorkflowNode.builder("begin").type(NodeType.START).build())
                .node(javaNode("call", "ghost", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END).output("x").build())
                .edge("begin", "call")
                .edge("call", "done")
                .build();

        assertThatThrownBy(() -> run(definition, "x", runtime(registry)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("known");
    }

    @Test
    @DisplayName("黑板快照是只读的：处理器改不动上游变量，只能经 NodeOutput 显式交回")
    void variablesSnapshotIsReadOnly() {
        Map<String, Object> seen = new LinkedHashMap<>();
        WorkflowHandlerRegistry registry = WorkflowHandlerRegistry.empty()
                .register("peek", (WorkflowHandler) input -> {
                    seen.putAll(input.variables());
                    assertThatThrownBy(() -> input.variables().put("hacked", true))
                            .isInstanceOf(UnsupportedOperationException.class);
                    return NodeOutput.of("peeked", Map.of("readOnly", true));
                });

        WorkflowDefinition definition = WorkflowDefinition.builder("snapshot")
                .node(WorkflowNode.builder("seed").type(NodeType.SET)
                        .set(Map.of("topic", "原始值")).build())
                .node(javaNode("peek", "peek", Map.of()))
                .node(WorkflowNode.builder("done").type(NodeType.END)
                        .output("${topic}/${readOnly}").build())
                .edge("seed", "peek")
                .edge("peek", "done")
                .build();

        WorkflowRun run = run(definition, "x", runtime(registry));

        assertThat(seen).containsEntry("topic", "原始值");
        assertThat(run.output()).isEqualTo("原始值/true");
    }
}
