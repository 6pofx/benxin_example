package com.benxin.exp.override.component;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.DshMinimalLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;
import com.benxin.llm.core.loop.LoopRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code @LlmLoop} 的注册示范：打上注解就进 {@link LoopRegistry}，不需要再补 {@code @Component}。
 *
 * <p>这里有两件事值得一起看：</p>
 * <ul>
 *   <li><b>注册名优先取注解</b>：{@code LlmAutoConfiguration.llmLoopRegistry()} 的规则是
 *       {@code annotation.value() 非空 ? annotation.value() : loop.name()}。
 *       本类的 {@link #name()} <b>刻意</b>返回一个跟注解不一样的值，好让"到底取了哪个"可断言。</li>
 *   <li><b>它不会抢走默认 Loop</b>：{@code defaultLoop = false}，而且 yml 里写了
 *       {@code llm.agent.default-loop: dsh-minimal} —— 配置的优先级高于注解（见下方 {@link #ORDER_ONLY} 说明）。</li>
 * </ul>
 *
 * <p>行为上它直接复用 {@code DshMinimalLoop} 的循环体（{@code runToolCallingLoop}），
 * 因为本模块要演示的是<b>注册机制</b>，不是新的编排思想。想看编排本身请去 exp-06 / exp-07。</p>
 */
@LlmLoop(value = "exp-demo-step", defaultLoop = false, order = 5)
public class DemoAgentLoop extends AbstractAgentLoop {

    /** 注册名由注解决定，与 {@link #name()} 刻意不同 —— 这个常量就是断言里的期望值。 */
    public static final String REGISTERED_NAME = "exp-demo-step";

    /** {@link #name()} 的返回值；如果注解被忽略了，注册名会变成这个。 */
    public static final String METHOD_NAME = "exp-demo-step-method-name";

    private final AtomicInteger runs = new AtomicInteger();

    @Override
    public String name() {
        return METHOD_NAME;
    }

    @Override
    public String description() {
        return "exp-09 用来演示 @LlmLoop(value=...) 注册名的自定义 Loop。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        runs.incrementAndGet();
        // 直接复用内置极简循环的循环体：调模型 → 执行工具 → 再调模型。
        return runToolCallingLoop(ctx);
    }

    public int runs() {
        return runs.get();
    }

    /** 与 {@link DshMinimalLoop} 的关系写在这里，避免读者以为是复制粘贴来的。 */
    public String basedOn() {
        return DshMinimalLoop.class.getSimpleName();
    }
}
