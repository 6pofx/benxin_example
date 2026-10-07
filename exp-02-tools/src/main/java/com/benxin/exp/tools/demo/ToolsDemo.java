package com.benxin.exp.tools.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.tools.agent.ByClassAssistant;
import com.benxin.exp.tools.agent.ByNameAssistant;
import com.benxin.exp.tools.dto.TreeNode;
import com.benxin.exp.tools.support.ConcurrencyRecorder;
import com.benxin.exp.tools.support.GateOrderProbe;
import com.benxin.exp.tools.support.RecordingApprovalHandler;
import com.benxin.exp.tools.support.ScriptedToolCallModel;
import com.benxin.exp.tools.support.ToolEventCollector;
import com.benxin.exp.tools.support.ToolFixtures;
import com.benxin.exp.tools.tool.AnnotationSpectrumTools;
import com.benxin.exp.tools.tool.ClassLevelToolSet;
import com.benxin.exp.tools.tool.ParamAnnotationTools;
import com.benxin.exp.tools.tool.ParameterSpectrumTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.DefaultToolRegistry;
import com.benxin.llm.core.tool.JsonSchemaGenerator;
import com.benxin.llm.core.tool.MethodToolCallback;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolCallRecord;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.spring.AgentRegistry;
import com.benxin.llm.spring.LlmAgentFactory;
import com.benxin.llm.spring.LlmToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

