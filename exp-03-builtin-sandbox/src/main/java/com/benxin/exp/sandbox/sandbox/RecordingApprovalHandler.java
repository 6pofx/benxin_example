package com.benxin.exp.sandbox.sandbox;

import com.benxin.llm.core.sandbox.ApprovalHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 会记账的审批处理器：永远批准 / 永远拒绝 / 按脚本回答，并留下"被问了几次、问了什么"。
 *
 * <p>演示 {@code ApprovalPolicy} 四态时最需要的就是这个：策略决定"问不问"，
 * 处理器决定"批不批"，两者必须能被分开观察。{@link #summaries()} 就是那个观察窗口。</p>
 */
public class RecordingApprovalHandler implements ApprovalHandler {

    private final boolean alwaysApprove;
    private final List<ApprovalRequest> requests = new ArrayList<>();

    private RecordingApprovalHandler(boolean alwaysApprove) {
        this.alwaysApprove = alwaysApprove;
    }

    /** 相当于 {@link ApprovalHandler#autoApprove()}，但会记账。 */
    public static RecordingApprovalHandler alwaysApprove() {
        return new RecordingApprovalHandler(true);
    }

    /** 相当于 {@link ApprovalHandler#denyAll()}，但会记账。 */
    public static RecordingApprovalHandler denyAll() {
        return new RecordingApprovalHandler(false);
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        synchronized (requests) {
            requests.add(request);
        }
        return alwaysApprove;
    }

    /** 被问过的次数。 */
    public int count() {
        synchronized (requests) {
            return requests.size();
        }
    }

    /** 每一次询问的一行摘要（谁、要干什么、为什么问）。 */
    public List<String> summaries() {
        synchronized (requests) {
            return requests.stream().map(ApprovalRequest::summary).toList();
        }
    }

    /** 被问到的工具名（按顺序）。 */
    public List<String> toolNames() {
        synchronized (requests) {
            return requests.stream().map(ApprovalRequest::toolName).toList();
        }
    }

    public void reset() {
        synchronized (requests) {
            requests.clear();
        }
    }
}
