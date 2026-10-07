package com.benxin.exp.loops.config;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.util.Json;

import java.util.List;

/**
 * {@code codex} 的 {@code apply_patch} 演示专用模型：真的把补丁<b>当工具参数</b>交出去。
 *
 * <p><b>为什么不能只让 {@code ScriptedLlmModel} 吐一段补丁文本</b>（实测结论）：
 * {@code CodexLoop} <b>不会</b>从模型的回复文本里解析补丁 —— 它只有一条执行路径
 * （{@code runToolCallingLoop} → {@code ctx.executeTools(...)}），
 * 也就是说补丁必须以 {@code apply_patch} 这个<b>工具调用</b>的形式出现。
 * 只吐文本的结果是：文件不会被创建、{@code toolCalls()} 里也没有 {@code apply_patch}，
 * 而模型输出的那段补丁看起来完全正常 —— 又是一种"看着像成功"的假绿。</p>
 *
 * <p>本模型因此把"模型想写的内容"直接放进工具参数：内容仍然来自
 * {@link OfflineModelConfiguration#patchDemoText()}（同一份文本既是模型的意图，
 * 也是测试断言的依据），只是把它从"散文里的一段"提升成"结构化的参数"。</p>
 *
 * <p><b>补丁文本同时留在回复正文里</b>：真实模型也常这么干（先讲一句再动手），
 * 而 {@code CodexLoop} 会把它当普通文本留在历史里 —— 演示里那句
 * "回灌给模型的补丁原文"因此仍然有东西可打。</p>
 */
public class CodexPatchScriptModel implements LlmModel {

    /** 工具名（= {@code CodexPatchTools#applyPatch} 上的 {@code @LlmTool(name = ...)}）。 */
    private static final String PATCH_TOOL = "apply_patch";

    @Override
    public String name() {
        return OfflineModelConfiguration.CODEX_PATCH;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.DEFAULT;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        boolean hasToolResult = request.messages().stream()
                .anyMatch(m -> m.role() == com.benxin.llm.core.message.Role.TOOL);

        // 已经拿到工具结果：说明补丁落盘了（或明确失败了），这一轮收尾，别再重复贴补丁。
        if (hasToolResult) {
            return text("本次改动新建了一个演示文件，路径与内容都来自模型给出的补丁。", request);
        }

        if (!hasPatchTool(request)) {
            // 没有 apply_patch 工具就退化成纯文本：演示不该因为工具没开而整个人挂掉，
            // 但那句话必须说清楚"没落盘"，免得读者以为成功了。
            return text("（离线脚本化模型）工具集里没有 " + PATCH_TOOL
                    + "，补丁无法落盘。补丁内容如下：\n\n"
                    + OfflineModelConfiguration.patchDemoText(), request);
        }

        String patch = OfflineModelConfiguration.patchDemoText();
        String arguments = Json.object().put("patch", patch).toString();
        return ChatResponse.builder()
                .id(name() + "-patch")
                .model(name())
                // 正文里也带一句 + 补丁原文：真实模型就是这么"边说边动手"的
                .message(ChatMessage.assistant(patch, List.of(
                        new ToolUsePart("codex-patch-call", PATCH_TOOL, arguments))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(usageOf(request, patch))
                .build();
    }

    private static boolean hasPatchTool(ChatRequest request) {
        for (ToolSpec spec : request.tools()) {
            if (PATCH_TOOL.equals(spec.name())) {
                return true;
            }
        }
        return false;
    }

    private ChatResponse text(String content, ChatRequest request) {
        return ChatResponse.builder()
                .id(name() + "-text")
                .model(name())
                .message(ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(usageOf(request, content))
                .build();
    }

    private static Usage usageOf(ChatRequest request, String output) {
        return new Usage(estimateTokens(allTextOf(request)), estimateTokens(output));
    }

    private static String allTextOf(ChatRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(request.systemText() == null ? "" : request.systemText());
        request.messages().forEach(m -> sb.append(m.allText()));
        return sb.toString();
    }

    /** 粗估：与 {@code ScriptedLlmModel} 同一套口径（不是真实 tokenizer，仅供演示）。 */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + Math.max(1, other / 4);
    }
}
