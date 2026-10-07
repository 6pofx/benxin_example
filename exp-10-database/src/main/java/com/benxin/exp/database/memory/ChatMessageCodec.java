package com.benxin.exp.database.memory;

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
 * {@link ChatMessage} ↔ JSON 的编解码器。
 *
 * <p><b>为什么必须有这个类？</b>因为本心的 {@code ChatMessage} 与 {@code ContentPart}
 * 是<b>刻意不可被 Jackson 序列化/反序列化</b>的：</p>
 *
 * <ul>
 *   <li>{@code ChatMessage} 没有公开构造器、没有 setter，字段全 private 且没有 {@code get}
 *       前缀的访问器 —— 只有 {@code role()} / {@code parts()} / {@code isEmpty()} 这种。
 *       实测 {@code Json.write(ChatMessage.user("hi"))} <b>不报错</b>，但只输出
 *       {@code {"textEmpty":false}}：message 的 role 与全部内容都丢了。
 *       这是最容易踩的坑 —— 它不抛异常，只是把数据静默写没了。</li>
 *   <li>{@code Role} 的反序列化只认枚举名（{@code USER}），不认 wire name（{@code user}），
 *       直接绑会抛 {@code InvalidFormatException}。</li>
 * </ul>
 *
 * <p>因此本示例不试图"让 Jackson 自己搞定"，而是显式写一层往返编解码：
 * <b>字段名与 {@link ContentPart#type()} 一一对应，逐部件 switch，未知部件直接报错</b>。
 * 这样做的额外收益是：{@code payload} 列的内容是"本心消息结构的显式快照"，
 * 换一个不兼容的插件版本时能立刻看出来，而不是等到 load 出一堆 null。</p>
 *
 * <p>消息的 JSON 形状（每种部件都带 {@code type} 判别字段）：</p>
 * <pre>{@code
 * {
 *   "role": "USER",
 *   "name": null,
 *   "toolCallId": null,
 *   "parts": [
 *     {"type": "text",        "text": "查一下张三的订单"},
 *     {"type": "thinking",    "text": "……", "signature": null},
 *     {"type": "tool_use",    "id": "call_1", "name": "query_orders", "argumentsJson": "{...}"},
 *     {"type": "tool_result", "toolUseId": "call_1", "name": "query_orders", "content": "[...]", "error": false},
 *     {"type": "image",       "mediaType": "image/png", "base64Data": null, "url": "https://..."}
 *   ]
 * }
 * }</pre>
 */
public final class ChatMessageCodec {

    private ChatMessageCodec() {
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 把一条消息编码成 JSON 文本。 */
    public static String encode(ChatMessage message) {
        ObjectNode root = Json.object();
        root.put("role", message.role().name());
        putOrNull(root, "name", message.name());
        putOrNull(root, "toolCallId", message.toolCallId());
        ArrayNode parts = root.putArray("parts");
        for (ContentPart part : message.parts()) {
            parts.add(encodePart(part));
        }
        return root.toString();
    }

    private static ObjectNode encodePart(ContentPart part) {
        ObjectNode node = Json.object();
        // 判别字段放在最前面：payload 列肉眼读起来第一眼就知道这是什么块
        if (part instanceof TextPart text) {
            node.put("type", "text");
            node.put("text", text.text());
            return node;
        }
        if (part instanceof ThinkingPart thinking) {
            node.put("type", "thinking");
            putOrNull(node, "text", thinking.text());
            putOrNull(node, "signature", thinking.signature());
            return node;
        }
        if (part instanceof ToolUsePart use) {
            node.put("type", "tool_use");
            putOrNull(node, "id", use.id());
            putOrNull(node, "name", use.name());
            putOrNull(node, "argumentsJson", use.argumentsJson());
            return node;
        }
        if (part instanceof ToolResultPart result) {
            node.put("type", "tool_result");
            putOrNull(node, "toolUseId", result.toolUseId());
            putOrNull(node, "name", result.name());
            putOrNull(node, "content", result.content());
            node.put("error", result.error());
            return node;
        }
        if (part instanceof ImagePart image) {
            node.put("type", "image");
            putOrNull(node, "mediaType", image.mediaType());
            putOrNull(node, "base64Data", image.base64Data());
            putOrNull(node, "url", image.url());
            return node;
        }
        // 插件新增部件类型时会走到这里。这里选择"响亮地失败"而不是静默丢掉：
        // 静默丢部件会让模型看不到自己刚调过的工具结果，症状是"模型反复调同一个工具"，
        // 极难排查；启动就报错反而最好修。
        throw new IllegalArgumentException("ChatMessageCodec 不认识的 ContentPart 实现: "
                + part.getClass().getName() + "（type=" + part.type() + "）。"
                + "这通常意味着 benxin 升级后新增了内容块类型，请同步扩展本编解码器。");
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 把 JSON 文本还原成一条消息。 */
    public static ChatMessage decode(String payload) {
        JsonNode root = Json.parse(payload);
        Role role = Role.fromWire(text(root, "role"));
        String name = text(root, "name");
        String toolCallId = text(root, "toolCallId");

        List<ContentPart> parts = new ArrayList<>();
        JsonNode partsNode = root.get("parts");
        if (partsNode != null && partsNode.isArray()) {
            partsNode.forEach(node -> parts.add(decodePart(node)));
        }
        return ChatMessage.builder(role)
                .parts(parts)
                .name(name)
                .toolCallId(toolCallId)
                .build();
    }

    private static ContentPart decodePart(JsonNode node) {
        String type = text(node, "type");
        if (type == null) {
            throw new IllegalArgumentException("payload 里的内容块缺少 type 判别字段: " + node);
        }
        return switch (type) {
            case "text" -> new TextPart(text(node, "text"));
            case "thinking" -> new ThinkingPart(text(node, "text"), text(node, "signature"));
            case "tool_use" -> new ToolUsePart(text(node, "id"), text(node, "name"),
                    text(node, "argumentsJson"));
            case "tool_result" -> new ToolResultPart(text(node, "toolUseId"), text(node, "name"),
                    text(node, "content"), node.path("error").asBoolean(false));
            case "image" -> new ImagePart(text(node, "mediaType"), text(node, "base64Data"),
                    text(node, "url"));
            default -> throw new IllegalArgumentException("payload 里有未知的内容块 type: " + type);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