/**
 * 启动即跑的演示：把"工具"这个切面从上到下打一遍。
 *
 * <p>输出刻意保持"看得见结论"：不只打印工具返回了什么，还把<b>框架内部的中间产物</b>
 * 打印出来 —— 自动生成的 JSON Schema、模型这一轮实际收到的工具表、错误结果回灌的那条
 * tool 消息、并行时的并发峰值、注册表里的覆盖关系。工具的难点从来不在"写一个方法"，
 * 而在"这些看不见的口径"。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolsDemo implements ApplicationRunner {

    /** 演示里显式用到的会话 id：每节开始都清一次，免得上一节的对话进入下一节的消息体。 */
    private static final List<String> SESSIONS = List.of(
            "exp02-sources", "exp02-annotations", "exp02-params", "exp02-spectrum", "exp02-return",
            "exp02-error", "exp02-context", "exp02-direct", "exp02-parallel", "exp02-serial",
            "exp02-programmatic", "exp02-dynamic", "exp02-registry", "exp02-gate");

    private final AgentRegistry agentRegistry;
    private final LlmAgentFactory agentFactory;
    private final LlmToolCatalog catalog;
    private final RecordingModel mock;
    private final ScriptedToolCallModel scriptedModel;
    private final ToolEventCollector events;
    private final RecordingApprovalHandler approvals;
    private final ConcurrencyRecorder recorder;
    private final GateOrderProbe gateProbe;
    private final ParameterSpectrumTools paramSpectrum;
    private final CommonTools commonTools;
    private final java.util.Map<String, Agent> scriptedAgents = new java.util.concurrent.ConcurrentHashMap<>();

    public ToolsDemo(AgentRegistry agentRegistry,
                     LlmAgentFactory agentFactory,
                     LlmToolCatalog catalog,
                     RecordingModel mockLlmModel,
                     ScriptedToolCallModel scriptedToolCallModel,
                     ToolEventCollector events,
                     RecordingApprovalHandler approvals,
                     ConcurrencyRecorder recorder,
                     GateOrderProbe gateProbe,
                     ParameterSpectrumTools paramSpectrum,
                     CommonTools commonTools) {
        this.agentRegistry = agentRegistry;
        this.agentFactory = agentFactory;
        this.catalog = catalog;
        this.mock = mockLlmModel;
        this.scriptedModel = scriptedToolCallModel;
        this.events = events;
        this.approvals = approvals;
        this.recorder = recorder;
        this.gateProbe = gateProbe;
        this.paramSpectrum = paramSpectrum;
        this.commonTools = commonTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        Demo demo = Demo.begin("示例 02 · 工具切面 —— 一个普通 Java 方法，怎么变成模型能调的工具");
        demo.banner("模型: %s（默认 %s）", agentFactory.modelRegistry().names(),
                agentFactory.modelRegistry().defaultName());
        demo.banner("Agent: %s", agentRegistry.names());
        demo.banner("容器全局工具表 %d 个: %s", catalog.global().size(), catalog.global().names());

        demo.guard("三种工具来源", () -> toolSources(demo));
        demo.guard("@LlmTool 注解属性", () -> annotationAttributes(demo));
        demo.guard("@LlmToolParam 语义", () -> paramSemantics(demo));
        demo.guard("参数类型全谱与 JSON Schema", () -> parameterSpectrum(demo));
        demo.guard("返回值全谱", () -> returnSpectrum(demo));
        demo.guard("工具异常 → 错误结果回灌", () -> errorReflux(demo));
        demo.guard("ToolContext 注入与自定义事件", () -> toolContext(demo));
        demo.guard("returnDirect 直达答案", () -> returnDirect(demo));
        demo.guard("parallelSafe 与记账顺序", () -> parallelSafe(demo));
        demo.guard("程序化 ToolCallback", () -> programmaticCallback(demo));
        demo.guard("LlmToolCatalog 动态登记", () -> dynamicRegistration(demo));
        demo.guard("ToolScanner 与 JsonSchemaGenerator", () -> scannerAndSchema(demo));
        demo.guard("ToolRegistry API", () -> registryApi(demo));
        demo.guard("五道关卡的先后", () -> gateOrder(demo));

        demo.done();
    }

    // ------------------------------------------------------------------
    // ① 三种工具来源
    // ------------------------------------------------------------------

    private void toolSources(Demo demo) {
        demo.section("三种工具来源 —— tools / toolNames / 什么都不写");
        reset();

        Agent byClass = agentRegistry.get("byClassAssistant");
        Agent byName = agentRegistry.get("byNameAssistant");
        Agent inheriting = agentRegistry.get("inheritingAssistant");

        demo.info("容器全局工具表（%d 个）: %s", catalog.global().size(), catalog.global().names());
        demo.ok("tools = {CommonTools.class} → %d 个: %s", byClass.tools().size(), byClass.tools().names());
        demo.ok("toolNames = {get_current_time, calculate, explode} → %d 个: %s",
                byName.tools().size(), byName.tools().names());
        demo.ok("什么都不写 → 继承全局表 → %d 个（等于全局表的全部工具）",
                inheriting.tools().size());

        demo.info("按来源类取的两条边界：");
        demo.info("   ① LlmToolCatalog.snapshot() 记录了每个来源类贡献了哪些工具：");
        catalog.snapshot().forEach((type, tools) -> demo.info("      %s → %s",
                typeName(type), tools.stream().map(ToolCallback::name).toList()));
        demo.info("   ② 名字写错不会报错，只在启动日志留一条 WARN（LlmToolCatalog.forNames）");

        // 反面证据：被"收紧"的 Agent 看不见别的工具，模型因此根本不会去调它。
        AgentResult restricted = byClass.call("#tool:param_default {}", "exp02-sources");
        demo.ok("对 tools = {CommonTools.class} 的 Agent 点名它没有的工具 → 工具表里没有，"
                        + "模型连 schema 都没收到，本轮工具调用数 = %d，回答是兜底文本：%s",
                restricted.toolCalls().size(), ToolFixtures.oneLine(restricted.text(), 60));

        AgentResult full = runScripted("param_default", "{}", "exp02-sources");
        demo.ok("同一个工具在全局表里 → 脚本模型点名就能真的调到：工具调用 %d 次，工具 = %s",
                full.toolCalls().size(),
                full.toolCalls().stream().map(ToolCallRecord::name).toList());

        reset();
        AgentResult inheritingCall = inheriting.call("北京天气怎么样？", "exp02-sources");
        demo.ok("继承全局表的 Agent 真干活（默认模型按关键词挑中 get_weather）：%s → %s",
                inheritingCall.toolCalls().stream().map(ToolCallRecord::name).toList(),
                ToolFixtures.oneLine(inheritingCall.text(), 60));
    }

    // ------------------------------------------------------------------
    // ② @LlmTool 注解属性
    // ------------------------------------------------------------------

    private void annotationAttributes(Demo demo) {
        demo.section("@LlmTool 注解属性 —— name / value / description / returnDirect / enabled");
        reset();

        for (String name : List.of("named_tool", "valued_tool", "methodNameFallback", "direct_tool")) {
            ToolCallback callback = catalog.global().find(name).orElseThrow();
            ToolSpec spec = callback.spec();
            demo.info("%-20s ← %s()", name, methodOf(callback));
            demo.info("   description   = %s", spec.description());
            demo.info("   returnDirect  = %s（ToolCallback#returnDirect 读的就是 spec 里的这个值）",
                    spec.returnDirect());
        }

        demo.ok("value 与 name 等价：valued_tool 用了 value，工具名照样是 valued_tool");
        demo.ok("name / value 都留空 → 工具名回落到方法名：methodNameFallback");
        demo.ok("enabled = false → 全局表里没有 disabled_tool: %s",
                !catalog.global().names().contains("disabled_tool"));
        demo.ok("ToolScanner.hasTools(AnnotationSpectrumTools.class) = %s",
                ToolScanner.hasTools(AnnotationSpectrumTools.class));

        List<String> classLevel = ToolScanner.scan(new ClassLevelToolSet()).stream()
                .map(ToolCallback::name).toList();
        demo.ok("类级 @LlmTool：所有 public 方法都是工具 → %s（方法级 enabled=false 的那个被摘掉）",
                classLevel);
        demo.warn("类级注解不要写 name：本类所有方法会共用一个工具名，注册表里后一个覆盖前一个");
    }

    // ------------------------------------------------------------------
    // ③ @LlmToolParam 语义
    // ------------------------------------------------------------------

    private void paramSemantics(Demo demo) {
        demo.section("@LlmToolParam 语义 —— value / name / required / defaultValue");
        reset();

        Method paramNames = ToolFixtures.methodOf(ParamAnnotationTools.class, "param_names");
        demo.info("param_names 的 schema（参数名三个来源）：");
        printJson(demo, ToolFixtures.pretty(JsonSchemaGenerator.forMethod(paramNames)));

        Parameter[] parameters = paramNames.getParameters();
        for (Parameter parameter : parameters) {
            demo.info("   %-14s ← %s", JsonSchemaGenerator.resolveName(parameter,
                    parameter.getAnnotation(LlmToolParam.class)), parameter.getName());
        }

        AgentResult onlyRequired = runScripted("required_vs_optional", "{\"must\":\"给了\"}", "exp02-params");
        demo.ok("只给必填参数时的真实取值：%s", toolResult(onlyRequired));
        demo.warn("required 只是 schema 提示，框架不做运行期校验：String 收到 null、int 收到 0、"
                + "Optional 收到 empty，都不会报错");

        AgentResult withDefaults = runScripted("param_default", "{}", "exp02-params");
        demo.ok("一个参数都不传 → defaultValue 全部生效：%s", toolResult(withDefaults));

        AgentResult overridden = runScripted("param_default", "{\"limit\":7,\"mode\":\"SAFE\"}",
                "exp02-params");
        demo.ok("传了值就以传入值为准：%s", toolResult(overridden));
        demo.info("param_default 的 schema：注意 int 的 default 是字符串 \"200\"，"
                + "而 required 数组里没有它（写了 defaultValue 就自动不算必填）");
        printJson(demo, ToolFixtures.pretty(
                JsonSchemaGenerator.forMethod(ToolFixtures.methodOf(ParamAnnotationTools.class, "param_default"))));
    }

    // ------------------------------------------------------------------
    // ④ 参数类型全谱
    // ------------------------------------------------------------------

    private void parameterSpectrum(Demo demo) {
        demo.section("参数类型全谱 —— schema 侧与绑定侧必须对得上");
        reset();

        Method spectrum = ToolFixtures.methodOf(ParameterSpectrumTools.class, "param_spectrum");
        ObjectNode schema = JsonSchemaGenerator.forMethod(spectrum);
        demo.info("自动生成的 schema（属性名 → 类型）：");
        schema.get("properties").properties().forEach(entry ->
                demo.info("   %-22s %s", entry.getKey(), summarize(entry.getValue())));
        demo.info("   required = %s", schema.get("required"));
        demo.info("嵌套 POJO 参数 order 的完整展开：");
        printJson(demo, ToolFixtures.pretty(schema.get("properties").get("order")));

        // 真喂一份"每种类型都有"的参数进去，看方法体里实际收到什么。
        String arguments = """
                {
                  "count": 7,
                  "total": 1234567890123,
                  "ratio": 0.75,
                  "enabled": true,
                  "boxedCount": 42,
                  "boxedFlag": false,
                  "keyword": "示波器",
                  "unit": "CELSIUS",
                  "tags": ["a", "b"],
                  "codes": [1, 2, 3],
                  "extras": {"k": "v", "n": 2},
                  "aliases": ["x", "y"],
                  "note": "一条备注",
                  "order": {
                    "orderId": "A-1",
                    "customer": {"name": "张三", "phone": "138"},
                    "items": [{"sku": "S1", "quantity": 2}, {"sku": "S2", "quantity": 5}],
                    "priority": "URGENT"
                  },
                  "realNameParam": "来自真实参数名"
                }""";
        AgentResult result = runScripted("param_spectrum", arguments, "exp02-spectrum");
        demo.ok("方法体实际收到（注意 limit 没给，走的是 defaultValue）：");
        paramSpectrum.lastSeen().forEach((key, value) -> demo.info("   %-20s = %s", key, value));
        demo.info("工具返回给模型的文本（JSON）：%s", ToolFixtures.oneLine(toolResult(result), 160));

        demo.warn("基本类型参数缺参不会报错：bind 走 primitiveDefault，long 收到 0、boolean 收到 false");
    }

    // ------------------------------------------------------------------
    // ⑤ 返回值全谱
    // ------------------------------------------------------------------

    private void returnSpectrum(Demo demo) {
        demo.section("返回值全谱 —— 每种返回值会变成什么文本");
        reset();

        Agent agent = scriptedAgent("returnAgent");
        List<String> names = List.of("return_string", "return_dto", "return_node", "return_map",
                "return_ok_meta", "return_error", "return_null", "return_number", "return_boolean");
        for (String name : names) {
            scriptedModel.reset();
            scriptedModel.scriptCall(name, argumentsFor(name));
            AgentResult result = agent.call("跑 " + name, "exp02-return");
            ToolCallRecord record = result.toolCalls().get(0);
            demo.info("%-15s error=%-5s content=%s", name, record.error(),
                    ToolFixtures.oneLine(record.result(), 90));
        }
        demo.ok("ToolResult.ok/error 会原样成为内容与错误标记；null 变成固定的 (无返回值)");
        demo.ok("监听器看到的 meta（不进模型上下文、也不进 ToolCallRecord）：%s",
                events.metaSnapshots().stream().filter(m -> m.contains("exitCode"))
                        .findFirst().orElse("(无)"));
    }

    private static String argumentsFor(String tool) {
        return switch (tool) {
            case "return_string" -> "{\"text\":\"你好\"}";
            case "return_dto" -> "{\"id\":\"T-1\",\"urgent\":true}";
            case "return_node" -> "{\"city\":\"北京\"}";
            case "return_map" -> "{\"key\":\"k\",\"value\":\"v\"}";
            case "return_ok_meta" -> "{\"text\":\"带 meta 的结果\"}";
            case "return_error" -> "{\"reason\":\"自己判定为失败\"}";
            case "return_null" -> "{}";
            case "return_number" -> "{\"n\":21}";
            case "return_boolean" -> "{\"value\":true}";
            default -> "{}";
        };
    }

    // ------------------------------------------------------------------
    // ⑥ 异常 → 错误结果
    // ------------------------------------------------------------------

    private void errorReflux(Demo demo) {
        demo.section("工具异常 → 错误结果回灌模型，链路不断");
        reset();

        AgentResult result = runScripted("explode", "{\"reason\":\"演示\"}", "exp02-error");
        ToolCallRecord record = result.toolCalls().get(0);
        demo.ok("工具抛 IllegalStateException → ToolCallRecord.error() = %s", record.error());
        demo.ok("回灌给模型的内容：%s", record.result());
        demo.ok("模型调用次数 = %d（第 2 次就是「看到错误结果之后再收尾」）", scriptedModel.calls());
        demo.ok("模型收到的 tool 消息确实带着 error 标记：%s", lastToolMessage());

        AgentResult selfReported = runScripted("return_error", "{\"reason\":\"业务判定\"}", "exp02-error");
        demo.ok("与「工具自己返回 ToolResult.error」对照 → error=%s，内容：%s",
                selfReported.toolCalls().get(0).error(),
                ToolFixtures.oneLine(selfReported.toolCalls().get(0).result(), 60));

        AgentResult broken = runScripted("explode", "{\"reason\": }", "exp02-error");   // 故意给一段非法 JSON
        demo.warn("参数 JSON 非法时不会报「JSON 语法错」：解析失败被静默降级成空参数，"
                + "工具照样被调用 → %s", ToolFixtures.oneLine(broken.toolCalls().get(0).result(), 60));
    }

    // ------------------------------------------------------------------
    // ⑦ ToolContext
    // ------------------------------------------------------------------

    private void toolContext(Demo demo) {
        demo.section("ToolContext 注入 —— 工具能看到的运行时环境");
        reset();

        AgentResult result = runScripted("context_probe", "{\"tag\":\"demo\"}", "exp02-context");
        demo.ok("工具读到的上下文（这个 Map 本身就是工具返回值）：");
        ToolFixtures.pretty(Json.parse(toolResult(result))).lines()
                .forEach(line -> demo.info("   %s", line));
        demo.ok("工具写进 attributes 的东西会出现在 AgentResult.attributes()：%s",
                result.attributes());
        demo.ok("emit 出去的自定义事件到了监听器：%s",
                events.customEvents().stream()
                        .filter(e -> e.type().equals("tool.custom-event"))
                        .map(e -> e.type() + " → " + e.payload())
                        .toList());
        demo.info("ToolContext 参数不进 JSON Schema：schema 里只有 tag 一个属性 → %s",
                JsonSchemaGenerator.forMethod(
                        ToolFixtures.methodOf(com.benxin.exp.tools.tool.ContextAwareTools.class,
                                "context_probe")).get("properties").fieldNames().next());

        AgentResult probe = runScripted("agent_session_probe", "{}", "exp02-context");
        demo.warn("AgentSession 注入：isInjected=%s（承认它是注入参数、不进 schema），"
                        + "但实际注入的是 null → %s",
                JsonSchemaGenerator.isInjected(AgentSession.class), toolResult(probe));
    }

    // ------------------------------------------------------------------
    // ⑧ returnDirect
    // ------------------------------------------------------------------

    private void returnDirect(Demo demo) {
        demo.section("returnDirect = true —— 结果直达，模型不再被调第二次");
        reset();

        AgentResult direct = runScripted("direct_answer", "{\"question\":\"现在能下班了吗\"}",
                "exp02-direct");
        demo.ok("returnDirect = true → 模型调用 %d 次，步数 %d", scriptedModel.calls(), direct.steps());
        demo.ok("最终答案就是工具结果的原文：%s", ToolFixtures.oneLine(direct.text(), 80));

        AgentResult roundTrip = runScripted("round_trip_answer", "{\"question\":\"现在能下班了吗\"}",
                "exp02-direct");
        demo.ok("同样的内容但不写 returnDirect → 模型调用 %d 次，步数 %d，答案是模型的收尾文本：%s",
                scriptedModel.calls(), roundTrip.steps(), ToolFixtures.oneLine(roundTrip.text(), 60));
        demo.warn("一轮里只要有一个工具声明了 returnDirect 就生效，且取声明顺序里的第一个 —— "
                + "别把它和别的工具混在同一轮");
    }

    // ------------------------------------------------------------------
    // ⑨ parallelSafe
    // ------------------------------------------------------------------

    private void parallelSafe(Demo demo) {
        demo.section("parallelSafe —— 全真才并行，且记账永远按声明顺序");
        reset();

        scriptedModel.script(
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"A\",\"millis\":400}"),
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"B\",\"millis\":50}"));
        AgentResult parallel = scriptedAgent("parallelAgent").call("并行跑两个工具", "exp02-parallel");
        demo.ok("两个注解工具（默认 parallelSafe = true）→ 并发峰值 = %d", recorder.maxInFlight());
        demo.ok("完成顺序 = %s（B 先睡醒）", recorder.completionOrder());
        demo.ok("toolCalls 记账顺序 = %s（仍按模型声明顺序，与完成顺序无关）",
                parallel.toolCalls().stream().map(ToolCallRecord::name).toList());
        demo.info("执行线程 = %s（工具跑在 Agent 的 toolExecutor 上，不是调用方线程）",
                recorder.threadNames());

        reset();
        scriptedModel.script(
                new ScriptedToolCallModel.ToolCall("quick_probe", "{\"id\":\"C\",\"millis\":200}"),
                new ScriptedToolCallModel.ToolCall("serial_probe", "{\"id\":\"D\"}"));
        AgentResult serial = scriptedAgent("serialAgent").call("混一个不安全的工具", "exp02-serial");
        demo.ok("混入 parallelSafe = false 的 serial_probe → 并发峰值 = %d（整轮退化成串行）",
                recorder.maxInFlight());
        demo.ok("顺序仍然是声明顺序：%s",
                serial.toolCalls().stream().map(ToolCallRecord::name).toList());
        demo.ok("serial_probe 声明了 requiresApproval → 审批器被问过：%s", approvals.toolNames());
        demo.info("注解工具永远是 parallelSafe = true：@LlmTool 没有这个属性，"
                + "MethodToolCallback 也没覆写它");
    }

    // ------------------------------------------------------------------
    // ⑩ 程序化 ToolCallback
    // ------------------------------------------------------------------

    private void programmaticCallback(Demo demo) {
        demo.section("程序化 ToolCallback —— 实现接口与打注解等价");
        reset();

        ToolCallback serial = catalog.global().find("serial_probe").orElseThrow();
        ToolCallback manual = catalog.global().find("manual_report").orElseThrow();
        demo.ok("两个 bean 都是「直接实现 ToolCallback」，容器照样收进全局工具表：%s",
                List.of(serial.name(), manual.name()));
        demo.info("serial_probe: parallelSafe=%s requiresApproval=%s returnDirect=%s",
                serial.parallelSafe(), serial.requiresApproval(), serial.returnDirect());
        demo.info("manual_report 的手写 schema：");
        printJson(demo, ToolFixtures.pretty(manual.spec().inputSchema()));

        scriptedModel.scriptCall("manual_report", "{\"title\":\"工具切面\",\"format\":\"json\"}");
        AgentResult result = scriptedAgent("manualAgent").call("生成报告", "exp02-programmatic");
        demo.ok("手写 call() 返回的 ToolResult.ok(content, meta) → 内容 %s",
                ToolFixtures.oneLine(result.toolCalls().get(0).result(), 60));
        demo.ok("同一份 meta 只有监听器拿得到：%s",
                events.metaSnapshots().stream().filter(m -> m.contains("hand-written"))
                        .findFirst().orElse("(无)"));
    }

    // ------------------------------------------------------------------
    // ⑪ 动态登记
    // ------------------------------------------------------------------

    private void dynamicRegistration(Demo demo) {
        demo.section("LlmToolCatalog 动态登记 —— 注意「创建时解析」这条边界");
        reset();

        demo.info("登记前全局表大小 = %d，含 dynamic_echo = %s",
                catalog.global().size(), catalog.global().names().contains("dynamic_echo"));

        catalog.register(ToolFixtures.simple("dynamic_echo", "运行期登记进来的工具（不在任何 @Bean 里）",
                args -> "dynamic_echo ← " + args.get("text")));
        demo.ok("catalog.register(callback) 之后全局表大小 = %d，含 dynamic_echo = %s",
                catalog.global().size(), catalog.global().names().contains("dynamic_echo"));

        // 如实说明：Agent 的工具表在"创建时"就解析好了。继承全局表的 Agent 拿到的是
        // 同一个 ToolRegistry 实例（所以它也会看到新工具），但它的 spec().toolNames()
        // 是创建时的快照 —— 两者并不一致，别依赖这个巧合。
        Agent existing = agentRegistry.get("inheritingAssistant");
        demo.warn("已存在的 Agent：tools().names() 含 dynamic_echo = %s（它持有的就是全局注册表这个对象），"
                        + "但 spec().toolNames() 是创建时快照 = %s",
                existing.tools().names().contains("dynamic_echo"),
                existing.spec().toolNames().contains("dynamic_echo"));

        Agent fresh = agentFactory.create(AgentSpec.builder()
                .name("dynamicAgent")
                .model("scripted-tools")
                .memory(false)
                .maxSteps(4)
                .build());
        demo.ok("正确姿势：登记之后再手工组装一个 Agent → 它的工具表里有 dynamic_echo = %s",
                fresh.tools().names().contains("dynamic_echo"));

        scriptedModel.reset();
        scriptedModel.scriptCall("dynamic_echo", "{\"text\":\"我来自运行期登记\"}");
        AgentResult result = fresh.call("用一下新工具", "exp02-dynamic");
        demo.ok("新 Agent 真的调到了它：%s → %s",
                result.toolCalls().stream().map(ToolCallRecord::name).toList(),
                ToolFixtures.oneLine(result.toolCalls().get(0).result(), 60));
    }

    // ------------------------------------------------------------------
    // ⑫ ToolScanner / JsonSchemaGenerator
    // ------------------------------------------------------------------

    private void scannerAndSchema(Demo demo) {
        demo.section("ToolScanner 与 JsonSchemaGenerator 直接用法");
        reset();

        List<ToolCallback> scanned = ToolScanner.scan(new AnnotationSpectrumTools());
        demo.ok("ToolScanner.scan(对象) → %s", scanned.stream().map(ToolCallback::name).toList());
        demo.ok("scan(对象, 类型) 与 scanAll 两个重载：%d 个 / %d 个",
                ToolScanner.scan(new ClassLevelToolSet(), ClassLevelToolSet.class).size(),
                ToolScanner.scanAll(new AnnotationSpectrumTools(), new ClassLevelToolSet()).size());
        demo.ok("hasTools：AnnotationSpectrumTools=%s，ClassLevelToolSet=%s，String=%s",
                ToolScanner.hasTools(AnnotationSpectrumTools.class),
                ToolScanner.hasTools(ClassLevelToolSet.class),
                ToolScanner.hasTools(String.class));

        demo.info("JsonSchemaGenerator.forType(TreeNode.class, 0)：自引用类型在 MAX_DEPTH=4 之后被截断成自由对象");
        printJson(demo, ToolFixtures.pretty(JsonSchemaGenerator.forType(TreeNode.class, 0)));
        demo.warn("截断发生在类型判断之前：深度 5 的 String 字段也会变成 {\"type\":\"object\"}，"
                + "而不是保留 string（见 README 的「插件问题」）");

        demo.ok("isInjected：ToolContext=%s，AgentSession=%s，String=%s",
                JsonSchemaGenerator.isInjected(ToolContext.class),
                JsonSchemaGenerator.isInjected(AgentSession.class),
                JsonSchemaGenerator.isInjected(String.class));

        Method contextProbe = ToolFixtures.methodOf(
                com.benxin.exp.tools.tool.ContextAwareTools.class, "context_probe");
        demo.info("被注入的参数不会出现在 schema 里（context_probe 只暴露 tag）：");
        printJson(demo, ToolFixtures.pretty(JsonSchemaGenerator.forMethod(contextProbe)));
        demo.info("resolveName：注解 value > 注解 name > 真实参数名 > argN（缺 -parameters 时）");
        Method paramNames = ToolFixtures.methodOf(ParamAnnotationTools.class, "param_names");
        for (Parameter parameter : paramNames.getParameters()) {
            demo.info("   %-14s ← 注解=%s，真实参数名=%s",
                    JsonSchemaGenerator.resolveName(parameter,
                            parameter.getAnnotation(LlmToolParam.class)),
                    parameter.getAnnotation(LlmToolParam.class) == null ? "(无)" : "有",
                    parameter.isNamePresent() ? parameter.getName() : "(未保留)");
        }
    }

    // ------------------------------------------------------------------
    // ⑬ ToolRegistry API
    // ------------------------------------------------------------------

    private void registryApi(Demo demo) {
        demo.section("ToolRegistry API —— register / find / all / names / filtered / composite / of");
        reset();

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(ToolFixtures.simple("alpha", "第一个", args -> "alpha"));
        registry.register(ToolFixtures.simple("beta", "第二个", args -> "beta"));
        demo.ok("register → size=%d，names=%s，all=%s", registry.size(), registry.names(),
                registry.all().stream().map(ToolCallback::name).toList());
        demo.ok("find(\"alpha\") = %s，find(\"missing\") = %s",
                registry.find("alpha").map(ToolCallback::name).orElse("-"),
                registry.find("missing").isPresent());
        demo.ok("specs() = %s（暴露给模型的那一份声明）",
                registry.specs().stream().map(ToolSpec::name).toList());
        demo.ok("filtered(名字以 b 开头) = %s", registry.filtered(c -> c.name().startsWith("b")).names());

        registry.register(ToolFixtures.simple("alpha", "同名的后来者", args -> "alpha-v2"));
        demo.ok("同名再注册 → 覆盖而不是追加：size=%d，names 顺序=%s",
                registry.size(), registry.names());
        demo.ok("被覆盖后的实现：%s",
                registry.find("alpha").orElseThrow().call(Map.of(), null).content());
        demo.warn("注意 ToolCallRecord 里没有 meta 字段：想知道「耗时/退出码」只能挂监听器或拦截器");

        DefaultToolRegistry other = new DefaultToolRegistry();
        other.register(ToolFixtures.simple("gamma", "只在另一个注册表里", args -> "gamma"));
        ToolRegistry composite = ToolRegistry.composite(registry, other);
        demo.ok("composite(registry, other) → names=%s，find(\"gamma\")=%s",
                composite.names(), composite.find("gamma").map(ToolCallback::name).orElse("-"));
        try {
            composite.register(ToolFixtures.simple("delta", "试图写只读视图", args -> "delta"));
            demo.warn("composite 竟然允许 register —— 与源码里的 UnsupportedOperationException 不一致");
        } catch (UnsupportedOperationException e) {
            demo.ok("composite 是只读视图：register 抛 UnsupportedOperationException");
        }
        demo.ok("ToolRegistry.of(list) = %s，ToolRegistry.empty().isEmpty() = %s",
                ToolRegistry.of(registry.all()).names(), ToolRegistry.empty().isEmpty());
    }

    // ------------------------------------------------------------------
    // ⑭ 五道关卡的先后
    // ------------------------------------------------------------------

    private void gateOrder(Demo demo) {
        demo.section("五道关卡的先后 —— 沙箱在拦截器之前");
        reset();

        AgentResult denied = runScripted("read_note", "{\"path\":\"../../outside-secret.txt\"}",
                "exp02-gate");
        ToolCallRecord record = denied.toolCalls().get(0);
        demo.ok("路径越出围栏 → error=%s", record.error());
        demo.info("   %s", ToolFixtures.oneLine(record.result(), 110));
        demo.ok("拦截器 beforeTool 记录到的工具名 = %s（空 → 它根本没被跑到）", gateProbe.seen());
        demo.warn("docs/ARCHITECTURE.md「五道关卡」写的是 ①拦截器 ②沙箱 ③审批，"
                + "而 DefaultLoopContext.executeOne 的实现是先沙箱、再审批、最后才轮到拦截器");

        AgentResult allowed = runScripted("read_note", "{\"path\":\"pom.xml\"}", "exp02-gate");
        demo.ok("放行路径上拦截器确实在工作：seen=%s，工具结果=%s", gateProbe.seen(),
                ToolFixtures.oneLine(allowed.toolCalls().get(0).result(), 50));
    }

    // ------------------------------------------------------------------
    // 公共设施
    // ------------------------------------------------------------------

    /** 每个演示节开始前清一次：模型调用计数、工具内部计数、监听器、审批记录、历史。 */
    private void reset() {
        mock.reset();
        scriptedModel.reset();
        events.reset();
        approvals.reset();
        recorder.reset();
        gateProbe.reset();
        commonTools.reset();
        // 声明式 Agent 默认开着记忆，不传 sessionId 时都落在 "<agentName>-default" 上。
        // 必须走 names() + find()：@LlmAgent 接口在注册表里是惰性条目（registerLazy），
        // resolved() 只列出"已经解析过"的那些，直接用它遍历一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name).ifPresent(agent -> {
            agent.clearHistory(agent.name() + "-default");
            SESSIONS.forEach(agent::clearHistory);
        }));
    }

    /** 组装一个用"脚本化工具调用模型"驱动的 Agent（工具表 = 容器全局工具表）。 */
    private Agent scriptedAgent(String name) {
        // 按名字缓存：Agent 本身是无状态的（memory = false，会话 id 每次调用传入），
        // 反复 create 只会把 LlmAgentFactory 的装配日志刷满屏。
        return scriptedAgents.computeIfAbsent(name, key -> agentFactory.create(AgentSpec.builder()
                .name(key)
                .model("scripted-tools")
                .memory(false)
                .maxSteps(4)
                .build()));
    }

    /**
     * 让脚本模型发起一次工具调用并跑完整个闭环。
     *
     * <p>为什么不用 exp-common 的 {@code #tool:名字 {JSON}} 语法：它在"工具确实存在"时
     * 每轮都会重新命中同一条规则（{@code ScriptedLlmModel.java:180} 没有 hasToolResult 判断），
     * 于是一路调到步数预算耗尽、最终答案是空串。详见 README 的口径说明。</p>
     */
    private AgentResult runScripted(String toolName, String argumentsJson, String sessionId) {
        scriptedModel.reset();
        scriptedModel.scriptCall(toolName, argumentsJson);
        return scriptedAgent("scriptedAgent").call("调用 " + toolName, sessionId);
    }

    private static String toolResult(AgentResult result) {
        return result.toolCalls().isEmpty() ? "(没有工具调用)" : result.toolCalls().get(0).result();
    }

    /** 从模型收到的最后一条 tool 消息里读"它到底看到了什么"。 */
    private String lastToolMessage() {
        return scriptedModel.lastRequest() == null ? "(没有录到请求)"
                : scriptedModel.lastRequest().messages().stream()
                        .filter(m -> m.role() == com.benxin.llm.core.message.Role.TOOL)
                        .reduce((first, second) -> second)
                        .map(m -> ToolFixtures.oneLine(m.text(), 90))
                        .orElse("(没有 tool 消息)");
    }

    private void printJson(Demo demo, String pretty) {
        pretty.lines().forEach(line -> demo.info("   %s", line));
    }

    /** 工具回调背后的方法名：注解工具能直接拿到，手工 ToolCallback 只能给个类型名。 */
    private static String methodOf(ToolCallback callback) {
        return callback instanceof MethodToolCallback method
                ? method.method().getName()
                : typeName(callback.getClass());
    }

    /** 匿名内部类的 {@code getSimpleName()} 是空串，直接打出来会只剩一个箭头。 */
    private static String typeName(Class<?> type) {
        return type.getSimpleName().isEmpty() ? "（匿名 ToolCallback bean）" : type.getSimpleName();
    }

    /** 把某个属性的 schema 压成一句人话（只用于概览表）。 */
    private static String summarize(JsonNode schema) {
        StringBuilder sb = new StringBuilder(schema.path("type").asText("?"));
        if (schema.has("enum")) {
            sb.append("  enum=").append(schema.get("enum"));
        }
        if (schema.has("items")) {
            sb.append("  items=").append(summarize(schema.get("items")));
        }
        if (schema.has("properties")) {
            sb.append("  properties=").append(schema.get("properties").fieldNames().next())
                    .append("...（嵌套）");
        }
        if (schema.has("default")) {
            sb.append("  default=").append(schema.get("default"));
        }
        return sb.toString();
    }
}
