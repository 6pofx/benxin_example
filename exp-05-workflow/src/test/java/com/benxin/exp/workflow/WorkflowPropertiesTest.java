package com.benxin.exp.workflow;

import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowHandlerRegistry;
import com.benxin.llm.spring.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code llm.workflows.*} 的装配逻辑与三条 fail-fast 校验。
 *
 * <p>两条成功路径用真实的 {@code @SpringBootTest} 验证（真的注册出了 {@code workflow:<key>} Loop）；
 * 三条失败路径直接调用 starter 的私有装配方法 {@code registerDeclaredWorkflows}
 * —— 它是 <b>{@code llm.workflows.*} 唯一的入口</b>，在这里断言就等于断言"启动期会失败"，
 * 而且能拿到<b>准确的那一句话</b>（用 {@code @SpringBootTest} 触发只能看到一个被包装过的
 * {@code BeanCreationException}，消息里还混着容器的样板文字）。</p>
 *
 * <p><b>为什么内联定义要用 {@link DynamicPropertySource} 而不是 {@code @SpringBootTest(properties=…)}？</b>
 * 这是本次实测出来的一个测试写法坑（与插件无关，与 Spring 的测试支持有关）：
 * {@code @SpringBootTest} 的 {@code properties} 里每个元素都被当成<b>一份 properties 文件</b>解析，
 * 于是<b>多行值只有第一行生效</b> —— 一份 YAML 定义写进去，最终只剩下 {@code name: probe-inline}
 * 那一行，启动时必然报「工作流 [probe-inline] 没有任何节点」。
 * {@code @DynamicPropertySource} 直接往环境里放字符串，没有这层解析，所以多行 YAML 可以原样送达。</p>
 */
@SpringBootTest(properties = "exp.demo.enabled=false")
@DisplayName("llm.workflows.* 装配")
class WorkflowPropertiesTest {

    /** 内联校验用的最小图：块写法（flow 写法会被 WorkflowIo 当 JSON，见 WorkflowIoDetectionTest）。 */
    private static final String INLINE_YAML = """
            name: probe-inline
            description: 内联校验用的最小图
            nodes:
              - id: only
                type: end
                output: 内联定义跑通了
            edges: []
            """;

