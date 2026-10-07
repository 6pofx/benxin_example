package com.benxin.exp.context.demo;

/**
 * 演示/测试共用的"上下文填充文本"。
 *
 * <p>压缩与裁剪只有在历史有<b>确定的体积</b>时才能被断言，因此这里提供两种可复现的长文本：</p>
 * <ul>
 *   <li>{@link #ascii(int, String)} —— 纯 ASCII。默认分词估算按 4 字符 1 token、本示例的
 *       自定义估算按 2 字符 1 token，于是<b>同一段历史在两种估算下会跨过/跨不过压缩阈值</b>，
 *       这正是"自定义 TokenEstimator 真的被采用"的判据。</li>
 *   <li>{@link #chinese(int, String)} —— 纯中文。中文字符在两种估算下都是 1 字 1 token，
 *       用它把 Loop 层的压缩（按 {@code TokenEstimator.DEFAULT} 估算）撑过触发线最省事。</li>
 * </ul>
 *
 * <p>两种文本都刻意不含 {@code json} / {@code #tool:} / 内置工具关键词（天气、时间、计算、搜索），
 * 避免脚本化模型的关键字规则被"填充文本"意外命中。</p>
 */
public final class DemoText {

    private DemoText() {
    }

    /** 生成恰好 {@code chars} 个字符的确定性 ASCII 文本。 */
    public static String ascii(int chars, String seed) {
        String unit = "context budget probe " + seed
                + " the quick brown fox jumps over the lazy dog while the window fills up. ";
        return repeatTo(unit, chars);
    }

    /** 生成恰好 {@code chars} 个字符的确定性中文文本。 */
    public static String chinese(int chars, String topic) {
        String unit = "本心上下文切面示例：" + topic
                + "。这一段文字用来把会话历史撑大，以便观察阈值判定、压缩与窗口裁剪的实际行为；"
                + "内容本身没有语义，只是可复现的体量。";
        return repeatTo(unit, chars);
    }

    private static String repeatTo(String unit, int chars) {
        int size = Math.max(1, chars);
        StringBuilder sb = new StringBuilder(size + unit.length());
        while (sb.length() < size) {
            sb.append(unit);
        }
        return sb.substring(0, size);
    }
}
