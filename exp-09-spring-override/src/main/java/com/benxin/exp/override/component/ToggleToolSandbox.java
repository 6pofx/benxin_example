package com.benxin.exp.override.component;

import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolInvocation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 切面 11 的自定义实现：{@code ToolSandbox}。
 *
 * <p>这是 14 个切面里<b>最该被认真对待</b>的一个：它的默认值由
 * {@code BuiltinToolkit.sandbox(...)} 从 {@code llm.tools.*} 推导出来，
 * 是"模型能不能动你的文件与命令"的唯一闸门。</p>
 *
 * <p>本类做成"可开关的闸门"：默认放行（否则整个演示都跑不动），
 * 需要证明"换了沙箱就真的拦得住"时把它关掉即可 ——
 * {@code check()} 返回 {@code Decision.deny(...)} 会让工具调用<b>在真正执行之前</b>被短路。</p>
 *
 * <p>值得记住的一条：{@code ToolSandbox} 与"启用了哪些内置工具"同源。
 * 以前沙箱只看 {@code allow-write}，于是 {@code tools.enabled: [write]} 而没写
 * {@code allow-write} 时工具"看得见却跑不动"；现在两者由 {@code BuiltinToolkit} 统一推导。</p>
 */
public class ToggleToolSandbox implements ToolSandbox {

    private final AtomicBoolean permissive = new AtomicBoolean(true);
    private final List<String> checks = new CopyOnWriteArrayList<>();

    @Override
    public Decision check(ToolInvocation invocation) {
        String tool = invocation == null || invocation.toolName() == null ? "?" : invocation.toolName();
        checks.add(tool + " → " + (permissive.get() ? "allow" : "deny"));
        return permissive.get()
                ? Decision.allow()
                : Decision.deny("exp-09 的沙箱闸门被显式合上了（工具 " + tool + " 被拒绝）");
    }

    public void setPermissive(boolean value) {
        permissive.set(value);
    }

    public boolean isPermissive() {
        return permissive.get();
    }

    public List<String> checks() {
        return List.copyOf(checks);
    }

    public int count() {
        return checks.size();
    }

    public void reset() {
        checks.clear();
    }

    /** 超时也一并自己定：沙箱的三个默认方法（timeout / maxResultChars / truncate）都能覆盖。 */
    @Override
    public Duration timeout() {
        return Duration.ofSeconds(5);
    }

    @Override
    public int maxResultChars() {
        return 4000;
    }

    @Override
    public String toString() {
        return "ToggleToolSandbox(当前" + (permissive.get() ? "放行" : "拒绝") + ")";
    }
}
