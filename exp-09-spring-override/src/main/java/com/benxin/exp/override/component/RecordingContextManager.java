package com.benxin.exp.override.component;

import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 切面 7 的自定义实现：{@code ContextManager}。
 *
 * <p>它演示的是"切面之间可以互相替换、而且换一个不影响另一个"：
 * 本类<b>通过构造器拿到</b> {@link ContextCompactor} 与 {@link TokenEstimator}，
 * 所以换成 {@link RecordingNoopCompactor} 与 {@link DoublingTokenEstimator} 之后，
 * 我这份自己写的窗口策略也会自动用上它们 —— 依赖注入把"可插拔"从一个点变成了一张网。</p>
 *
 * <p>策略刻意跟内置的 {@code DefaultContextManager} 不一样：<b>永不压缩</b>，
 * 只把每轮"准备时看到了几条历史"记下来。测试靠这个计数证明换的是我这份，
 * 而不是"看起来差不多的另一份"。</p>
 */
public class RecordingContextManager implements ContextManager {

    private final ContextCompactor compactor;
    private final TokenEstimator estimator;
    private final List<String> prepares = new ArrayList<>();
    private final List<String> afterSteps = new ArrayList<>();

    public RecordingContextManager(ContextCompactor compactor, TokenEstimator estimator) {
        this.compactor = compactor;
        this.estimator = estimator;
    }

    @Override
    public List<ChatMessage> prepare(String systemPrompt, List<ChatMessage> history, LlmModel model) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (history != null) {
            messages.addAll(history);
        }
        prepares.add("prepare(历史 " + (history == null ? 0 : history.size())
                + " 条, 系统提示词 " + (systemPrompt == null ? 0 : systemPrompt.length()) + " 字, 估算 "
                + estimator.estimate(messages) + " token)");
        return messages;
    }

    @Override
    public void afterStep(List<ChatMessage> history, LlmModel model) {
        afterSteps.add("afterStep(" + (history == null ? 0 : history.size()) + " 条)");
        // 这里显式不调 compactor：本策略的语义就是"永不压缩"。
        // 之所以还持有它，是为了演示"切面之间的依赖是走构造器注入的"。
        if (compactor == null) {
            throw new IllegalStateException("compactor 不该为 null");
        }
    }

    /** 当前持有的压缩策略与估算器（示例里用来打印"这张网"的实际接线）。 */
    public ContextCompactor compactor() {
        return compactor;
    }

    public TokenEstimator estimator() {
        return estimator;
    }

    public List<String> prepares() {
        return List.copyOf(prepares);
    }

    public List<String> afterSteps() {
        return List.copyOf(afterSteps);
    }

    public void reset() {
        prepares.clear();
        afterSteps.clear();
    }

    @Override
    public String toString() {
        return "RecordingContextManager(compactor=" + compactor + ", estimator=" + estimator + ")";
    }
}
