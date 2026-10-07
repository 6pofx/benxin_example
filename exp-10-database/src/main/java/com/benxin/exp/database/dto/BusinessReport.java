package com.benxin.exp.database.dto;

import java.util.List;

/**
 * 经营概览 —— 直接由模型输出的 JSON 反序列化而来。
 *
 * <p>与 exp-01 的 {@code ReviewReport} 是同一套用法：方法的返回类型写成 DTO 时，
 * 本心会把模型输出的 JSON 反序列化成它。差别只在于本示例里这份 JSON 是"
 * 关于数据库里真实数据的结论"，因此它同时也是"模型看到了查库结果"的证据。</p>
 */
public record BusinessReport(String summary, List<String> highlights, int orderCount) {
}
