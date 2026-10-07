package com.benxin.exp.loops.config;

import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.util.Json;

import java.util.List;

/**
 * 子代理演示专用模型：父侧"先派生再收尾"，子侧"直接给结论"。
 *
 * <p><b>为什么不直接用 {@code ScriptedLlmModel} 的 {@code systemRule}</b>
 * （这是本模块实测出来的一条离线设施局限）：脚本模型的求值顺序是</p>
 *
 * <pre>
 *   ① #tool: 强制指令 → ② 使用者规则 → ③ 编排类 Loop 内建应答
 *     → ④ 文本协议 → ⑤ 关键词挑工具 → ⑥ 有工具结果就汇总
 * </pre>
 *
 * <p>父侧的收尾规则写在 ②，而"该收尾了"的信号（历史里有工具结果）在第 ⑥ 步才被检查。
 * 于是一条<b>无条件</b>的父侧规则会在<b>第一轮</b>就命中、直接吐出最终答案，
 * {@code task} / {@code delegate_task} 一次都不会被调用 —— 运行结果还挺"正常"
 * （有文本、不报错），是最难查的那种假绿；而给规则加"第一轮不说话"的开关，
 * 又会被 ①（{@code #tool:} 排在规则之前）抢走，父侧永远拿不到工具结果。</p>
 *
 * <p>这两条约束叠在一起，用 {@code systemRule} 表达不出"先派生、后收尾"。
 * 于是这里写一个本模块自己的模型：它的决策顺序就是<b>先看历史里有没有工具结果</b>，
 * 再决定这一轮该做什么 —— 与"真实模型看到工具结果后收尾"的行为一致，
 * 不需要靠"第几轮调用"这种与上下文无关的计数来猜。</p>
 *
 * <p><b>它怎么同时服务父与子两个 Agent：</b>父与子的系统提示词带各自的标记
 * （{@link OfflineModelConfiguration#PARENT_MARKER} / {@link OfflineModelConfiguration#CHILD_MARKER}），
 * 模型按标记分流。两边共用同一个 bean，是因为它们本来就绑同一个模型名；
 * 分发依据是提示词而不是"谁先调用我"，因此父子里谁先跑都不影响结果。</p>
 */
public class SubAgentScriptModel implements LlmModel {

    /** 父侧派生时用的工具：取请求工具表里<b>第一个</b>名字含这部分的工具。 */
    private static final String SUB_AGENT_TOOL_HINT = "task";

    /** 子侧被要求"一句话、不调工具"，所以它的回答是纯文本。 */
    private static final String CHILD_ANSWER = OfflineModelConfiguration.CHILD_ANSWER;

    /** 父侧收尾时把自己收到的东西并进最终答案 —— 测试靠这句话证明派生真的发生过。 */
    private static final String PARENT_ANSWER = "父代理已收到子代理结论，任务完成：" + CHILD_ANSWER;

    @Override
    public String name() {
        return OfflineModelConfiguration.SUB_AGENT;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.DEFAULT;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String systemText = request.systemText() == null ? "" : request.systemText();
        String userText = allUserText(request);
        boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == Role.TOOL);

        // ① 子侧：一句话结论，绝不调工具 —— "子代理的独立上下文"因此一眼可见
        if (systemText.contains(OfflineModelConfiguration.CHILD_MARKER)) {
            return text(CHILD_ANSWER, request);
        }

        // ② 父侧且已经拿到工具结果：收尾。这一步必须排在"派生"之前，
        //    否则父代理会一轮接一轮地派生同一个子任务，把步数预算烧光（实测会跑满 8 步）。
        if (systemText.contains(OfflineModelConfiguration.PARENT_MARKER) && hasToolResult) {
            return text(PARENT_ANSWER, request);
        }

        // ③ 父侧、还没有工具结果、且有人点名了派生工具：派生一次。
        //    工具名从请求里取而不是写死，换内置 task 或本模块的 delegate_task 都能用。
        String tool = subAgentTool(request);
        if (tool != null && userText.contains("#tool:" + tool)) {
            return toolCall(tool, request);
        }

        // ④ 兜底：没有可派生的工具时也别把链路憋死，给一句可读的回复
        return text("（离线脚本化模型）我收到了你的请求："
                + abbreviate(lastUserText(request), 60), request);
    }

    /** 找"派生型"工具：优先名字里含 {@code task} 的，其次名字里含 {@code delegate} 的。 */
    private static String subAgentTool(ChatRequest request) {
        for (String hint : List.of(SUB_AGENT_TOOL_HINT, "delegate")) {
            for (ToolSpec spec : request.tools()) {
                if (spec.name().toLowerCase().contains(hint)) {
                    return spec.name();
                }
            }
        }
        return null;
    }

    private ChatResponse toolCall(String toolName, ChatRequest request) {
        // 参数按工具名分派：内置 task 用 description/prompt，本模块的 delegate_task 用 instruction。
        // 两个工具都要在这里能跑，是因为"注解 subAgents + task"与"ToolContext.spawn"是本模块要
        // 分别演示的两条装配方式，它们绑的是同一个模型名。
        //
        // 两边都**不**传 subagent_type：留给工具自己按 benxin.default-subagent 回退，
        // 这样"默认子代理"这条口径也能在同一条链路上被演示到。
        String arguments = toolName.contains("delegate")
                ? Json.object()
                        .put("instruction", "请告诉我北京今天的天气与气温。")
                        .put("subagent_type", OfflineModelConfiguration.CHILD_AGENT)
                        .put("trace", "test-trace")
                        .toString()
                : Json.object()
                        .put("description", "查询北京天气")
                        .put("prompt", "请告诉我北京今天的天气与气温。")
                        .toString();
        return ChatResponse.builder()
                .id(name() + "-tool")
                .model(name())
                .message(ChatMessage.assistant("", List.of(
                        new ToolUsePart("sub-agent-call", toolName, arguments))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(usageOf(request, arguments))
                .build();
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

    /**
     * 估一份像样的用量，让"父的 usage 含子代理消耗"这类断言有真实数字可看。
     *
     * <p>刻意按<b>请求全文长度</b>估输入（而不是给个常数）：父侧第二轮的历史里带着子代理的
     * 结论与整段环境注入，输入量本来就该明显大于第一轮 —— 这是 {@code ScriptedLlmModel}
     * 的做法，这里保持一致，示例里的 token 数字才不至于和别的模块两套口径。</p>
     */
    private static Usage usageOf(ChatRequest request, String output) {
        int input = estimateTokens(allTextOf(request));
        return new Usage(input, estimateTokens(output));
    }

    /**
     * 全部 user 消息拼起来。
     *
     * <p><b>为什么不是"只看最后一条 user 消息"</b>（这里踩过一个坑）：
     * {@code DecorateLoop} 在跑主循环之前会自己 {@code append} 一条复述指令，
     * 于是"最后一条 user 消息"变成了复述指令，原本那句
     * {@code #tool:delegate_task …} 被挤到后面去了 ——
     * 按最后一条判断就会漏掉派生指令，表现为工具一次都不被调用。</p>
     */
    private static String allUserText(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            if (message.role() == Role.USER) {
                sb.append(message.text() == null ? "" : message.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                String text = messages.get(i).text();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    private static String allTextOf(ChatRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(request.systemText() == null ? "" : request.systemText());
        request.messages().forEach(m -> sb.append(m.allText()));
        return sb.toString();
    }

    /** 粗估：与 {@code ScriptedLlmModel} 用同一套口径（不是真实 tokenizer，仅供演示）。 */
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

    private static String abbreviate(String text, int max) {
        String oneLine = text == null ? "" : text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
