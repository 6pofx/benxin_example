package com.benxin.exp.override.component;

import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;

import java.util.List;

/**
 * 切面 6 的自定义实现：{@code ContextCompactor}。
 *
 * <p>内置有两个：{@code SummarizingCompactor}（调模型做摘要，默认）与
 * {@code SlidingWindowCompactor}（不调模型，直接丢旧消息）。本类取第三种策略 ——
 * <b>什么都不做，只记账</b>：它是"我想先观测压缩到底被触发了几次，再决定用哪种策略"
 * 这个真实诉求的最小实现，也顺便证明替换压缩策略不需要碰任何其它切面。</p>
 */
public class RecordingNoopCompactor implements ContextCompactor {

    private final java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent) {
        calls.add("compact(history=" + (history == null ? 0 : history.size())
                + ", model=" + (model == null ? "null" : model.name())
                + ", keepRecent=" + keepRecent + ")");
        // 原样返回：不压缩。示例要的是"看得见压缩被触发"，而不是真的把上下文丢掉。
        return history == null ? List.of() : List.copyOf(history);
    }

    /** 依次记录每次压缩请求的参数。 */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public int count() {
        return calls.size();
    }

    public void reset() {
        calls.clear();
    }

    @Override
    public String toString() {
        return "RecordingNoopCompactor(只记账不压缩)";
    }
}
