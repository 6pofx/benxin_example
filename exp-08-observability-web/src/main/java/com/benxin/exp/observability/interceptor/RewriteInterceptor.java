package com.benxin.exp.observability.interceptor;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求与响应的<b>改写</b>示范：{@code beforeModel} 往请求里塞追踪字段，
 * {@code afterModel} 往响应文本尾部追加一段标记。
 *
 * <p>本模块用两种方式证明改写真的生效，而不是"调用了但没人看得见"：</p>
 * <ol>
 *   <li>{@code beforeModel} 塞进去的 {@code trace_id} 由 {@code RecordingModel} 抓到的
 *       {@link ChatRequest#extra()} 断言 —— 这是"下游真的收到了"的证据；</li>
 *   <li>{@code afterModel} 追加的标记直接出现在 {@code AgentResult.text()} 里 ——
 *       这是"改写真的影响了最终结果"的证据。</li>
 * </ol>
 *
 * <p><b>顺序语义值得留意</b>：拦截器链按 {@code order()} 升序调用 {@code afterModel}，
 * 后调用者的改写会覆盖先调用者（{@code DefaultLoopContext} 里是
 * {@code response = interceptor.afterModel(...)}，逐个覆盖）。本示例只有一个改写者，
 * 但把 order 显式写出来，替换成多个时行为才可预期。</p>
 */
public class RewriteInterceptor implements AgentInterceptor {

    /** 注入请求 extra 的键名。 */
    public static final String TRACE_KEY = "trace_id";

    /** 追加到响应文本尾部的标记。 */
    public static final String SUFFIX_MARKER = "【响应已被 afterModel 改写】";

    private final AtomicInteger requestRewrites = new AtomicInteger();
    private final AtomicInteger responseRewrites = new AtomicInteger();

    @Override
    public int order() {
        return 30;
    }

    @Override
    public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        requestRewrites.incrementAndGet();
        return request.toBuilder()
                // extra 是"协议特有字段"的透传口；这里借它示范"给每次调用盖上可追踪的戳"。
                .extra(TRACE_KEY, invocation.agentName() + ":" + invocation.sessionId())
                .build();
    }

    @Override
    public ChatResponse afterModel(AgentInvocation invocation, ChatResponse response) {
        if (response == null || response.message() == null) {
            return response;
        }
        responseRewrites.incrementAndGet();
        ChatMessage original = response.message();
        List<com.benxin.llm.core.message.ContentPart> parts = new ArrayList<>(original.parts());
        // 把标记插在最后一个文本块里，工具调用块与思维链块原样保留 ——
        // 否则"改写响应"会把模型请求的工具一并抹掉，Loop 就拿不到工具结果了。
        boolean replaced = false;
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (parts.get(i) instanceof TextPart textPart) {
                parts.set(i, new TextPart(textPart.text() + SUFFIX_MARKER));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            return response;
        }
        return ChatResponse.builder()
                .id(response.id())
                .model(response.model())
                .message(ChatMessage.assistant(parts))
                .finishReason(response.finishReason())
                .usage(response.usage())
                .raw(response.raw())
                .build();
    }

    public int requestRewrites() {
        return requestRewrites.get();
    }

    public int responseRewrites() {
        return responseRewrites.get();
    }

    public void reset() {
        requestRewrites.set(0);
        responseRewrites.set(0);
    }

    @Override
    public String toString() {
        return "RewriteInterceptor[请求改写 " + requestRewrites() + " 次 / 响应改写 " + responseRewrites() + " 次]";
    }
}
