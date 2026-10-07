package com.benxin.exp.context;

import com.benxin.exp.context.config.ContextPipelineConfiguration;
import com.benxin.exp.context.context.Exp06ContextManager;
import com.benxin.exp.context.context.HardLimitCompactor;
import com.benxin.exp.context.demo.DemoText;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SlidingWindowCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.ToolUsePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ContextManager} 的断言：自定义实现如何接管"系统提示词放哪、历史怎么裁剪"，
 * 以及 {@code DefaultContextManager} 三个构造参数的真实语义。
 *
 * <p>所有结论都落在"真正下发的消息序列"上 —— 那是唯一有意义的口径，
 * {@code ContextManager} 的返回值就是它。</p>
 */
@DisplayName("示例 06 · ContextManager")
class ContextManagerTest extends AbstractContextMemoryTest {

    private static final String SYSTEM = "（演示用系统提示词）";

    @Test
    @DisplayName("容器里的 ContextManager 就是自定义实现，Agent 用的也是它")
    void containerContextManagerIsTheCustomImplementation() {
        assertThat(contextManager).isInstanceOf(Exp06ContextManager.class);
        assertThat(contextManager.historyWindow())
                .isEqualTo(ContextPipelineConfiguration.HISTORY_WINDOW);
        assertThat(contextManager.threshold()).isEqualTo(ContextPipelineConfiguration.THRESHOLD);
        assertThat(contextManager.keepRecent()).isEqualTo(ContextPipelineConfiguration.KEEP_RECENT);
        assertThat(contextManager.reservedOutputTokens())
                .isEqualTo(ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS);
        assertThat(agent("memoryAssistant")).isInstanceOf(com.benxin.llm.core.agent.DefaultAgent.class);
        assertThat(((com.benxin.llm.core.agent.DefaultAgent) agent("memoryAssistant")).contextManager())
                .isSameAs(contextManager);
    }

    @Test
    @DisplayName("自定义 ContextManager 接管系统提示词：下发的第一条带来源标记")
    void systemPromptIsInjectedByTheCustomManager() {
        memoryAssistant.chat("ctx-sys", "你好");

        ChatRequest request = mockModel.lastRequest();
        assertThat(systemMessages(request)).hasSize(1);
        assertThat(systemMessages(request).get(0).text())
                .startsWith(Exp06ContextManager.PROVENANCE)
                .contains("会话记忆");   // 注解里的提示词仍然在后面
        // 默认实现不会加这段标记：同一份参数喂给它，结果不同
        List<ChatMessage> plain = new DefaultContextManager(contextCompactor, tokenEstimator, 0.8, 4, 4096)
                .prepare(agent("memoryAssistant").spec().systemPrompt(),
                        agent("memoryAssistant").history("ctx-sys"), mockModel);
        assertThat(plain.get(0).text()).doesNotContain(Exp06ContextManager.PROVENANCE);
    }

    @Test
    @DisplayName("窗口裁剪只改下发的副本：记忆里 12 条，这一次只发 9 条")
    void windowTrimsTheRequestButNeverTheMemory() {
        for (int i = 0; i < 6; i++) {
            memoryAssistant.chat("ctx-window", i % 2 == 0 ? "你好" : "第二轮");
        }
        Agent agent = agent("memoryAssistant");

        assertThat(agent.history("ctx-window")).hasSize(12);
        // 窗口 8 条 + 1 条系统提示词
        assertThat(mockModel.lastRequest().messages()).hasSize(9);
        assertThat(contextManager.lastPreparedSize()).isEqualTo(9);
        assertThat(contextManager.trimmedMessages()).isPositive();
        // 被裁掉的消息仍然在记忆里，下一轮还会被装载
        assertThat(agent.history("ctx-window")).hasSize(12);

        // 窗口是"最后 8 条"，而在跑这一轮时手里的列表是「记忆 10 条 + 本轮输入 1 条」= 11 条，
        // 因此切掉的是最早 3 条 —— 第 3 条正是第 2 轮的 assistant 回答。
        // （这里刻意不写死 "你好"：窗口边界取决于"本轮输入也算一条历史"，
        //   而这正是最容易把它想成"从记忆里取最后 8 条"的地方。
        //   末尾那条是本轮刚生成的回答，它不在这一轮的请求里，所以上界要减 1。）
        List<ChatMessage> history = agent.history("ctx-window");
        List<ChatMessage> expectedWindow = history.subList(3, history.size() - 1);
        assertThat(expectedWindow).hasSize(8);
        assertThat(nonSystem(mockModel.lastRequest())).containsExactlyElementsOf(expectedWindow);
        assertThat(nonSystem(mockModel.lastRequest()).get(0).text()).isEqualTo(history.get(3).text());
    }

