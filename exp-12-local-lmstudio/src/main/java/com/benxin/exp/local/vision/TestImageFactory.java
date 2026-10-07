package com.benxin.exp.local.vision;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * 运行时生成一张"内容已知"的测试图，用来验证**真实视觉能力**。
 *
 * <p>为什么要在代码里画图，而不是往仓库里塞一个 png？</p>
 * <ol>
 *   <li>断言可以写得非常硬：我们<b>确切知道</b>图里是"白底 + 红圆 + 黑字 42"，
 *       所以"模型答对了"是可判定的，而不是靠人眼看；</li>
 *   <li>仓库里不用放二进制资源，示例仍然可以整份拷走；</li>
 *   <li>换模型/换端点时，这张图就是一把固定的尺子。</li>
 * </ol>
 *
 * <p>注意 AWT 的 headless 设置必须在 AWT 初始化<b>之前</b>生效，因此在静态块里设置。</p>
 */
public final class TestImageFactory {

    static {
        // 无显示环境（CI）下必须显式 headless，否则 AWT 初始化会抛 HeadlessException
        System.setProperty("java.awt.headless", "true");
    }

    /** 图里的数字：断言时用得到。 */
    public static final String NUMBER = "42";

    /** 图里的颜色：断言时用得到。 */
    public static final String COLOR = "红";

    /** 画布尺寸。 */
    public static final int WIDTH = 640;
    public static final int HEIGHT = 320;

    private TestImageFactory() {
    }

    /**
     * 画一张 {@value #WIDTH}×{@value #HEIGHT} 的 PNG：白底 + 红色实心圆 + 黑色粗体数字。
     *
     * <p>尺寸与字号是<b>实测调出来的</b>：320×160 / 64px 时，本机 8B 视觉模型会偶发把
     * "42" 读成 "12"（见 README 的"实测观察"）；放大到 640×320 / 150px 之后连测多次全部读对。
     * 这不是插件的性质，而是"小模型的分辨率下限"—— 把它写进示例，读者换模型时就知道该先怀疑什么。</p>
     */
    public static byte[] pngBytes() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(Color.RED);
            g.fillOval((int) (WIDTH * 0.06), (int) (HEIGHT * 0.12), (int) (HEIGHT * 0.75), (int) (HEIGHT * 0.75));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(4f));
            g.drawRect(2, 2, WIDTH - 5, HEIGHT - 5);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 150));
            g.drawString(NUMBER, (int) (WIDTH * 0.55), (int) (HEIGHT * 0.72));
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("生成测试图失败", e);
        }
    }

    /** 同一个模型支持的两种图片入参形态之一：base64 内联（另一种是公网 URL）。 */
    public static String base64() {
        return Base64.getEncoder().encodeToString(pngBytes());
    }

    /** 建议的提问方式：把"看颜色"和"读数字"分开问，小模型的遵从度明显更好。 */
    public static String question() {
        return "这张图里的圆形是什么颜色？图中的数字是多少？只回答「颜色, 数字」。";
    }

    /** 给演示打印用的一句话。 */
    public static String describe() {
        return WIDTH + "×" + HEIGHT + " PNG，白底 + 红圆 + 黑色粗体数字 " + NUMBER
                + "（" + pngBytes().length + " 字节）";
    }
}
