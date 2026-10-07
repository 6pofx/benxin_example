package com.benxin.exp.tools.tool;

import com.benxin.llm.core.annotation.LlmTool;

/**
 * 类级 {@code @LlmTool}：类上标了它，<b>所有 public 方法</b>都成为工具。
 *
 * <p>两个必须知道的边界：</p>
 * <ol>
 *   <li>方法名就是工具名 —— 因为类级注解没有名字可用。所以<b>不要在类级注解上写
 *       {@code name}</b>：那会让本类所有方法都叫同一个名字，进注册表时后一个覆盖前一个
 *       （{@code DefaultToolRegistry} 用 {@code Map.put}），最后只剩一个工具。
 *       类级注解只该写 {@code description}。</li>
 *   <li>方法级 {@code @LlmTool(enabled = false)} 依然可以单独关掉某一个方法 ——
 *       方法级注解存在时以它为准（{@code ToolScanner.java:50}）。</li>
 * </ol>
 *
 * <p>参数没写 {@code @LlmToolParam}，名字来自编译期保留的真实参数名，
 * 这依赖编译时的 {@code -parameters}（聚合工程的父 pom 已打开）。</p>
 */
@LlmTool(description = "类级注解：本类所有 public 方法都是工具")
public class ClassLevelToolSet {

    public int class_level_add(int a, int b) {
        return a + b;
    }

    public int class_level_multiply(int a, int b) {
        return a * b;
    }

    /** 类级模式下依然能点名关掉一个方法。 */
    @LlmTool(enabled = false)
    public String class_level_disabled() {
        throw new IllegalStateException("方法级 enabled = false 应该把它从工具表里摘掉");
    }
}
