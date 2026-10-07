package com.benxin.exp.tools.dto;

import java.util.List;

/**
 * DTO 返回值：工具方法返回它时，框架用 {@code Json.writeQuietly} 序列化成 JSON 文本，
 * 这条文本才是"回灌给模型的内容"（也是 {@code ToolCallRecord#result()}）。
 *
 * <p>注意方向：这是<b>工具返回 → 文本</b>，与 exp-01 里"模型输出 JSON → DTO"正好相反。
 * 两者都会经过同一个 Jackson 门面（{@code com.benxin.llm.core.util.Json}），
 * 因此你替换全局 ObjectMapper 时，两个方向会一起变。</p>
 */
public record Ticket(String id, String title, List<String> labels, boolean urgent) {
}
