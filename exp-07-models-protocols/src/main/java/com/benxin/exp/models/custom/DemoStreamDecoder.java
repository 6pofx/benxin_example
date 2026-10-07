package com.benxin.exp.models.custom;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.SseEvent;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * demo 协议的流式解码器：把 {@code {"event":"…"}} 形态的分片翻译成统一回调。
 *
 * <p>它与四种内置协议的流式实现共享同一套"契约"，也就是 {@link StreamDecoder}
 * 的四条约定 —— 自定义协议只要守住这四条，上层的流式渲染、用量统计、重试判定
 * 就都不需要知道底层是什么协议：</p>
 * <ol>
 *   <li>{@code onStart} 最多一次（这里用 {@code started} 标记保证）；</li>
 *   <li>工具调用<b>参数拼装完整后</b>才回调 {@code onToolCall}，一次工具调用只回调一次；</li>
 *   <li>{@link #finish()} 幂等，且是"发出 {@code onComplete}"的唯一位置；</li>
 *   <li>出错后不再补 {@code onComplete}（否则半截响应会被上层当成功结果）。</li>
 * </ol>
 *
 * <p>本协议的流用显式的 {@code {"event":"done"}} 帧收尾，因此它也属于
 * "有结束标记"的一类（与 OpenAI 的 {@code [DONE]} 同类），
 * 与 Gemini 那种"靠连接关闭"的形态形成对照。</p>
 */
public class DemoStreamDecoder implements StreamDecoder {

    private final LlmStreamHandler handler;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder muse = new StringBuilder();
    private final List<ToolUsePart> tools = new ArrayList<>();

    private boolean started;
    private boolean finished;
    private boolean failed;
    private String outcome;
    private String id;
    private String model;
    private Usage usage = Usage.ZERO;

    public DemoStreamDecoder(LlmStreamHandler handler) {
        this.handler = handler == null ? new LlmStreamHandler() {
        } : handler;
    }

    @Override
    public void accept(SseEvent event) {
        if (event == null || event.isBlank() || finished || failed) {
            return;
        }
        JsonNode payload = Json.parseQuietly(event.data());
        if (payload == null || !payload.isObject()) {
            // 单个坏帧不打断整条流，与内置协议的口径一致
            return;
        }
        if (payload.hasNonNull("id")) {
            id = payload.get("id").asText();
        }
        if (payload.hasNonNull("model")) {
            model = payload.get("model").asText();
        }
        JsonNode spend = payload.path("spend");
        if (spend.isObject()) {
            usage = new Usage(spend.path("in").asInt(usage.inputTokens()),
                    spend.path("out").asInt(usage.outputTokens()));
        }

        switch (payload.path("event").asText("")) {
            case "start" -> ensureStarted();
            case "say" -> {
                ensureStarted();
                String delta = payload.path("value").asText("");
                if (!delta.isEmpty()) {
                    text.append(delta);
                    handler.onTextDelta(delta);
                }
            }
            case "muse" -> {
                ensureStarted();
                String delta = payload.path("value").asText("");
                if (!delta.isEmpty()) {
                    muse.append(delta);
                    handler.onThinkingDelta(delta);
                }
            }
            case "skill" -> {
                ensureStarted();
                // 本协议一次性给全参数，因此收到即可回调（不像 OpenAI 要按 index 拼 arguments）
                ToolUsePart tool = new ToolUsePart(
                        payload.hasNonNull("callId") ? payload.get("callId").asText()
                                : "skill_" + tools.size(),
                        payload.path("skill").asText(""),
                        payload.hasNonNull("args") ? Json.write(payload.get("args")) : "{}");
                tools.add(tool);
                handler.onToolCall(tool);
            }
            case "usage" -> {
                ensureStarted();
                handler.onUsage(usage);
            }
            case "done" -> {
                outcome = payload.path("outcome").asText("done");
                finish();
            }
            case "problem" -> {
                failed = true;
                handler.onError(new ModelException("demo 协议流式错误: "
                        + payload.path("message").asText(Json.write(payload))));
            }
            default -> {
                // ping 之类的未知帧忽略
            }
        }
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (failed) {
            return;
        }
        ensureStarted();
        List<ContentPart> parts = new ArrayList<>();
        if (!muse.isEmpty()) {
            parts.add(new ThinkingPart(muse.toString()));
        }
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(tools);
        handler.onComplete(ChatResponse.builder()
                .id(id)
                .model(model)
                .message(ChatMessage.assistant(parts))
                .finishReason(DemoProtocolCodec.finishReasonOf(outcome, !tools.isEmpty()))
                .usage(usage)
                .build());
    }

    @Override
    public void error(Throwable cause) {
        if (cause == null) {
            return;
        }
        failed = true;
        handler.onError(cause);
    }

    private void ensureStarted() {
        if (!started) {
            started = true;
            handler.onStart();
        }
    }
}
