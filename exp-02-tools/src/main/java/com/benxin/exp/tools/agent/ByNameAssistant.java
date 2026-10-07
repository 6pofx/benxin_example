package com.benxin.exp.tools.agent;

import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.User;

/**
 * 工具来源 ②：{@code toolNames = {...}} —— 按<b>名字精确</b>拿。
 *
 * <p>它比 {@code tools = {类}} 更细：可以只从某个类里挑两个方法，
 * 也可以把散落在多个类里的工具拼成一份"最小权限清单"。
 * 名字写错不会抛异常，只在启动日志里留一条 WARN
 * （{@code LlmToolCatalog.forNames}）—— 也就是说<b>打错一个字母，
 * 这个工具就静默地不在表里</b>，示例里用"工具表实际名字集合"来兜住这一点。</p>
 *
 * <p>这里刻意点名了三个来自不同类（甚至不同 jar：{@code CommonTools} 在 exp-common 里）的工具。</p>
 */
@LlmAgent(
        name = "byNameAssistant",
        toolNames = {"get_current_time", "calculate", "explode"},
        systemPrompt = "你是按名字点工具的最小权限助手。")
public interface ByNameAssistant {

    String ask(@User String question);
}
