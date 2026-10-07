package com.benxin.exp.loops.agent;

/**
 * 本模块额外注册的 Loop 名（都是"同一个 Loop 类的不同构造参数"，不是新写的类）。
 *
 * <p>为什么不直接在注解里写字符串：注册名一旦与某个常量漂移，
 * {@code LlmAgentFactory.specOf} 只会打一条 warn 然后**静默回退到默认 Loop** ——
 * 表现为"这个 Agent 的行为怎么不对"，所以集中一份常量更稳。</p>
 */
public final class LoopNames {

    /** {@code new ReflexionLoop(3, 0.9)}：阈值更严的 reflexion。 */
    public static final String REFLEXION_STRICT = "exp-reflexion-strict";

    /** {@code new StagedLoop(false, 0)}：关掉验收闸门。 */
    public static final String STAGED_NO_VERIFY = "exp-staged-no-verify";

    /** {@code new CodexLoop(true, true, ApprovalPolicy.NEVER, 3)}：权限全开，用于对照。 */
    public static final String CODEX_UNRESTRICTED = "exp-codex-unrestricted";

    private LoopNames() {
    }
}
