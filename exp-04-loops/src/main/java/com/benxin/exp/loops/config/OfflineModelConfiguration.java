package com.benxin.exp.loops.config;

import com.benxin.exp.common.model.ScriptedLlmModel;
import com.benxin.llm.core.model.LlmModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 离线模型装配：本模块的**每个 Loop 配一个脚本模型**。
 *
 * <p>为什么不是"一个模型打天下"：8 个 Loop 的提示词特征、终止条件、分支完全不同，
 * 用一个模型把所有分支都塞进同一份规则里，演示会变得没法读（"这条规则是给谁用的？"）。
 * 一个 Loop 一个模型之后，"这个 Loop 到底问了模型什么"就写在它对应的那段脚本旁边的注释里。</p>
 *
 * <p>规则求值顺序见 {@link ScriptedLlmModel} 的类注释：{@code #tool:} → 使用者规则 →
 * Loop 内建应答（编号计划 / 验收者 / SCORE 自省）→ 文本协议 → 关键词挑工具 → 汇总。</p>
 *
 * <p><b>为什么每个 {@code @Bean} 都要写 {@code name = ...}：</b>本模块的模型全是
 * {@link ScriptedLlmModel} 的<b>不同实例</b>，Spring 的类型注入对它们完全无法区分。
 * 演示与测试里到处是 {@code @Qualifier(OfflineModelConfiguration.XXX)}，
 * 而 {@code @Qualifier} 匹配的是 <b>Spring bean 名</b>，不是 {@code ScriptedLlmModel.name()}
 * （后者只决定它在<b>本心模型注册表</b>里的注册名，由 {@code LlmAutoConfiguration.llmModelRegistry}
 * 读取，两者互不相干）。默认的 bean 名是方法名（{@code codexPatchModel}），
 * 于是 {@code @Qualifier("mock-codex-patch")} 会直接报
 * "No qualifying bean of type 'ScriptedLlmModel'"。
 * 把 bean 名显式对齐成模型名，注册名与 bean 名就是同一个字符串，少一层心智负担。</p>
 */
@Configuration
public class OfflineModelConfiguration {

    /** {@code llm.default-model} 指向它；也是未显式指定 {@code model} 的 Agent 用的那一个。 */
    public static final String GENERIC = "mock";
    public static final String REACT_TEXT = "mock-react-text";
    public static final String PLAN_EXECUTE = "mock-plan-execute";
    public static final String STAGED_PASS = "mock-staged-pass";
    public static final String STAGED_FAIL = "mock-staged-fail";
    public static final String STAGED_NO_VERIFY = "mock-staged-no-verify";
    public static final String REFLEXION = "mock-reflexion";
    public static final String SUB_AGENT = "mock-sub-agent";
    public static final String CODEX_PATCH = "mock-codex-patch";
    public static final String BUDGET = "mock-budget";

    /** 子代理父侧的提示词标记。 */
    public static final String PARENT_MARKER = "本模块的子代理父侧演示";
    /** 子代理子侧的提示词标记。 */
    public static final String CHILD_MARKER = "本模块的子代理子侧演示";
    /**
     * 子代理的注册名。
     *
     * <p>它住在配置类而不是 {@code AgentNames}，是因为 {@link SubAgentScriptModel}
     * 需要它来伪造"派给谁"这个参数，而 {@code AgentNames} 反过来要引用本类的模型名 ——
     * 常量放哪边都会形成一次环。<b>事实来源就在这里</b>，
     * {@code AgentNames.CHILD} 引用它，两处不会漂移。</p>
     */
    public static final String CHILD_AGENT = "childWeatherAgent";
    /** 子代理子侧脚本产出的那句话 —— 测试靠它证明"子代理真的跑完了"。 */
    public static final String CHILD_ANSWER = "【子代理结论】北京今天晴，气温 26 摄氏度。";

    /**
     * 交给 {@code claudeCodeAgent} 的模型名：{@link #GENERIC} 外面套一层
     * {@link TodoAwareModel}。
     *
     * <p>为什么要单独一个名字而不是直接换掉 {@code mock}：装饰器只修
     * {@code todo_write} 这一个工具的参数，其它 Agent（codex / react / plan-execute）
     * 用不到它，让它们继续吃裸的脚本模型，示例里"哪个模型被改造过"才是可对照的。
     * 别名在注册表里显式可见（演示会把它与原因一起打出来），比"悄悄换掉 mock"更诚实。</p>
     *
     * <p>取值直接引用 {@link TodoAwareModel#NAME}：模型名（{@code @LlmAgent(model = ...)} 匹配的）
     * 与 bean 名是同一个字符串，两者一旦分叉，就会出现"注解能解析到、注入却失败"这类怪问题。</p>
     */
    public static final String TODO_AWARE_BEAN = TodoAwareModel.NAME;

    /**
     * {@code todo_write} 的显式调用指令。
     *
     * <p>集中在这里有一个实际理由：它被演示与测试共用了好几处，
     * 而它同时又是"模型吐出的原始串必须被 {@link TodoAwareModel} 修补"的那份输入 ——
     * 抄成多份之后，改一处忘一处会让演示与测试悄悄跑在不同的输入上。</p>
     *
     * <p><b>为什么这里只有一项待办（实测口径，别改成两项）：</b>
     * {@code ScriptedLlmModel} 的强制工具正则是
     * {@code "#tool:(名字)\\s*(\\{.*?})?"}，末尾那个 {@code }} 是<b>普通字符</b>而不是量词，
     * 于是整个捕获必须<b>以一个 {@code }} 收尾</b>，而 {@code .*?} 又是惰性的。
     * 两者一夹，被 {@code #tool:} 带出去的内容在遇到第一个 {@code }} 时就结束了 ——
     * 想让它带两个对象，第二个对象会在截断中丢掉，而且丢得<b>毫无声响</b>。</p>
     *
     * <p>所以这份输入的约定是"一项一项地写"，{@link TodoAwareModel} 负责把缺的括号补齐。
     * 这不是偷懒：单对象形态下被截掉的确实只有括号，补括号是等价变换；
     * 多对象形态下丢的是内容，任何"补"都补不回来 —— 那才是真正会骗人的写法。
     * 待办清单的"多项能力"由 {@code TodoWriteTool} 自己的 schema 保证
     * （它把 {@code todos} 定义成数组），不靠这一条演示输入来证明。</p>
     */
    public static final String TODO_WRITE_INPUT =
            "#tool:todo_write {\"todos\":[{\"content\":\"用离线模型跑通 claude-code 的待办链路\","
                    + "\"status\":\"in_progress\"}]}";

    /** apply_patch 演示中，脚本模型吐出的补丁要新建的文件名。 */
    public static final String PATCH_DEMO_FILE = "codex-patch-demo.txt";

    /** 自省/验收类提示词的注入标记（{@code ClaudeCodeLoop} 的兜底提示词与它们无关，各 Loop 各自识别）。 */
    private static final String VERIFY_PROMPT_MARKER = "作为验收者";

    /**
     * 通用脚本：够跑通"工具闭环 + 编排类 Loop 的公共分支"。
     *
     * <p>它刻意不做任何 Loop 专属的脚本化 —— 谁想演示什么，就写自己的模型。</p>
     */
    @Bean(name = GENERIC)
    public ScriptedLlmModel genericModel() {
        return ScriptedLlmModel.builder(GENERIC)
                .fallback("（离线脚本化模型）我收到了你的请求：{input}")
                .build();
    }

    /**
     * {@link #GENERIC} 外面套一层 {@link TodoAwareModel}，专门给 {@code claudeCodeAgent} 用。
     *
     * <p>返回类型是 {@code LlmModel} 而不是 {@code ScriptedLlmModel}：容器里已经有十来个
     * {@code ScriptedLlmModel} bean，再声明一个同类型的会让
     * {@code @Qualifier(...) ScriptedLlmModel} 这类注入点多出一个候选。
     * 而"容器里的 {@code LlmModel} bean 也算一种模型来源"本来就是 starter 的既有通道
     * （{@code LlmAutoConfiguration.llmModelRegistry} 会把它们全部收进注册表）。</p>
     *
     * <p>{@code @Bean} 显式写 {@code name} 且与 {@link TodoAwareModel#NAME} 取同一个值：
     * 注册名与 bean 名一致，"这个模型叫什么"在这个模块里只有一个答案。</p>
     */
    @Bean(name = TodoAwareModel.BEAN_NAME)
    public TodoAwareModel todoAwareModel(@Qualifier(GENERIC) LlmModel genericModel) {
        return new TodoAwareModel(genericModel);
    }

    /**
     * {@code react} 的降级路径专用：模型**声明自己不支持 function calling**。
     *
     * <p>{@code toolCalling(false)} 是这条路径的开关：{@code ReActLoop.nativeToolCalling()}
     * 会因此返回 false，于是它改走文本协议 —— 先往历史里注入一段
     * {@code Action / Action Input / Final Answer} 的格式说明，再把模型回复当文本解析。</p>
     *
     * <p>脚本本身也照着文本协议写：第一轮吐 {@code Action:}，看到 {@code Observation:} 后吐
     * {@code Final Answer:}。这样"降级链路真的走通了"是能被断言的，而不是只看代码分支。</p>
     */
    @Bean(name = REACT_TEXT)
    public ScriptedLlmModel reactTextModel() {
        return ScriptedLlmModel.builder(REACT_TEXT)
                .toolCalling(false)
                .textProtocol("现在几点了", "get_current_time", "{}",
                        "（文本协议收尾）现在是北京时间 10:24。")
                .build();
    }

    /**
     * {@code plan-execute} 专用：规划提示里含"编号计划"，脚本模型的内建应答会回一段三行计划。
     *
     * <p>这就是"内建应答"存在的理由 —— 少了它，{@code PlanParser} 解析不出任何步骤，
     * Loop 会静默降级成普通 tool-calling 循环，示例就演示不到真正的"先规划后执行"。</p>
     */
    @Bean(name = PLAN_EXECUTE)
    public ScriptedLlmModel planExecuteModel() {
        return ScriptedLlmModel.builder(PLAN_EXECUTE)
                .plan("""
                        1. 明确本次演示的目标与验收口径
                        2. 调用工具取回真实数据
                        3. 汇总成一份可读的结论""")
                .build();
    }

    /**
     * {@code staged} 一次通过：验收提示里含"作为验收者"，内建应答回「结论：通过」。
     */
    @Bean(name = STAGED_PASS)
    public ScriptedLlmModel stagedPassModel() {
        return ScriptedLlmModel.builder(STAGED_PASS)
                .plan("""
                        1. 拆解公开接口的入参校验
                        2. 补上缺失的参数校验
                        3. 复核改动是否影响既有调用方""")
                .verifyPass(true)
                .rule(VERIFY_PROMPT_MARKER + "（结构化验收）", "结论：通过\n（已逐条对照任务要求，未发现遗漏。）")
                .build();
    }

    /**
     * {@code staged} 验收不通过：第一轮验收回「结论：不通过」并列出问题，
     * 进入 REPAIR 阶段后再验收一次 —— 这次通过，于是流程正常收尾而不是烧完修复轮次。
     */
    @Bean(name = STAGED_FAIL)
    public ScriptedLlmModel stagedFailModel() {
        return ScriptedLlmModel.builder(STAGED_FAIL)
                .plan("""
                        1. 写出初版实现
                        2. 补充边界条件处理
                        3. 给出可执行的下一步""")
                .rule("验收未通过", "已针对验收提出的问题逐条修正：补上了边界条件与可执行的下一步。")
                .rule("第 2 次验收", "结论：通过\n（修复后已满足任务要求。）")
                .rule(VERIFY_PROMPT_MARKER, "结论：不通过\n1. 缺少边界条件的处理说明\n2. 结论没有给出可执行的下一步")
                .build();
    }

    /**
     * {@code staged} 关闭验收闸门（{@code new StagedLoop(false, 0)}）：
     * 此时它应当退化成"带汇总的 plan-execute"，一次验收请求都不该发出。
     */
    @Bean(name = STAGED_NO_VERIFY)
    public ScriptedLlmModel stagedNoVerifyModel() {
        return ScriptedLlmModel.builder(STAGED_NO_VERIFY)
                .plan("""
                        1. 收集必要输入
                        2. 形成结论""")
                .build();
    }

    /**
     * {@code reflexion} 专用：低分 → 带批评重做 → 高分收敛。
     *
     * <p>脚本模型只有一个 {@code reflexion(score, critique)} 配置项，两轮自省会回同一个分数。
     * 因此"低于阈值→重做→达标"这条链路要靠**作答内容不同**来体现：
     * 第一轮命中普通规则给出初稿，第二轮命中"上一轮答案的自评分"这条规则给出修订稿。
     * 分数统一取 0.85，阈值取 1.5（{@code ReflexionLoop} 会把它夹到 1.0）—— 先低于、后达标。</p>
     */
    @Bean(name = REFLEXION)
    public ScriptedLlmModel reflexionModel() {
        return ScriptedLlmModel.builder(REFLEXION)
                .rule("请写一份变更说明", "（初稿）这次改动的要点是：把配置读取从硬编码改成外部化。")
                .rule("上一轮答案的自评分是",
                        "（修订稿）这次改动的要点是：把配置读取从硬编码改成外部化；"
                                + "影响范围为启动期；验证方式为跑一遍 mvn -o test。")
                .reflexion(0.85, "缺少影响范围与验证方式的说明，下一轮补上。")
                .build();
    }

    /**
     * 子代理父侧 + 子侧的共用模型。
     *
     * <p><b>它是本模块唯一一个不来自 {@link ScriptedLlmModel} 的模型</b>，
     * 原因写在 {@link SubAgentScriptModel} 的类注释里，一句话概括：
     * 脚本模型的求值顺序是"使用者规则 → … → 有工具结果就汇总"，
     * 于是无条件写一条父侧收尾规则会在第一轮就命中，派生永远发生不了；
     * 而加个"第一轮不说话"的开关又会被 {@code #tool:}（排在规则之前）抢走。
     * "先派生、后收尾"这件事用 {@code systemRule} 表达不出来，只能让模型自己看历史。</p>
     *
     * <p>返回类型是 {@link LlmModel}：它和别的模型一样由
     * {@code LlmAutoConfiguration.llmModelRegistry} 按 {@code name()} 收进注册表，
     * 因此 {@code @LlmAgent(model = OfflineModelConfiguration.SUB_AGENT)} 照旧生效。</p>
     */
    @Bean(name = SUB_AGENT)
    public LlmModel subAgentModel() {
        return new SubAgentScriptModel();
    }

    /**
     * {@code codex} 的 apply_patch 演示专用模型。
     *
     * <p>补丁必须是<b>工具调用</b>而不是普通文本 —— {@code CodexLoop} 只从
     * {@code ctx.executeTools(...)} 这一条路径执行工具，不会解析回复里的补丁。
     * 原因与取舍写在 {@link CodexPatchScriptModel} 的类注释里。</p>
     */
    @Bean(name = CODEX_PATCH)
    public LlmModel codexPatchModel() {
        return new CodexPatchScriptModel();
    }

    /**
     * 步数预算专用：无论问什么、无论历史里有没有工具结果，**永远**请求同一个工具。
     *
     * <p>只有这样才耗得完步数预算。普通的 {@code ScriptedLlmModel} 一旦看到工具结果就会收尾，
     * "预算耗尽"这条路径根本走不到 —— 这也是本模块第二个不来自脚本模型的模型，
     * 原因写在 {@link BudgetProbeModel} 的类注释里。</p>
     */
    @Bean(name = BUDGET)
    public LlmModel budgetModel() {
        return new BudgetProbeModel();
    }

    /** 演示补丁的原文（既是模型"想写的内容"，也是测试断言的依据）。 */
    public static String patchDemoText() {
        return """
                好的，我用最小改动新建一个演示文件。补丁如下：

                *** Begin Patch
                *** Add File: %s
                +这是一个由 codex Loop 的 apply_patch 工具创建的文件。
                +演示要点：补丁走的是 *** Begin Patch 段落格式，而不是 unified diff 的行号。
                *** End Patch

                请把这个补丁应用下去。""".formatted(PATCH_DEMO_FILE);
    }

    /** 沙箱围栏根目录（= {@code llm.tools.workdir}）。 */
    public static Path sandboxRoot() {
        return Path.of("target", "sandbox").toAbsolutePath().normalize();
    }

    /** 补丁演示的目标文件绝对路径。 */
    public static Path patchDemoTarget() {
        return sandboxRoot().resolve(PATCH_DEMO_FILE);
    }
}
