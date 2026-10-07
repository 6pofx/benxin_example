package com.benxin.exp.override.component;

import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.prompt.SystemPromptProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 切面 9 的自定义实现：{@code SystemPromptProvider}。
 *
 * <p>内置实现 {@code TemplateSystemPromptProvider} 做的是 {@code {变量}} 插值。
 * README 说"替换它即可接入提示词管理平台（Langfuse、自建配置中心）"——
 * 本类就是那个形状的最小版：它<b>不</b>去管 {@code spec.systemPrompt()} 里写了什么，
 * 而是统一到一张"本地提示词表"里查（查不到才回落）。</p>
 *
 * <p>它同时把每个 {@code AgentSpec} 的提问记下来，于是测试可以断言
 * "装配出来的 Agent 真的调到了我这一份" —— 提示词是另一个隐式生效的切面。</p>
 */
public class PlatformSystemPromptProvider implements SystemPromptProvider {

    /** 一个"就当我们接了提示词平台"的本地表。 */
    private static final Map<String, String> PLATFORM_PROMPTS = Map.of(
            "supportAssistant", "【平台提示词 v7】你是 exp-09 的装配助手。",
            "toolAssistant", "【平台提示词 v3】你是 exp-09 的工具助手，需要外部信息就调工具。");

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<AgentSpec> specs = new ArrayList<>();

    @Override
    public String systemPrompt(AgentSpec spec, Map<String, Object> vars) {
        String name = spec == null ? null : spec.name();
        asked.add(name + "（vars=" + (vars == null ? 0 : vars.size()) + " 个）");
        if (spec != null) {
            specs.add(spec);
        }
        String fromPlatform = name == null ? null : PLATFORM_PROMPTS.get(name);
        // 平台里没有就回落到 Agent 自己的提示词 —— 真实实现里这一层就是"灰度开关"的位置。
        return fromPlatform != null ? fromPlatform : (spec == null ? null : spec.systemPrompt());
    }

    /** 依次记录被问过的 Agent 名。 */
    public List<String> asked() {
        return List.copyOf(asked);
    }

    public int count() {
        return asked.size();
    }

    public void reset() {
        asked.clear();
        specs.clear();
    }

    @Override
    public String toString() {
        return "PlatformSystemPromptProvider(本地提示词表 " + PLATFORM_PROMPTS.size() + " 条)";
    }
}
