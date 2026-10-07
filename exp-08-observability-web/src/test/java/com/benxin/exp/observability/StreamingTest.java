package com.benxin.exp.observability;

import com.benxin.exp.observability.agent.ChatAssistant;
import com.benxin.exp.observability.stream.StreamingService;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.StreamCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流式三件套：{@code LlmStreamHandler} 的 7 个回调、{@code StreamCollector} 的聚合口径、
 * 以及 {@code LlmStreamHandler.ofText(consumer)} 便捷工厂。
 *
 * <p>这一组刻意绕开 Agent 直接调模型：在 Agent 路径上这三件东西是"被框架用掉"的，
 * 你只能看到结果，看不到它们各自的边界。</p>
 */
@DisplayName("示例 08 · 流式三件套")
class StreamingTest extends ObservabilityTestSupport {

    @Autowired
    private ChatAssistant chat;

    @Autowired
    private ModelRegistry modelRegistry;

    /** 直接用被 RecordingModel 包起来的那个模型；它的 stream() 会按 chunkSize 分片。 */
    private StreamingService streaming() {
        return new StreamingService(mockLlmModel);
    }

    // ------------------------------------------------------------------
    // ① LlmStreamHandler 的 7 个回调
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7 个回调全部可达：onStart 恰好一次，onUsage/onComplete 各一次")
    void sevenHooksAllFire() {
        StreamingService.SevenHookRecorder hooks =
                streaming().observeHooks(StreamingService.textRequest(mockLlmModel, "自我介绍"));

        assertThat(hooks.starts()).as("onStart 在一次 stream() 调用内恰好一次").isEqualTo(1);
        assertThat(hooks.completions()).isEqualTo(1);
        assertThat(hooks.usages()).isEqualTo(1);
        assertThat(hooks.errors()).isZero();

        assertThat(hooks.text()).isNotBlank();
        // 脚本模型把文本按 12 字符分片，因此文本增量不止一次
        assertThat(hooks.text()).contains("离线模型");
        assertThat(hooks.thinking()).isNotBlank();   // thinking(true) 的模型才有思维链
        // 文本请求没有工具调用
        assertThat(hooks.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("onToolCall 只在工具参数拼装完整后回调一次（这里恰好一次 get_weather）")
    void toolCallHookFiresOnceWithCompleteArguments() {
        StreamingService.SevenHookRecorder hooks = streaming().observeHooks(
                StreamingService.toolRequest(mockLlmModel, "北京天气", "get_weather", "{\"city\":\"北京\"}"));

        assertThat(hooks.starts()).isEqualTo(1);
        assertThat(hooks.toolCalls()).hasSize(1);
        ToolUsePart toolUse = hooks.toolCalls().get(0);
        assertThat(toolUse.name()).isEqualTo("get_weather");
        assertThat(toolUse.argumentsJson()).contains("北京");
        // 工具调用轮没有正文
        assertThat(hooks.text()).isEmpty();
        assertThat(hooks.completions()).isEqualTo(1);
    }

    @Test
    @DisplayName("onStart 是列表化计数：一次 stream() 只有一个 onStart（重试语义的锚点）")
    void onStartIsExactlyOncePerStreamCall() {
        List<String> lifecycle = new ArrayList<>();
        LlmStreamHandler recorder = new LlmStreamHandler() {
            @Override
            public void onStart() {
                lifecycle.add("start");
            }

            @Override
            public void onComplete(ChatResponse response) {
                lifecycle.add("complete");
            }

            @Override
            public void onError(Throwable error) {
                lifecycle.add("error");
            }
        };

        mockLlmModel.stream(StreamingService.textRequest(mockLlmModel, "自我介绍"), recorder);
        mockLlmModel.stream(StreamingService.textRequest(mockLlmModel, "自我介绍"), recorder);

        assertThat(lifecycle).containsExactly("start", "complete", "start", "complete");
    }

    @Test
    @DisplayName("模型抛异常时 onError 触发，onComplete 不触发")
    void errorHookFiresOnModelFailure() {
        LlmModel failing = modelRegistry.get("failing");
        StreamingService.SevenHookRecorder hooks = new StreamingService.SevenHookRecorder();

        assertThatThrownBy(() -> failing.stream(
                StreamingService.textRequest(failing, "随便问点什么"), hooks))
                .isInstanceOf(ModelException.class);

        // FailingModel 刻意覆写了 stream() 为"直接抛"，因此连 onStart 都不会有 ——
        // 这与"模型连接失败时不该先宣告开始"的语义一致。
        assertThat(hooks.starts()).isZero();
    }

    // ------------------------------------------------------------------
    // ② StreamCollector
    // ------------------------------------------------------------------

    @Test
    @DisplayName("StreamCollector 聚合：response()/text()/thinkingText()/toolUses()/usage()/error()/isStarted()/hasContent()")
    void streamCollectorAggregatesEverything() {
        StreamingService.CollectorSummary summary = streaming().collect(
                StreamingService.textRequest(mockLlmModel, "自我介绍"));

        assertThat(summary.started()).isTrue();
        assertThat(summary.hasContent()).isTrue();
        assertThat(summary.error()).isNull();
        assertThat(summary.text()).isNotBlank();
        assertThat(summary.thinking()).isNotBlank();
        assertThat(summary.toolUses()).isEmpty();
        assertThat(summary.usage().totalTokens()).isPositive();

        ChatResponse response = summary.response();
        assertThat(response).isNotNull();
        assertThat(response.message()).isNotNull();
        // 模型回调了 onComplete，因此 response() 直接返回那个完整响应
        assertThat(response.text()).isEqualTo(summary.text());
    }

    @Test
    @DisplayName("聚合出的工具调用与用量可用于决策（hasContent 在有工具调用时也为 true）")
    void collectorReportsToolUses() {
        StreamingService.CollectorSummary summary = streaming().collect(
                StreamingService.toolRequest(mockLlmModel, "北京天气", "get_weather", "{\"city\":\"北京\"}"));

        assertThat(summary.hasContent()).isTrue();       // 有 toolUses 也算"有内容"
        assertThat(summary.toolUses()).hasSize(1);
        assertThat(summary.toolNames()).contains("get_weather");
        assertThat(summary.usage().inputTokens()).isPositive();
        assertThat(summary.text()).isEmpty();
    }

    @Test
    @DisplayName("StreamCollector 可以把事件透传给下游 handler（Agent Loop 内部就是这么做的）")
    void collectorForwardsToDownstream() {
        List<String> downstreamText = new ArrayList<>();
        LlmStreamHandler downstream = new LlmStreamHandler() {
            @Override
            public void onStart() {
                downstreamText.add("<start>");
            }

            @Override
            public void onTextDelta(String delta) {
                downstreamText.add(delta);
            }

            @Override
            public void onComplete(ChatResponse response) {
                downstreamText.add("<complete>");
            }
        };

        StreamingService.CollectorSummary summary = streaming().collect(
                StreamingService.textRequest(mockLlmModel, "自我介绍"), downstream);

        assertThat(downstreamText).isNotEmpty();
        assertThat(downstreamText.get(0)).isEqualTo("<start>");
        assertThat(downstreamText.get(downstreamText.size() - 1)).isEqualTo("<complete>");
        // 下游收到的增量拼起来等于 collector 聚合出的文本
        String joined = String.join("", downstreamText.subList(1, downstreamText.size() - 1));
        assertThat(joined).isEqualTo(summary.text());
    }

    @Test
    @DisplayName("StreamCollector 未收到 onComplete 时用增量自行合成 response()（summary 的兜底路径）")
    void collectorSynthesizesResponseWithoutOnComplete() {
        StreamCollector collector = new StreamCollector();
        collector.onStart();
        collector.onTextDelta("后半");
        collector.onTextDelta("段拼起来");
        collector.onUsage(new com.benxin.llm.core.chat.Usage(3, 4));

        assertThat(collector.isStarted()).isTrue();
        assertThat(collector.hasContent()).isTrue();
        assertThat(collector.text()).isEqualTo("后半段拼起来");
        assertThat(collector.response().text()).isEqualTo("后半段拼起来");
        assertThat(collector.response().finishReason())
                .isEqualTo(com.benxin.llm.core.chat.FinishReason.STOP);
        assertThat(collector.usage().totalTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("没有任何增量时 hasContent()=false、isStarted()=true（onStart 先到）")
    void collectorWithoutContent() {
        StreamCollector collector = new StreamCollector();
        collector.onStart();

        assertThat(collector.isStarted()).isTrue();
        assertThat(collector.hasContent()).isFalse();
        assertThat(collector.text()).isEmpty();
        assertThat(collector.thinkingText()).isEmpty();
        assertThat(collector.usage()).isEqualTo(com.benxin.llm.core.chat.Usage.ZERO);
    }

    @Test
    @DisplayName("StreamCollector.error() 记录失败原因，供 Loop 决定是否抛出")
    void collectorCapturesError() {
        StreamCollector collector = new StreamCollector();
        RuntimeException boom = new IllegalStateException("解码失败");
        collector.onError(boom);

        assertThat(collector.error()).isSameAs(boom);
        assertThat(collector.hasContent()).isFalse();
    }

    // ------------------------------------------------------------------
    // ③ ofText 便捷工厂
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LlmStreamHandler.ofText(consumer) 只转发文本增量")
    void ofTextOnlyForwardsTextDeltas() {
        StringBuilder sink = new StringBuilder();

        String text = streaming().collectTextOnly(
                StreamingService.textRequest(mockLlmModel, "自我介绍"), sink);

        assertThat(sink.length()).isGreaterThan(0);
        assertThat(sink.toString()).isEqualTo(text);
        assertThat(text).doesNotContain("离线模型的思考占位");
    }

    @Test
    @DisplayName("ofText 工厂确实只是「一个覆写 onTextDelta 的匿名实现」")
    void ofTextShape() {
        StringBuilder sink = new StringBuilder();
        LlmStreamHandler handler = LlmStreamHandler.ofText(sink::append);

        // 只有 onTextDelta 有行为，其余回调全是默认空实现
        handler.onStart();
        handler.onThinkingDelta("思维链不会进 sink");
        handler.onToolCall(new ToolUsePart("id", "tool", "{}"));
        handler.onUsage(com.benxin.llm.core.chat.Usage.ZERO);
        handler.onComplete(null);
        handler.onError(new IllegalStateException("x"));
        handler.onTextDelta("只有这一段");

        assertThat(sink.toString()).isEqualTo("只有这一段");
    }

    // ------------------------------------------------------------------
    // ④ 与 Agent 路径的对照：监听器 vs 流式 handler
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不传 handler 的调用：监听器 onTextDelta 仍被触发，调用方自己什么都收不到")
    void listenerGetsDeltasEvenWithoutHandler() {
        chat.ask("自我介绍");

        assertThat(monitorListener.count("onTextDelta")).isPositive();
        assertThat(monitorListener.joinDetails("onTextDelta", "")).contains("离线模型");
    }

    @Test
    @DisplayName("传 handler 的调用：监听器与 handler 收到同一批增量")
    void handlerAndListenerReceiveTheSameDeltas() {
        StringBuilder external = new StringBuilder();

        String text = chat.askWithHandler("自我介绍", LlmStreamHandler.ofText(external::append));

        assertThat(external.length()).isPositive();
        assertThat(text).isEqualTo(external.toString());
        // 两条通道是并列关系：监听器是全局广播，handler 是本次调用的私有出口。
        // 必须用 joinRaw（回调收到的原始增量）而不是 joinDetails —— 后者的 detail 会给增量
        // 加上引号（方便人读），拼起来自然不等于原文。
        assertThat(monitorListener.joinRaw("onTextDelta")).isEqualTo(external.toString());
    }
}
