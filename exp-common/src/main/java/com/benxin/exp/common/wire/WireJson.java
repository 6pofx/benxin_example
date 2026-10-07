/*
 * 本文件改编自本心插件自带的协议契约测试工程 benxin_test：
 *   benxin_test/test-01-protocol-transport/src/main/java/com/benxin/test/protocol/wire/WireJson.java
 * 改编内容仅限包名与注释措辞，报文字段一个都没动 —— 这些字段名本身就是"协议契约"的可执行记录，
 * 示例工程照抄它们，才谈得上"对着真实报文演示协议"。
 */
package com.benxin.exp.common.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 报文构造自用的极小 JSON 门面。
 *
 * <p>刻意<b>不复用</b>插件的 {@code com.benxin.llm.core.util.Json}：桩服务吐出的响应必须是
 * "协议原文"，如果用它来生成，一旦插件的 mapper 配置（例如命名策略、null 处理）出了问题，
 * 构造与解析会同时按同一套错误规则工作，测试就会被抵消成永远通过。</p>
 */
final class WireJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireJson() {
    }

    static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("报文序列化失败", e);
        }
    }

    static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON: " + text, e);
        }
    }
}

