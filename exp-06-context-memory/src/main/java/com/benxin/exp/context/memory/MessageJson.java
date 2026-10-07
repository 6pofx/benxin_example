package com.benxin.exp.context.memory;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatMessage} 列表 ⇄ JSON 的编解码。
 *
 * <p>为什么不让 Jackson 直接序列化 {@code ChatMessage}：它是<b>不可变、构造器私有、
 * 没有 Jackson 注解</b>的最终类，而 {@code ContentPart} 又是 sealed 接口 ——
 * 直接反序列化既拿不到构造器，也无法还原出具体的 part 子类型。本心的定位是
 * "消息结构由插件定义、存储方式由使用者决定"，因此自定义 {@code MemoryStore}
 * 必须自带一份编解码，这里就是那份编解码的最小可用形态。</p>
 *
 * <p>编码格式刻意做成"扁平的、带类型标签的 JSON 数组"，因为它要能同时容纳
 * 文本 / 图片 / 思维链 / 工具调用 / 工具结果五种内容块，
 * 又要让人能直接打开数据库一眼看懂：</p>
 *
 * <pre>{@code
 * [
 *   {"role":"user","parts":[{"type":"text","text":"你好"}]},
 *   {"role":"assistant","parts":[{"type":"tool_use","id":"call_1","name":"read_document","argumentsJson":"{}"}]},
 *   {"role":"tool","name":"read_document","toolCallId":"call_1",
 *    "parts":[{"type":"tool_result","toolUseId":"call_1","name":"read_document","content":"...","error":false}]}
 * ]
 * }</pre>
 */
public final class MessageJson {

    private MessageJson() {
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    public static String write(List<ChatMessage> messages) {
        ArrayNode array = Json.array();
        if (messages != null) {
            messages.forEach(message -> array.add(toNode(message)));
        }
        return array.toString();
    }

    private static ObjectNode toNode(ChatMessage message) {
        ObjectNode node = Json.object();
        node.put("role", message.role().wireName());
        // name / toolCallId 只有工具消息才有值，但它们参与 ChatMessage 的 equals，
        // 漏掉任何一个都会让"存进去再读出来"与原对象不相等 —— 所以按原值补 null。
        node.put("name", message.name());
        node.put("toolCallId", message.toolCallId());
        ArrayNode parts = node.putArray("parts");
        for (ContentPart part : message.parts()) {
            parts.add(partToNode(part));
        }
        return node;
    }

    private static ObjectNode partToNode(ContentPart part) {
        ObjectNode node = Json.object();
        node.put("type", part.type());
        if (part instanceof TextPart text) {
            node.put("text", text.text());
        } else if (part instanceof ImagePart image) {
            node.put("mediaType", image.mediaType());
            node.put("base64Data", image.base64Data());
            node.put("url", image.url());
        } else if (part instanceof ThinkingPart thinking) {
            node.put("text", thinking.text());
            node.put("signature", thinking.signature());
        } else if (part instanceof ToolUsePart toolUse) {
            node.put("id", toolUse.id());
            node.put("name", toolUse.name());
            node.put("argumentsJson", toolUse.argumentsJson());
        } else if (part instanceof ToolResultPart result) {
            node.put("toolUseId", result.toolUseId());
            node.put("name", result.name());
            node.put("content", result.content());
            node.put("error", result.error());
        } else {
            // sealed 接口理论上到不了这里；真到了也不能静默丢内容，
            // 否则"历史少了一条"会变成只在多轮对话里才暴露的诡异现象。
            throw new IllegalStateException("不支持序列化的内容块类型：" + part.getClass().getName());
        }
        return node;
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    public static List<ChatMessage> read(String json) {
        List<ChatMessage> messages = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return messages;
        }
        for (JsonNode node : Json.parse(json)) {
            messages.add(toMessage(node));
        }
        return messages;
    }

    private static ChatMessage toMessage(JsonNode node) {
        ChatMessage.Builder builder = ChatMessage.builder(Role.fromWire(node.path("role").asText(null)));
        if (node.hasNonNull("name")) {
            builder.name(node.path("name").asText());
        }
        if (node.hasNonNull("toolCallId")) {
            builder.toolCallId(node.path("toolCallId").asText());
        }
        for (JsonNode part : node.path("parts")) {
            builder.part(toPart(part));
        }
        return builder.build();
    }

    private static ContentPart toPart(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> new TextPart(node.path("text").asText(""));
            case "image" -> new ImagePart(nullable(node, "mediaType"), nullable(node, "base64Data"),
                    nullable(node, "url"));
            case "thinking" -> new ThinkingPart(nullable(node, "text"), nullable(node, "signature"));
            case "tool_use" -> new ToolUsePart(nullable(node, "id"), node.path("name").asText(""),
                    nullable(node, "argumentsJson"));
            case "tool_result" -> new ToolResultPart(nullable(node, "toolUseId"),
                    node.path("name").asText(""), node.path("content").asText(""),
                    node.path("error").asBoolean(false));
            default -> throw new IllegalStateException("不支持反序列化的内容块类型：" + type);
        };
    }

    /** Jackson 的 {@code asText()} 会把缺失字段变成空串，而空串与 null 在 part 里语义不同。 */
    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
