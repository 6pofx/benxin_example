package com.benxin.exp.context;

import com.benxin.exp.context.context.Exp06TokenEstimator;
import com.benxin.exp.context.context.HardLimitCompactor;
import com.benxin.exp.context.demo.DemoText;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SummarizingCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TokenEstimator} 的断言：默认实现的实际取值，以及"自定义实现被 ContextManager 采用"。
 *
 * <p>"被采用"这件事必须用<b>行为分叉</b>来证：同一份历史、同一个压缩策略，
 * 只换估算器，压缩是否发生就不同了。只看 bean 注入成功是证明不了任何事的。</p>
 */
@DisplayName("示例 06 · TokenEstimator")
class TokenEstimatorTest extends AbstractContextMemoryTest {

    @Test
    @DisplayName("默认估算的实际取值：中文 1 字 1 token，其余 4 字符向上取整")
    void defaultEstimatorValues() {
        assertThat(TokenEstimator.DEFAULT.estimate((String) null)).isZero();
        assertThat(TokenEstimator.DEFAULT.estimate("")).isZero();
        // 纯中文就是"1 字 1 token"：2 个汉字 → 2。
        // （修复前是 cjk + max(1, other / 4)，other = 0 时也补 1，于是多算 1）
        assertThat(TokenEstimator.DEFAULT.estimate("你好")).isEqualTo(2);
        assertThat(TokenEstimator.DEFAULT.estimate("abcd")).isEqualTo(1);        // 4/4
        assertThat(TokenEstimator.DEFAULT.estimate("abc")).isEqualTo(1);         // (3+3)/4
        assertThat(TokenEstimator.DEFAULT.estimate(DemoText.ascii(900, "x"))).isEqualTo(225);
        assertThat(TokenEstimator.DEFAULT.estimate("你好abc")).isEqualTo(3);      // 2 + (3+3)/4
    }

    @Test
    @DisplayName("自定义估算的实际取值：中文 1 字 1 token，其余 2 字符 1 token")
    void customEstimatorValues() {
        assertThat(tokenEstimator.estimate((String) null)).isZero();
        assertThat(tokenEstimator.estimate("")).isZero();
        assertThat(tokenEstimator.estimate("你好")).isEqualTo(2);
        assertThat(tokenEstimator.estimate("abcd")).isEqualTo(2);                // (4+1)/2
        assertThat(tokenEstimator.estimate("abc")).isEqualTo(2);                 // (3+1)/2
        assertThat(tokenEstimator.estimate(DemoText.ascii(900, "x"))).isEqualTo(450);
        assertThat(tokenEstimator.estimate("你好abc")).isEqualTo(4);              // 2 + (3+1)/2
        // 同一段文本，两个实现差一倍：这就是"取值真的不同"的证据
        assertThat(tokenEstimator.estimate(DemoText.ascii(900, "x")))
                .isEqualTo(2 * TokenEstimator.DEFAULT.estimate(DemoText.ascii(900, "x")));
    }

    @Test
    @DisplayName("按消息估算会加上固定开销：自定义 12/条，默认 4/条 + 8/内容块")
    void messageLevelEstimateIncludesOverhead() {
        List<ChatMessage> one = List.of(ChatMessage.user("你好"));
        assertThat(tokenEstimator.estimate(one)).isEqualTo(12 + 2 + 8);
        // 默认实现同样是 4 + 8 的固定开销，estimate("你好") 现在是 2
        assertThat(TokenEstimator.DEFAULT.estimate(one)).isEqualTo(4 + 2 + 8);

        List<ChatMessage> withParts = List.of(
                ChatMessage.user(new TextPart("你好"), ImagePart.ofBase64("image/png", "AAAA")));
        assertThat(tokenEstimator.estimate(withParts)).isEqualTo(12 + 2 + 16);

        assertThat(tokenEstimator.estimate(List.of())).isZero();
        assertThat(tokenEstimator.estimate((List<ChatMessage>) null)).isZero();
    }

    @Test
    @DisplayName("容器里的 TokenEstimator 就是自定义实现，ContextManager 用的也是它")
    void customEstimatorBeanIsAdopted() {
        assertThat(tokenEstimator).isInstanceOf(Exp06TokenEstimator.class);
        assertThat(tokenEstimator).isNotSameAs(TokenEstimator.DEFAULT);
        assertThat(tokenEstimator.describe()).contains("2 字符 1 token");
        // ContextManager 拿到的就是容器里这一个（不是 interface 上的 DEFAULT 单例）
        assertThat(contextManager.estimator()).isSameAs(tokenEstimator);
    }