    @Test
    @DisplayName("窗口边界落在工具结果上时会向前补齐，不产生孤立的 tool_result")
    void windowNeverStartsAtAnOrphanToolResult() {
        List<ChatMessage> history = new ArrayList<>(List.of(
                ChatMessage.user("u0"),
                ChatMessage.assistant("", List.of(new ToolUsePart("call_1", "read_document", "{}"))),
                ChatMessage.toolResult("call_1", "read_document", "结果", false),
                ChatMessage.user("u3"),
                ChatMessage.assistant("a4"),
                ChatMessage.user("u5"),
                ChatMessage.assistant("a6"),
                ChatMessage.user("u7"),
                ChatMessage.assistant("a8"),
                ChatMessage.user("u9")));
        // 窗口 8 → 天然边界落在 index 2（那条 tool_result）；往前补一位才算合法
        assertThat(history.get(2).role()).isEqualTo(Role.TOOL);

        List<ChatMessage> prepared = contextManager.prepare(SYSTEM, history, mockModel);

        assertThat(prepared).hasSize(10);   // 1 系统 + 9（补齐后的窗口）
        List<ChatMessage> conversation = prepared.stream().filter(m -> m.role() != Role.SYSTEM).toList();
        assertThat(conversation.get(0).role()).isNotEqualTo(Role.TOOL);
        assertThat(conversation.get(0).hasToolUse()).isTrue();
    }

