package com.benxin.exp.tools.support;

import com.benxin.llm.core.sandbox.ApprovalHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审批处理器：一律放行，但把每一次"问我了没有"记下来。
 *
 * <p>它的意义在于让 {@code ToolCallback.requiresApproval()} 变成可观测的事实 ——
 * 注意 {@code ApprovalPolicy} 必须是 {@code ON_REQUEST}（本模块 yml 里显式写了）才会
 * 查询这个方法；写成 {@code NEVER} 时它一次都不会被问到，
 * 而 {@code NEVER} 的语义是"<b>不问、直接放行</b>"，不是"拒绝"。</p>
 *
 * <p>本 bean 会顶掉默认的 {@code ApprovalHandler.autoApprove()}（同样是按类型让位），
 * 行为上仍然是"全批准"，因此不会影响其它演示。</p>
 */
@Component
public class RecordingApprovalHandler implements ApprovalHandler {

    private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public boolean approve(ApprovalRequest request) {
        requests.add(request);
        return true;
    }

    public void reset() {
        requests.clear();
    }

    public List<ApprovalRequest> requests() {
        return List.copyOf(requests);
    }

    public List<String> toolNames() {
        return requests.stream().map(ApprovalRequest::toolName).toList();
    }
}
