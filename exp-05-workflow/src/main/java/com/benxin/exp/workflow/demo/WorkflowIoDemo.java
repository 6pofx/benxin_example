package com.benxin.exp.workflow.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.util.Json;
import com.benxin.llm.core.workflow.Conditions;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.spring.LlmProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link WorkflowIo} 与<b>加载期校验</b>的演示。
 *
 * <p>两件事放在一起，是因为它们是同一条承诺的两面：<b>定义有问题，就在加载期说清楚</b>。
 * 一份跑起来才发现画错的图，比一个启动期的报错昂贵得多 —— 所以你在这里会看到
 * 缺 entry、边指向不存在的节点、节点 id 与保留变量重名、条件表达式语法错误……
 * 全部在 {@code read(...)} 的那一刻就被拦下，而且报的是一句能照着改的话。</p>
 *
 * <p>{@code WofkflowIo} 的两条入口（YAML 与 JSON）最终都汇到同一套 Map/List 结构再交给
 * Jackson 绑定，因此<b>校验逻辑只有一份</b>：无论从哪种格式来，报的都是同一句话。</p>
 */
public final class WorkflowIoDemo {

    private WorkflowIoDemo() {
    }

    // ------------------------------------------------------------------
    // WorkflowIo API
    // ------------------------------------------------------------------

    public static void show(Demo demo, ResourceLoader resourceLoader, ClassPathResource triageYaml) {
        demo.section("WorkflowIo API：YAML / JSON 两条入口，一套校验");
        demo.info("yamlAvailable()          = %s", WorkflowIo.yamlAvailable());
        demo.info("yamlUnavailableReason()  = %s", WorkflowIo.yamlUnavailableReason());
        demo.info("   ★ core 只有 Jackson 一个硬依赖；SnakeYAML 是**反射发现**的可选入口。");
        demo.info("   Spring Boot 应用自带 snakeyaml，所以 YAML 开箱可用。");

        demo.info("formatOfFileName(\"a.yml\")  = %s", WorkflowIo.formatOfFileName("a.yml"));
        demo.info("formatOfFileName(\"a.yaml\") = %s", WorkflowIo.formatOfFileName("a.yaml"));
        demo.info("formatOfFileName(\"a.json\") = %s（只有 .json 才是 JSON，其余按 YAML）",
                WorkflowIo.formatOfFileName("a.json"));

        WorkflowDefinition fromClasspath = readResource(triageYaml);
        demo.ok("read(InputStream)  ← classpath:workflows/triage.yml → %s", fromClasspath);
        demo.info("   name=%s entry=%s maxVisitsPerNode=%d",
                fromClasspath.name(), fromClasspath.entry(), fromClasspath.maxVisitsPerNode());

        WorkflowDefinition fromString = WorkflowIo.read(yamlText(resourceLoader));
        demo.ok("read(String) 自动识别格式 → 与上面是同一个图：%s",
                fromString.nodes().size() == fromClasspath.nodes().size());

        demo.info("read(String, Format.JSON) 强制按 JSON 解析 → %s",
                WorkflowIo.read(jsonText(resourceLoader), WorkflowIo.Format.JSON));

        Path tempFile = writeTemp(resourceLoader);
        if (tempFile != null) {
            demo.ok("read(Path)    ← %s → %s", tempFile.getFileName(), WorkflowIo.read(tempFile));
            deleteQuietly(tempFile);
        }

        // ---- 往返：YAML → 定义 → JSON / YAML ----
        String json = WorkflowIo.writeJson(fromClasspath);
        demo.ok("writeJson 往返：JSON 文本 %d 字符，前 90 字符 = %s",
                json.length(), flatten(json.substring(0, Math.min(90, json.length()))));
        WorkflowDefinition fromJson = WorkflowIo.read(json);
        demo.ok("   再从 JSON 读回来：nodes=%d edges=%d（与 YAML 入口是同一个模型）",
                fromJson.nodes().size(), fromJson.edges().size());

        Optional<String> yaml = WorkflowIo.writeYaml(fromClasspath);
        demo.info("writeYaml 往返：isPresent=%s，长度=%s", yaml.isPresent(),
                yaml.map(text -> String.valueOf(text.length())).orElse("（YAML 入口不可用）"));
        yaml.ifPresent(text -> {
            WorkflowDefinition back = WorkflowIo.read(text);
            demo.ok("   再从 YAML 读回来：nodes=%d edges=%d，name=%s",
                    back.nodes().size(), back.edges().size(), back.name());
        });

        // ---- toMap 快照：默认值不入镜 ----
        Map<String, Object> snapshot = WorkflowIo.toMap(fromClasspath);
        demo.info("toMap 快照的顶层键 = %s", snapshot.keySet());
        demo.ok("toMap 刻意不写默认值：retry: 0 / maxSteps: -1 / maxVisitsPerNode: 100 都不出现");
        demo.info("   第一行节点 = %s", firstNode(snapshot));
    }

    // ------------------------------------------------------------------
    // 加载期校验
    // ------------------------------------------------------------------

