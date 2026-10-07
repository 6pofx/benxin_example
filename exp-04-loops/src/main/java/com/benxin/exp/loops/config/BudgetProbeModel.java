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
 * 步数预算演示专用模型：<b>无论问什么、无论历史里有没有工具结果，永远请求同一个工具</b>。
 *
 * <p><b>为什么不能复用 {@code ScriptedLlmModel}</b>（实测结论）：脚本模型的求值顺序里
 * 有一条"⑥ 已有工具结果 → 汇总作答"，而且它写在"关键词挑工具"后面的<b>兜底</b>位置。
 * 也就是说只要历史里出现过一次工具结果，脚本模型下一轮必定收尾 ——</p>
 *
 * <pre>
 *   第 1 步：没有工具结果 → 挑一个工具 → 调工具
 *   第 2 步：有工具结果了 → 汇总 → STOP
 * </pre>
 *
 * <p>于是"预算被耗尽（{@code maxStepsReached = true}）"这条路径根本走不到：
 * 实测它稳定在第 2 步收尾，而 {@code maxSteps} 是 12。
 * 想让一个循环真的烧完预算，模型必须<b>每一轮都请求工具、从不给最终答案</b>。</p>
 *
 * <p>这正是 {@code llm.agent.hard-max-steps} 存在的理由 —— 它防的是"模型陷在工具循环里
 * 出不来"，所以演示它也得先造一个真的会陷进去的模型。脚本规则做不到这件事
 * （规则同样排在"有工具结果就汇总"之前，会被那条兜底抢走），只能由模型自己决定。</p>
 */
public class BudgetProbeModel implements LlmModel {

    /** 演示里写死的城市：与 {@code BudgetProbeAgent} 的提示词一致。 */
    private static final String CITY = "北京";

    @Override
    public String name() {
        return OfflineModelConfiguration.BUDGET;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.DEFAULT;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String tool = firstWeatherTool(request);
        if (tool == null) {
            // 没有天气工具就退化成一句话 —— 演示不该因为工具名对不上而整个人挂掉
            return text("（离线脚本化模型）没有可用的天气工具，无法演示预算耗尽。", request);
        }
        String arguments = Json.object().put("city", CITY).put("unit", "celsius").toString();
        return ChatResponse.builder()
                .id(name() + "-loop")
                .model(name())
                .message(ChatMessage.assistant("", List.of(
                        new ToolUsePart("budget-call", tool, arguments))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(usageOf(request, arguments))
                .build();
    }

    /** 取工具表里第一个名字含 {@code weather} 的；取不到返回 {@code null}。 */
    private static String firstWeatherTool(ChatRequest request) {
        for (ToolSpec spec : request.tools()) {
            if (spec.name().toLowerCase().contains("weather")) {
                return spec.name();
            }
        }
        return null;
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
