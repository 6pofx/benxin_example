package com.benxin.exp.tools;

import com.benxin.exp.tools.support.ScriptedToolCallModel;
import com.benxin.exp.tools.support.ToolFixtures;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.tool.ToolCallRecord;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行侧：返回值 → 文本的转换、异常 → 错误结果、{@code ToolContext} 注入、
 * {@code returnDirect} 的模型调用次数、{@code parallelSafe} 的并行与记账顺序、
 * 程序化 {@code ToolCallback}、以及沙箱关卡的实际先后。
 */
@DisplayName("示例 02 · 工具执行（返回值 / 异常 / 上下文 / 直达 / 并行）")
class ToolExecutionTest extends AbstractToolTest {

    private static final String SESSION = "test-execution";

    @Override
    protected List<String> sessions() {
        return List.of(SESSION);
    }

    // ------------------------------------------------------------------
    // 返回值全谱
    // ------------------------------------------------------------------

    @Test
    @DisplayName("String / 数字 / 布尔：内容就是 String.valueOf，不加引号")
    void scalarReturns() {
        assertThat(runOne("return_string", "{\"text\":\"你好\"}").result()).isEqualTo("String 返回值：你好");
        assertThat(runOne("return_number", "{\"n\":21}").result()).isEqualTo("42");
        assertThat(runOne("return_boolean", "{\"value\":true}").result()).isEqualTo("false");
    }

