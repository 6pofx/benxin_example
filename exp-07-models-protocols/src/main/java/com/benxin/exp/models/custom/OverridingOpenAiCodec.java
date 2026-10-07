package com.benxin.exp.models.custom;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelException;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.StreamDecoder;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个 {@code protocol()} 返回 {@link Protocol#OPENAI} 的自定义 Codec。
 *
 * <p>它演示的是被<b>刻意保留</b>的一条能力：<b>同名即覆盖</b>。
 * {@code ProtocolRegistry} 先登记内置四协议、再登记容器里的 {@code ProtocolCodec} bean，
 * 因此后登记的这个会把内置 {@code openai} 顶掉，且注册时会打一条 WARN ——
 * 因为被换掉的是"全应用的默认通道"，静默替换的排查成本太高。</p>
 *
 * <p>报文用的是<b>简到极致</b>的私有格式（{@code {"prompt":"…"}} / {@code {"reply":"…"}}），
 * 这样"内置 openai 那条路真的被换掉了"是看得见的事实，而不是一句声明。</p>
 *
 * <p>注意：它是独立的 bean 定义，只在专门的测试上下文里注册 ——
 * 主演示上下文里绝不能出现它，否则本模块用来演示四种协议的那条 openai 通道会被悄悄换掉。</p>
 */
public class OverridingOpenAiCodec implements ProtocolCodec {

    /** 累计调用次数：用来证明"走的是我这一份实现"。 */
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Protocol protocol() {
        return Protocol.OPENAI;
    }

    @Override
    public String endpoint(ModelConfig config, ChatRequest request) {
        // 故意用一个与内置 OpenAI 完全不同的路径：抓包时一眼能看出走了谁
        return config.baseUrl() + "/override/v9/talk";
    }

    @Override
    public Map<String, String> headers(ModelConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        if (config != null && config.hasApiKey()) {
            headers.put("X-Override-Key", config.apiKey());
        }
        return headers;
    }

    @Override
    public String encode(ChatRequest request, ModelConfig config) {
        ObjectNode root = Json.object();
        root.put("prompt", request.messages().isEmpty() ? "" : request.messages().get(0).text());
        if (request.hasTools()) {
            var names = root.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                names.add(spec.name());
            }
        }
        return Json.write(root);
    }

    @Override
    public ChatResponse decode(String responseBody, ModelConfig config) {
        var node = Json.parseQuietly(responseBody);
        if (node == null || !node.hasNonNull("reply")) {
            throw new ModelException("覆盖版 openai 实现收到的响应没有 reply 字段: "
                    + Json.abbreviate(responseBody));
        }
        calls.incrementAndGet();
        return ChatResponse.builder()
                .message(com.benxin.llm.core.message.ChatMessage.assistant(node.get("reply").asText()))
                .finishReason(com.benxin.llm.core.chat.FinishReason.STOP)
                .build();
    }

    @Override
    public StreamDecoder newStreamDecoder(ModelConfig config, LlmStreamHandler handler) {
        // 覆盖演示只走同步通道，流式直接借用 demo 协议的解码器没有意义，这里给一个明确失败：
        // "没实现却安静地退化"比"响亮地报错"危险得多
        throw new ModelException("覆盖版 openai 实现不支持流式调用（本示例只演示同步通道）");
    }

    /** 已经成功解码过几次。 */
    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }
}
