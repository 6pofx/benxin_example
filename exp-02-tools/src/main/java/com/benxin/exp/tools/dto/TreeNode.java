package com.benxin.exp.tools.dto;

/**
 * 自引用 POJO：用来暴露 schema 生成器的<b>深度上限</b>。
 *
 * <p>{@code JsonSchemaGenerator} 里的 {@code MAX_DEPTH = 4}：深度超过它就退化成
 * 一个光秃秃的 {@code {"type":"object"}}（不再展开 properties）。
 * 没有这道闸门，一个自引用类型（或环状类型）能把 schema 撑到内存溢出。</p>
 */
public record TreeNode(String name, TreeNode child) {
}