    @Test
    @DisplayName("DTO / ObjectNode / Map：自动序列化成 JSON 文本")
    void structuredReturnsAreSerialized() throws Exception {
        JsonNode ticket = Json.mapper().readTree(runOne("return_dto", "{\"id\":\"T-1\",\"urgent\":true}").result());
        assertThat(ticket.get("id").asText()).isEqualTo("T-1");
        assertThat(ticket.get("urgent").asBoolean()).isTrue();
        assertThat(ticket.get("labels")).hasSize(2);

        JsonNode node = Json.mapper().readTree(runOne("return_node", "{\"city\":\"北京\"}").result());
        assertThat(node.get("city").asText()).isEqualTo("北京");
        assertThat(node.get("alerts")).hasSize(2);

        JsonNode map = Json.mapper().readTree(runOne("return_map", "{\"key\":\"k\",\"value\":\"v\"}").result());
        assertThat(map.get("k").asText()).isEqualTo("v");
        assertThat(map.at("/nested/deep").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("ToolResult.ok(content, meta)：meta 只到监听器，内容里没有它")
    void okWithMetaKeepsMetaOffTheWire() {
        ToolCallRecord record = runOne("return_ok_meta", "{\"text\":\"带 meta\"}");

        assertThat(record.result()).isEqualTo("ok(content)：带 meta");
        assertThat(record.error()).isFalse();
        assertThat(events.metaSnapshots()).anySatisfy(meta -> assertThat(meta).contains("exitCode=0"));
    }

    @Test
    @DisplayName("ToolResult.error：错误标记由工具自己说了算，不需要抛异常")
    void selfReportedError() {
        ToolCallRecord record = runOne("return_error", "{\"reason\":\"业务判定\"}");

        assertThat(record.error()).isTrue();
        assertThat(record.result()).isEqualTo("工具自报错误：业务判定");
    }

    @Test
    @DisplayName("返回 null → 固定文本 (无返回值)，不是空串")
    void nullReturnBecomesPlaceholder() {
        assertThat(runOne("return_null", "{}").result()).isEqualTo("(无返回值)");
    }

    // ------------------------------------------------------------------
    // 异常 → 错误结果回灌
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具抛 RuntimeException → 错误结果回灌模型，链路不断，模型看得到 error 标记")
    void runtimeExceptionBecomesErrorResult() {
        AgentResult result = runScripted("explode", "{\"reason\":\"演示\"}", SESSION);

        ToolCallRecord record = call(result, 0);
        assertThat(record.error()).isTrue();
        assertThat(record.result()).contains("工具执行抛出 IllegalStateException").contains("工具内部炸了：演示");
        assertThat(result.text()).contains("已拿到工具结果");

        // 模型调用两次：第一次决定调工具、第二次拿着错误结果收尾。
        assertThat(scriptedModel.calls()).isEqualTo(2);

        // 回灌的那条 tool 消息带着 error = true（协议层能区分"工具失败了"与"工具正常返回了这句话"）。
        List<ChatMessage> toolMessages = scriptedModel.lastRequest().messages().stream()
                .filter(message -> message.role() == Role.TOOL).toList();
        assertThat(toolMessages).isNotEmpty();
        ToolResultPart part = toolMessages.get(toolMessages.size() - 1).parts().stream()
                .filter(ToolResultPart.class::isInstance).map(ToolResultPart.class::cast)
                .findFirst().orElseThrow();
        assertThat(part.error()).isTrue();
        assertThat(part.content()).contains("工具执行抛出 IllegalStateException");
    }

    @Test
    @DisplayName("参数 JSON 非法时被静默降级成空参数：不会报语法错，工具照样被调用")
    void brokenArgumentJsonDegradesToEmptyArguments() {
        scriptedModel.scriptCall("explode", "{\"reason\": }");

        AgentResult result = scriptedAgent("brokenArgsAgent").call("喂一段非法 JSON", SESSION);
        ToolCallRecord record = call(result, 0);

        assertThat(record.error()).isTrue();
        assertThat(record.result()).contains("工具内部炸了：null");
        assertThat(record.arguments()).isEmpty();
    }

    // ------------------------------------------------------------------
    // ToolContext
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ToolContext 注入：读得到运行时环境，写属性进 AgentResult，emit 到监听器")
    void toolContextIsInjectedAndObservable() throws Exception {
        AgentResult result = runScripted("context_probe", "{\"tag\":\"demo\"}", SESSION);

        JsonNode info = Json.mapper().readTree(call(result, 0).result());
        assertThat(info.get("agentName").asText()).isEqualTo("scriptedAgent");
        assertThat(info.get("sessionId").asText()).isEqualTo(SESSION);
        assertThat(info.get("step").asInt()).isEqualTo(1);
        assertThat(info.get("model().name()").asText()).isEqualTo("scripted-tools");
        assertThat(info.get("sandbox()").asText()).isEqualTo("CompositeSandbox");
        assertThat(info.get("approvalHandler()").asText()).isEqualTo("RecordingApprovalHandler");
        assertThat(info.get("执行线程").asText()).startsWith("benxin-tool-");

        assertThat(result.attributes()).containsEntry("probe.tag", "demo");
        assertThat(result.attributes()).containsKey("probe.step");

        assertThat(events.customEvents()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("tool.custom-event");
            assertThat(String.valueOf(event.payload())).contains("demo", "scriptedAgent");
        });
    }

    @Test
    @DisplayName("AgentSession 参数被排除出 schema，但 1.1.1 起真的注入了可用的会话视图（此前恒为 null）")
    void agentSessionParameterIsInjected() {
        // 1.1.0 时这里拿到的是 null（参数被认作"可注入"却硬编码成 null，见 FINDINGS 第 3 条）；
        // 1.1.1 起注入的是真实会话，id() 就是本次运行的 sessionId。
        AgentResult result = runScripted("agent_session_probe", "{}", SESSION);

        String content = call(result, 0).result();
        assertThat(content).startsWith("AgentSession 参数 = " + SESSION);
        assertThat(content).doesNotContain("AgentSession 参数 = null");
        assertThat(content).contains("ToolContext.sessionId() = " + SESSION);
    }

    // ------------------------------------------------------------------
    // returnDirect
    // ------------------------------------------------------------------

    @Test
    @DisplayName("returnDirect = true：工具结果直达，模型只被调用一次")
    void returnDirectSkipsSecondModelCall() {
        AgentResult direct = runScripted("direct_answer", "{\"question\":\"能下班吗\"}", SESSION);

        assertThat(scriptedModel.calls()).isEqualTo(1);
        assertThat(direct.steps()).isEqualTo(1);
        assertThat(direct.text()).isEqualTo(call(direct, 0).result());
        assertThat(direct.text()).contains("【直达答案】");
    }

    @Test
    @DisplayName("不写 returnDirect 的同类工具：结果回灌模型，模型被调用两次")
    void withoutReturnDirectTheModelRunsTwice() {
        AgentResult roundTrip = runScripted("round_trip_answer", "{\"question\":\"能下班吗\"}", SESSION);

        assertThat(scriptedModel.calls()).isEqualTo(2);
        assertThat(roundTrip.steps()).isEqualTo(2);
        assertThat(roundTrip.text()).contains("已拿到工具结果");
        assertThat(roundTrip.text()).isNotEqualTo(call(roundTrip, 0).result());
    }

    // ------------------------------------------------------------------
    // parallelSafe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部 parallelSafe 的工具并行执行，但记账顺序仍是声明顺序")
    void parallelSafeToolsRunConcurrentlyInDeclarationOrder() {
        scriptedModel.script(
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"A\",\"millis\":400}"),
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"B\",\"millis\":50}"));

