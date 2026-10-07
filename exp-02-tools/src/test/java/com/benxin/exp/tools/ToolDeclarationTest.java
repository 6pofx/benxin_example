package com.benxin.exp.tools;

import com.benxin.exp.tools.dto.TreeNode;
import com.benxin.exp.tools.support.ToolFixtures;
import com.benxin.exp.tools.tool.AnnotationSpectrumTools;
import com.benxin.exp.tools.tool.ClassLevelToolSet;
import com.benxin.exp.tools.tool.ContextAwareTools;
import com.benxin.exp.tools.tool.ParamAnnotationTools;
import com.benxin.exp.tools.tool.ParameterSpectrumTools;
import com.benxin.exp.tools.tool.ReturnSpectrumTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.DefaultToolRegistry;
import com.benxin.llm.core.tool.JsonSchemaGenerator;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolScanner;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明侧：{@code @LlmTool} / {@code @LlmToolParam} 的语义、参数类型 → JSON Schema 的翻译、
 * {@code ToolScanner} / {@code JsonSchemaGenerator} / {@code ToolRegistry} 的直接用法。
 *
 * <p>全部离线：模型是 {@code exp-common} 的脚本化模型与本模块的
 * {@code ScriptedToolCallModel}，不联网、不消耗 token。</p>
 */
@DisplayName("示例 02 · 工具声明（注解 / 参数 / Schema / 扫描器 / 注册表）")
class ToolDeclarationTest extends AbstractToolTest {

    private static final String SESSION = "test-declaration";

    @Override
    protected List<String> sessions() {
        return List.of(SESSION);
    }

    // ------------------------------------------------------------------
    // @LlmTool
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@LlmTool：name 优先于 value，两者都空时回落到方法名")
    void nameResolutionOrder() {
        assertThat(catalog.global().find("named_tool")).isPresent();          // name
        assertThat(catalog.global().find("valued_tool")).isPresent();         // value
        assertThat(catalog.global().find("methodNameFallback")).isPresent();  // 方法名

        assertThat(ToolFixtures.methodOf(AnnotationSpectrumTools.class, "named_tool").getName())
                .isEqualTo("named");
        assertThat(ToolFixtures.methodOf(AnnotationSpectrumTools.class, "valued_tool").getName())
                .isEqualTo("valued");
    }

    @Test
    @DisplayName("@LlmTool：description 与 returnDirect 原样进 ToolSpec")
    void descriptionAndReturnDirect() {
        ToolSpec direct = catalog.global().find("direct_tool").orElseThrow().spec();
        assertThat(direct.description()).contains("最终答案");
        assertThat(direct.returnDirect()).isTrue();
        assertThat(catalog.global().find("direct_tool").orElseThrow().returnDirect()).isTrue();

        ToolSpec roundTrip = catalog.global().find("round_trip_answer").orElseThrow().spec();
        assertThat(roundTrip.returnDirect()).isFalse();
    }

    @Test
    @DisplayName("@LlmTool(enabled = false)：扫描阶段就被摘掉，全局表里没有它")
    void disabledToolIsSkippedByScanner() {
        List<String> scanned = ToolScanner.scan(new AnnotationSpectrumTools()).stream()
                .map(ToolCallback::name).toList();

        assertThat(scanned).containsExactlyInAnyOrder(
                "named_tool", "valued_tool", "methodNameFallback", "direct_tool");
        assertThat(scanned).doesNotContain("disabled_tool");
        assertThat(catalog.global().names()).doesNotContain("disabled_tool");
    }

    @Test
    @DisplayName("类级 @LlmTool：所有 public 方法都成为工具，方法级 enabled=false 仍可单独摘掉")
    void classLevelAnnotationExposesAllPublicMethods() {
        assertThat(ToolScanner.hasTools(ClassLevelToolSet.class)).isTrue();

        List<ToolCallback> tools = ToolScanner.scan(new ClassLevelToolSet());
        assertThat(tools).extracting(ToolCallback::name)
                .containsExactlyInAnyOrder("class_level_add", "class_level_multiply");

        // 方法上没有 description，于是继承类级那份
        assertThat(tools).allSatisfy(tool -> assertThat(tool.spec().description())
                .isEqualTo("类级注解：本类所有 public 方法都是工具"));

        // 无注解参数用编译期保留的真实参数名（依赖 -parameters）
        assertThat(ToolFixtures.propertyNames(
                catalog.global().find("class_level_add").orElseThrow().spec().inputSchema()))
                .containsExactly("a", "b");
    }

