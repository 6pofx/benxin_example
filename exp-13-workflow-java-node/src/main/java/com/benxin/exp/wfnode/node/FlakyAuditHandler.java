package com.benxin.exp.wfnode.node;

import com.benxin.llm.core.annotation.LlmNode;
import com.benxin.llm.core.workflow.NodeInput;
import com.benxin.llm.core.workflow.NodeOutput;
import com.benxin.llm.core.workflow.WorkflowHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 节点④：一个"前 N 次必失败"的处理器，用来证明 <b>retry 对 java 节点同样生效</b>。
 *
 * <p><b>它故意是有状态的，而这恰恰违反接口约定</b>（{@code WorkflowHandler} 要求无状态或线程安全）。
 * 这里的状态只是为了"演一次失败"：按节点 id 记尝试次数，前 {@code failTimes} 次抛异常。
 * 真实业务里的处理器应当是无状态的 —— 这一点写在类上比写在文档里更容易被看到。</p>
 *
 * <p>计数按<b>节点 id</b>分开，所以同一个处理器实例挂到两个节点上时互不干扰；
 * 演示要重跑时调用 {@link #reset()}。</p>
 */
@Component
@LlmNode("flaky-audit")
public class FlakyAuditHandler implements WorkflowHandler {

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    @Override
    public NodeOutput handle(NodeInput input) {
        int failTimes = parseInt(input.arg("failTimes"), 2);
        int attempt = attempts.computeIfAbsent(input.nodeId(), key -> new AtomicInteger()).incrementAndGet();
        if (attempt <= failTimes) {
            // 抛什么异常都行：引擎会把它转成"节点 [audit]（java）执行失败：..."，
            // 再按节点的 retry 重试、按 continueOnError 决定中断还是走补偿
            throw new IllegalStateException("模拟的审计服务不可用（第 " + attempt + " 次尝试）");
        }
        return NodeOutput.of("审计通过（第 " + attempt + " 次尝试才成功）",
                Map.of("auditAttempts", attempt));
    }

    /** 清空计数：演示重跑时需要，否则第二次跑就"一次就成功"了。 */
    public void reset() {
        attempts.clear();
    }

    /** 某节点到目前为止被尝试了多少次（含失败的那些）。 */
    public int attempts(String nodeId) {
        AtomicInteger counter = attempts.get(nodeId);
        return counter == null ? 0 : counter.get();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