    @Test
    @DisplayName("prepare 会把历史里的 SYSTEM 消息丢掉，只保留参数传入的那一条并置顶")
    void systemMessagesInHistoryAreDropped() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("历史里的旧系统提示词"),
                ChatMessage.user("你好"));

        List<ChatMessage> prepared = contextManager.prepare(SYSTEM, history, mockModel);

        assertThat(prepared).hasSize(2);
        assertThat(prepared.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(prepared.get(0).text()).startsWith(Exp06ContextManager.PROVENANCE);
        assertThat(prepared).noneSatisfy(m -> assertThat(m.text()).isEqualTo("历史里的旧系统提示词"));
    }

    @Test
    @DisplayName("threshold 是「占模型窗口的比例」：0.2 压缩、0.95 不压缩")
    void thresholdControlsWhetherCompactionHappens() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(ChatMessage.user(DemoText.ascii(900, "threshold" + i)));
        }

        List<ChatMessage> strict = manager(new SlidingWindowCompactor(tokenEstimator, 0.2, 4096),
                tokenEstimator, 0.2, 1, 4096).prepare(SYSTEM, history, tinyModel);
        List<ChatMessage> loose = manager(new SlidingWindowCompactor(tokenEstimator, 0.95, 4096),
                tokenEstimator, 0.95, 1, 4096).prepare(SYSTEM, history, tinyModel);

        // tiny 窗口 6000、预留输出 4096 → 输入预算 1904；这条历史的估算在 380 与 1808 之间。
        // keepRecent 取 1：预算紧到"连一条都放不下"时才能观察到压缩真的丢消息
        // （keepRecent 是下限，取 4 而历史只有 3 条时压缩器不允许丢任何一条）。
        assertThat(strict).anySatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        assertThat(strict.size()).isLessThan(loose.size());
        assertThat(loose).noneSatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        assertThat(loose).hasSize(1 + history.size());    // 1 系统 + 3 历史，一条不丢
    }

    @Test
    @DisplayName("reservedOutputTokens 直接吃掉输入预算：预留等于窗口时压缩完全不触发")
    void reservedOutputTokensCanRemoveTheWholeBudget() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(ChatMessage.user(DemoText.ascii(900, "reserved" + i)));
        }

        // 预算 = 窗口 6000 - 预留 6000 = 0 → prepare 里的 budget > 0 判定直接跳过压缩
        List<ChatMessage> prepared = manager(new SlidingWindowCompactor(tokenEstimator, 0.8, 6000),
                tokenEstimator, 0.8, 4, 6000).prepare(SYSTEM, history, tinyModel);

        assertThat(prepared).hasSize(4);
        assertThat(prepared).noneSatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
    }

    @Test
    @DisplayName("keepRecent 是「上限」不是「下限」：压缩不一定留下这么多条")
    void keepRecentIsAnUpperBound() {
        // 6 条 600 字符的 ASCII 历史，用本示例的估算器：每条 12 + 300 + 8 = 320 token，合计 1920；
        // tiny 窗口 6000 − 预留输出 4096 = 1904 输入预算，触发线 0.8 × 1904 = 1523 → 必然压缩。
        List<ChatMessage> history = asciiMessages(6, 600, "keep");

        // 用"照着 keepRecent 硬砍"的参考实现来观察上限语义（它不掺任何 token 预算判断）：
        List<ChatMessage> keepEight = manager(new HardLimitCompactor(), tokenEstimator, 0.8, 8, 4096)
                .prepare(SYSTEM, history, tinyModel);
        List<ChatMessage> keepTwo = manager(new HardLimitCompactor(), tokenEstimator, 0.8, 2, 4096)
                .prepare(SYSTEM, history, tinyModel);

        // keepRecent = 8 比历史本身还长 → 一条都不用丢，只有系统提示词，没有占位提示
        assertThat(keepEight).hasSize(1 + history.size());
        assertThat(keepEight).noneSatisfy(m -> assertThat(m.text()).contains(HardLimitCompactor.MARKER));
        // keepRecent = 2 → 系统提示词 + 占位提示 + 最近 2 条
        assertThat(keepTwo).hasSize(1 + 1 + 2);
        assertThat(keepTwo.get(1).text()).startsWith(HardLimitCompactor.MARKER);

        // 也就是说：keepRecent 表达的是"最多留几条"，不是"至少留几条"；
        // 真正能留几条由压缩器自己的预算/策略决定（滑动窗口会留得更少，见下一个用例）。
        assertThat(keepTwo.size() - 2).isEqualTo(2);
        assertThat(keepTwo.size() - 2).isLessThan(8);
    }

    @Test
    @DisplayName("keepRecent 是「下限」：压缩后至少留下最近这么多条，预算宽裕时还会多留")
    void keepRecentIsALowerBound() {
        // 6 条 600 字符的 ASCII 历史，用本示例的估算器：每条 12 + 300 + 8 = 320 token，合计 1920；
        // tiny 窗口 6000 − 预留输出 4096 = 1904 输入预算，触发线 0.8 × 1904 = 1523 → 必然压缩。
        List<ChatMessage> history = asciiMessages(6, 600, "keep");

        List<ChatMessage> keepEight = manager(new SlidingWindowCompactor(tokenEstimator, 0.8, 4096),
                tokenEstimator, 0.8, 8, 4096).prepare(SYSTEM, history, tinyModel);
        List<ChatMessage> keepTwo = manager(new SlidingWindowCompactor(tokenEstimator, 0.8, 4096),
                tokenEstimator, 0.8, 2, 4096).prepare(SYSTEM, history, tinyModel);

        // keepRecent = 8 比历史本身还长 → 一条都不能丢（下限说了算），也就没有占位提示
        assertThat(keepEight).hasSize(1 + history.size());
        assertThat(keepEight).noneSatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));

        // keepRecent = 2 → 至少留 2 条；预算还宽裕时按预算多留（上限由体积决定）
        assertThat(keepTwo).anySatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        long kept = keepTwo.stream().filter(history::contains).count();
        assertThat(kept).isGreaterThanOrEqualTo(2).isLessThan(history.size());
        // 留下的必须是"最近的"那些：尾部对齐
        assertThat(keepTwo.get(keepTwo.size() - 1).text()).isEqualTo(history.get(5).text());
        // 修复前两种 keepRecent 的结果完全相同（都只剩最后 1 条，keepRecent 是死参数）
        assertThat(keepEight).isNotEqualTo(keepTwo);
    }

    @Test
    @DisplayName("DefaultContextManager 构造参数会被夹取：threshold<=0 → 0.8、reserved<0 → 0、keepRecent<=0 → 1")
    void constructorClampsItsArguments() {
        // ① threshold <= 0 会退回 0.8：3 条 600 字符 = 960 + 系统 30 = 990 token，
        //    低于 0.8 的触发线 1523，但远高于 -1 的触发线（-1904）→ 不压缩正好证明夹取发生了
        List<ChatMessage> shortHistory = asciiMessages(3, 600, "short");
        List<ChatMessage> negativeThreshold = manager(new HardLimitCompactor(), tokenEstimator, -1, 2, 4096)
                .prepare(SYSTEM, shortHistory, tinyModel);
        assertThat(negativeThreshold).hasSize(1 + shortHistory.size());
        assertThat(negativeThreshold).noneSatisfy(m -> assertThat(m.text()).contains(HardLimitCompactor.MARKER));

        // ② reservedOutputTokens < 0 会夹成 0：预算 6000（而不是 6100），触发线 4800。
        //    15 条 = 4800 + 系统 30 = 4830 → 越线压缩；若不夹取则触发线是 4880，就不会压缩
        List<ChatMessage> longHistory = asciiMessages(15, 600, "long");
        List<ChatMessage> negativeReserved = manager(new HardLimitCompactor(), tokenEstimator, 0.8, 2, -100)
                .prepare(SYSTEM, longHistory, tinyModel);
        assertThat(negativeReserved).hasSize(1 + 1 + 2);

        // ③ keepRecent <= 0 会夹成 1：与显式写 1 的结果完全一致
        List<ChatMessage> zeroKeep = manager(new HardLimitCompactor(), tokenEstimator, 0.8, 0, 4096)
                .prepare(SYSTEM, asciiMessages(6, 600, "zero"), tinyModel);
        List<ChatMessage> oneKeep = manager(new HardLimitCompactor(), tokenEstimator, 0.8, 1, 4096)
                .prepare(SYSTEM, asciiMessages(6, 600, "zero"), tinyModel);
        assertThat(zeroKeep).hasSize(1 + 1 + 1);
        assertThat(zeroKeep).isEqualTo(oneKeep);
    }

    @Test
    @DisplayName("压缩器 / 估算器传 null 不会 NPE：分别回落成 none() 与 TokenEstimator.DEFAULT")
    void nullCollaboratorsFallBackToDefaults() {
        List<ChatMessage> history = asciiMessages(9, 600, "null");

        // ① 估算器为 null → 用 DEFAULT（4 字符 1 token）：9 条 × 162 + 系统 22 = 1480，
        //    低于触发线 1523 → 不压缩。若误用了本示例的估算器，这里是 2910，一定会压缩。
        List<ChatMessage> byDefaultEstimator = new DefaultContextManager(
                new HardLimitCompactor(), null, 0.8, 2, 4096).prepare(SYSTEM, history, tinyModel);
        assertThat(byDefaultEstimator).hasSize(1 + history.size());

        // ② 压缩器为 null → 用 none()：这份历史在本示例的估算器下是 2910 > 1523 本该压缩，
        //    结果却一条不丢 —— 证明回落的是"什么都不做"的那个实现
        List<ChatMessage> byNoneCompactor = new DefaultContextManager(
                null, tokenEstimator, 0.8, 2, 4096).prepare(SYSTEM, history, tinyModel);
        assertThat(byNoneCompactor).hasSize(1 + history.size());
        assertThat(byNoneCompactor.get(0).role()).isEqualTo(Role.SYSTEM);
    }

    @Test
    @DisplayName("afterStep 每步回调一次：一步工具调用 = 2 次 prepare + 2 次 afterStep")
    void afterStepIsCalledOncePerModelStep() {
        contextManager.resetStats();
        agent("memoryAssistant").call("请读一下这份文档", "ctx-steps");

        assertThat(contextManager.prepareCount()).isEqualTo(2);
        assertThat(contextManager.afterStepCount()).isEqualTo(2);
        // afterStep 收到的是完整历史：最后一步之后是 user + assistant(tool_use) + tool
        assertThat(contextManager.lastEstimatedTokens()).isPositive();
    }

    @Test
    @DisplayName("自定义管理器与默认管理器的差别是真实存在的：同一份参数，下发条数不同")
    void customManagerDiffersFromDefaultManager() {
        for (int i = 0; i < 6; i++) {
            memoryAssistant.chat("ctx-diff", i % 2 == 0 ? "你好" : "第二轮");
        }
        List<ChatMessage> history = agent("memoryAssistant").history("ctx-diff");
        String systemPrompt = agent("memoryAssistant").spec().systemPrompt();

        List<ChatMessage> mine = contextManager.prepare(systemPrompt, history, mockModel);
        List<ChatMessage> theirs = manager(contextCompactor, tokenEstimator, 0.8, 4, 4096)
                .prepare(systemPrompt, history, mockModel);

        assertThat(mine).hasSize(9);        // 窗口 8 + 系统
        assertThat(theirs).hasSize(13);     // 12 条历史 + 系统
        assertThat(mine.get(0).text()).startsWith(Exp06ContextManager.PROVENANCE);
        assertThat(theirs.get(0).text()).doesNotContain(Exp06ContextManager.PROVENANCE);
    }

    @Test
    @DisplayName("ContextManager 是接口：自定义实现只需要 prepare 与（可选的）afterStep")
    void contextManagerIsATwoMethodInterface() {
        ContextManager lambda = (systemPrompt, history, model) -> {
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null) {
                messages.add(ChatMessage.system(systemPrompt));
            }
            messages.addAll(history);
            return messages;
        };

        List<ChatMessage> prepared = lambda.prepare("S", List.of(ChatMessage.user("你好")), mockModel);
        assertThat(prepared).hasSize(2);
        // afterStep 是默认方法：不实现也能编译（这正是它容易被忽略的原因）
        lambda.afterStep(List.of(), mockModel);
    }

    // ------------------------------------------------------------------

    private static DefaultContextManager manager(ContextCompactor compactor,
                                                 com.benxin.llm.core.util.TokenEstimator estimator,
                                                 double threshold, int keepRecent, int reserved) {
        return new DefaultContextManager(compactor, estimator, threshold, keepRecent, reserved);
    }

    /**
     * 造 {@code count} 条长度精确可控的 ASCII 历史。
     *
     * <p>压缩/裁剪的断言必须建立在"确定的体积"上：本示例的估算器对 ASCII 是
     * 2 字符 1 token、每条消息再加 12 + 8 的固定开销，于是 600 字符的消息恒等于 320 token，
     * 触发线是 1523 —— 算得清才断言得住。</p>
     */
    private static List<ChatMessage> asciiMessages(int count, int chars, String seed) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(ChatMessage.user(DemoText.ascii(chars, seed + i)));
        }
        return history;
    }
}
