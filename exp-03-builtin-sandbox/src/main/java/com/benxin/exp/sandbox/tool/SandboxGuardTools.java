package com.benxin.exp.sandbox.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.tool.builtin.TodoWriteTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用者自己写的工具 —— 本示例用它证明两件事。
 *
 * <p><b>① 沙箱与内置工具开关无关。</b>{@code llm.tools.builtin-enabled=false} 只是"不装配内置工具"，
 * 容器里的 {@code ToolSandbox} 依然是真正的 {@code PathSandbox + CommandSandbox}。
 * {@link #guardedEcho} 刻意把参数命名为 {@code path} —— {@code PathSandbox} 正是按
 * {@code path / file / dir / pattern ...} 这些键名识别"这是不是一个路径参数"的，
 * 于是这个自写工具会像内置工具一样被围栏拦住。</p>
 *
 * <p><b>② 工具之间可以共享"运行属性"。</b>{@link #currentTodos} 读的就是内置
 * {@code todo_write} 写进 {@code benxin.todos} 的那份清单 —— 内置工具与自写工具
 * 用的是同一个 {@link ToolContext}，没有"内置的上下文"与"用户的上下文"之分。</p>
 */
public class SandboxGuardTools {

    /** 让示例与测试能看到"这个自写工具到底被执行了几次"。 */
    private int callCount;

    @LlmTool(name = "guarded_echo",
            description = "回显一个沙箱围栏内的相对路径，用于验证自写工具是否同样受 PathSandbox 约束")
    public Map<String, Object> guardedEcho(
            @LlmToolParam(value = "path", description = "沙箱围栏内的相对路径") String path,
            ToolContext ctx) {
        callCount++;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("accepted", path);
        info.put("agent", ctx == null ? "-" : ctx.agentName());
        info.put("sandbox", ctx == null || ctx.sandbox() == null
                ? "-" : ctx.sandbox().getClass().getSimpleName());
        info.put("note", "自写工具被真正执行了：能走到这里，说明沙箱没有拦它");
        return info;
    }

    @LlmTool(name = "current_todos",
            description = "读取当前运行里由内置 todo_write 工具写下的待办清单")
    public Object currentTodos(ToolContext ctx) {
        callCount++;
        if (ctx == null) {
            return "当前没有工具上下文。";
        }
        Object todos = ctx.attributes() == null ? null : ctx.attributes().get(TodoWriteTool.ATTRIBUTE_KEY);
        return todos == null
                ? "运行属性 " + TodoWriteTool.ATTRIBUTE_KEY + " 还不存在（本次运行里没调用过 todo_write）。"
                : todos;
    }

    public int callCount() {
        return callCount;
    }

    public void reset() {
        callCount = 0;
    }
}