    /** 逐条演示加载期校验，每条都打印异常消息。 */
    public static void showValidation(Demo demo, ResourceLoader resourceLoader) {
        demo.section("加载期校验：定义有问题就在启动期报错，而不是跑到一半才发现");

        expectFailure(demo, "缺 entry（entry 指向不存在的节点）", """
                name: broken
                entry: nowhere
                nodes:
                  - id: a
                    type: start
                edges: []
                """);

        expectFailure(demo, "边指向不存在的节点", """
                name: broken
                nodes:
                  - id: a
                    type: start
                edges:
                  - from: a
                    to: ghost
                """);

        expectFailure(demo, "节点 id 与保留变量重名", """
                name: broken
                nodes:
                  - id: last
                    type: start
                edges: []
                """);

        expectFailure(demo, "条件表达式语法错误", """
                name: broken
                nodes:
                  - id: a
                    type: branch
                  - id: b
                    type: end
                edges:
                  - from: a
                    to: b
                    when: "${x} =="
                """);

        expectFailure(demo, "agent 节点缺 prompt", """
                name: broken
                nodes:
                  - id: a
                    type: agent
                edges: []
                """);

        expectFailure(demo, "tool 节点缺工具名", """
                name: broken
                nodes:
                  - id: a
                    type: tool
                edges: []
                """);

        expectFailure(demo, "节点 id 不允许带点号", """
                name: broken
                nodes:
                  - id: a.b
                    type: start
                edges: []
                """);

        // ---- 不可达边：警告（而不是异常）----
        demo.info("── 最后一条是**警告**而不是异常：默认边之前的条件边永远不可达");
        WorkflowDefinition withDeadEdge = WorkflowIo.read("""
                name: dead-edge
                nodes:
                  - id: route
                    type: branch
                  - id: a
                    type: end
                    output: A
                  - id: b
                    type: end
                    output: B
                edges:
                  - from: route
                    to: b
                  - from: route
                    to: a
                    when: "${x} == 1"
                """);
        demo.ok("图能读进来（语义上仍然是确定的）：%s", withDeadEdge);
        demo.info("   但加载期会打一条 WARN：节点 [route] 的第 2 条出边永远不可达（边序即优先级）");
        demo.info("   为什么是警告不是异常：把默认边写在前面的图**语义仍然确定**，只是与作者本意多半不符。");

        // ---- location / inline 是 starter 的属性装配，由 LlmAutoConfiguration 校验 ----
        demo.info("── location 与 inline 同时配置：由 starter 的装配逻辑拦下（见 WorkflowIoTest）");
        LlmProperties.WorkflowProperties conflict = new LlmProperties.WorkflowProperties();
        conflict.setLocation("classpath:workflows/triage.yml");
        conflict.setInline("name: x\nnodes: []\n");
        demo.info("   llm.workflows.x.location=%s", conflict.getLocation());
        demo.info("   llm.workflows.x.inline=%s", flatten(conflict.getInline()));
        demo.info("   → LlmAutoConfiguration.loadWorkflow 抛 IllegalStateException："
                + "「llm.workflows.x 同时配置了 location 与 inline，请二选一」");
        demo.info("   LlmProperties 是可绑定的普通配置类，所以断言它只需要 new 一个对象。");
    }

    // ------------------------------------------------------------------

    /** 跑一段"本该失败"的加载，把异常消息打出来。返回异常消息（演示与测试共用同一份判定）。 */
    public static String expectFailure(Demo demo, String what, String definition) {
        try {
            WorkflowIo.read(definition);
        } catch (RuntimeException e) {
            demo.ok("%s", what);
            demo.info("   %s: %s", e.getClass().getSimpleName(), flatten(e.getMessage()));
            return e.getMessage();
        }
        demo.warn("%s —— 本该报错却通过了！", what);
        return "";
    }

    // ------------------------------------------------------------------

    private static WorkflowDefinition readResource(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return WorkflowIo.read(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + resource + " 失败", e);
        }
    }

    private static String yamlText(ResourceLoader resourceLoader) {
        return text(resourceLoader.getResource("classpath:workflows/triage.yml"));
    }

    private static String jsonText(ResourceLoader resourceLoader) {
        return WorkflowIo.writeJson(readResource(resourceLoader.getResource("classpath:workflows/triage.yml")));
    }

    private static String text(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + resource + " 失败", e);
        }
    }

    private static Path writeTemp(ResourceLoader resourceLoader) {
        try {
            Path file = Files.createTempFile("exp05-", ".json");
            Files.writeString(file, jsonText(resourceLoader), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响演示
        }
    }

    private static String firstNode(Map<String, Object> snapshot) {
        Object nodes = snapshot.get("nodes");
        if (nodes instanceof List<?> list && !list.isEmpty()) {
            return Json.writeQuietly(list.get(0));
        }
        return "(空)";
    }

    /** 条件表达式也能被加载期校验：顺手把最常见的三个错法列出来。 */
    public static List<String> invalidConditions() {
        return List.of("${a} ==", "(${a} == 1", "${a} is 差不多");
    }

    /** 供演示与测试共用：校验一个条件表达式，返回异常消息（合法时返回 null）。 */
    public static String conditionError(String expression) {
        try {
            Conditions.validate(expression);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static String flatten(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 150 ? flat : flat.substring(0, 150) + "...";
    }
}
