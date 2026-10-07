package com.benxin.exp.tools.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * 带"路径"语义参数的工具：用来观察沙箱这一道关卡。
 *
 * <p>本模块 {@code llm.tools.builtin-enabled=false} 只是"不装配内置文件工具"，
 * 容器里的 {@code ToolSandbox} 依然是真围栏（{@code PathSandbox + CommandSandbox}）——
 * <b>自己写的工具同样受它约束</b>。参数名只要命中
 * {@code PathSandbox.PATH_KEYS}（{@code path} / {@code file} / {@code dir} …），
 * 值就会被当成路径解析：越出围栏、命中敏感规则一律拒绝，且拒绝结果同样是以
 * {@code ToolResult.error} 回灌给模型。</p>
 */
public class NoteTools {

    @LlmTool(name = "read_note", description = "读取围栏目录内的一个笔记文件（路径参数会被沙箱校验）")
    public String readNote(@LlmToolParam(value = "path", description = "相对路径") String path) {
        // 真读文件不在本示例的目标里（那是 exp-03 的事）：这里只要证明"能走到工具方法体"，
        // 因为沙箱拒绝发生在方法体之前 —— 拒绝时这个方法根本不会被调用。
        return "工具方法体被执行：path=" + path;
    }
}
