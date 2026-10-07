package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.tool.CommonTools;
import com.benxin.exp.loops.agent.LoopNames;
import com.benxin.exp.loops.agent.StagedFailAgent;
import com.benxin.exp.loops.agent.StagedNoVerifyAgent;
import com.benxin.exp.loops.agent.StagedPassAgent;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.loop.StagedLoop;
import com.benxin.llm.core.message.Role;
import org.springframework.stereotype.Component;

/**
 * 内置 Loop ⑦：{@code staged} —— 规划 → 执行 → <b>验收</b> → 修复 → 汇总。
 *
 * <p>它与 {@code plan-execute} 的差别只有一条，但很关键：<b>多了一道独立验收闸门</b>。
 * "步骤都跑了"和"任务真的完成了"经常不是一回事 —— 模型可能漏掉一步、可能只做了一半。
 * 本演示把三个方向都跑一遍：</p>
 * <ol>
 *   <li>{@code verifyPass(true)}：一次通过，直接进 SYNTHESIZE；</li>
 *   <li>{@code verifyPass(false)}：验收不通过 → REPAIR → 再验收 → SYNTHESIZE。
 *       <b>实测：修复之后那次验收的结论仍然是 fail</b> —— 脚本模型的验收应答是固定的
 *       "结论：不通过"，它做不到"两次给出不同结论"。所以这条演示证明的是
 *       "验收闸门会拦住收尾、并强制走一轮修复"，而不是"修完就通过了"；
 *       {@code benxin.staged.repair-rounds = 1} 与历史里那两条"作为验收者"
 *       才是它真正钉住的东西。</li>
 *   <li>{@code new StagedLoop(false, 0)}：关掉验收闸门，退化成"带汇总的 plan-execute"。</li>
 * </ol>
 */
@Component
public class StagedLoopDemo {

    private final StagedPassAgent stagedPassAgent;
    private final StagedFailAgent stagedFailAgent;
    private final StagedNoVerifyAgent stagedNoVerifyAgent;
    private final CommonTools commonTools;

    public StagedLoopDemo(StagedPassAgent stagedPassAgent,
                          StagedFailAgent stagedFailAgent,
                          StagedNoVerifyAgent stagedNoVerifyAgent,
                          CommonTools commonTools) {
        this.stagedPassAgent = stagedPassAgent;
        this.stagedFailAgent = stagedFailAgent;
        this.stagedNoVerifyAgent = stagedNoVerifyAgent;
        this.commonTools = commonTools;
    }

    void run(Demo demo) {
        demo.section("staged ① 验收一次通过（PLAN → EXECUTE → VERIFY → SYNTHESIZE）");
        passPath(demo);
        demo.section("staged ② 验收不通过 → 修复 1 轮 → 再验收（多出 REPAIR 阶段）");
        repairPath(demo);
        demo.section("staged ③ new StagedLoop(false, 0)：关掉验收，退化成带汇总的 plan-execute");
        noVerifyPath(demo);
    }

    private void passPath(Demo demo) {
        commonTools.reset();
        AgentResult result = stagedPassAgent.ask("请把这次接口改动整理成一份可交付的说明。");
        LoopReport.result(demo, result);
        demo.ok("STAGE_ATTRIBUTE = %s（最后一个进入的阶段）",
                result.attributes().get(StagedLoop.STAGE_ATTRIBUTE));
        demo.ok("VERDICT_ATTRIBUTE = %s（pass）",
                result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE));
        demo.ok("REPAIR_ROUNDS_ATTRIBUTE 存在 = %s（键不存在 = 一次都没修）",
                result.attributes().containsKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE));
        stageTrail(demo, result);
    }

    private void repairPath(Demo demo) {
        commonTools.reset();
        AgentResult result = stagedFailAgent.ask("请写一份初版实现，并给出边界条件与下一步。");
        LoopReport.result(demo, result);
        demo.ok("VERDICT_ATTRIBUTE = %s（最后一次验收的结论）",
                result.attributes().get(StagedLoop.VERDICT_ATTRIBUTE));
        demo.ok("REPAIR_ROUNDS_ATTRIBUTE = %s（走过几轮修复）",
                result.attributes().get(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE));
        demo.info("注意 VERDICT 记的是**最后一次**验收的结论。本演示里它是 fail："
                + "脚本模型的验收应答是固定的「结论：不通过」（verifyPass=false），"
                + "两次验收因此给出同一个结论 —— 这条链路要证明的是"
                + "「闸门拦住了收尾并强制走了一轮修复」，而不是「修完就通过」。");
        demo.info("验收者给出的问题清单（被原样带进修复阶段的提示词）：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null
                        && m.text().startsWith("验收未通过"))
                .findFirst()
                .ifPresent(m -> demo.info("   %s", LoopReport.oneLine(m.text(), 220)));
        stageTrail(demo, result);
        demo.info("口径提醒：验收结论解析不出来时**判定为通过** —— 与 reflexion 同一取舍："
                + "把格式跑偏当成「再来一遍」的理由，只会把预算烧在反复验收上。");
    }

    private void noVerifyPath(Demo demo) {
        commonTools.reset();
        AgentResult result = stagedNoVerifyAgent.ask("请把这次配置外部化的改动写清楚。");
        LoopReport.result(demo, result);
        demo.ok("绑定的 Loop = %s，它一次验收请求都没发：VERDICT 存在 = %s，REPAIR_ROUNDS 存在 = %s",
                result.loopName(),
                result.attributes().containsKey(StagedLoop.VERDICT_ATTRIBUTE),
                result.attributes().containsKey(StagedLoop.REPAIR_ROUNDS_ATTRIBUTE));
        demo.info("所以它此时的行为就是「带汇总的 plan-execute」：计划 → 执行 → 汇总，没有闸门。");
        stageTrail(demo, result);
        demo.info("四个变体的注册名：%s", LoopNames.STAGED_NO_VERIFY + " / exp-staged-lenient（允许修 3 轮）");
    }

    /** 把历史里出现过的阶段提示词列出来，作为"阶段真的流转过"的证据。 */
    private void stageTrail(Demo demo, AgentResult result) {
        demo.info("历史里出现过的阶段提示词：");
        result.messages().stream()
                .filter(m -> m.role() == Role.USER && m.text() != null)
                .map(m -> stageOf(m.text()))
                .filter(stage -> stage != null)
                .distinct()
                .forEach(stage -> demo.info("   → %s", stage));
    }

    private static String stageOf(String text) {
        if (text.contains("制定一份可执行的编号计划")) {
            return "PLAN";
        }
        if (text.startsWith("这是你刚刚制定的计划")) {
            return "EXECUTE";
        }
        if (text.startsWith("已完成 ")) {
            return "EXECUTE（进度回显）";
        }
        if (text.contains("作为验收者")) {
            return "VERIFY";
        }
        if (text.startsWith("验收未通过")) {
            return "REPAIR";
        }
        if (text.startsWith("请综合前面各步骤的结果与验收结论")) {
            return "SYNTHESIZE";
        }
        return null;
    }
}
