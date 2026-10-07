package com.benxin.exp.tools.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * 会抛异常的工具：用来证明"工具炸了不等于整条链断了"。
 *
 * <p>{@code MethodToolCallback.call} 把 {@code InvocationTargetException} 的<b>目标异常</b>
 * 捞出来，转成 {@code ToolResult.error("工具执行抛出 X: msg")} 交给模型；
 * 于是模型看到的是"这一步失败了，换个做法"，而不是调用方收到一个异常。
 * 这与 exp-01 里"工具必须自己 try/catch"的直觉不同：注解路径<b>包了</b>这一层。</p>
 *
 * <p>反过来，如果你希望"这个失败必须冒泡到调用方"，请别用工具 ——
 * 工具的错误是<b>给模型看</b>的，不是给调用栈看的。</p>
 */
public class FaultyTools {

    @LlmTool(name = "explode", description = "故意抛 RuntimeException，观察框架如何把它变成错误结果")
    public String explode(@LlmToolParam(value = "reason", description = "炸的理由", required = false)
                          String reason) {
        throw new IllegalStateException("工具内部炸了：" + reason);
    }
}
