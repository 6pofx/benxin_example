package com.benxin.exp.real.config;

/**
 * API Key 的取值口径：三级兜底，取不到就"不联网"，绝不抛异常。
 *
 * <p>为什么需要它？因为"key 在哪里"这件事在不同启动方式下并不一样：</p>
 * <ul>
 *   <li>本机把 key 放在 Windows <b>用户级环境变量</b> {@code DEEPSEEK_API_KEY} 里，
 *       但子进程不一定继承得到它；</li>
 *   <li>命令行常用 {@code -Ddeepseek.api-key=...} 临时传；</li>
 *   <li>CI 里可能叫 {@code DEEPSEEK_KEY}。</li>
 * </ul>
 *
 * <p>顺序刻意写成 ① 环境变量 {@code DEEPSEEK_API_KEY} → ② JVM 属性 {@code deepseek.api-key}
 * → ③ 环境变量 {@code DEEPSEEK_KEY}：环境变量是"这台机器的标准答案"，
 * 但显式传进来的 JVM 属性应当能覆盖它（临时换 key 联调时最常用）。</p>
 *
 * <p><b>本类永远不会把 key 写进日志或文件</b>：对外只暴露"取到了没有"和"来源"，
 * 需要展示时用 {@link #describe()}。</p>
 */
public final class ApiKeyResolver {

    /** 主要来源：环境变量（本机 Windows 用户级变量）。 */
    public static final String ENV_PRIMARY = "DEEPSEEK_API_KEY";

    /** 次要来源：JVM 属性 {@code -Ddeepseek.api-key=...}。 */
    public static final String PROPERTY = "deepseek.api-key";

    /** 兜底来源：环境变量 {@code DEEPSEEK_KEY}。 */
    public static final String ENV_BACKUP = "DEEPSEEK_KEY";

    private ApiKeyResolver() {
    }

    /** 按三级兜底取 key；取不到返回空串（调用方据此跳过真实调用）。 */
    public static String resolve() {
        String value = System.getenv(ENV_PRIMARY);
        if (present(value)) {
            return value.trim();
        }
        value = System.getProperty(PROPERTY);
        if (present(value)) {
            return value.trim();
        }
        value = System.getenv(ENV_BACKUP);
        if (present(value)) {
            return value.trim();
        }
        return "";
    }

    /** key 的来源描述，用于打印（不含 key 本身）。 */
    public static String source() {
        if (present(System.getenv(ENV_PRIMARY))) {
            return "环境变量 " + ENV_PRIMARY;
        }
        if (present(System.getProperty(PROPERTY))) {
            return "JVM 属性 -D" + PROPERTY;
        }
        if (present(System.getenv(ENV_BACKUP))) {
            return "环境变量 " + ENV_BACKUP;
        }
        return "（未取到）";
    }

    /** 是否取到了 key。 */
    public static boolean available() {
        return !resolve().isEmpty();
    }

    /**
     * 把"Java 侧看到的 key"桥接成系统属性 {@code deepseek.api-key}。
     *
     * <p>这一步是必要的，因为 key 有两个消费方，它们必须看到同一个值：</p>
     * <ol>
     *   <li>yml 里的 {@code llm.models.deepseek.api-key: ${DEEPSEEK_API_KEY:${deepseek.api-key:}}}
     *       —— 由 Spring 的 Environment 解析；</li>
     *   <li>本类 —— 由 {@code System.getenv} / {@code System.getProperty} 解析。</li>
     * </ol>
     *
     * <p>如果本进程的环境变量里没有 {@code DEEPSEEK_API_KEY}（父进程没继承），
     * Environment 只能看到第 ② 级；而用户级环境变量恰好只有 {@code System.getenv}
     * 在"当前进程能看到"时才有值。桥接一次，两边就同源了，也不会覆盖用户显式传入的属性。</p>
     */
    public static void bridgeToSystemProperty() {
        if (present(System.getProperty(PROPERTY))) {
            return;
        }
        String resolved = resolve();
        if (!resolved.isEmpty()) {
            System.setProperty(PROPERTY, resolved);
        }
    }

    /** 只描述"有没有、多长"，不打印内容。 */
    public static String describe() {
        String key = resolve();
        if (key.isEmpty()) {
            return "未取到（不联网）";
        }
        return "已取到（来源：" + source() + "，长度 " + key.length() + "，内容不打印）";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
