package com.benxin.exp.tools.support;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简脚本化模型：第一轮按脚本发起（可以是多个）工具调用，拿到工具结果后回一段固定文本。
 *
 * <p><b>为什么不能只用 exp-common 的 {@code ScriptedLlmModel}：</b></p>
 * <ul>
 *   <li>它的 {@code #tool:名字 {JSON}} 语法用<b>非贪婪</b>正则截取参数
 *       （{@code ScriptedLlmModel.java:51} 的 {@code (\{.*?})?}），遇到嵌套 JSON
 *       （对象里套对象、数组里套对象）会在第一个 {@code }} 处提前收尾 ——
 *       "嵌套 POJO 参数"这一类演示根本喂不进去；</li>
 *   <li>它一轮只发一个工具调用，而 {@code parallelSafe} 的并行/串行判定要求同一轮里
 *       有<b>多个</b>工具调用（{@code DefaultLoopContext.java:349} 的 {@code toolUses.size() > 1}）。</li>
 * </ul>
 *
 * <p>本类因此是示例自己的测试夹具：直接给出 {@link ToolUsePart} 的原始 JSON 参数，
 * 一轮可以给多个。它同样<b>离线、零 token</b>，并且刻意声明
 * {@code streaming=false}，顺带走一遍"模型不支持流式时 Loop 走同步通道"的分支。</p>
 */
public final class ScriptedToolCallModel implements LlmModel {

    private final String name;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<ChatRequest> requests = new ArrayList<>();

    private volatile List<ToolUsePart> roundOne = List.of();
    private volatile String finalText = "（脚本化工具模型）已拿到工具结果，收尾。";

    public ScriptedToolCallModel(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelCapabilities capabilities() {
        // 不支持流式：DefaultLoopContext 会走 callModelSynchronously()，
        // 少一层流式聚合，出错时更好定位。
        return ModelCapabilities.builder()
                .streaming(false)
                .toolCalling(true)
                .parallelToolCalls(true)
                .build();
    }

    // ------------------------------------------------------------------
    // 脚本
    // ------------------------------------------------------------------

    /** 设置第一轮要发起的工具调用；之后的调用一律回 {@link #finalText}。 */
    public void script(ToolCall... calls) {
        List<ToolUsePart> parts = new ArrayList<>();
        for (int i = 0; i < calls.length; i++) {
            // id 必须互不相同：结果回灌时靠它把"第几个工具调用"对回"第几条结果"。
            parts.add(calls[i].toPart("call_" + i));
        }
        this.roundOne = List.copyOf(parts);
    }

    /** 只发一个工具调用时的简写。 */
    public void scriptCall(String toolName, String argumentsJson) {
        script(new ToolCall(toolName, argumentsJson));
    }

    public void finalText(String text) {
        this.finalText = text;
    }

    public void reset() {
        calls.set(0);
        synchronized (requests) {
            requests.clear();
        }
        this.roundOne = List.of();
    }

    // ------------------------------------------------------------------
    // 观测
    // ------------------------------------------------------------------

    public int calls() {
        return calls.get();
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

    // ------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        int index = calls.incrementAndGet();
        synchronized (requests) {
            requests.add(request);
        }
        // 与 ScriptedLlmModel 同口径：只有"还没取过工具结果"的那一轮才发工具调用，
        // 否则会无限循环（每轮都发同一个工具调用）。
        boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == Role.TOOL);
        if (!hasToolResult && !roundOne.isEmpty()) {
            return ChatResponse.builder()
                    .id(name + "-" + index)
                    .model(name)
                    .message(ChatMessage.assistant("", roundOne))
                    .finishReason(FinishReason.TOOL_CALLS)
                    .usage(new Usage(16, 8))
                    .build();
        }
        return ChatResponse.builder()
                .id(name + "-" + index)
                .model(name)
                .message(ChatMessage.assistant(finalText))
                .finishReason(FinishReason.STOP)
                .usage(new Usage(16, 8))
                .build();
    }

    /** 一条"发哪个工具、参数 JSON 是什么"的脚本项。 */
    public record ToolCall(String toolName, String argumentsJson) {

        ToolUsePart toPart(String id) {
            return new ToolUsePart(id, toolName, argumentsJson == null ? "{}" : argumentsJson);
        }
    }
}
