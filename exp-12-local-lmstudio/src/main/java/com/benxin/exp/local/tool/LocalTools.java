package com.benxin.exp.local.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 本地演示用的极小工具集。
 *
 * <p>刻意只有两个工具、参数也简单：本模块要验证的是"**真实模型**能不能按协议
 * 正确发起 function calling、结果能不能回灌收尾"，而不是工具本身的复杂度
 * （那是 exp-02 / exp-03 的职责）。工具越简单，失败时越容易定位是模型的问题还是工具的问题。</p>
 */
public class LocalTools {

    private int callCount;

    @LlmTool(name = "now", description = "获取本机当前时间")
    public String now() {
        callCount++;
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @LlmTool(name = "add", description = "计算两个整数之和")
    public String add(@LlmToolParam(value = "a", description = "第一个整数") int a,
                      @LlmToolParam(value = "b", description = "第二个整数") int b) {
        callCount++;
        return a + " + " + b + " = " + (a + b);
    }

    public int callCount() {
        return callCount;
    }

    public void reset() {
        callCount = 0;
    }
}
