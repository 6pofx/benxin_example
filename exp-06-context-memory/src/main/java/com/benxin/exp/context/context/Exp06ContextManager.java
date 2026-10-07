package com.benxin.exp.context.context;

import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义上下文管理器：接管"系统提示词放哪、历史怎么裁剪"，再把阈值判定与压缩委托给
 * {@link DefaultContextManager}。
 *
 * <p>{@link ContextManager} 只有两个方法，其中 {@code afterStep} 还是默认方法 ——
 * 这正是它容易被低估的原因：它是<b>唯一</b>决定"这一次真正发给模型的消息序列长什么样"的地方，
 * 记忆、成本、超窗、协议约束（工具调用必须成对出现）最终都在这里收口。</p>
 *
 * <p>本实现做了三件默认实现没做的事，每件都可被 {@code RecordingModel} 观察到：</p>
 * <ol>
 *   <li><b>系统提示词加来源标记</b>：真实项目里这一段最常见的是"提示词版本号 / 灰度分组标签"，
 *       用来在日志与评测里区分"这句话到底是哪一版提示词说的"。
 *       示例把标记打进系统提示词，于是"最终下发的系统提示词确实经过了自定义 ContextManager"
 *       变成一条可断言的字符串。</li>
 *   <li><b>历史按窗口裁剪</b>：只把最近 {@code historyWindow} 条历史交给模型 ——
 *       记忆本身不删（{@code MemoryStore} 里仍然是完整历史），
 *       这是"上下文与记忆是两件事"的最直观体现。</li>
 *   <li><b>裁剪时保护工具调用配对</b>：窗口边界如果落在 {@code tool_result} 上，
 *       会向前补齐到它对应的 {@code assistant(tool_use)}。Anthropic 一类协议对
 *       "孤立的 tool_result" 是直接报错的，按条数硬切会把一个协议错误引进生产。</li>
 * </ol>
 *
 * <p>阈值判定与压缩本身没有重写：{@link #delegate} 用构造参数
 * {@code (compactor, estimator, threshold, keepRecent, reservedOutputTokens)} 装配，
 * 于是"自定义管理器"与"默认管理器"的差别只在本类新增的三件事上，对照起来更干净。</p>
 */
public class Exp06ContextManager implements ContextManager {

    /** 系统提示词来源标记：证明这一段是自定义 ContextManager 组装的。 */
    public static final String PROVENANCE = "【系统提示词来源：Exp06ContextManager v1（自定义上下文管理器注入）】";

    private final DefaultContextManager delegate;
    private final TokenEstimator estimator;
    private final ContextCompactor compactor;
    private final double threshold;
    private final int keepRecent;
    private final int reservedOutputTokens;
    private final int historyWindow;

    private final AtomicInteger prepareCount = new AtomicInteger();
    private final AtomicInteger afterStepCount = new AtomicInteger();
    private final AtomicInteger trimmedMessages = new AtomicInteger();
    private final AtomicInteger lastPreparedSize = new AtomicInteger();
    private final AtomicInteger lastEstimatedTokens = new AtomicInteger();

    /**
     * @param compactor             压缩器（本示例装配的是会调用模型的 {@code SummarizingCompactor}）
     * @param estimator             分词估算器（压缩阈值的判据）
     * @param threshold             占模型窗口的比例，超过它才考虑压缩
     * @param keepRecent            压缩时保留的最近消息条数
     * @param reservedOutputTokens  为模型输出预留的 token（不参与输入预算）
     * @param historyWindow         下发给模型的历史条数上限；{@code <= 0} 表示不裁剪
     */
    public Exp06ContextManager(ContextCompactor compactor, TokenEstimator estimator,
                               double threshold, int keepRecent, int reservedOutputTokens,
                               int historyWindow) {
        this.compactor = compactor;
        this.estimator = estimator == null ? TokenEstimator.DEFAULT : estimator;
        this.threshold = threshold <= 0 ? 0.8 : threshold;
        this.keepRecent = Math.max(1, keepRecent);
        // 阈值/压缩逻辑不重写：委托给默认实现，差别只在本类新增的三件事上
        this.delegate = new DefaultContextManager(compactor, estimator, this.threshold,
                this.keepRecent, reservedOutputTokens);
        this.reservedOutputTokens = Math.max(0, reservedOutputTokens);
        this.historyWindow = historyWindow;
    }

    @Override
    public List<ChatMessage> prepare(String systemPrompt, List<ChatMessage> history, LlmModel model) {
        prepareCount.incrementAndGet();

        // ① 系统提示词由本管理器"注入 + 标记"：放哪由 ContextManager 决定，而不是由 Loop 或模型决定
        String decorated = decorate(systemPrompt);

        // ② 历史裁剪：记忆里的历史不动，只有下发给模型的这一份被缩短
        List<ChatMessage> trimmed = trim(history);
        trimmedMessages.addAndGet((history == null ? 0 : history.size()) - trimmed.size());

        // ③ 阈值判定与压缩交给默认实现（它会把系统提示词放到最前面，并丢弃历史里的 SYSTEM 消息）
        List<ChatMessage> prepared = delegate.prepare(decorated, trimmed, model);
        lastPreparedSize.set(prepared.size());
        lastEstimatedTokens.set(estimator.estimate(prepared));
        return prepared;
    }

    /**
     * 默认方法：每一步模型调用结束后回调。默认实现是空方法，因此"用它做统计"是最省事的可观测手段 ——
     * 不需要监听器、不需要拦截器，就能知道这一步花了多少预算。
     */
    @Override
    public void afterStep(List<ChatMessage> history, LlmModel model) {
        afterStepCount.incrementAndGet();
        // 注意这里拿到的是"本步结束后的完整历史"（含刚追加的 assistant / tool 消息），
        // 而不是下发给模型的那份裁剪结果 —— 两者的差额正是"上下文 ≠ 记忆"。
        lastEstimatedTokens.set(estimator.estimate(history));
    }

    private String decorate(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return null;
        }
        return PROVENANCE + "\n" + systemPrompt;
    }

    /**
     * 按窗口裁剪历史；{@code historyWindow <= 0} 时原样返回。
     *
     * <p>裁剪只做"从尾部留 N 条"，但会向前补齐工具调用：如果留下窗口的第一条是
     * {@code tool} 消息，说明它对应的 {@code assistant(tool_use)} 被切掉了，
     * 于是把窗口往前扩，直到边界不再是一条孤立的工具结果。</p>
     */
    private List<ChatMessage> trim(List<ChatMessage> history) {
        if (historyWindow <= 0 || history == null || history.size() <= historyWindow) {
            return history == null ? List.of() : history;
        }
        int start = history.size() - historyWindow;
        while (start > 0 && history.get(start).role() == Role.TOOL) {
            start--;
        }
        return new ArrayList<>(history.subList(start, history.size()));
    }

    // ------------------------------------------------------------------
    // 观测入口（演示与测试用）
    // ------------------------------------------------------------------

    public int prepareCount() {
        return prepareCount.get();
    }

    public int afterStepCount() {
        return afterStepCount.get();
    }

    /** 累计被窗口裁掉的消息条数。 */
    public int trimmedMessages() {
        return trimmedMessages.get();
    }

    public int lastPreparedSize() {
        return lastPreparedSize.get();
    }

    /** 最近一次估算的 token 数（{@code afterStep} 之后是"完整历史"的口径）。 */
    public int lastEstimatedTokens() {
        return lastEstimatedTokens.get();
    }

    public int historyWindow() {
        return historyWindow;
    }

    public double threshold() {
        return threshold;
    }

    public int keepRecent() {
        return keepRecent;
    }

    public int reservedOutputTokens() {
        return reservedOutputTokens;
    }

    public ContextCompactor compactor() {
        return compactor;
    }

    public TokenEstimator estimator() {
        return estimator;
    }

    public void resetStats() {
        prepareCount.set(0);
        afterStepCount.set(0);
        trimmedMessages.set(0);
        lastPreparedSize.set(0);
        lastEstimatedTokens.set(0);
    }

    public String describe() {
        return "Exp06ContextManager(threshold=" + threshold + ", keepRecent=" + keepRecent
                + ", reservedOutputTokens=" + reservedOutputTokens
                + ", historyWindow=" + historyWindow + ")";
    }
}
