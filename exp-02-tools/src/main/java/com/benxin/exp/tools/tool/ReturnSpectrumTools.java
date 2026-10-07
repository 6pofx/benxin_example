package com.benxin.exp.tools.tool;

import com.benxin.exp.tools.dto.Ticket;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolResult;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 返回值全谱：工具方法能返回什么，以及每种返回值会变成什么文本。
 *
 * <p>转换规则集中在 {@code MethodToolCallback.toResult}，一共四档：</p>
 * <ol>
 *   <li>{@code ToolResult} → <b>原样返回</b>（于是错误标记与 meta 都由你掌控）；</li>
 *   <li>{@code null} → 文本 {@code "(无返回值)"}；</li>
 *   <li>{@code CharSequence / Number / Boolean} → {@code String.valueOf(...)}；</li>
 *   <li>其它（DTO、{@code ObjectNode}、{@code Map}…） → {@code Json.writeQuietly(...)}。</li>
 * </ol>
 *
 * <p>"回灌给模型的文本"就是上面这一份，不存在"对象直传"这种捷径 ——
 * 模型看到的永远是字符串。{@code ToolResult.meta()} 是个例外：
 * 它既不进模型、也不进 {@code AgentResult.toolCalls()}，只有监听器/拦截器拿得到。</p>
 */
public class ReturnSpectrumTools {

    /** 纯文本：原样回灌。 */
    @LlmTool(name = "return_string", description = "返回 String：文本原样回灌")
    public String text(@LlmToolParam(value = "text", description = "要回显的文本") String text) {
        return "String 返回值：" + text;
    }

    /** DTO：自动序列化成 JSON 文本。 */
    @LlmTool(name = "return_dto", description = "返回 DTO：自动序列化成 JSON 文本")
    public Ticket ticket(@LlmToolParam(value = "id", description = "工单号") String id,
                         @LlmToolParam(value = "urgent", description = "是否加急", required = false,
                                 defaultValue = "false") boolean urgent) {
        return new Ticket(id, "由 DTO 生成的工单", List.of("工具", "示例"), urgent);
    }

    /** {@code ObjectNode}：已经是 JSON 树，序列化时不会二次转义。 */
    @LlmTool(name = "return_node", description = "返回 ObjectNode：JSON 树直接序列化")
    public ObjectNode node(@LlmToolParam(value = "city", description = "城市") String city) {
        ObjectNode node = Json.object();
        node.put("city", city);
        node.put("source", "ObjectNode");
        node.putArray("alerts").add("高温").add("大风");
        return node;
    }

    /** {@code Map}：与 DTO 同一条序列化通道。 */
    @LlmTool(name = "return_map", description = "返回 Map：同样序列化成 JSON 文本")
    public Map<String, Object> map(@LlmToolParam(value = "key", description = "键") String key,
                                   @LlmToolParam(value = "value", description = "值") String value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        map.put("size", 2);
        map.put("nested", Map.of("deep", true));
        return map;
    }

    /**
     * {@code ToolResult.ok(content, meta)}：内容与错误标记都由自己掌控，
     * meta 走"旁路"（不污染模型上下文）。
     */
    @LlmTool(name = "return_ok_meta", description = "返回 ToolResult.ok(content, meta)：meta 只给监听器看")
    public ToolResult okWithMeta(@LlmToolParam(value = "text", description = "内容") String text) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", "return_ok_meta");
        meta.put("durationMillis", 12);
        meta.put("exitCode", 0);
        return ToolResult.ok("ok(content)：" + text, meta);
    }

    /** {@code ToolResult.error(...)}：错误标记由工具自己说了算，不需要靠抛异常。 */
    @LlmTool(name = "return_error", description = "返回 ToolResult.error(...)：自己控制错误标记")
    public ToolResult error(@LlmToolParam(value = "reason", description = "错误原因") String reason) {
        return ToolResult.error("工具自报错误：" + reason, Map.of("level", "warn"));
    }

    /** {@code null} 返回值：文本是 {@code (无返回值)}，不是空字符串。 */
    @LlmTool(name = "return_null", description = "返回 null：结果是文本 (无返回值)")
    public String none() {
        return null;
    }

    /** {@code Number}：{@code String.valueOf}，不会有 JSON 引号。 */
    @LlmTool(name = "return_number", description = "返回 int：按数字字符串回灌")
    public int number(@LlmToolParam(value = "n", description = "整数") int n) {
        return n * 2;
    }

    /** {@code boolean}：同上。 */
    @LlmTool(name = "return_boolean", description = "返回 boolean：按 true/false 回灌")
    public boolean flag(@LlmToolParam(value = "value", description = "真假值") boolean value) {
        return !value;
    }
}