    @DynamicPropertySource
    static void workflowProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.workflows.probe-location.location", () -> "classpath:workflows/triage.yml");
        registry.add("llm.workflows.probe-inline.inline", () -> INLINE_YAML);
    }

    @Autowired
    private LoopRegistry loopRegistry;

    // ------------------------------------------------------------------
    // 两条成功路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("location 指向存在的资源 → 注册成 workflow:<配置键> Loop")
    void locationRegistersNamedLoop() {
        assertThat(loopRegistry.contains("workflow:probe-location")).isTrue();
        assertThat(loopRegistry.get("workflow:probe-location")).isInstanceOf(WorkflowLoop.class);
        // 注册名取配置键，不是 YAML 里的 name
        assertThat(loopRegistry.get("workflow:probe-location").name()).isEqualTo("workflow:probe-location");
        assertThat(loopRegistry.get("workflow:probe-location").description()).contains("工单分诊");
    }

    @Test
    @DisplayName("inline 内联 YAML → 同样注册；定义名取 YAML 里的 name，两者可以不同")
    void inlineRegistersNamedLoop() {
        assertThat(loopRegistry.contains("workflow:probe-inline")).isTrue();
        WorkflowLoop loop = (WorkflowLoop) loopRegistry.get("workflow:probe-inline");
        assertThat(loop.name()).isEqualTo("workflow:probe-inline");

        WorkflowDefinition definition = loop.definition().orElseThrow();
        assertThat(definition.name()).isEqualTo("probe-inline");
        assertThat(definition.nodeIds()).containsExactly("only");
        assertThat(definition.description()).isEqualTo("内联校验用的最小图");
    }

    @Test
    @DisplayName("application.yml 里声明的五个工作流都注册好了（deadloop 刻意没注册）")
    void declaredWorkflowsFromApplicationYml() {
        assertThat(loopRegistry.names()).contains(
                "workflow:triage", "workflow:loop-workflow", "workflow:inline-demo",
                "workflow:flaky-tools", "workflow:budget-probe");
        // deadloop.yml 是一张跑不完的图，刻意不注册
        assertThat(loopRegistry.contains("workflow:deadloop")).isFalse();
        // 光秃秃的 workflow Loop 也在（无默认定义，靠属性传）
        assertThat(loopRegistry.contains(WorkflowLoop.NAME)).isTrue();
        assertThat(loopRegistry.get(WorkflowLoop.NAME).description()).contains("benxin.workflow");
    }

    @Test
    @DisplayName("内联定义原样送达：多行 YAML 与 ${} 占位符都没有被 Spring 的占位符解析吃掉")
    void inlineDefinitionKeepsTemplatePlaceholders() {
        WorkflowLoop loop = (WorkflowLoop) loopRegistry.get("workflow:inline-demo");
        WorkflowDefinition definition = loop.definition().orElseThrow();

        // ★ 实测：@ConfigurationProperties 绑定不会把 ${input} 当占位符解析掉，
        //   所以内联 YAML 里的 ${} 模板能原样活到运行期（否则内联工作流的提示词会缺一块）。
        assertThat(definition.require("begin").prompt()).isEqualTo("内联工作流收到：${input}");
        assertThat(definition.require("done").output()).contains("${note}").contains("${begin}");
    }

    // ------------------------------------------------------------------
    // 三条 fail-fast 校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("location 与 inline 同时配置 → IllegalStateException「请二选一」")
    void rejectsBothSources() {
        LlmProperties.WorkflowProperties config = new LlmProperties.WorkflowProperties();
        config.setLocation("classpath:workflows/triage.yml");
        config.setInline("name: x\nnodes: []\nedges: []\n");

        assertThatThrownBy(() -> register("conflict", config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.conflict 同时配置了 location 与 inline")
                .hasMessageContaining("请二选一");
    }

    @Test
    @DisplayName("location 与 inline 都不配 → IllegalStateException「需要配置 location 或 inline 之一」")
    void rejectsNoSource() {
        LlmProperties.WorkflowProperties config = new LlmProperties.WorkflowProperties();

        assertThatThrownBy(() -> register("empty", config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.empty 需要配置 location 或 inline 之一");

        // 空串等于没配
        LlmProperties.WorkflowProperties blank = new LlmProperties.WorkflowProperties();
        blank.setLocation("   ");
        assertThatThrownBy(() -> register("blank", blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("需要配置 location 或 inline 之一");
    }

    @Test
    @DisplayName("location 指向不存在的资源 → IllegalStateException「指向的资源不存在」")
    void rejectsMissingResource() {
        LlmProperties.WorkflowProperties config = new LlmProperties.WorkflowProperties();
        config.setLocation("classpath:workflows/does-not-exist.yml");

        assertThatThrownBy(() -> register("missing", config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.missing 指向的资源不存在")
                .hasMessageContaining("does-not-exist.yml");
    }

    @Test
    @DisplayName("定义本身非法（条件语法错误 / 缺 prompt）→ 被包成「的定义非法」并带上原始原因")
    void rejectsInvalidDefinition() {
        LlmProperties.WorkflowProperties broken = new LlmProperties.WorkflowProperties();
        broken.setInline("name: bad\n"
                + "nodes:\n"
                + "  - id: a\n"
                + "    type: branch\n"
                + "  - id: b\n"
                + "    type: end\n"
                + "    output: x\n"
                + "edges:\n"
                + "  - from: a\n"
                + "    to: b\n"
                + "    when: \"${q} ==\"\n");
        assertThatThrownBy(() -> register("bad-condition", broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.bad-condition 的定义非法")
                .hasMessageContaining("条件表达式解析失败");

        LlmProperties.WorkflowProperties noPrompt = new LlmProperties.WorkflowProperties();
        noPrompt.setInline("name: bad\nnodes:\n  - id: a\n    type: agent\nedges: []\n");
        assertThatThrownBy(() -> register("bad-agent", noPrompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.bad-agent 的定义非法")
                .hasMessageContaining("必须提供 prompt");
    }

    @Test
    @DisplayName("装配是 fail-fast：一次调用里第一个坏定义就让它抛，不会注册出半个注册表")
    void failsFast() {
        LoopRegistry fresh = new LoopRegistry();
        Map<String, LlmProperties.WorkflowProperties> workflows = new LinkedHashMap<>();
        LlmProperties.WorkflowProperties good = new LlmProperties.WorkflowProperties();
        good.setLocation("classpath:workflows/triage.yml");
        LlmProperties.WorkflowProperties bad = new LlmProperties.WorkflowProperties();
        workflows.put("good", good);
        workflows.put("bad", bad);

        assertThatThrownBy(() -> registerAll(fresh, workflows, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm.workflows.bad 需要配置 location 或 inline 之一");
    }

    // ------------------------------------------------------------------
    // 属性对象本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LlmProperties.WorkflowProperties 的绑定口径")
    void propertiesShape() {
        LlmProperties.WorkflowProperties config = new LlmProperties.WorkflowProperties();
        assertThat(config.getLocation()).isNull();
        assertThat(config.getInline()).isNull();

        config.setLocation("classpath:workflows/triage.yml");
        config.setInline("name: x\nnodes: []\n");
        assertThat(config.getLocation()).isNotBlank();
        assertThat(config.getInline()).isNotBlank();

        LlmProperties properties = new LlmProperties();
        properties.setWorkflows(null);
        assertThat(properties.getWorkflows()).isEmpty();   // setter 会把 null 归一成空表
    }

    // ------------------------------------------------------------------
    // 反射调用 starter 的私有装配方法（它就是 llm.workflows.* 的唯一入口）
    // ------------------------------------------------------------------

    private static void register(String key, LlmProperties.WorkflowProperties config) throws Exception {
        registerAll(new LoopRegistry(), Map.of(key, config), new DefaultResourceLoader());
    }

    private static void registerAll(LoopRegistry registry,
                                    Map<String, LlmProperties.WorkflowProperties> workflows,
                                    ResourceLoader resourceLoader) throws Exception {
        Class<?> type = Class.forName("com.benxin.llm.spring.LlmAutoConfiguration");
        Object configuration = type.getDeclaredConstructor().newInstance();

        LlmProperties properties = new LlmProperties();
        properties.setWorkflows(new LinkedHashMap<>(workflows));

        // 1.1.1 之后多了第 4 个参数：工作流处理器注册表。装配期要拿它校验每个 java 节点的
        // handler 是否存在（未注册的处理器直接让启动失败，演示见 exp-13）。这里没有处理器，传空表。
        Method method = type.getDeclaredMethod("registerDeclaredWorkflows",
                LoopRegistry.class, LlmProperties.class, ResourceLoader.class,
                WorkflowHandlerRegistry.class);
        method.setAccessible(true);
        try {
            method.invoke(configuration, registry, properties, resourceLoader,
                    WorkflowHandlerRegistry.empty());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Test
    @DisplayName("反射拿到的确实是 starter 里那个私有装配方法（方法签名是契约的一部分）")
    void reflectionTargetExists() throws Exception {
        Class<?> type = Class.forName("com.benxin.llm.spring.LlmAutoConfiguration");
        Method method = type.getDeclaredMethod("registerDeclaredWorkflows",
                LoopRegistry.class, LlmProperties.class, ResourceLoader.class,
                WorkflowHandlerRegistry.class);
        assertThat(method.getParameterCount()).isEqualTo(4);
        assertThat(java.lang.reflect.Modifier.isPrivate(method.getModifiers())).isTrue();
        Field field = type.getDeclaredField("log");
        assertThat(field.getType().getName()).isEqualTo("org.slf4j.Logger");
    }
}