        AgentResult result = scriptedAgent("parallelAgent").call("并行跑", SESSION);

        assertThat(recorder.maxInFlight()).isEqualTo(2);                       // 真的并行
        assertThat(recorder.completionOrder()).containsExactly("B", "A");      // 完成顺序被打乱
        assertThat(result.toolCalls()).extracting(ToolCallRecord::name)
                .containsExactly("quick_probe", "quick_probe");
        assertThat(result.toolCalls()).extracting(record -> record.arguments().get("id"))
                .containsExactly("A", "B");                                    // 记账顺序 = 声明顺序
        assertThat(result.toolCalls()).allSatisfy(record -> assertThat(record.error()).isFalse());
    }

    @Test
    @DisplayName("只要有一个工具 parallelSafe = false，整轮退化成串行")
    void oneUnsafeToolMakesTheWholeRoundSerial() {
        scriptedModel.script(
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"C\",\"millis\":200}"),
                new ScriptedToolCallModel.ToolCall("serial_probe", "{\"id\":\"D\"}"));

        AgentResult result = scriptedAgent("serialAgent").call("混一个不安全的", SESSION);

        assertThat(recorder.maxInFlight()).isEqualTo(1);
        assertThat(result.toolCalls()).extracting(ToolCallRecord::name)
                .containsExactly("quick_probe", "serial_probe");
        assertThat(recorder.completionOrder()).containsExactly("C", "serial_probe:D");
    }

    // ------------------------------------------------------------------
    // 程序化 ToolCallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("程序化 ToolCallback：手写 spec / call / parallelSafe / requiresApproval 全部生效")
    void programmaticCallbackKeepsItsOwnSpec() {
        var serial = catalog.global().find("serial_probe").orElseThrow();

        assertThat(serial.parallelSafe()).isFalse();
        assertThat(serial.requiresApproval()).isTrue();
        assertThat(serial.returnDirect()).isFalse();
        assertThat(ToolFixtures.propertyNames(serial.spec().inputSchema())).containsExactly("id");

        scriptedModel.scriptCall("serial_probe", "{\"id\":\"X\"}");
        AgentResult result = scriptedAgent("serialOnlyAgent").call("只跑串行工具", SESSION);

        assertThat(call(result, 0).result()).contains("serial_probe(X)");
        // requiresApproval = true + ApprovalPolicy.ON_REQUEST → 审批器真的被问过
        assertThat(approvals.toolNames()).containsExactly("serial_probe");
    }

    @Test
    @DisplayName("程序化工具的 ToolResult.ok(content, meta)：meta 只有监听器拿得到")
    void programmaticCallbackCanReturnMeta() throws Exception {
        var manual = catalog.global().find("manual_report").orElseThrow();
        assertThat(ToolFixtures.textArray(manual.spec().inputSchema().at("/properties/format/enum")))
                .containsExactly("text", "json");

        scriptedModel.scriptCall("manual_report", "{\"title\":\"工具切面\",\"format\":\"json\"}");
        AgentResult result = scriptedAgent("manualAgent").call("生成报告", SESSION);

        JsonNode body = Json.mapper().readTree(call(result, 0).result());
        assertThat(body.get("title").asText()).isEqualTo("工具切面");
        assertThat(body.get("source").asText()).isEqualTo("hand-written");
        assertThat(events.metaSnapshots()).anySatisfy(meta -> assertThat(meta).contains("hand-written"));
    }

    // ------------------------------------------------------------------
    // 沙箱关卡的实际先后
    // ------------------------------------------------------------------

    @Test
    @DisplayName("沙箱在拦截器之前：被沙箱拒绝的调用不会触发 beforeTool")
    void sandboxRunsBeforeInterceptors() {
        AgentResult denied = runScripted("read_note", "{\"path\":\"../../outside-secret.txt\"}", SESSION);

        assertThat(call(denied, 0).error()).isTrue();
        assertThat(call(denied, 0).result()).contains("沙箱拒绝执行");
        assertThat(gateProbe.seen()).isEmpty();

        AgentResult allowed = runScripted("read_note", "{\"path\":\"pom.xml\"}", SESSION);
        assertThat(call(allowed, 0).error()).isFalse();
        assertThat(gateProbe.seen()).containsExactly("read_note");
    }

    // ------------------------------------------------------------------

    /** 用脚本模型跑一次单工具调用，返回那条工具记录。 */
    private ToolCallRecord runOne(String toolName, String argumentsJson) {
        return call(runScripted(toolName, argumentsJson, SESSION), 0);
    }
}
