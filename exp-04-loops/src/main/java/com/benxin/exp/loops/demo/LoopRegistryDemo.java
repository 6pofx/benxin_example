package com.benxin.exp.loops.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.loop.WorkflowLoop;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code LoopRegistry} 的完整 API。
 *
 * <p>注册表是"Loop 可插拔"的落点：{@code @LlmAgent(loop = "...")} 里的字符串、
 * {@code llm.agent.default-loop} 的配置值、{@code workflow:<name>} 这种派生名，
 * 全都先到这里查一次。查不到时 {@code LlmAgentFactory.specOf} 只打一条 warn
 * 然后**静默回落默认 Loop** —— 所以把它的 API 走一遍是有意义的：
 * 拼错名字的代价是"行为不对"，而不是"启动失败"。</p>
 */
@Component
public class LoopRegistryDemo {

    private final LoopRegistry loopRegistry;

    public LoopRegistryDemo(LoopRegistry loopRegistry) {
        this.loopRegistry = loopRegistry;
    }

    void run(Demo demo) {
        demo.section("LoopRegistry API：names / contains / find / get / descriptions / default*");
        reads(demo);
        demo.section("register(...) 与 setDefault(...)：运行时改注册表");
        writes(demo);
        demo.section("BuiltinLoops.NAMES 与 DEFAULT_LOOP：内置清单的单一事实来源");
        builtins(demo);
    }

    private void reads(Demo demo) {
        demo.ok("names() = %s", loopRegistry.names());
        demo.ok("contains(\"react\") = %s，contains(\"不存在的-loop\") = %s",
                loopRegistry.contains("react"), loopRegistry.contains("不存在的-loop"));
        Optional<AgentLoop> found = loopRegistry.find("codex");
        demo.ok("find(\"codex\") = present %s，实现类 = %s",
                found.isPresent(), found.map(loop -> loop.getClass().getSimpleName()).orElse("-"));
        demo.ok("find(\"不存在的-loop\") = %s（空 Optional，不抛异常）",
                loopRegistry.find("不存在的-loop").isPresent());
        demo.ok("get(\"staged\") = %s（查不到会抛 IllegalArgumentException）",
                loopRegistry.get("staged").getClass().getSimpleName());
        demo.ok("defaultName() = %s", loopRegistry.defaultName());
        demo.ok("defaultLoop() = %s（实际取到的实例）", loopRegistry.defaultLoop().getClass().getSimpleName());
        demo.ok("isEmpty() = %s", loopRegistry.isEmpty());

        List<String> descriptions = loopRegistry.descriptions();
        demo.info("descriptions() 共 %d 条，前 4 条：", descriptions.size());
        descriptions.stream().limit(4).forEach(line -> demo.info("   %s", LoopReport.oneLine(line, 150)));
        descriptions.stream()
                .filter(line -> line.startsWith(WorkflowLoop.NAME + " ——"))
                .findFirst()
                .ifPresent(line -> demo.info("   （workflow 那条）%s", LoopReport.oneLine(line, 150)));
    }

    private void writes(Demo demo) {
        String name = "exp-demo-ephemeral";
        AgentLoop ephemeral = new EphemeralLoop(name);
        loopRegistry.register(ephemeral);
        demo.ok("register(loop) 之后 contains(\"%s\") = %s，names() 里也有 = %s",
                name, loopRegistry.contains(name), loopRegistry.names().contains(name));
        demo.ok("新 Loop 的 description 立刻出现在 descriptions() 里 = %s",
                loopRegistry.descriptions().stream().anyMatch(line -> line.startsWith(name + " ——")));

        String original = loopRegistry.defaultName();
        loopRegistry.setDefault(name);
        demo.ok("setDefault(\"%s\") 之后 defaultName() = %s，defaultLoop() = %s",
                name, loopRegistry.defaultName(), loopRegistry.defaultLoop().getClass().getSimpleName());
        loopRegistry.setDefault(original);
        demo.ok("恢复默认：defaultName() = %s（演示与测试都靠这一步不污染主上下文）",
                loopRegistry.defaultName());

        loopRegistry.register("exp-demo-registered-by-name", new EphemeralLoop("exp-demo-registered-by-name"));
        demo.ok("register(name, loop) 也可以显式指定注册名 = %s",
                loopRegistry.contains("exp-demo-registered-by-name"));
        demo.info("注意：注册表没有 unregister —— 演示里新增的条目会留到进程结束。"
                + "所以演示与测试都只加不删，并且不去断言 names() 的精确集合。");
    }

    private void builtins(Demo demo) {
        demo.ok("BuiltinLoops.NAMES = %s", BuiltinLoops.NAMES);
        demo.ok("BuiltinLoops.DEFAULT_LOOP = %s", BuiltinLoops.DEFAULT_LOOP);
        demo.ok("内置名单 %d 个，注册表里都在 = %s",
                BuiltinLoops.NAMES.size(),
                loopRegistry.names().containsAll(BuiltinLoops.NAMES));
        demo.info("默认 Loop 的优先级（LlmAutoConfiguration.llmLoopRegistry）：");
        demo.info("   1) llm.agent.default-loop 且注册表里有这个名字  → 用它");
        demo.info("   2) 否则取第一个 @LlmLoop(defaultLoop = true) 的 bean（按 order 排序）");
        demo.info("   3) 都没有则回落到写死的 \"%s\"", BuiltinLoops.DEFAULT_LOOP);
        demo.info("本模块走的是第 1 条（application.yml 里写了 default-loop = exp-decorate）；"
                + "第 2 条由 ApplicationContextRunner 的独立上下文验证。");
        demo.info("BuiltinLoops.defaultRegistry() 还能给「脱离 Spring」的场景直接发一份内置注册表。");
    }

    /** 一个只为演示 {@code register} 而存在的最小 Loop。 */
    private static final class EphemeralLoop extends AbstractAgentLoop {

        private final String loopName;

        private EphemeralLoop(String loopName) {
            this.loopName = loopName;
        }

        @Override
        public String name() {
            return loopName;
        }

        @Override
        public String description() {
            return "演示用的一次性 Loop（只为验证 LoopRegistry.register）";
        }

        @Override
        protected LoopResult doRun(LoopContext ctx) {
            return runToolCallingLoop(ctx);
        }
    }
}
