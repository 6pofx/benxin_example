package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.loops.agent.ReflexionAgent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.ReflexionLoop;
import com.benxin.llm.core.message.Role;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ⑥：{@code reflexion} —— 自省评分 → 低于阈值带着批评重做 → 直到用满轮次。
 *
 * <p>它最容易被猜错的不是流程，而是<b>阈值与解析失败的口径</b>：</p>
 * <ul>
 *   <li>阈值是 <b>0-1 的小数</b>，构造函数是 {@code ReflexionLoop(maxAttempts, threshold)}；
 *       写 {@code 90} 会被 {@code Math.min(1d, ...)} 夹成 1.0，于是永远达不到、每轮都重做；</li>
 *   <li>自省解析不出 {@code SCORE:} 时判为**达标**：没有分数也没有可用的批评，
 *       重试就是纯粹的空转烧 token。</li>
 * </ul>
 *
 * <p><b>实测到的收敛形态（与"第二轮就达标"的直觉不同）：</b>
 * 离线脚本模型只有一个 {@code reflexion(score, critique)} 配置项，
 * 也就是<b>每一轮自省都回同一个分数</b>（本模块取 0.85），而
 * {@code exp-reflexion-strict} 的达标线是 0.9 —— 永远够不着，
 * 于是跑满 3 轮后置 {@code benxin.reflexion.exhausted = true}。
 * "低于阈值 → 带批评重做"这两步是真的，只是终点落在 EXHAUSTED 而不是"达标"。
 * 想看"第二轮达标"需要模型两轮给出不同的分数，脚本模型做不到；
 * 反面做法是换一个阈值低于 0.85 的变体（{@code reflexion-lenient} 一类）。</p>
 */
@Component
public class ReflexionLoopDemo {

    private final ReflexionAgent reflexionAgent;

    public ReflexionLoopDemo(ReflexionAgent reflexionAgent) {
        this.reflexionAgent = reflexionAgent;
    }

    void run(Demo demo) {
        demo.section("reflexion —— 自省评分 → 低于阈值带着批评重做 → 用满轮次");

        AgentResult result = reflexionAgent.ask(
                "自评分 0.55，未达到 0.9：请写一份变更说明。");

        LoopReport.result(demo, result);
        demo.ok("SCORE_ATTRIBUTE = %s（最后一轮的自评分）",
                result.attributes().get(ReflexionLoop.SCORE_ATTRIBUTE));
        demo.ok("ATTEMPTS_ATTRIBUTE = %s（实际作答轮数）",
                result.attributes().get(ReflexionLoop.ATTEMPTS_ATTRIBUTE));
        demo.ok("EXHAUSTED_ATTRIBUTE 存在 = %s（存在 = 用满轮次仍未达标；键不存在才表示在阈值内收敛）",
                result.attributes().containsKey(ReflexionLoop.EXHAUSTED_ATTRIBUTE));

        demo.info("第一轮自省注入的提示词（REFLECTION_PROMPT，「严格按下面两行输出」）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().contains("严格按下面两行输出"))
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 180)));

        demo.info("低于阈值时注入的重做提示（含模型给出的批评）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().contains("低于达标线"))
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 220)));

        demo.ok("重做后的答案被采纳（模型两轮给了不同答案，最终文本来自第二轮）");
        demo.info("收敛形态：脚本模型每轮自省都回同一个分数（0.85），达标线 0.9 —— 所以"
                + "「重做」发生了、「达标」永远不会发生，终点是 EXHAUSTED。"
                + "要演示「第二轮达标」，得让模型两轮给出不同分数，或者换一个阈值更低的变体。");
        demo.info("阈值对照：本 Agent 绑的是 exp-reflexion-strict（new ReflexionLoop(3, 0.9)）；"
                + "内置 reflexion 是 new ReflexionLoop(3, 0.8)。");
    }
}
