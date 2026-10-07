package com.benxin.exp.common.model;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制型模型装饰器：把每个请求原样记下来，再交给被装饰的模型。
 *
 * <p>它是本心"模型可以被任意装饰"这条主张的最小可用示范：装饰器不需要继承
 * {@code HttpLlmModel}，只要实现 {@link LlmModel} 就能插进任何 Agent ——
 * 插件自带的 {@code RetryingLlmModel} 也是同一套写法。</p>
 *
 * <p>示例里靠它做两件真实需求：<b>审计</b>（把每次真正发给模型的系统提示词与消息留档）
 * 和<b>断言</b>（测试里检查"@Ctx 变量有没有泄漏进消息体"这类口径）。</p>
 */
public class RecordingModel implements LlmModel {

    private final LlmModel delegate;
    private final List<ChatRequest> requests = new ArrayList<>();

    public RecordingModel(LlmModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        record(request);
        return delegate.chat(request);
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        record(request);
        delegate.stream(request, handler);
    }

    /** 便于链式取回被装饰的模型（与 {@code RetryingLlmModel#unwrap()} 同口径）。 */
    @Override
    public LlmModel unwrap() {
        return delegate;
    }

    private void record(ChatRequest request) {
        synchronized (requests) {
            requests.add(request);
        }
    }

    public List<ChatRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public ChatRequest lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    public int count() {
        synchronized (requests) {
            return requests.size();
        }
    }

    public void reset() {
        synchronized (requests) {
            requests.clear();
        }
    }

    @Override
    public String toString() {
        return "RecordingModel(" + delegate + ")";
    }
}
