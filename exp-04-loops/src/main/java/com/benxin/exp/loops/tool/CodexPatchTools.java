package com.benxin.exp.loops.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.loop.patch.ApplyPatchParser;
import com.benxin.llm.core.sandbox.SandboxConfig;

import java.nio.file.Path;

/**
 * Codex 风格的 {@code apply_patch} 工具：把一段 {@code *** Begin Patch} 文本落到磁盘上。
 *
 * <p>本心内置工具集里没有 {@code apply_patch}（{@code BuiltinToolkit.ALL_TOOLS} 只有
 * read/write/edit/glob/grep/bash/todo_write/task），但**补丁解析器是现成的公共 API**：
 * {@link ApplyPatchParser}。于是"给 codex Loop 配一个能落盘的补丁工具"只需把它包一层 ——
 * 这正是"Loop 只按工具名找能力、不绑定具体工具类"的收益。</p>
 *
 * <p>围栏根目录取自容器的 {@link SandboxConfig#workdirAbsolute()}（= {@code llm.tools.workdir}），
 * 与内置工具共用同一道边界，因此 {@code ApplyPatchParser} 自己的路径围栏校验也落在同一处。</p>
 *
 * <p><b>注意真正拦住写操作的是容器里的 {@code ToolSandbox}，不是这里的 {@code SandboxConfig}</b>：
 * 本类持有的配置只用来算围栏根目录；{@code llm.tools.allow-write} 才是"补丁能不能落盘"的闸门
 * （它由 {@code LlmAgentFactory} 把容器里的 {@code ToolSandbox} 装进每个 Agent）。
 * 本模块因此把 {@code allow-write} 打开，见 {@code application.yml} 的注释。</p>
 */
public class CodexPatchTools {

    private final SandboxConfig sandboxConfig;

    public CodexPatchTools(SandboxConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
    }

    /** 工具描述里带上完整格式说明，让"模型该怎么写补丁"这件事只维护一份。 */
    @LlmTool(name = "apply_patch",
            description = "应用一段 Codex 风格的补丁文本（*** Begin Patch … *** End Patch）。"
                    + "只改工作目录下的文件，越界路径整份补丁都会被拒绝。"
                    + "格式说明见本工具的参数描述。")
    public String applyPatch(
            @LlmToolParam(value = "patch",
                    description = "完整的补丁文本，必须包含 *** Begin Patch / *** End Patch 段落标记；"
                            + "段落标记之外可以有散文与代码围栏，会被忽略")
            String patch) {
        if (patch == null || patch.isBlank()) {
            return "补丁内容为空：请提供 *** Begin Patch ... *** End Patch 之间的文本。";
        }
        Path root = sandboxConfig.workdirAbsolute();
        ensureFenceExists(root);
        ApplyPatchParser.Result result = ApplyPatchParser.apply(patch, root, false);
        return result.toText() + "（围栏根目录：" + root + "）";
    }

    /**
     * 保证围栏根目录存在。
     *
     * <p><b>为什么必须有这一步（实测踩到过）：</b>{@code ApplyPatchParser.resolveInside} 在判断
     * "路径是否落在围栏内"时，会沿着目标路径向上找<b>第一个真实存在的祖先目录</b>，
     * 再用它的真实路径做符号链接校验。围栏目录本身不存在时，它一路找到盘符都找不到，
     * 于是<b>把所有相对路径都判成越界</b>，报的是
     * "xxx 不在工作目录 […] 之下，已拒绝该路径" —— 一条与真实原因（目录没建）
     * 毫无字面关系的错误，非常难反推。</p>
     *
     * <p>本模块的 {@code llm.tools.workdir} 指向 {@code target/sandbox}，
     * 而 {@code target} 会被 {@code mvn clean} 删掉、也不会被提交 ——
     * 也就是说"围栏目录不存在"是常态而不是异常。工具自己建一次，
     * 就用不着要求调用方先手工 mkdir（那种约定一定会被忘掉）。</p>
     */
    private static void ensureFenceExists(Path root) {
        try {
            java.nio.file.Files.createDirectories(root);
        } catch (java.io.IOException e) {
            // 建不出来不在这里报错：让 ApplyPatchParser 用它自己的诊断去说明结果，
            // 两条错误信息叠在一起只会让人更难读。
        }
    }

    /** 补丁格式说明，供演示直接打印。 */
    public static String describeFormat() {
        return ApplyPatchParser.describeFormat();
    }
}
