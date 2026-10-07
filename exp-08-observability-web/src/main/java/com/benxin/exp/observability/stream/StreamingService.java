package com.benxin.exp.observability.stream;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.StreamCollector;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式三件套的演示服务：直接从 {@link LlmModel} 出发，把
 * {@link LlmStreamHandler} / {@link StreamCollector} / {@code ofText} 一次讲清。
 *
 * <p><b>为什么要绕过 Agent 直接调模型：</b>在 Agent 路径上这三件东西是"被框架用掉"的 ——
 * 你只能看到结果，看不到它们各自的边界。直接 {@code model.stream(request, collector)}
 * 才能把"7 个回调何时触发"与"collector 聚合出什么"分开观察。</p>
 *
 * <p>三者的定位：</p>
 * <ul>
 *   <li>{@link LlmStreamHandler}：<b>订阅方视角</b>的 7 个回调，只关心自己覆写的那几个；</li>
 *   <li>{@link StreamCollector}：<b>聚合器</b>，把增量拼回一个完整 {@link ChatResponse}，
 *       同时可以把事件继续透传给下游 handler（构造器接收 downstream）；</li>
 *   <li>{@code LlmStreamHandler.ofText(consumer)}：只要文本增量的便捷工厂，
 *       适合"把增量直接打到控制台/前端"这类最简场景。</li>
 * </ul>
 */
public class StreamingService {

    /** 一次 {@code stream()} 之后 collector 聚合出的全部可观察状态。 */
    public record CollectorSummary(
            ChatResponse response,
            String text,
            String thinking,
            List<ToolUsePart> toolUses,
            Usage usage,
            Throwable error,
            boolean started,
            boolean hasContent) {

        public String toolNames() {
            return toolUses.stream().map(ToolUsePart::name).toList().toString();
        }
    }

    /**
     * 7 个回调的计数与留档：{@code onStart} 的次数是断言重点 ——
     * README 的实测口径是"一次 {@code stream()} 调用内恰好一次"（重试不会重新初始化订阅方）。
     */
    public static class SevenHookRecorder implements LlmStreamHandler {

        private int starts;
        private int completions;
        private int usages;
        private int errors;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final List<ToolUsePart> toolCalls = new java.util.ArrayList<>();

        @Override
        public void onStart() {
            starts++;
        }

        @Override
        public void onTextDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onThinkingDelta(String delta) {
            thinking.append(delta);
        }

        @Override
        public void onToolCall(ToolUsePart toolUse) {
            toolCalls.add(toolUse);
        }

        @Override
        public void onUsage(Usage usage) {
            usages++;
        }

        @Override
        public void onComplete(ChatResponse response) {
            completions++;
        }

        @Override
        public void onError(Throwable error) {
            errors++;
        }

        public int starts() {
            return starts;
        }

        public int completions() {
            return completions;
        }

        public int usages() {
            return usages;
        }

        public int errors() {
            return errors;
        }

        public String text() {
            return text.toString();
        }

        public String thinking() {
            return thinking.toString();
        }

        public List<ToolUsePart> toolCalls() {
            return List.copyOf(toolCalls);
        }
    }

    private static final Pattern FORCED_TOOL =
            Pattern.compile("#tool:([A-Za-z0-9_\\-]+)\\s*(\\{.*?})?", Pattern.DOTALL);

    private final LlmModel model;

    public StreamingService(LlmModel model) {
        this.model = model;
    }

    /** ① 直接观察 7 个回调。 */
    public SevenHookRecorder observeHooks(ChatRequest request) {
        SevenHookRecorder recorder = new SevenHookRecorder();
        model.stream(request, recorder);
        return recorder;
    }

    /** ② 用 StreamCollector 聚合，且不带下游。 */
    public CollectorSummary collect(ChatRequest request) {
        return collect(request, null);
    }

    /**
     * ②' 用 StreamCollector 聚合，同时把事件透传给下游 handler ——
     * 这正是 Agent Loop 内部的做法（{@code DefaultLoopContext.callModel}）。
     */
    public CollectorSummary collect(ChatRequest request, LlmStreamHandler downstream) {
        StreamCollector collector = new StreamCollector(downstream);
        model.stream(request, collector);
        return new CollectorSummary(collector.response(), collector.text(), collector.thinkingText(),
                collector.toolUses(), collector.usage(), collector.error(),
                collector.isStarted(), collector.hasContent());
    }

    /** ③ ofText 便捷工厂：只要文本增量。 */
    public String collectTextOnly(ChatRequest request, StringBuilder sink) {
        model.stream(request, LlmStreamHandler.ofText(sink::append));
        return sink.toString();
    }

    /**
     * 造一个"用户显式点名工具"的请求。
     *
     * <p>用 {@code #tool:<名字> {JSON}} 而不是靠关键字猜：流式演示要断言
     * {@code onToolCall} 与 collector 的 {@code toolUses()}，必须让模型确定性地请求工具。</p>
     */
    public static ChatRequest toolRequest(LlmModel model, String message, String toolName, String argumentsJson) {
        return baseRequest(model, "#tool:" + toolName + " " + argumentsJson
                + "\n（这是一次显式的工具调用请求：" + message + "）");
    }

    /** 造一个普通文本请求。 */
    public static ChatRequest textRequest(LlmModel model, String message) {
        return baseRequest(model, message);
    }

    private static ChatRequest baseRequest(LlmModel model, String message) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.system("你是本心示例 08 的流式演示对象。"),
                        ChatMessage.user(message)))
                .tools(toolSpecs(model))
                .build();
    }

    /**
     * 取模型的工具声明。
     *
     * <p>这里没有走 {@code ToolRegistry}，而是把 {@code CommonTools} 上的
     * {@code @LlmTool} 方法手动转成 {@link ToolSpec} —— 只为了让"流式三件套"这个演示
     * 不依赖任何 Agent 装配。真实项目里请用 {@code LlmToolCatalog.global()}。</p>
     */
    private static List<ToolSpec> toolSpecs(LlmModel model) {
        return com.benxin.llm.core.tool.ToolScanner.scan(new com.benxin.exp.common.tool.CommonTools())
                .stream().map(com.benxin.llm.core.tool.ToolCallback::spec).toList();
    }

    /** 从增量文本里抽出被请求的工具名，用于断言 {@code onToolCall} 的时机。 */
    public static String forcedToolName(String text) {
        Matcher matcher = FORCED_TOOL.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 便捷：模型不可用时给出统一的异常文案（演示里用它打印"预期中的失败"）。 */
    public static String describe(Throwable error) {
        return error == null ? "-" : error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    /** 供演示打印：把 usage 变成一行。 */
    public static String usageLine(Usage usage) {
        return usage == null ? "-" : usage.inputTokens() + " / " + usage.outputTokens()
                + " / 合计 " + usage.totalTokens();
    }

    /** 便于把 ModelException 与其它失败区分开。 */
    public static boolean isModelFailure(Throwable error) {
        return error instanceof ModelException;
    }
}
