package com.benxin.exp.tools.config;

import com.benxin.exp.tools.support.ConcurrencyRecorder;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 程序化工具：直接实现 {@link ToolCallback} 并把它当 bean 交给容器。
 *
 * <p>{@code LlmToolCatalog} 对 {@code ToolCallback} 类型的 bean 有专门的短路分支
 * （{@code LlmToolCatalog.java:54}）：<b>"实现接口"与"打注解"是等价的两种扩展方式</b>，
 * 不需要为了走 bean 路径再套一层 {@code @LlmTool}。这里演示它多出来的三件事：</p>
 * <ol>
 *   <li>{@code spec().inputSchema()} 完全手写 —— 想要什么 schema 就写什么；</li>
 *   <li>{@code parallelSafe() = false}：注解路径<b>表达不了</b>这个语义
 *       （{@code @LlmTool} 没有该属性，{@code MethodToolCallback} 也没覆写，
 *       于是注解工具恒为默认的 true）；</li>
 *   <li>{@code requiresApproval() = true}：只有 {@code ApprovalPolicy.ON_REQUEST}
 *       （本模块 yml 里显式打开）才会真的来问。</li>
 * </ol>
 */
@Configuration
public class ProgrammaticToolConfiguration {

    /**
     * 一个"不能被并行执行"的工具：它睡一小会儿再返回，示例靠它对照
     * "全真才并行、有一个说不安全就整轮串行"这条规则。
     */
    @Bean
    public ToolCallback serialProbeTool(ConcurrencyRecorder recorder) {
        return new ToolCallback() {

            private final ToolSpec spec = new ToolSpec("serial_probe",
                    "手写 ToolCallback：parallelSafe=false + requiresApproval=true",
                    probeSchema(), false);

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                String id = String.valueOf(arguments.getOrDefault("id", "?"));
                return recorder.record("serial_probe:" + id, 120,
                        () -> ToolResult.ok("serial_probe(" + id + ") 在 "
                                + Thread.currentThread().getName() + " 上串行完成",
                                Map.of("parallelSafe", false)));
            }

            @Override
            public boolean parallelSafe() {
                return false;
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }
        };
    }

    /**
     * 手写 schema 的工具：证明"schema 里能表达什么"不受注解能力限制。
     *
     * <p>返回 {@code ToolResult.ok(content, meta)}：meta 是给监听器看的旁路信息，
     * 不会污染模型上下文。</p>
     */
    @Bean
    public ToolCallback manualReportTool() {
        return new ToolCallback() {

            private final ToolSpec spec = new ToolSpec("manual_report",
                    "手写 spec + call：与 @LlmTool 注解路径等价，但 schema 由自己拼",
                    reportSchema(), false);

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult call(Map<String, Object> arguments, ToolContext context) {
                String title = String.valueOf(arguments.getOrDefault("title", "(无标题)"));
                String format = String.valueOf(arguments.getOrDefault("format", "text"));
                String body = "json".equals(format)
                        ? Json.writeQuietly(Map.of("title", title, "source", "hand-written"))
                        : "手工工具报告：" + title;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("source", "hand-written ToolCallback");
                meta.put("agent", context.agentName());
                meta.put("format", format);
                return ToolResult.ok(body, meta);
            }
        };
    }

    // ------------------------------------------------------------------

    private static ObjectNode probeSchema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("id").put("type", "string").put("description", "本次调用的标记");
        schema.putArray("required").add("id");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode reportSchema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title").put("type", "string").put("description", "报告标题");
        ObjectNode format = properties.putObject("format");
        format.put("type", "string");
        format.putArray("enum").add("text").add("json");
        format.put("default", "text");
        schema.putArray("required").add("title");
        schema.put("additionalProperties", false);
        return schema;
    }
}
