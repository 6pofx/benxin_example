package com.benxin.exp.loops.agent;

/**
 * 本模块全部声明式 Agent 的注册名。
 *
 * <p>集中一份常量而不是到处写字符串：演示与测试都要按名字取 Agent
 * （尤其是"清空历史"那一步，必须能枚举出所有名字），拼错一个字母的代价是
 * "某个 Agent 的历史没被清掉"，表现为测试之间互相串味 —— 那是很难查的一类失败。</p>
 */
public final class AgentNames {

    public static final String GENERIC = "genericAgent";
    public static final String REACT_NATIVE = "reactNativeAgent";
    public static final String REACT_TEXT = "reactTextAgent";
    public static final String CLAUDE_CODE = "claudeCodeAgent";
    public static final String CLAUDE_CODE_FLAKY = "claudeCodeFlakyAgent";
    public static final String CODEX = "codexAgent";
    public static final String CODEX_PATCH = "codexPatchAgent";
    public static final String PLAN_EXECUTE = "planExecuteAgent";
    public static final String REFLEXION = "reflexionAgent";
    public static final String STAGED_PASS = "stagedPassAgent";
    public static final String STAGED_FAIL = "stagedFailAgent";
    public static final String PARENT_TASK = "parentTaskAgent";
    public static final String PARENT_DELEGATE = "parentDelegateAgent";
    public static final String PARENT_NO_SUB = "parentNoSubAgent";
    public static final String BUDGET = "budgetProbeAgent";
    /**
     * 子代理的注册名。
     *
     * <p>取值引用 {@link com.benxin.exp.loops.config.OfflineModelConfiguration#CHILD_AGENT}：
     * 子代理的脚本模型需要知道"派给谁"，那份脚本住在配置类里，
     * 常量写两份迟早会漂移。</p>
     */
    public static final String CHILD = com.benxin.exp.loops.config.OfflineModelConfiguration.CHILD_AGENT;

    private AgentNames() {
    }
}
