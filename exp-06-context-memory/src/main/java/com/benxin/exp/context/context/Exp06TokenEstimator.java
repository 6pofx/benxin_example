package com.benxin.exp.context.context;

import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.util.TokenEstimator;

import java.util.List;

/**
 * 自定义分词估算器：<b>中文 1 字 1 token、其余 2 字符 1 token</b>。
 *
 * <p>它与插件的默认实现（{@code TokenEstimator.DEFAULT}：中文 1 字 1 token、其余 4 字符 1 token）
 * 在中文上一模一样、在英文上差一倍 —— 这个差异是<b>故意做出来的</b>：
 * 只有取值真的不同，"自定义 bean 被 {@code DefaultContextManager} 采用"才可能被断言住
 * （同一段英文历史，默认估算没到阈值、本估算到了，于是压缩是否发生出现分叉）。</p>
 *
 * <p>接真实 tokenizer 时把 {@link #estimate(String)} 换成模型自带的分词即可，
 * 接口只有两个默认方法，替换成本极低。</p>
 */
public class Exp06TokenEstimator implements TokenEstimator {

    /** 每条消息的固定开销（角色、分隔符等）；默认实现是 {@code 4 + 8 * 内容块数}。 */
    public static final int MESSAGE_OVERHEAD = 12;

    /** 每个内容块的额外开销，与默认实现保持同一口径，避免差异来源过多。 */
    public static final int PART_OVERHEAD = 8;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        // 默认实现是 max(1, other / 4)；这里按 2 字符 1 token，因此更"贵"
        return cjk + (other + 1) / 2;
    }

    @Override
    public int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += MESSAGE_OVERHEAD + estimate(message.text());
            total += message.parts().size() * PART_OVERHEAD;
        }
        return total;
    }

    /** 与默认实现同一套 CJK 判定区间（含假名、谚文、兼容汉字区）。 */
    private static boolean isCjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7AF || c >= 0xF900 && c <= 0xFAFF;
    }

    public String describe() {
        return "Exp06TokenEstimator（中文 1 字 1 token、其余 2 字符 1 token、每条消息固定 "
                + MESSAGE_OVERHEAD + " token）";
    }
}
