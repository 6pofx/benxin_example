package com.benxin.exp.tools;

import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.tools.support.ToolFixtures;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.tool.ToolCallRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具的<b>来源</b>：{@code @LlmAgent(tools = {...})}、{@code toolNames = {...}}、
 * 两者都不写（继承全局表），以及 {@code LlmToolCatalog} 的查询与运行期登记。
 */
@DisplayName("示例 02 · 工具来源与工具目录（tools / toolNames / 继承 / 动态登记）")
class ToolSourcesAndCatalogTest extends AbstractToolTest {

    private static final String SESSION = "test-sources";

    @Override
    protected List<String> sessions() {
        return List.of(SESSION);
    }

    @Test
    @DisplayName("tools = {类}：按来源类整包拿，一个不多一个不少")
    void toolsByClassTakesTheWholeSourceClass() {
        Agent byClass = agentRegistry.get("byClassAssistant");

        assertThat(byClass.tools().names()).containsExactlyInAnyOrder(
                "get_weather", "get_current_time", "calculate", "describe_context");
        assertThat(byClass.tools().size()).isEqualTo(4);
        assertThat(byClass.tools().names()).doesNotContain("param_default", "explode");
    }

    @Test
    @DisplayName("toolNames = {...}：按名字精确拿，可以跨类、跨 jar 拼最小权限清单")
    void toolNamesTakesExactlyTheNamedOnes() {
        Agent byName = agentRegistry.get("byNameAssistant");

        assertThat(byName.tools().names())
                .containsExactlyInAnyOrder("get_current_time", "calculate", "explode");
        assertThat(byName.tools().size()).isEqualTo(3);
    }

    @Test
    @DisplayName("两者都不写 = 继承容器全局工具表（最容易猜反的一条）")
    void noDeclarationInheritsTheGlobalTable() {
        Agent inheriting = agentRegistry.get("inheritingAssistant");

        assertThat(inheriting.tools().names()).containsAll(catalog.global().names());
        assertThat(inheriting.tools().size()).isEqualTo(catalog.global().size());
        assertThat(inheriting.tools().names()).contains("get_weather", "param_spectrum", "serial_probe");
    }

    @Test
    @DisplayName("被收紧的 Agent 看不见别的工具：模型连它的 schema 都收不到")
    void restrictedAgentCannotCallAToolOutsideItsTable() {
        // 用 mock（ScriptedLlmModel）驱动：它会尝试按 #tool: 点名一个工具，
        // 但前提是"这个工具在该 Agent 的工具表里"——不在表里就退化成普通回答。
        AgentResult restricted = agentRegistry.get("byClassAssistant")
                .call("#tool:param_default {}", SESSION);

        assertThat(restricted.toolCalls()).isEmpty();
        assertThat(restricted.text()).contains("已收到");     // 脚本模型的兜底回答

        // 对照：全局工具表里确实有 param_default，脚本模型点名就能真的调到
        AgentResult full = runScripted("param_default", "{}", SESSION);
        assertThat(full.toolCalls()).extracting(ToolCallRecord::name).containsExactly("param_default");
        assertThat(full.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("LlmToolCatalog.forClasses / forNames 直接查询：名字不存在只 WARN，不抛异常")
    void catalogLookupsAreForgiving() {
        assertThat(catalog.forClasses(new Class<?>[]{CommonTools.class}).names())
                .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate", "describe_context");
        assertThat(catalog.forClasses(new Class<?>[]{String.class}).names()).isEmpty();
        assertThat(catalog.forClasses(null).isEmpty()).isTrue();

        assertThat(catalog.forNames(new String[]{"explode", "并不存在的工具"}, "probeAgent").names())
                .containsExactly("explode");
        assertThat(catalog.forNames(null, "probeAgent").isEmpty()).isTrue();

        assertThat(catalog.snapshot()).containsKey(CommonTools.class);
        assertThat(catalog.snapshot().get(CommonTools.class)).hasSize(4);
    }

    @Test
    @DisplayName("LlmToolCatalog.register：登记之后手工组装的 Agent 能看到并真的调用它")
    void registerIsVisibleToAgentsBuiltAfterwards() {
        String toolName = "dynamic_echo_probe";
        assertThat(catalog.global().find(toolName)).isEmpty();

        catalog.register(ToolFixtures.simple(toolName, "运行期登记进来的工具",
                args -> "dynamic ← " + args.get("text")));
        assertThat(catalog.global().find(toolName)).isPresent();

        // 手工组装：工具表在"创建时"解析，所以必须重新 create 一次
        Agent fresh = agentFactory.create(AgentSpec.builder()
                .name("dynamicProbeAgent")
                .model("scripted-tools")
                .memory(false)
                .maxSteps(4)
                .build());
        assertThat(fresh.tools().names()).contains(toolName);

        scriptedModel.scriptCall(toolName, "{\"text\":\"我来自运行期登记\"}");
        AgentResult result = fresh.call("用一下新工具", SESSION);

        assertThat(result.toolCalls()).extracting(ToolCallRecord::name).containsExactly(toolName);
        assertThat(result.toolCalls().get(0).result()).isEqualTo("dynamic ← 我来自运行期登记");
    }

    @Test
    @DisplayName("已存在的 Agent 持有的是活的全局注册表，但 spec().toolNames() 是创建时的快照")
    void existingAgentHoldsLiveRegistryWhileSpecIsASnapshot() {
        catalog.register(ToolFixtures.simple("snapshot_probe", "观察快照与活引用", args -> "ok"));

        Agent existing = agentRegistry.get("inheritingAssistant");

        // tools() 返回的就是 LlmToolCatalog.global() 这个对象本身 —— 于是它"顺带"也看得见新工具；
        // 但 spec().toolNames() 是 DefaultAgent 构造时写下的快照，两者并不一致。
        // 示例只把这个事实钉住，不把"运行中的 Agent 立刻能用新工具"当成推荐用法（见 README）。
        assertThat(existing.tools().names()).contains("snapshot_probe");
        assertThat(existing.spec().toolNames()).doesNotContain("snapshot_probe");

        // 反过来，tools = {类} 的 Agent 拿到的是创建时另建的一份注册表（LlmToolCatalog.forClasses），
        // 它不会因为全局表变大而变大 —— "创建时解析"这条口径在它身上成立。
        assertThat(agentRegistry.get("byClassAssistant").tools().names()).doesNotContain("snapshot_probe");
        assertThat(agentRegistry.get("byNameAssistant").tools().names()).doesNotContain("snapshot_probe");
    }

    @Test
    @DisplayName("按来源类取工具的 Agent 照样能干活：默认模型按关键词挑中它表里的 get_weather")
    void byClassAgentStillWorksWithItsOwnTools() {
        Agent byClass = agentRegistry.get("byClassAssistant");
        // 关键词路径（ScriptedLlmModel 规则⑤）：命中"天气" → 在【该 Agent 的工具表里】找名字含 weather 的工具
        AgentResult result = byClass.call("北京天气怎么样？", SESSION);

        assertThat(result.toolCalls()).extracting(ToolCallRecord::name).containsExactly("get_weather");
        assertThat(result.toolCalls().get(0).error()).isFalse();
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("city", "北京");
        assertThat(commonTools.callCount()).isEqualTo(1);
        assertThat(result.text()).contains("根据工具返回的结果");
    }
}
