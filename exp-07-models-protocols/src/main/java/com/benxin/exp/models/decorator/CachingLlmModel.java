package com.benxin.exp.models.decorator;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自己写的模型装饰器：给任意 {@link LlmModel} 加一层"同一请求只打一次上游"的缓存。
 *
 * <p>它与插件自带的 {@code RetryingLlmModel} 是同一套写法的两个例子，
 * 因此放在一起演示"装饰器是可以叠加的"：</p>
 * <pre>
 * 缓存装饰器( 重试装饰器( HttpLlmModel ) )     或     重试装饰器( 缓存装饰器( HttpLlmModel ) )
 * </pre>
 * <p>顺序会带来真实差异 —— 缓存在外层时"重试过的请求"也一并被缓存；缓存在内层时
 * 每次重试都会重新查一次缓存。这不是考试题，而是选装饰器顺序时真正要想清楚的事。</p>
 *
 * <h2>三条刻意的取舍</h2>
 * <ol>
 *   <li>{@link #capabilities()} <b>透传</b>被包装模型：缓存不会让模型多出视觉能力，
 *       而 {@code LoopContext} 正是靠这个声明决定走流式还是同步分支。</li>
 *   <li>{@link #stream(ChatRequest, LlmStreamHandler)} <b>不缓存</b>，直接转发。
 *       流式响应是"过程"而不是"值"，缓存一个过程需要把整条事件序列录下来再重放，
 *       那是"录制回放"装饰器的职责，混进缓存里只会让语义变浑。</li>
 *   <li>{@link #unwrap()} 返回被包装的模型，这样
 *       {@code registry.get("x").unwrap().unwrap()} 能一路取回最里层，
 *       "链式取回"这条能力才谈得上可用。</li>
 * </ol>
 */
public class CachingLlmModel implements LlmModel {

    private final LlmModel delegate;
    private final Map<String, ChatResponse> cache = new LinkedHashMap<>();
    private final boolean cacheStreamingRequests;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    /**
     * @param delegate               被包装的模型
     * @param cacheStreamingRequests 是否连"标记了 stream 的请求"也缓存。
     *                               默认 false：声明式 Agent 路径恒为流式，若把它也缓存，
     *                               "第二次调用不再打上游"这句话会盖住流式行为本身。
     */
    public CachingLlmModel(LlmModel delegate, boolean cacheStreamingRequests) {
        this.delegate = delegate;
        this.cacheStreamingRequests = cacheStreamingRequests;
    }

    public CachingLlmModel(LlmModel delegate) {
        this(delegate, false);
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
        if (request != null && request.stream() && !cacheStreamingRequests) {
            misses.incrementAndGet();
            return delegate.chat(request);
        }
        String key = keyOf(request);
        synchronized (cache) {
            ChatResponse cached = cache.get(key);
            if (cached != null) {
                hits.incrementAndGet();
                return cached;
            }
        }
        ChatResponse response = delegate.chat(request);
        misses.incrementAndGet();
        synchronized (cache) {
            cache.put(key, response);
        }
        return response;
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        delegate.stream(request, handler);
    }

    /** 链式取回：一路 {@code unwrap()} 就能拿回真正的模型。 */
    @Override
    public LlmModel unwrap() {
        return delegate;
    }

    public int hits() {
        return hits.get();
    }

    public int misses() {
        return misses.get();
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
        hits.set(0);
        misses.set(0);
    }

    /**
     * 只把"会影响上游输出"的字段纳入键。
     *
     * <p>刻意不用 {@code request.toString()} 之类的东西：那些文本里常带对象身份，
     * 会让"两次一模一样的请求"算出两个键，缓存命中率静默变成 0 —— 这类 bug 不报错、只烧钱。</p>
     */
    private static String keyOf(ChatRequest request) {
        if (request == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(request.model())
                .append("|t=").append(request.temperature())
                .append("|max=").append(request.maxTokens())
                .append("|topP=").append(request.topP())
                .append("|choice=").append(request.toolChoice())
                .append("|extra=").append(request.extra());
        for (ChatMessage message : request.messages()) {
            sb.append("||").append(message.role()).append(':').append(partsOf(message));
        }
        for (ToolSpec spec : request.tools()) {
            sb.append("||tool:").append(spec.name()).append('=').append(spec.inputSchema());
        }
        return sb.toString();
    }

    /** 内容块的稳定文本形式：图片只算"有图 + 摘要"，避免把 base64 整个塞进键里。 */
    private static String partsOf(ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.parts()) {
            if (part instanceof TextPart text) {
                sb.append(text.text());
            } else if (part instanceof ToolUsePart use) {
                sb.append("[tool:").append(use.name()).append(use.argumentsJson()).append(']');
            } else if (part instanceof ToolResultPart result) {
                sb.append("[result:").append(result.toolUseId()).append(':').append(result.content()).append(']');
            } else if (part instanceof ThinkingPart thinking) {
                sb.append("[thinking:").append(thinking.text()).append(']');
            } else if (part instanceof ImagePart image) {
                sb.append("[image:").append(image.isInline() ? image.mediaType() : image.url()).append(']');
            }
        }
        return sb.toString();
    }
}
