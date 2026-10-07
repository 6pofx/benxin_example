package com.benxin.exp.common.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 演示输出的小工具：让 12 个子项目的控制台输出长成一个样子。
 *
 * <p>刻意做成"失败不抛异常、只记账"：示例应用的职责是<b>展示</b>，
 * 某一条可选能力没打开时应该打印一句"跳过并说明原因"，而不是让整个演示崩在中间。
 * 真正需要强断言的场合请用测试（每个子项目都配了 {@code @SpringBootTest}）。</p>
 */
public final class Demo {

    private final String title;
    private final List<String> failures = new ArrayList<>();
    private int step;

    private Demo(String title) {
        this.title = title;
    }

    public static Demo begin(String title) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  " + title);
        System.out.println("=".repeat(78));
        return new Demo(title);
    }

    /** 打印演示环境信息（模型/Loop/Agent 清单之类）。 */
    public void banner(String format, Object... args) {
        System.out.println("  " + String.format(format, args));
    }

    public void section(String sectionTitle) {
        step++;
        System.out.println();
        System.out.println("── " + step + ". " + sectionTitle + " "
                + "─".repeat(Math.max(0, 66 - sectionTitle.length())));
    }

    public void info(String format, Object... args) {
        System.out.println("   " + String.format(format, args));
    }

    /** 期待中的关键结论，统一加 ✓ 前缀，方便肉眼扫。 */
    public void ok(String format, Object... args) {
        System.out.println("   ✓ " + String.format(format, args));
    }

    public void warn(String format, Object... args) {
        System.out.println("   ! " + String.format(format, args));
    }

    /** 跑一段可能失败的演示；失败只记账，不中断后续演示。 */
    public void guard(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException | AssertionError e) {
            failed(what, e);
        }
    }

    public <T> T guard(String what, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (RuntimeException | AssertionError e) {
            failed(what, e);
            return fallback;
        }
    }

    public void failed(String what, Throwable e) {
        String line = what + " → " + e.getClass().getSimpleName() + ": " + e.getMessage();
        failures.add(line);
        System.out.println("   ✗ 演示失败：" + line);
    }

    public void done() {
        System.out.println();
        System.out.println("-".repeat(78));
        if (failures.isEmpty()) {
            System.out.println("  演示结束：全部 " + step + " 项正常完成。");
        } else {
            System.out.println("  演示结束：" + step + " 项中有 " + failures.size() + " 项失败：");
            failures.forEach(f -> System.out.println("    - " + f));
        }
        System.out.println("-".repeat(78));
        System.out.println();
    }

    public int failures() {
        return failures.size();
    }

    public List<String> failureDetails() {
        return List.copyOf(failures);
    }
}
