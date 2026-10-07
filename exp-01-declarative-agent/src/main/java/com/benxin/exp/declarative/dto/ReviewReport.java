package com.benxin.exp.declarative.dto;

import java.util.List;

/**
 * 强类型返回值示例：模型输出的 JSON 会被自动反序列化成它。
 *
 * <p>刻意用 record —— 本心的 DTO 反序列化走 Jackson，record 与普通 POJO 一视同仁。</p>
 */
public record ReviewReport(String summary, List<String> issues, int score) {
}
