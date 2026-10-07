package com.benxin.exp.loops.loop;

import com.benxin.exp.loops.agent.LoopNames;
import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.CodexLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.loop.ReflexionLoop;
import com.benxin.llm.core.loop.StagedLoop;
import com.benxin.llm.core.sandbox.ApprovalPolicy;

/**
 * 用**现成的 Loop 类 + 不同构造参数**做出变体 Loop。
 *
 * <p>这是"一切可插拔"里最省事的一档：想换参数（阈值、修复轮次、子代理名、沙箱权限、压缩间隔），
 * 不必新写一个 Loop 类，把现成的包一层、挂上 {@code @LlmLoop} 给个新名字就行。
 * 本模块用它把四个"参数不同、行为不同"的变体露出来：</p>
 *
 * <table border="1">
 *   <caption>本模块额外注册的 Loop 变体</caption>
 *   <tr><th>注册名</th><th>被包装的实例</th><th>与内置版的差别</th></tr>
 *   <tr><td>{@code exp-reflexion-strict}</td><td>{@code new ReflexionLoop(3, 0.9)}</td>
 *       <td>内置版阈值 0.8；这里 0.9 更严</td></tr>
 *   <tr><td>{@code exp-staged-no-verify}</td><td>{@code new StagedLoop(false, 0)}</td>
 *       <td>关掉验收闸门，退化成"带汇总的 plan-execute"</td></tr>
 *   <tr><td>{@code exp-staged-lenient}</td><td>{@code new StagedLoop(true, 3)}</td>
 *       <td>验收照开，但允许修 3 轮（内置版只修 1 轮）</td></tr>
 *   <tr><td>{@code exp-codex-unrestricted}</td>
 *       <td>{@code new CodexLoop(true, true, ApprovalPolicy.NEVER, 3)}</td>
 *       <td>写权限与执行权限全开，用来对照"权限边界如实告知"</td></tr>
 * </table>
 *
 * <p><b>为什么是"包一层"而不是直接 {@code @Bean} 返回内置 Loop 实例</b>：
 * {@code LlmAutoConfiguration} 注册自定义 Loop 时用的名字是
 * 「{@code @LlmLoop.value()} 非空就取它，否则取 {@code loop.name()}」。
 * 直接返回 {@code new ReflexionLoop(...)} 的话注册名会退化成 {@code "reflexion"} ——
 * 于是它**顶掉**内置的那个，而不是以新名字并存。包一层之后注册名才真正可控。</p>
 */
public final class LoopVariants {

    private LoopVariants() {
    }

    /** {@code new ReflexionLoop(3, 0.9)}：阈值比内置版更严。 */
    @LlmLoop(LoopNames.REFLEXION_STRICT)
    public static class StrictReflexionLoop extends AbstractAgentLoop {

        private final ReflexionLoop delegate = new ReflexionLoop(3, 0.9);

        @Override
        public String name() {
            return LoopNames.REFLEXION_STRICT;
        }

        @Override
        public String description() {
            return "reflexion 的严格变体：" + delegate.description()
                    + "（阈值 0.9，最多 3 轮）";
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return delegate.run(ctx);
        }
    }

    /** {@code new StagedLoop(false, 0)}：关掉验收闸门。 */
    @LlmLoop(LoopNames.STAGED_NO_VERIFY)
    public static class NoVerifyStagedLoop extends AbstractAgentLoop {

        private final StagedLoop delegate = new StagedLoop(false, 0);

        @Override
        public String name() {
            return LoopNames.STAGED_NO_VERIFY;
        }

        @Override
        public String description() {
            return "staged 的无验收变体：" + delegate.description();
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return delegate.run(ctx);
        }
    }

    /** {@code new StagedLoop(true, 3)}：允许修 3 轮。 */
    @LlmLoop("exp-staged-lenient")
    public static class LenientStagedLoop extends AbstractAgentLoop {

        private final StagedLoop delegate = new StagedLoop(true, 3);

        @Override
        public String name() {
            return "exp-staged-lenient";
        }

        @Override
        public String description() {
            return "staged 的宽松变体：" + delegate.description();
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return delegate.run(ctx);
        }
    }

    /**
     * {@code new CodexLoop(true, true, ApprovalPolicy.NEVER, 3)}：写权限与执行权限全开。
     *
     * <p>存在的唯一理由是<b>做对照</b>：codex 的"如实告知权限边界"是通过兜底系统提示词下发的，
     * 而兜底提示词只在 {@code ctx.systemPrompt()} 为空时才注入。所以两个 Agent
     * （一个配了 systemPrompt、一个不配）打出来的第一条 user 消息，就是这条口径最直接的证据。</p>
     */
    @LlmLoop("exp-codex-unrestricted")
    public static class UnrestrictedCodexLoop extends AbstractAgentLoop {

        private final CodexLoop delegate = new CodexLoop(true, true, ApprovalPolicy.NEVER, 3);

        @Override
        public String name() {
            return "exp-codex-unrestricted";
        }

        @Override
        public String description() {
            return "codex 的放开权限变体（写=true，执行=true，审批=never）："
                    + delegate.description();
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return delegate.run(ctx);
        }
    }
}
