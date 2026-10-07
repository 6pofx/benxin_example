package com.benxin.exp.tools.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * {@code returnDirect = true}：工具结果<b>直接作为最终答案</b>，模型不会被再调一次。
 *
 * <p>实现点在 {@code AbstractAgentLoop.directAnswer}：一轮工具执行完之后，
 * 只要其中任意一个工具的 {@code returnDirect()} 为真，就拿它的结果当答案并结束循环。
 * 注意三个边界：</p>
 * <ol>
 *   <li>只有"标准 tool-calling 循环"（{@code dsh-minimal} 等复用
 *       {@code runToolCallingLoop} 的 Loop）走这条捷径 —— 这也是它最省 token 的用法；</li>
 *   <li>拿到的是<b>工具结果的原始文本</b>：返回 DTO 的话，最终答案是一段 JSON 文本，
 *       不会再有人把它润色成人话；</li>
 *   <li>一轮里只要有一个工具声明了它就生效，且按声明顺序取<b>第一个</b> ——
 *       所以别把 returnDirect 工具和别的工具混在同一轮里。</li>
 * </ol>
 */
public class DirectAnswerTools {

    @LlmTool(name = "direct_answer", description = "结果直接作为最终答案（returnDirect = true）",
            returnDirect = true)
    public String answer(@LlmToolParam(value = "question", description = "问题") String question) {
        return "【直达答案】" + question + " —— 这句由工具直接交付，模型没有被再调一次。";
    }

    /** 与上面完全同构，只是没写 returnDirect：用来对照模型调用次数。 */
    @LlmTool(name = "round_trip_answer", description = "同样的内容但不写 returnDirect：结果要回灌模型")
    public String roundTrip(@LlmToolParam(value = "question", description = "问题") String question) {
        return "【回灌模型】" + question + " —— 这句还要交回模型收尾。";
    }
}
