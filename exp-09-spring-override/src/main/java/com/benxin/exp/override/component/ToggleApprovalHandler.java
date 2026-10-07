package com.benxin.exp.override.component;

import com.benxin.llm.core.sandbox.ApprovalHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 切面 10 的自定义实现：{@code ApprovalHandler}。
 *
 * <p>内置默认是 {@code ApprovalHandler.autoApprove()} —— 一行 lambda，
 * 因为"无人值守环境下弹窗式审批只会把请求挂死"。本类把它换成<b>可开关</b>的形态，
 * 用来演示审批这条链真的会被走到：</p>
 *
 * <ul>
 *   <li>{@code ApprovalPolicy.ON_REQUEST}（默认）时，只有
 *       {@code ToolCallback.requiresApproval() == true} 的工具才会来问；</li>
 *   <li>{@code ApprovalPolicy.ALWAYS} 时每个工具调用都会来问。</li>
 * </ul>
 *
 * <p>注意 {@code NEVER} 这一档的语义最容易猜反：它是"<b>不问、直接放行</b>"（最宽松），
 * 不是"不问就拒绝"。想一律拒绝得用 {@code ApprovalHandler.denyAll()} ——
 * 也就是把本类的 {@link #setApproved(boolean)} 设成 false。</p>
 */
public class ToggleApprovalHandler implements ApprovalHandler {

    private final AtomicBoolean approved = new AtomicBoolean(true);
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @Override
    public boolean approve(ApprovalRequest request) {
        requests.add(request.summary());
        return approved.get();
    }

    public void setApproved(boolean value) {
        approved.set(value);
    }

    public boolean isApproved() {
        return approved.get();
    }

    /** 依次记录每次审批请求的可读摘要。 */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public int count() {
        return requests.size();
    }

    public void reset() {
        requests.clear();
    }

    @Override
    public String toString() {
        return "ToggleApprovalHandler(当前" + (approved.get() ? "放行" : "拒绝") + ")";
    }
}
