package com.benxin.exp.tools.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 工具来源 ③：{@code tools} 与 {@code toolNames} <b>都不写</b> —— 继承容器的全局工具表。
 *
 * <p>这是最容易被猜反的一条口径：什么都不写不表示"没有工具"，
 * 而是"容器里有多少工具就给我多少"。本模块 20 多个工具会全部装配进来，
 * 每次请求都会把它们<b>全部的 schema</b> 发给模型 —— token 是这么烧掉的。</p>
 *
 * <p>想收紧请显式写 {@code tools} 或 {@code toolNames}；两者的取舍见
 * {@link ByClassAssistant} 与 {@link ByNameAssistant}。</p>
 */
@LlmAgent(
        name = "inheritingAssistant",
        systemPrompt = "你是继承容器全局工具表的助手。")
public interface InheritingAssistant {

    String ask(@User String question);
}
