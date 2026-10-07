package com.benxin.exp.context.tool;

import com.benxin.exp.context.demo.DemoText;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个"返回很长内容"的工具，专门用来把上下文撑大。
 *
 * <p>Loop 层的上下文压缩（{@code ClaudeCodeLoop} 把较早的工具结果改写成占位摘要）
 * 只在历史真的超过窗口比例时才发生，而工具结果通常是历史里体积最大的一块。
 * 所以演示里需要一个<b>体积可控</b>的工具：这里固定返回 1800 个中文字符
 * （默认估算 1 字 1 token），十几次工具调用就足以越过 {@code 0.7 × 窗口} 的触发线。</p>
 */
public class LongDocTools {

    /** 固定返回的字符数：既大于压缩保留头部（600 字符），又够把历史撑过阈值。 */
    public static final int DOC_CHARS = 1800;

    private final AtomicInteger calls = new AtomicInteger();

    @LlmTool(name = "read_document", description = "读取一篇内部文档的全文；返回内容较长，请按需调用")
    public String readDocument(
            @LlmToolParam(value = "topic", description = "文档主题，例如 memory / context",
                    required = false, defaultValue = "memory") String topic) {
        calls.incrementAndGet();
        return DemoText.chinese(DOC_CHARS, topic == null ? "memory" : topic);
    }

    /** 被调用次数：示例用它证明"工具真的执行了"。 */
    public int callCount() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }
}
