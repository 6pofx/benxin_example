package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.spring.AgentRegistry;

import java.util.function.Supplier;

/**
 * 切面 14 的自定义实现：{@code AgentRegistry}。
 *
 * <p>它演示的是 {@link AgentRegistry} 的<b>惰性语义</b>，也是本模块最实用的一条口径：</p>
 *
 * <ul>
 *   <li>{@code @LlmAgent} 接口在注册表里是"惰性条目"（{@code registerLazy}），
 *       由 {@code LlmAgentFactoryBean} 登记 —— 它是 eager-init 的 {@code SmartFactoryBean}，
 *       所以<b>启动期</b>就会把每个 Agent 登记进来，冷启动即可见；</li>
 *   <li>但 {@link AgentRegistry#resolved()} 只看"已经解析成真身"的那些，
 *       所以在没人调用之前它是<b>空</b>的；</li>
 *   <li>{@link AgentRegistry#names()} 是"真身 + 惰性条目"的并集，才是全量视图；
 *       而 {@link AgentRegistry#find(String)} 会把惰性条目<b>顺手解析</b>成真身。</li>
 * </ul>
 *
 * <p>结论写成一句话：<b>想遍历全部 Agent，请用 {@code names()} + {@code find()}</b>，
 * 别用 {@code resolved()}。本类把这两个计数分开暴露出来，就是为了让这句结论能被断言。</p>
 */
public class DemoAgentRegistry extends AgentRegistry {

    private final String marker = "DemoAgentRegistry";
    private int lazyRegistrations;

    @Override
    public synchronized void registerLazy(String name, Supplier<Agent> supplier) {
        lazyRegistrations++;
        super.registerLazy(name, supplier);
    }

    public String marker() {
        return marker;
    }

    /** 走过 {@code registerLazy} 的次数（= 被登记的 {@code @LlmAgent} 接口个数）。 */
    public int lazyRegistrations() {
        return lazyRegistrations;
    }

    /** 一句话概括两种视图的差别，示例里直接打印。 */
    public String describe() {
        return marker + "：names()=" + names() + "，resolved()=" + resolved().keySet()
                + "，惰性登记 " + lazyRegistrations + " 次";
    }
}
