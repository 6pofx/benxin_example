package com.benxin.exp.context.context;

import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.model.LlmModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义压缩器：<b>只按条数硬砍</b>，不算 token、不调用模型。
 *
 * <p>它扮演的是"第三种压缩策略"：{@code SlidingWindowCompactor} 按 token 预算决定留几条，
 * {@code SummarizingCompactor} 花一次模型调用换语义，而本实现把决定权完全交给调用方
 * （{@code keepRecent} 就是硬上限）。当上游对"消息条数"有硬限制、
 * 或者需要"零意外开销"时，这种策略最合适：成本与结果都是常数级的。</p>
 *
 * <p><b>写自定义压缩器最容易踩的一个坑</b>：必须自己把 SYSTEM 消息择出来单独保留。
 * {@code ContextManager} 交给压缩器的是"已经含系统提示词"的完整列表，
 * 如果像丢弃普通消息一样从头部开砍，第一刀砍掉的就是系统提示词 ——
 * 表现为"模型突然不遵守人设了"，而不是一个显眼的报错。</p>
 */
public class HardLimitCompactor implements ContextCompactor {

    /** 占位提示的标记：让模型知道"你没看到全部对话"，而不是以为对话只有这么长。 */
    public static final String MARKER = "[硬上限压缩]";

    @Override
    public List<ChatMessage> compact(List<ChatMessage> history, LlmModel model, int keepRecent) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> system = new ArrayList<>();
        List<ChatMessage> conversation = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message.role() == Role.SYSTEM) {
                system.add(message);
            } else {
                conversation.add(message);
            }
        }
        int keep = Math.max(1, keepRecent);
        int dropped = Math.max(0, conversation.size() - keep);

        List<ChatMessage> result = new ArrayList<>(system);
        if (dropped > 0) {
            result.add(ChatMessage.user(MARKER + " 为保证消息条数不超过上限，已丢弃最早的 "
                    + dropped + " 条历史消息（保留最近 " + keep + " 条）。"));
        }
        result.addAll(conversation.subList(dropped, conversation.size()));
        return result;
    }
}
