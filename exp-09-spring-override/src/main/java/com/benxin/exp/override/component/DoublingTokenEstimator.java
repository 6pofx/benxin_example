package com.benxin.exp.override.component;

import com.benxin.llm.core.util.TokenEstimator;

/**
 * 切面 5 的自定义实现：{@code TokenEstimator}。
 *
 * <p>内置实现在 {@code TokenEstimator.DEFAULT} 里：中日韩 1 字 1 token、其余约 4 字符 1 token，
 * 刻意不依赖分词库。真实项目接 tiktoken / 厂商 tokenizer 时，就换成本类的样子。</p>
 *
 * <p>本类是一个<b>可数</b>的估算器：固定返回 {@link #PER_CHAR} × 字符数，
 * 于是"这个估算器到底有没有被用上"变成了一个可以算出来的数，
 * 而不用去猜上下文压缩的阈值有没有触发。</p>
 */
public class DoublingTokenEstimator implements TokenEstimator {

    /** 每个字符算几个 token —— 一个一眼能算的数。 */
    public static final int PER_CHAR = 2;

    private int invocations;

    @Override
    public int estimate(String text) {
        invocations++;
        return text == null ? 0 : text.length() * PER_CHAR;
    }

    /** 被调用过多少次（含 {@code estimate(List)} 默认实现内部的调用）。 */
    public int invocations() {
        return invocations;
    }

    public void reset() {
        invocations = 0;
    }

    @Override
    public String toString() {
        return "DoublingTokenEstimator(" + PER_CHAR + " token/字符)";
    }
}
