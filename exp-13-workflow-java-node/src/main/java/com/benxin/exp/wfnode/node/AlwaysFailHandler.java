package com.benxin.exp.wfnode.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

/**
 * Java 节点⑤：次次失败的处理器，用来演示 {@code continueOnError} 的补偿分支。
 *
 * <p>它证明的是"失败被如实传播"：处理器只要抛异常，引擎就会</p>
 * <ol>
 *   <li>把这次失败记进访问表（{@code visited} 里有它，因为"尝试过"与"成功走过"是两回事）；</li>
 *   <li>按 {@code retry} 重试；</li>
 *   <li>{@code continueOnError: true} 时<b>不中断整张图</b>，而是把失败原因写进 {@code ${error}}，
 *       让出边的条件表达式决定走补偿分支还是正常分支。</li>
 * </ol>
 *
 * <p>这三个动作对 {@code agent} / {@code tool} / {@code java} 三种节点完全一致 ——
 * 新增一种节点类型没有带来第二套容错语义。</p>
 */
@Component
@LlmNode("always-fail")
public class AlwaysFailHandler implements WorkflowHandler {

    @Override
    public NodeOutput handle(NodeInput input) {
        String reason = input.arg("reason");
        throw new IllegalStateException(reason.isBlank() ? "这个处理器注定失败（模拟）" : reason);
    }
}
