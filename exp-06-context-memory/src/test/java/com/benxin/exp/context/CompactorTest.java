package com.benxin.exp.context;

import com.benxin.exp.context.config.ContextPipelineConfiguration;
import com.benxin.exp.context.config.OfflineModelConfiguration;
import com.benxin.exp.context.context.HardLimitCompactor;
import com.benxin.exp.context.demo.DemoText;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SlidingWindowCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三种 {@code ContextCompactor} 的断言。
 *
 * <p>核心结论只有两条：摘要式压缩<b>会额外花一次模型调用</b>（可以被 {@code RecordingModel} 数出来），
 * 另外两种不会；以及"压缩只发生在下发的副本上，记忆里的历史一条没少"。</p>
 */
@DisplayName("示例 06 · ContextCompactor")
class CompactorTest extends AbstractContextMemoryTest {

    @Test
    @DisplayName("SummarizingCompactor 会调一次模型做摘要，并把摘要以 user 消息插进下发序列")
    void summarizingCompactorSpendsOneExtraModelCall() {
        prime("compact-a", 4);
        tinyModel.reset();

        AgentResult result = agent("compactAssistant").call("继续", "compact-a");

        List<ChatRequest> recorded = tinyModel.requests();
        // 1 次压缩请求 + 1 次正常请求
        assertThat(recorded).hasSize(2);
        assertThat(result.steps()).isEqualTo(1);        // 压缩不算"步"
        assertThat(recorded.get(0).systemText()).contains(OfflineModelConfiguration.COMPRESSION_PROMPT_HINT);
        assertThat(recorded.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly(Role.SYSTEM, Role.USER);

        ChatRequest finalRequest = tinyModel.lastRequest();
        assertThat(finalRequest.messages()).anySatisfy(m ->
                assertThat(m.text()).startsWith("[本心历史摘要]"));
        // 摘要以 user 消息插入：让模型把它当"已知背景"，而不是"自己说过的话"
        assertThat(finalRequest.messages().stream()
                .filter(m -> m.text().startsWith("[本心历史摘要]"))
                .map(ChatMessage::role)).containsExactly(Role.USER);
    }

    @Test
    @DisplayName("没到阈值时摘要式压缩器一次模型都不会多调")
    void summarizingCompactorStaysQuietBelowThreshold() {
        compactAssistant.chat("compact-b", "你好");
        tinyModel.reset();

        compactAssistant.chat("compact-b", "继续");

        assertThat(tinyModel.count()).isEqualTo(1);
        assertThat(tinyModel.lastRequest().messages()).noneSatisfy(m ->
                assertThat(m.text()).contains("[本心历史摘要]"));
    }

    @Test
    @DisplayName("SlidingWindowCompactor 不调用模型，只在丢弃位置插一条说明")
    void slidingWindowCompactorNeverCallsTheModel() {
        List<ChatMessage> history = primedHistory("compact-c", 4);
        tinyModel.reset();

        List<ChatMessage> prepared = new DefaultContextManager(
                new SlidingWindowCompactor(tokenEstimator, ContextPipelineConfiguration.THRESHOLD,
                        ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS),
                tokenEstimator, ContextPipelineConfiguration.THRESHOLD,
                ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("S", history, tinyModel);

        // 纯丢弃：一次模型调用都不花（这正是它相比摘要式压缩器的全部价值）
        assertThat(tinyModel.count()).isZero();
        assertThat(prepared).anySatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩] 为控制上下文长度"));
        assertThat(prepared.size()).isLessThan(history.size() + 1);
        assertThat(prepared.get(0).role()).isEqualTo(Role.SYSTEM);
        // 实际只留下最后 1 条历史（插件 1.1.0 的实现缺陷，详见 ContextManagerTest 与 README「插件问题」）：
        // 期望是"系统 + 占位 + 4 条"，实测是"系统 + 占位 + 1 条"
        assertThat(prepared).hasSize(3);
    }

    @Test
    @DisplayName("ContextCompactor.none() 什么都不做：条数与历史完全一致")
    void noneCompactorKeepsEverything() {
        List<ChatMessage> history = primedHistory("compact-d", 4);

        List<ChatMessage> prepared = new DefaultContextManager(ContextCompactor.none(), tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, ContextPipelineConfiguration.KEEP_RECENT,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("S", history, tinyModel);

        assertThat(prepared).hasSize(history.size() + 1);
        assertThat(prepared.get(0).text()).isEqualTo("S");
        assertThat(prepared.subList(1, prepared.size())).isEqualTo(history);
    }

    @Test
    @DisplayName("自定义压缩器：只按条数硬砍，并且必须自己保留住系统提示词")
    void customCompactorKeepsSystemPromptFirst() {
        List<ChatMessage> history = primedHistory("compact-e", 4);

        List<ChatMessage> prepared = new DefaultContextManager(new HardLimitCompactor(), tokenEstimator,
                ContextPipelineConfiguration.THRESHOLD, 2,
                ContextPipelineConfiguration.RESERVED_OUTPUT_TOKENS)
                .prepare("S", history, tinyModel);

        assertThat(prepared.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(prepared.get(0).text()).isEqualTo("S");
        assertThat(prepared.get(1).text()).startsWith(HardLimitCompactor.MARKER);
        assertThat(prepared).hasSize(4);        // 系统 + 占位 + 最近 2 条
    }

    @Test
    @DisplayName("是否压缩由模型窗口决定：同一份历史，64000 窗口不压缩、6000 窗口压缩")
    void compactionTriggerIsDrivenByTheModelWindow() {
        // 6 轮 × 900 字符英文：默认估算（4 字符 1 token）下整份历史约 1693 token。
        // 这个体量是算出来的，不是拍出来的 —— 它必须跨过 tiny 的触发线 1523，
        // 又远低于 64000 窗口的 47923，两种模型才会给出相反的结果。
        List<ChatMessage> history = primedHistory("compact-f", 6);

        List<ChatMessage> wide = new DefaultContextManager(
                new SlidingWindowCompactor(TokenEstimator.DEFAULT, 0.8, 4096),
                TokenEstimator.DEFAULT, 0.8, 4, 4096).prepare("S", history, mockModel);
        List<ChatMessage> narrow = new DefaultContextManager(
                new SlidingWindowCompactor(TokenEstimator.DEFAULT, 0.8, 4096),
                TokenEstimator.DEFAULT, 0.8, 4, 4096).prepare("S", history, tinyModel);

        assertThat(wide).hasSize(history.size() + 1);
        assertThat(wide).noneSatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        assertThat(narrow).anySatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        assertThat(narrow.size()).isLessThan(wide.size());
    }

    @Test
    @DisplayName("压缩只改下发的副本：历史一条不少，仍然完整地留在记忆里")
    void compactionNeverTouchesTheMemory() {
        List<ChatMessage> history = primedHistory("compact-g", 4);
        int before = agent("compactAssistant").history("compact-g").size();

        new DefaultContextManager(ContextCompactor.none(), tokenEstimator, 0.8, 4, 4096)
                .prepare("S", history, tinyModel);
        new DefaultContextManager(
                new SlidingWindowCompactor(tokenEstimator, 0.8, 4096), tokenEstimator, 0.8, 4, 4096)
                .prepare("S", history, tinyModel);

        assertThat(agent("compactAssistant").history("compact-g")).hasSize(before);
        assertThat(before).isEqualTo(history.size());
    }

    @Test
    @DisplayName("摘要式压缩器在「最近消息本身就超预算」时退化为滑动窗口，不会挂掉")
    void summarizingCompactorFallsBackToSlidingWindow() {
        // keepRecent 比实际历史还大 → split = 0，摘要无从下手，只能走滑动窗口
        List<ChatMessage> history = primedHistory("compact-h", 4);
        tinyModel.reset();

        List<ChatMessage> prepared = contextCompactor.compact(history, tinyModel, 99);

        assertThat(prepared).isNotEmpty();
        assertThat(prepared).anySatisfy(m -> assertThat(m.text()).contains("[本心上下文压缩]"));
        // 退化为滑动窗口之后，摘要是不会发生的（也就没有额外模型调用）
        assertThat(tinyModel.count()).isZero();
    }

    @Test
    @DisplayName("claude-code Loop 的压缩发生在历史本身：工具结果被就地改写，条数不变")
    void claudeCodeLoopCompactsTheHistoryItself() {
        Agent claudeCode = agent("claudeCodeAssistant").toBuilder()
                .contextManager(new DefaultContextManager(ContextCompactor.none(), TokenEstimator.DEFAULT,
                        0.8, 4, 4096))
                .build();

        AgentResult result = claudeCode.call("#tool:read_document {} 请读文档并汇总", "cc-test");
        List<ChatMessage> stored = claudeCode.history("cc-test");

        assertThat(result.steps()).isEqualTo(14);
        assertThat(longDocTools.callCount()).isEqualTo(14);
        // 1 条用户输入 + 14 步 ×（assistant(tool_use) + tool(result)）= 29 条。
        // 压缩不删消息，只把较早的工具结果改写短，所以条数就是"步数 × 2 + 1"。
        assertThat(stored).hasSize(29);
        assertThat(result.messages()).hasSize(stored.size());  // 条数一样：就地改写而不是丢消息
        assertThat(stored).anySatisfy(m -> assertThat(m.text()).contains("…[已压缩]"));
        // 被压掉的工具结果只保留头部 600 字符 + 占位说明
        ChatMessage compacted = stored.stream()
                .filter(m -> m.text().contains("…[已压缩]")).findFirst().orElseThrow();
        assertThat(compacted.role()).isEqualTo(Role.TOOL);
        assertThat(compacted.text()).hasSizeLessThan(com.benxin.exp.context.tool.LongDocTools.DOC_CHARS);
        assertThat(compacted.toolCallId()).isNotBlank();
        // 第 12 步才做第一次压缩检查，最近 6 条工具结果不压 → 恰好改写前 6 条（14 步里已产生 12 条）
        assertThat(stored.stream().filter(m -> m.text().contains("…[已压缩]"))).hasSize(6);
    }

    // ------------------------------------------------------------------

    /** 用真实的多轮对话把会话撑到"必然触发压缩"的体量。 */
    private void prime(String session, int rounds) {
        for (int i = 0; i < rounds; i++) {
            compactAssistant.chat(session, DemoText.ascii(900, "round" + i));
        }
    }

    private List<ChatMessage> primedHistory(String session, int rounds) {
        prime(session, rounds);
        return new ArrayList<>(agent("compactAssistant").history(session));
    }
}
