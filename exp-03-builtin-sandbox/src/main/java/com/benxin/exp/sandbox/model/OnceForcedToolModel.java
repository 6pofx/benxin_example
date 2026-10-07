package com.benxin.exp.sandbox.model;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.FinishReason;
import com.benxin.llm.core.chat.ToolSpec;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code #tool:} 强制工具调用的两个工程化补丁（其余决策全部交给 {@link ScriptedLlmModel}）。
 *
 * <p>沙箱演示必须精确指定工具参数，因此本示例沿用 {@code exp-common} 的
 * {@code #tool:<名字> {JSON}} 语法。但直接用 {@link ScriptedLlmModel} 跑沙箱场景会撞上两个坎，
 * 这里用一层装饰器补掉 —— 与 exp-01 里 {@code RecordingModel} 的做法同源：
 * <b>模型可以被任意装饰，装饰器不需要继承任何基类</b>。</p>
 *
 * <ol>
 *   <li><b>花括号配平。</b>{@link ScriptedLlmModel} 用 {@code \#tool:(\w+)\s*(\{.*?})?}
 *       抓参数，非贪婪的 {@code .*?} 会在<b>第一个</b> {@code }} 处停下。于是任何
 *       "对象数组"参数（最典型的就是 {@code todo_write} 的
 *       {@code {"todos":[{"content":"x","status":"pending"}]}}）都会被截断成非法 JSON，
 *       最终表现为「参数 todos 必填」——工具是对的，是参数在到达工具之前就烂掉了。
 *       这里改成从第一个 {@code {} 起做深度配平扫描。</li>
 *   <li><b>一次运行只强制一次。</b>{@link ScriptedLlmModel} 的第 ① 条规则在<b>每一轮</b>
 *       都会命中同一条 user 消息，于是"强制一次工具"实际会变成"每轮都调这个工具"，
 *       一直撞到步数上限，模型永远没机会收尾。这里约定：已经拿到工具结果之后就不再强制，
 *       把收尾交回脚本模型（用与 {@code ScriptedLlmModel#answerFromToolResults} 同口径的措辞），
 *       演示因此能完整跑通「模型决策 → 工具执行 → 结果回灌 → 收尾」。</li>
 * </ol>
 *
 * <p>非 {@code #tool:} 的请求、以及工具不在本次请求工具表里的情况，一律原样交给脚本模型，
 * 所以 {@code #tool:read {"path":"a.txt"}} 之外的每一轮仍然是纯粹由 {@link ScriptedLlmModel} 驱动的。</p>
 */
public final class OnceForcedToolModel implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(OnceForcedToolModel.class);

    /** {@code #tool:<名字>}；参数部分单独配平扫描，所以这里不抓 JSON。 */
    private static final String DIRECTIVE = "#tool:";

    private final ScriptedLlmModel delegate;

    /** 与 {@link ScriptedLlmModel#toolCallLog()} 同口径的调用留痕，便于断言"到底调了什么"。 */
    private final List<String> toolCallLog = new ArrayList<>();
    private final AtomicInteger forcedCalls = new AtomicInteger();

    public OnceForcedToolModel(ScriptedLlmModel delegate) {
        this.delegate = delegate;
    }

    public ScriptedLlmModel delegate() {
        return delegate;
    }

    public int forcedCalls() {
        return forcedCalls.get();
    }

    public List<String> toolCallLog() {
        synchronized (toolCallLog) {
            return List.copyOf(toolCallLog);
        }
    }

    public void reset() {
        forcedCalls.set(0);
        synchronized (toolCallLog) {
            toolCallLog.clear();
        }
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
    public LlmModel unwrap() {
        return delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String userText = lastUserText(request);
        String toolName = parseToolName(userText);
        if (toolName != null && hasTool(request, toolName)) {
            if (!hasToolResult(request)) {
                String arguments = balancedArguments(userText);
                if (arguments == null) {
                    log.warn("无法配平 #tool:{} 的参数 JSON，已交回脚本模型处理", toolName);
                } else {
                    return toolCall(toolName, arguments, request);
                }
            } else {
                // 已经拿到工具结果：不再重复强制，用与脚本模型同口径的措辞收尾
                return text(summarize(request), request);
            }
        }
        return delegate.chat(request);
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        handler.onStart();
        ChatResponse response = chat(request);
        String content = response.message().text();
        if (content != null && !content.isEmpty()) {
            handler.onTextDelta(content);
        }
        response.message().toolUses().forEach(handler::onToolCall);
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    // ------------------------------------------------------------------
    // 报文构造（与 ScriptedLlmModel 保持一致，便于对照）
    // ------------------------------------------------------------------

    private ChatResponse toolCall(String toolName, String argumentsJson, ChatRequest request) {
        forcedCalls.incrementAndGet();
        synchronized (toolCallLog) {
            toolCallLog.add(toolName + "(" + argumentsJson + ")");
        }
        return ChatResponse.builder()
                .id(name() + "-forced-" + forcedCalls.get())
                .model(name())
                .message(ChatMessage.assistant("", List.of(new ToolUsePart(
                        "call_forced_" + Math.abs(toolName.hashCode()) + "_" + forcedCalls.get(),
                        toolName, argumentsJson))))
                .finishReason(FinishReason.TOOL_CALLS)
                .usage(new Usage(estimateTokens(allTextOf(request)), 32))
                .build();
    }

    private ChatResponse text(String content, ChatRequest request) {
        return ChatResponse.builder()
                .id(name() + "-wrapup")
                .model(name())
                .message(ChatMessage.assistant(content))
                .finishReason(FinishReason.STOP)
                .usage(new Usage(estimateTokens(allTextOf(request)), estimateTokens(content)))
                .build();
    }

    /** 把工具结果汇总成一句可读结论（措辞与 {@code ScriptedLlmModel} 的收尾保持同口径）。 */
    private static String summarize(ChatRequest request) {
        StringBuilder sb = new StringBuilder("根据工具返回的结果：\n");
        request.messages().stream()
                .filter(message -> message.role() == Role.TOOL)
                .forEach(message -> sb.append("- ").append(message.text()).append('\n'));
        sb.append("\n综合以上信息给出结论。（本回答由离线脚本化模型生成。）");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 取 {@code #tool:} 后面的工具名；没有指令时返回 {@code null}。 */
    private static String parseToolName(String text) {
        int at = text.indexOf(DIRECTIVE);
        if (at < 0) {
            return null;
        }
        int start = at + DIRECTIVE.length();
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!valid) {
                break;
            }
            end++;
        }
        return end == start ? null : text.substring(start, end);
    }

    /**
     * 从第一个 {@code {} 开始做深度配平扫描，取出完整的 JSON 对象文本。
     *
     * <p>字符串字面量里的花括号与转义不参与计数，因此
     * {@code {"content":"a}b"}} 也不会被截断。</p>
     */
    private static String balancedArguments(String text) {
        int at = text.indexOf(DIRECTIVE);
        if (at < 0) {
            return null;
        }
        int open = text.indexOf('{', at);
        if (open < 0) {
            return "{}";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return null;
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

    private static boolean hasTool(ChatRequest request, String toolName) {
        return request.tools().stream().anyMatch(tool -> tool.name().equals(toolName));
    }

    private static boolean hasToolResult(ChatRequest request) {
        return request.messages().stream().anyMatch(message -> message.role() == Role.TOOL);
    }

    private static String allTextOf(ChatRequest request) {
        StringBuilder sb = new StringBuilder(request.systemText() == null ? "" : request.systemText());
        request.messages().forEach(message -> sb.append(message.allText()));
        return sb.toString();
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 3);
    }

    /** 便于示例打印"本次请求到底暴露了哪些工具"。 */
    public static List<String> toolNames(ChatRequest request) {
        return request.tools().stream().map(ToolSpec::name).toList();
    }

    @Override
    public String toString() {
        return "OnceForcedToolModel(" + delegate.name() + ")";
    }
}
