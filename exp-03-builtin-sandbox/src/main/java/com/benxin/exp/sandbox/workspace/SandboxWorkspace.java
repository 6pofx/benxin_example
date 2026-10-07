package com.benxin.exp.sandbox.workspace;

import com.benxin.llm.core.sandbox.CompositeSandbox;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.spring.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 演示用的沙箱工作区：把 {@code target/sandbox} 这棵树建出来。
 *
 * <p>为什么要在代码里建而不是往 {@code src/main/resources} 里塞文件：沙箱围栏是
 * {@code llm.tools.workdir=target/sandbox}，而 {@code target} 是构建产物目录、随时会被
 * {@code mvn clean} 抹掉。用代码建目录有两个好处：</p>
 * <ol>
 *   <li>演示可以反复跑，{@code mvn clean} 之后依然能自解释地重建；</li>
 *   <li>"哪些文件是可读的、哪些是敏感路径"这件事变成了可读的代码，
 *       而不是一堆藏在资源目录里、看不出用途的样例文件。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：本类只写 {@link #root()} 之内的路径；这里出现的
 * {@code .env / id_rsa / *.pem / aws/credentials.json / .git/config} 全是<b>故意造出来的靶子</b>，
 * 内容是无意义的占位串，用来验证 {@code PathSandbox} 的敏感路径规则真的会拦住它们。</p>
 */
@Component
public class SandboxWorkspace {

    private static final Logger log = LoggerFactory.getLogger(SandboxWorkspace.class);

    /** 生成一个"两处相同片段"的文件，用来演示 edit 的"多处匹配即拒绝"。 */
    private static final String NOTES = """
            TODO 补一个越界路径的用例
            TODO 补一个命令黑名单的用例
            已确认：沙箱围栏按真实路径比较
            """;

    private final Path root;

    public SandboxWorkspace(LlmProperties properties, ToolSandbox sandbox) {
        this.root = resolveRoot(properties, sandbox);
        ensure();
    }

    /**
     * 围栏根目录取自<b>容器里那个真正生效的 {@link ToolSandbox}</b>，而不是再抄一遍 yml。
     *
     * <p>这样做是有意的：示例要证明"运行期真的生效的围栏就是这里"，
     * 抄一遍配置只能证明"我读到了配置"。顺带示范 {@link CompositeSandbox#members()}
     * 这个自检入口 —— 拿到组合沙箱之后能一眼看出它由谁组成。</p>
     */
    private static Path resolveRoot(LlmProperties properties, ToolSandbox sandbox) {
        PathSandbox pathSandbox = pathSandboxOf(sandbox);
        if (pathSandbox != null) {
            return pathSandbox.config().workdirAbsolute();
        }
        String workdir = properties.getTools().getWorkdir();
        return workdir == null || workdir.isBlank()
                ? Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : Paths.get(workdir).toAbsolutePath().normalize();
    }

    /** 从（可能是组合的）沙箱里取出 {@link PathSandbox} 成员。 */
    public static PathSandbox pathSandboxOf(ToolSandbox sandbox) {
        if (sandbox instanceof PathSandbox pathSandbox) {
            return pathSandbox;
        }
        if (sandbox instanceof CompositeSandbox composite) {
            for (ToolSandbox member : composite.members()) {
                PathSandbox found = pathSandboxOf(member);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 围栏根目录（绝对、规范化）。 */
    public Path root() {
        return root;
    }

    /** 生成一条围栏内的相对路径（统一用 {@code /} 分隔，便于写进 {@code #tool:} 参数）。 */
    public String relative(String child) {
        return root.relativize(root.resolve(child)).toString().replace('\\', '/');
    }

    public Path resolve(String child) {
        return root.resolve(child);
    }

    /** 真实存在的样例文件（相对围栏的路径，已排序，便于断言与打印）。 */
    public List<String> sampleFiles() {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 幂等重建整个工作区（只写样例文件，不删任何东西）。 */
    public void ensure() {
        write("a.txt", """
                alpha line one
                benxin sandbox baseline
                BENXIN uppercase marker
                delta line four
                """);
        write("docs/guide.txt", """
                本心内置工具在真正落地之前要过五道关卡：
                拦截器 → 沙箱 → 审批 → 执行 → 截断。
                benxin 的围栏默认只读，写与执行都要显式打开。
                """);
        write("docs/notes.txt", NOTES);
        write("data/config.txt", """
                max-output-chars=20000
                exec-timeout=10s
                """);
        // ---- 以下全是"敏感路径靶子"：内容无意义，只用来验证默认禁止规则 ----
        write("certs/server.pem", "-----BEGIN CERTIFICATE-----\n（占位：不是真证书）\n-----END CERTIFICATE-----\n");
        write("keys/id_rsa", "（占位：不是真私钥）\n");
        write("aws/credentials.json", "{\"aws_access_key_id\":\"（占位）\"}\n");
        write("repo/.git/config", "[core]\n\trepositoryformatversion = 0\n");
        write(".env", "DEMO_API_KEY=（占位）\n");
        log.debug("[exp-03] 沙箱工作区就绪：{}", root);
    }

    /**
     * 清掉"被工具写出来"的产物目录（{@code generated/} 与 {@code tests/}）。
     *
     * <p>为什么需要它：{@code write} 演示会在 {@code generated/} 下落文件，而这些文件会改变
     * {@code glob}/{@code grep} 的匹配数量。演示与测试都要求"同一份输入给出同一份输出"，
     * 所以每次开跑之前先把上一轮的产物清掉 —— 样例文件（含敏感路径靶子）不在清理范围内。</p>
     */
    public void resetOutputs() {
        deleteTree(root.resolve("generated"));
        deleteTree(root.resolve("tests"));
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String relative, String content) {
        Path target = root.resolve(relative);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("初始化沙箱样例文件失败: " + target, e);
        }
    }

    /** 便于测试与演示直接拿到一份"和容器一致的"配置快照。 */
    public SandboxConfig configOf(ToolSandbox sandbox) {
        PathSandbox pathSandbox = pathSandboxOf(sandbox);
        return pathSandbox == null ? null : pathSandbox.config();
    }
}
