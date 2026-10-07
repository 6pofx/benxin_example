package com.benxin.exp.loops.config;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelCapabilities;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个只为 {@code todo_write} 而存在的模型装饰器：把被截断的工具参数补成合法 JSON。
 *
 * <p><b>它为什么必须存在</b>（这是实测出来的一条 {@link ScriptedLlmModel} 局限，
 * 也是本示例唯一一处"为了让链路真跑起来而加的适配层"）：</p>
 *
 * <p>脚本模型的强制工具语法是 {@code #tool:<名字> <一段 JSON>}，它用正则
 * {@code Pattern.compile("#tool:([A-Za-z0-9_\\-]+)\\s*(\\{.*?})?", DOTALL)} 抠参数。
 * 那个 {@code \\{.*?}} 是<b>惰性</b>的，于是在第一个 {@code }} 就收手：</p>
 *
 * <pre>{@code
 * 写进 prompt 的： #tool:todo_write {"todos":[{"content":"演示待办","status":"in_progress"}]}
 * 模型实际吐出的： {"todos":[{"content":"演示待办","status":"in_progress"}   ← 少一层右括号
 * }</pre>
 *
 * <p>结果是 {@code TodoWriteTool} 永远看到"参数 todos 必填"，
 * {@code benxin.todos} 这个运行属性<b>一辈子写不进去</b>；
 * 而 {@code #tool:} 分支排在脚本模型全部规则之前，连"换个规则把完整 JSON 交出去"这条退路都没有。</p>
 *
 * <p>{@code ScriptedLlmModel} 在 {@code exp-common} 里、是多个示例共用的离线设施 ——
 * 为一个示例去改它的公共正则，代价是别的示例行为一起漂移。所以这里选"在装饰器里补齐"：
 * {@link LlmModel} 接口的注释本来就写着"想让模型带上缓存、限流、路由、降级、录制回放，
 * 也只需再包一层装饰器"，内置的 {@code RetryingLlmModel} 就是这条路线的示范。</p>
 *
 * <p><b>为什么是"另一个 bean"而不是改掉 {@code mock}：</b>装饰器只修
 * {@code todo_write} 一个工具的参数，其它 Agent（codex / react / plan-execute）用不到它。
 * 让 {@code mock} 保持原样、再挂一个 {@link #BEAN_NAME}，示例里
 * "哪个模型被改造过"才是可对照、可解释的。</p>
 *
 * <p><b>它不做什么</b>：只改写 {@code todo_write} 这一个工具的参数，其它工具调用原样透传；
 * 也<b>不</b>凭空伪造工具调用 —— 只有脚本模型自己已经决定调 {@code todo_write} 时才动手。
 * 换句话说它修的是"参数被截断"，不是"模型不够聪明"。</p>
 */
public class TodoAwareModel implements LlmModel {
    /** 被补齐的工具名（= {@code BuiltinToolkit.TODO_WRITE}）。 */
    private static final String TODO_TOOL = "todo_write";

    /** 被截断的参数一定这样开头；用它把"要修的那一处"从别的调用里摘出来。 */
    private static final String TRUNCATED_PREFIX = "{\"todos\":[";

    /**
     * 这个装饰器的<b>注册名</b>，同时也是它的 Spring bean 名。
     *
     * <p><b>为什么不让 {@link #name()} 直接返回 {@code mock}：</b>{@code @LlmAgent(model = ...)}
     * 匹配的是<b>模型注册名</b>（{@code LlmAgentFactory} 走 {@code ModelRegistry.get(name)}），
     * 不是 bean 名。若这里也叫 {@code mock}，容器里的 {@code mock} bean 会与它同名，
     * {@code llmModelRegistry} 注册两次同一个名字 —— 谁最终胜出取决于
     * {@code ObjectProvider.orderedStream()} 的顺序，是一条不该出现在示例里的隐式约定。</p>
     *
     * <p>于是这里的取舍是"<b>多一个显式可见的模型名</b>"：注册表里会多出
     * {@code mock-todo-aware}，它的 {@code capabilities()} 与调用日志全部来自 {@code mock}。
     * 演示会把这个别名与它存在的原因一起打出来 —— 藏起来反而更糟。</p>
     */
    static final String NAME = "mock-todo-aware";

    /** Spring bean 名与注册名保持一致，避免"两套名字"这种最容易写错的约定。 */
    public static final String BEAN_NAME = NAME;

    /** 捕一对 {@code "content": "…"} 与紧跟其后的 {@code "status": "…"}（字段顺序由 schema 固定）。 */
    private static final java.util.regex.Pattern TODO_ITEM = java.util.regex.Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"status\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 允许的 status 取值（= {@code TodoWriteTool} 的三个常量）。 */
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private final LlmModel delegate;
    private final AtomicInteger repaired = new AtomicInteger();
    private final List<String> lastRepaired = new ArrayList<>();

    /**
     * @param delegate 真身。必须非空 —— 传 null 会让装饰器在第一次调用时抛 NPE，
     *                 而那时的堆栈指向 Loop 内部，极难回推到装配问题。
     */
    public TodoAwareModel(LlmModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("TodoAwareModel 需要一个非空的被包装模型");
        }
        this.delegate = delegate;
    }

    // ------------------------------------------------------------------
    // 注册表视角：能力与真身完全一致，只有名字不同
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelCapabilities capabilities() {
        // 不是复制一份，而是转交：包装层不该改变"这个模型支持什么"这件事
        return delegate.capabilities();
    }

    @Override
    public LlmModel unwrap() {
        // 装饰器的约定：给外面一个拿回真身的口子
        return delegate;
    }

    /** 真身的名字（{@code mock}）—— 演示里要把这层"别名"关系打出来。 */
    public String delegateName() {
        return delegate.name();
    }

    // ------------------------------------------------------------------
    // 唯一的改动点
    // ------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        return repair(delegate.chat(request));
    }

    @Override
    public void stream(ChatRequest request, LlmStreamHandler handler) {
        // 不能直接委托 delegate.stream(...)：那样 onToolCall 事件里带的是**没修过的**参数，
        // 流式与非流式就会给出两种不同的工具调用。统一改成"先修好，再按同一份结果回调"。
        handler.onStart();
        ChatResponse response = repair(delegate.chat(request));
        String text = response.text();
        if (text != null && !text.isEmpty()) {
            handler.onTextDelta(text);
        }
        if (response.message() != null) {
            response.message().toolUses().forEach(handler::onToolCall);
        }
        handler.onUsage(response.usage());
        handler.onComplete(response);
    }

    /**
     * 补参数：只碰 {@code todo_write}，且只在它的参数确实不可用时才动手，其余一律原样放行。
     */
    private ChatResponse repair(ChatResponse response) {
        if (response == null || response.message() == null) {
            return response;
        }
        List<ToolUsePart> uses = response.message().toolUses();
        if (uses.isEmpty()) {
            return response;
        }
        List<ToolUsePart> fixed = new ArrayList<>(uses.size());
        boolean changed = false;
        for (ToolUsePart use : uses) {
            String args = use.argumentsJson();
            String rebuilt = TODO_TOOL.equals(use.name()) ? rebuildTodos(args) : null;
            if (rebuilt != null) {
                fixed.add(new ToolUsePart(use.id(), use.name(), rebuilt));
                changed = true;
            } else {
                fixed.add(use);
            }
        }
        if (!changed) {
            return response;
        }
        repaired.incrementAndGet();
        // 把"修好的参数"留档：示例与测试靠它说明这条适配层到底做了什么，
        // 而不是只能从"待办清单里少了一项"这种后果反推。
        synchronized (lastRepaired) {
            lastRepaired.clear();
            for (ToolUsePart use : fixed) {
                lastRepaired.add(use.name() + "(" + use.argumentsJson() + ")");
            }
        }
        return ChatResponse.builder()
                .id(response.id())
                .model(response.model())
                .message(ChatMessage.assistant(response.message().text(), List.copyOf(fixed)))
                .finishReason(response.finishReason())
                .usage(response.usage())
                .raw(response.raw())
                .build();
    }

    /**
     * 把 {@code todo_write} 的参数补成一份可用的 JSON；无法/无需修补时返回 {@code null}。
     *
     * <p><b>它修的是什么</b>：{@code ScriptedLlmModel} 的强制工具正则是
     * {@code "#tool:(名字)\\s*(\\{.*?})?"} —— 注意末尾那个 {@code }} 是<b>普通字符</b>、
     * 不是量词，所以整个捕获必须<b>以一个 {@code }} 收尾</b>，而 {@code .*?} 又是惰性的。
     * 两者一夹，{@code #tool:} 能带出去的内容在遇到第一个 {@code }} 时就结束了：</p>
     *
     * <pre>{@code
     * 输入： #tool:todo_write {"todos":[{"content":"A","status":"in_progress"}]}
     * 捕获： {"todos":[{"content":"A","status":"in_progress"}    ← 少一层 ]
     * }</pre>
     *
     * <p>少的是括号，所以"补齐"是等价变换。这条链路上真正危险的是<b>多对象形态</b>：
     * {@code {"todos":[{A},{B}]}} 会被截成 {@code {"todos":[{A}} ——
     * {@code {B}} 连内容都没进字符串，补括号只会补出"只有 A 一项"的合法 JSON，
     * 工具不报错、属性也写进去了，唯一线索是"清单里少了一项"。
     * 因此本模块的约定是<b>一次只写一项</b>（见 {@code TODO_WRITE_INPUT} 的注释），
     * 这里的重建逻辑只负责把落下来的内容忠实拼回去，不假装能还原丢掉的部分。</p>
     *
     * <p><b>判据是自校验的，而不是猜形状</b>：只在参数"解析不了、或解析出来
     * {@code todos} 不是非空数组"时才重建。合法调用一律原样透传 ——
     * 装饰器只在确实坏掉时动手，行为才可预测。</p>
     *
     * <p>返回 {@code null} 表示<b>不需要改</b>（不是 {@code todo_write}、本来就能用、
     * 或者一项都没捞到而不该伪造空清单）。</p>
     */
    private static String rebuildTodos(String argumentsJson) {
        if (argumentsJson == null) {
            return null;
        }
        String text = argumentsJson.trim();
        if (!text.startsWith(TRUNCATED_PREFIX)) {
            return null;
        }
        if (isUsableTodoArgs(text)) {
            return null;
        }
        String rebuilt = "{\"todos\":[" + String.join(",", extractItems(text)) + "]}";
        // 一项都没捞到就不要伪造空清单：那会让"模型什么都没做"看起来像成功。
        // 保持原样让 TodoWriteTool 自己报错，它的错误信息比我们的猜测更有用。
        return extractItems(text).isEmpty() ? null : rebuilt;
    }

    /** 参数能否直接用：能解析、且 {@code todos} 是一个非空数组。 */
    private static boolean isUsableTodoArgs(String text) {
        try {
            JsonNode todos = Json.parse(text).path("todos");
            return todos.isArray() && !todos.isEmpty();
        } catch (RuntimeException e) {
            // 解析失败就是"坏了"，重建是唯一出路 —— 这里刻意不区分异常类型：
            // 无论是语法错还是结构错，处理方式都一样。
            return false;
        }
    }

    /**
     * 把每个 {@code "content":"…","status":"…"} 成对捞出来，重建每一项。
     *
     * <p>成对匹配（而不是分别捞 content 与 status 再按下标配对）是刻意的：
     * 分开捞时，只要有一项内容在截断中丢掉了，两个列表就会错位，
     * 于是"内容 A 配上了状态 B"—— 结果是一份<b>看着完全正常</b>的错误清单。
     * 成对匹配对不上就整项不产出，宁可让 {@code TodoWriteTool} 报错，也不交出串味的清单。</p>
     */
    private static List<String> extractItems(String text) {
        java.util.regex.Matcher matcher = TODO_ITEM.matcher(text);
        List<String> items = new ArrayList<>();
        while (matcher.find()) {
            String status = matcher.group(2).trim();
            if (!STATUSES.contains(status)) {
                // 非法 status 的项直接丢：递过去也只会被 TodoWriteTool 判错，
                // 不如只交回确定可用的部分 —— 少一项也有明确的成功结果可读。
                continue;
            }
            items.add("{\"content\":\"" + matcher.group(1) + "\",\"status\":\"" + status + "\"}");
        }
        return items;
    }

    /** {@code opens} 比 {@code closes} 多几个；已经多出来（负数）时返回 0。 */
    private static int missing(String text, char opens, char closes) {
        return Math.max(0, count(text, opens) - count(text, closes));
    }

    private static int count(String text, char ch) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }

    /** 被补齐过多少次 —— 演示与测试都靠它证明"这条链路真的走了装饰器"。 */
    public int repairedCalls() {
        return repaired.get();
    }

    /** 最近一次补齐后的完整参数（{@code 工具名(参数)} 形式），供演示打印。 */
    public List<String> lastRepairedCalls() {
        synchronized (lastRepaired) {
            return List.copyOf(lastRepaired);
        }
    }

    public void resetRepairCount() {
        repaired.set(0);
        synchronized (lastRepaired) {
            lastRepaired.clear();
        }
    }
}