    @Test
    @DisplayName("换估算器会改变压缩阈值：默认估算不压缩，自定义估算触发压缩")
    void customEstimatorFlipsTheCompactionDecision() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(ChatMessage.user(DemoText.ascii(900, "msg" + i)));
        }
        String system = "（演示用系统提示词）";
        int triggerLine = (int) ((6_000 - 4_096) * 0.8);

        // 只差"用哪个估算器"这一个变量。压缩策略刻意用"照条数硬砍"的自定义实现：
        // 它的保留条数完全由 keepRecent 决定，不会掺进 SlidingWindowCompactor 自身的实现差异，
        // 于是这次对比里唯一的变量就是估算器。
        DefaultContextManager withDefault = new DefaultContextManager(
                new HardLimitCompactor(), TokenEstimator.DEFAULT, 0.8, 2, 4096);
        DefaultContextManager withCustom = new DefaultContextManager(
                new HardLimitCompactor(), tokenEstimator, 0.8, 2, 4096);

        int defaultTokens = TokenEstimator.DEFAULT.estimate(working(system, history));
        int customTokens = tokenEstimator.estimate(working(system, history));
        assertThat(defaultTokens).isLessThanOrEqualTo(triggerLine);
        assertThat(customTokens).isGreaterThan(triggerLine);

        List<ChatMessage> theirs = withDefault.prepare(system, history, tinyModel);
        List<ChatMessage> mine = withCustom.prepare(system, history, tinyModel);

        assertThat(theirs).hasSize(7);      // 1 系统 + 6 历史：没触发
        assertThat(mine).hasSize(4);        // 1 系统 + 1 占位 + 2 条近期：触发了
        assertThat(mine).anySatisfy(m -> assertThat(m.text()).contains(HardLimitCompactor.MARKER));
        assertThat(theirs).noneSatisfy(m -> assertThat(m.text()).contains(HardLimitCompactor.MARKER));
    }

    @Test
    @DisplayName("SummarizingCompactor 内部用的是构造时传入的估算器；无参构造用的是默认实现")
    void summarizingCompactorUsesItsOwnEstimator() {
        // tiny 窗口的输入预算是 (6000 − 4096) × 0.8 = 1523（插件 1.1.1 起压缩器与管理器统一口径）：
        //   默认估算 ≈ 1092（低于 1523）→ 无参构造的压缩器原样返回、一次模型都不调
        //   自定义估算 ≈ 2092（高于 1523）→ 会真的去摘要，并且摘要能留下来
        // 两条长消息刻意取 2000 字符，让"两个估算器"刚好落在 1523 的两侧 ——
        // 这个体量是算出来的：默认 1000/2 = 500、自定义 2000/2 = 1000（每条再加 12 的固定开销）。
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(DemoText.ascii(2000, "long-a")));
        history.add(ChatMessage.assistant(DemoText.ascii(2000, "long-b")));
        for (int i = 0; i < 4; i++) {
            history.add(ChatMessage.user(DemoText.ascii(20, "short" + i)));
        }
        assertThat(TokenEstimator.DEFAULT.estimate(history)).isLessThanOrEqualTo(1523);
        assertThat(tokenEstimator.estimate(history)).isGreaterThan(1523);

        tinyModel.reset();
        List<ChatMessage> byDefaultCtor = new SummarizingCompactor().compact(history, tinyModel, 4);
        assertThat(byDefaultCtor).isEqualTo(history);      // 原样返回
        assertThat(tinyModel.count()).isZero();            // 也就不会调用模型

        List<ChatMessage> byCustomEstimator = new SummarizingCompactor(tokenEstimator, 0.8, null)
                .compact(history, tinyModel, 4);
        assertThat(tinyModel.count()).isEqualTo(1);
        assertThat(byCustomEstimator).anySatisfy(m -> assertThat(m.text()).contains("[本心历史摘要]"));
    }

    private static List<ChatMessage> working(String system, List<ChatMessage> history) {
        List<ChatMessage> all = new ArrayList<>();
        all.add(ChatMessage.system(system));
        all.addAll(history);
        return all;
    }
}
