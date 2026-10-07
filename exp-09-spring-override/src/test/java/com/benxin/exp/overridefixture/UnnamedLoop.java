package com.benxin.exp.overridefixture;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AbstractAgentLoop;
import com.benxin.llm.core.loop.LoopContext;
import com.benxin.llm.core.loop.LoopResult;

/**
 * {@code @LlmLoop} 的注册名规则实验：注解不写 {@code value} 时，注册名取 {@code loop.name()}。
 *
 * <p>这就是 {@code LlmAutoConfiguration.llmLoopRegistry()} 里那一行：</p>
 * <pre>
 *   String name = annotation != null &amp;&amp; !annotation.value().isBlank() ? annotation.value() : loop.name();
 * </pre>
 *
 * <p>注意注解自己的 Javadoc 写的是"留空时取类简单名（去掉 Loop 后缀）并转 kebab-case"，
 * 而实现取的是 {@code loop.name()} —— 两者在"类名 == name()"时看起来一样，
 * 但本类的 {@code name()} 刻意返回一个普通类名<b>推不出来</b>的值，
 * 于是"到底取了哪一个"变成一个可断言的事实。</p>
 */
@LlmLoop
public class UnnamedLoop extends AbstractAgentLoop {

    /** 这个字符串推不出来 —— 它就是"注册名取了 name()"的证据。 */
    public static final String METHOD_NAME = "fixture-name-from-method";

    @Override
    public String name() {
        return METHOD_NAME;
    }

    @Override
    public String description() {
        return "fixture：验证 @LlmLoop 不写 value 时注册名取 loop.name()。";
    }

    @Override
    protected LoopResult doRun(LoopContext ctx) {
        return runToolCallingLoop(ctx);
    }
}