    // ------------------------------------------------------------------
    // @LlmToolParam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@LlmToolParam：参数名 = value > name > 真实参数名")
    void parameterNameResolutionOrder() {
        Method method = ToolFixtures.methodOf(ParamAnnotationTools.class, "param_names");
        ObjectNode schema = JsonSchemaGenerator.forMethod(method);

        assertThat(ToolFixtures.propertyNames(schema))
                .containsExactly("by_value", "by_name", "byRealName");

        Parameter[] parameters = method.getParameters();
        assertThat(JsonSchemaGenerator.resolveName(parameters[0],
                parameters[0].getAnnotation(LlmToolParam.class))).isEqualTo("by_value");
        assertThat(JsonSchemaGenerator.resolveName(parameters[1],
                parameters[1].getAnnotation(LlmToolParam.class))).isEqualTo("by_name");
        assertThat(JsonSchemaGenerator.resolveName(parameters[2], null)).isEqualTo("byRealName");
    }

    @Test
    @DisplayName("required 只影响 schema 的 required 数组，框架不做运行期校验")
    void requiredOnlyAffectsSchema() {
        ObjectNode schema = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ParamAnnotationTools.class, "required_vs_optional"));
        assertThat(ToolFixtures.textArray(schema.get("required"))).containsExactly("must");
        // description 会跟着参数一起进 schema（模型据此判断这个参数填什么）
        assertThat(schema.at("/properties/must/description").asText()).isEqualTo("required = true（默认值）");
        assertThat(schema.at("/properties/note/description").asText()).contains("Optional");

        AgentResult result = runScripted("required_vs_optional", "{\"must\":\"给了\"}", SESSION);
        JsonNode seen = Json.parse(call(result, 0).result());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(call(result, 0).error()).isFalse();
        assertThat(seen.get("must").asText()).isEqualTo("给了");
        // 缺参不会报错：String → null、int → 0、Optional → empty
        assertThat(seen.get("may").asText()).isEqualTo("null");
        assertThat(seen.get("count").asInt()).isZero();
        assertThat(seen.get("note").asText()).isEqualTo("Optional.empty");
    }

    @Test
    @DisplayName("defaultValue 只在参数缺失时生效，且写了它就不再算必填")
    void defaultValueAppliesOnlyWhenMissing() throws Exception {
        JsonNode defaults = Json.mapper().readTree(call(
                runScripted("param_default", "{}", SESSION), 0).result());
        assertThat(defaults.get("unit").asText()).isEqualTo("celsius");
        assertThat(defaults.get("limit").asInt()).isEqualTo(200);
        assertThat(defaults.get("ratio").asDouble()).isEqualTo(0.5);
        assertThat(defaults.get("flag").asBoolean()).isTrue();
        assertThat(defaults.get("mode").asText()).isEqualTo("FAST");

        JsonNode overridden = Json.mapper().readTree(call(
                runScripted("param_default", "{\"limit\":7}", SESSION), 0).result());
        assertThat(overridden.get("limit").asInt()).isEqualTo(7);
        assertThat(overridden.get("unit").asText()).isEqualTo("celsius");   // 其它参数照样走默认值

        ObjectNode schema = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ParamAnnotationTools.class, "param_default"));
        assertThat(ToolFixtures.textArray(schema.get("required")))
                .doesNotContain("unit", "limit", "ratio", "flag", "mode");
        // 1.1.1 修复（FINDINGS 第 7 条）：defaultValue 现在按 schema 的 type 转换 ——
        // int 参数的 default 是数字 200，而不是字符串 "200"（严格模式的上游不会再报类型不匹配）。
        // 注意实现走的是 Long.parseLong，所以节点是 LongNode：用 isIntegralNumber() 判断更稳。
        assertThat(schema.at("/properties/limit/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/limit/default").isTextual()).isFalse();
        assertThat(schema.at("/properties/limit/default").isIntegralNumber()).isTrue();
        assertThat(schema.at("/properties/limit/default").asInt()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // 参数类型 → schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("参数类型全谱：每一种 Java 类型都翻译成预期的 schema 片段")
    void schemaCoversEveryParameterType() {
        ObjectNode schema = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ParameterSpectrumTools.class, "param_spectrum"));

        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

        assertThat(schema.at("/properties/count/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/total/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/ratio/type").asText()).isEqualTo("number");
        assertThat(schema.at("/properties/enabled/type").asText()).isEqualTo("boolean");
        assertThat(schema.at("/properties/boxedCount/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/boxedFlag/type").asText()).isEqualTo("boolean");
        assertThat(schema.at("/properties/keyword/type").asText()).isEqualTo("string");

        assertThat(schema.at("/properties/unit/type").asText()).isEqualTo("string");
        assertThat(ToolFixtures.textArray(schema.at("/properties/unit/enum")))
                .containsExactly("CELSIUS", "FAHRENHEIT");

        assertThat(schema.at("/properties/tags/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/tags/items/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/codes/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/codes/items/type").asText()).isEqualTo("integer");
        assertThat(schema.at("/properties/aliases/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/aliases/items/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/extras/type").asText()).isEqualTo("object");

        // Optional<String> 被"拆掉"成内层类型：schema 里看不出它可空
        assertThat(schema.at("/properties/note/type").asText()).isEqualTo("string");

        assertThat(ToolFixtures.textArray(schema.get("required"))).contains(
                "count", "total", "ratio", "enabled", "boxedCount", "boxedFlag",
                "keyword", "unit", "tags", "order", "realNameParam");
        assertThat(ToolFixtures.textArray(schema.get("required")))
                .doesNotContain("codes", "extras", "aliases", "note", "limit");

        assertThat(ToolFixtures.propertyNames(schema)).hasSize(16);
    }

    @Test
    @DisplayName("嵌套 POJO 递归展开（含 List<POJO> 与内层枚举），自引用类型在 MAX_DEPTH 处截断")
    void nestedPojoIsExpandedRecursivelyAndDepthIsCapped() {
        ObjectNode schema = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ParameterSpectrumTools.class, "param_spectrum"));

        assertThat(schema.at("/properties/order/type").asText()).isEqualTo("object");
        assertThat(ToolFixtures.propertyNames(schema.at("/properties/order")))
                .containsExactly("orderId", "customer", "items", "priority");
        assertThat(schema.at("/properties/order/properties/customer/type").asText()).isEqualTo("object");
        assertThat(ToolFixtures.textArray(schema.at("/properties/order/properties/customer/required")))
                .containsExactly("name", "phone");
        assertThat(schema.at("/properties/order/properties/items/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/order/properties/items/items/type").asText()).isEqualTo("object");
        assertThat(schema.at("/properties/order/properties/items/items/properties/quantity/type").asText())
                .isEqualTo("integer");
        assertThat(ToolFixtures.textArray(schema.at("/properties/order/properties/priority/enum")))
                .containsExactly("LOW", "NORMAL", "URGENT");

        // 自引用：child 链在第 5 层退化成"自由对象"，不再有 properties
        ObjectNode tree = JsonSchemaGenerator.forType(TreeNode.class, 0);
        JsonNode capped = tree.at("/properties/child/properties/child/properties/child"
                + "/properties/child/properties/child");
        assertThat(capped.get("type").asText()).isEqualTo("object");
        assertThat(capped.has("properties")).isFalse();

        // 1.1.1 修复（FINDINGS 第 8 条）：深度闸门挪进了"展开 POJO"那一支，
        // 于是第 5 层的 String 字段仍然是 string —— 标量不再被降级成 object。
        assertThat(tree.at("/properties/child/properties/child/properties/child"
                + "/properties/child/properties/name/type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("schema 侧与绑定侧一致：一份全类型 JSON 原样进到方法体里")
    void bindingMatchesSchema() {
        scriptedModel.scriptCall("param_spectrum", """
                {
                  "count": 7, "total": 1234567890123, "ratio": 0.75, "enabled": true,
                  "boxedCount": 42, "boxedFlag": false, "keyword": "示波器", "unit": "CELSIUS",
                  "tags": ["a", "b"], "codes": [1, 2, 3], "extras": {"k": "v", "n": 2},
                  "aliases": ["x", "y"], "note": "一条备注",
                  "order": {
                    "orderId": "A-1",
                    "customer": {"name": "张三", "phone": "138"},
                    "items": [{"sku": "S1", "quantity": 2}, {"sku": "S2", "quantity": 5}],
                    "priority": "URGENT"
                  },
                  "realNameParam": "来自真实参数名"
                }""");

        AgentResult result = scriptedAgent("bindingAgent").call("跑一遍", SESSION);
        Map<String, Object> seen = paramSpectrum.lastSeen();

        assertThat(call(result, 0).error()).isFalse();
        assertThat(seen.get("int")).isEqualTo(7);
        assertThat(seen.get("long")).isEqualTo(1234567890123L);
        assertThat(seen.get("double")).isEqualTo(0.75);
        assertThat(seen.get("boolean")).isEqualTo(true);
        assertThat(seen.get("Integer")).isEqualTo(42);
        assertThat(seen.get("Boolean")).isEqualTo(false);
        assertThat(seen.get("String")).isEqualTo("示波器");
        assertThat(seen.get("枚举")).isEqualTo("CELSIUS");
        assertThat(seen.get("List<String>")).isEqualTo(List.of("a", "b"));
        assertThat(seen.get("Set<Integer>")).isInstanceOf(java.util.Set.class);
        assertThat(seen.get("Set<Integer>").toString()).isEqualTo("[1, 2, 3]");
        assertThat(seen.get("Map<String,Object>")).isEqualTo(Map.of("k", "v", "n", 2));
        assertThat(seen.get("String[]")).isEqualTo(List.of("x", "y"));
        assertThat(seen.get("Optional<String>")).isEqualTo("Optional[一条备注]");
        assertThat(seen.get("defaultValue(limit)")).isEqualTo(200);         // 没给 → 默认值
        assertThat(seen.get("无注解参数名")).isEqualTo("来自真实参数名");
        assertThat(String.valueOf(seen.get("嵌套 POJO"))).contains("S1", "S2", "URGENT", "张三");
    }

    @Test
    @DisplayName("注入参数不进 schema：ToolContext / AgentSession 都被 isInjected 认出来")
    void injectedParametersStayOutOfSchema() {
        assertThat(JsonSchemaGenerator.isInjected(ToolContext.class)).isTrue();
        assertThat(JsonSchemaGenerator.isInjected(AgentSession.class)).isTrue();
        assertThat(JsonSchemaGenerator.isInjected(String.class)).isFalse();

        ObjectNode contextProbe = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ContextAwareTools.class, "context_probe"));
        assertThat(ToolFixtures.propertyNames(contextProbe)).containsExactly("tag");

        ObjectNode sessionProbe = JsonSchemaGenerator.forMethod(
                ToolFixtures.methodOf(ContextAwareTools.class, "agent_session_probe"));
        assertThat(ToolFixtures.propertyNames(sessionProbe)).isEmpty();
    }

    // ------------------------------------------------------------------
    // ToolScanner
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ToolScanner：scan / scan(对象, 类型) / scanAll / hasTools")
    void scannerOverloads() {
        assertThat(ToolScanner.scan(new AnnotationSpectrumTools())).hasSize(4);
        assertThat(ToolScanner.scan(new ClassLevelToolSet(), ClassLevelToolSet.class)).hasSize(2);
        assertThat(ToolScanner.scanAll(new AnnotationSpectrumTools(), new ClassLevelToolSet()))
                .hasSize(6);
        assertThat(ToolScanner.scan(null)).isEmpty();
        assertThat(ToolScanner.scanAll()).isEmpty();

        assertThat(ToolScanner.hasTools(AnnotationSpectrumTools.class)).isTrue();
        assertThat(ToolScanner.hasTools(ClassLevelToolSet.class)).isTrue();
        assertThat(ToolScanner.hasTools(ReturnSpectrumTools.class)).isTrue();
        assertThat(ToolScanner.hasTools(String.class)).isFalse();
        assertThat(ToolScanner.hasTools(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // ToolRegistry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DefaultToolRegistry：register / find / all / names / specs / size / filtered")
    void defaultRegistryApi() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(ToolFixtures.simple("alpha", "第一个", args -> "alpha"));
        registry.register(ToolFixtures.simple("beta", "第二个", args -> "beta"));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.names()).containsExactly("alpha", "beta");     // 保持插入顺序
        assertThat(registry.all()).extracting(ToolCallback::name).containsExactly("alpha", "beta");
        assertThat(registry.specs()).extracting(ToolSpec::name).containsExactly("alpha", "beta");
        assertThat(registry.isEmpty()).isFalse();
        assertThat(registry.find("alpha")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.filtered(callback -> callback.name().startsWith("b")).names())
                .containsExactly("beta");

        // 同名覆盖：值被换掉，但位置仍是第一次插入的位置
        registry.register(ToolFixtures.simple("alpha", "同名的后来者", args -> "alpha-v2"));
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.names()).containsExactly("alpha", "beta");
        assertThat(registry.find("alpha").orElseThrow().call(Map.of(), null).content())
                .isEqualTo("alpha-v2");
        assertThat(registry.find("alpha").orElseThrow().description()).isEqualTo("同名的后来者");
    }

    @Test
    @DisplayName("ToolRegistry.composite 是只读叠加视图；of / empty 是便捷工厂")
    void compositeAndFactories() {
        DefaultToolRegistry first = new DefaultToolRegistry();
        first.register(ToolFixtures.simple("alpha", "第一个", args -> "alpha"));

        DefaultToolRegistry second = new DefaultToolRegistry();
        second.register(ToolFixtures.simple("gamma", "只在第二个里", args -> "gamma"));

        ToolRegistry composite = ToolRegistry.composite(first, second);
        assertThat(composite.names()).containsExactly("alpha", "gamma");
        assertThat(composite.size()).isEqualTo(2);
        assertThat(composite.find("gamma")).isPresent();
        assertThat(composite.find("missing")).isEmpty();
        assertThat(composite.filtered(callback -> callback.name().equals("gamma")).names())
                .containsExactly("gamma");

        assertThatThrownBy(() -> composite.register(ToolFixtures.simple("delta", "写只读视图", a -> "d")))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(ToolRegistry.of(first.all()).names()).containsExactly("alpha");
        assertThat(ToolRegistry.empty().isEmpty()).isTrue();
        assertThat(ToolRegistry.empty().size()).isZero();
    }
}
