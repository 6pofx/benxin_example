package com.benxin.exp.sandbox.sandbox;

import com.benxin.llm.core.sandbox.ApprovalHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义审批处理器：按<b>工具名 + 参数 + sessionId</b> 三要素决定批不批。
 *
 * <p>这是 {@link ApprovalHandler} 三种用法里最贴近生产的一种（另外两种是内置的
 * {@code autoApprove()} 与 {@code denyAll()}）：</p>
 * <ul>
 *   <li>写类工具（{@code write / edit}）只在目标路径落在围栏内时批准；</li>
 *   <li>{@code bash} <b>一律拒绝</b> —— 审批是人的判断，不该因为"沙箱允许 echo"就默认放行一切命令；</li>
 *   <li>sessionId 里带 {@code untrusted} 的会话，除只读工具外全部拒绝；</li>
 *   <li>其余（只读、内存类工具）直接批准，免得演示被无谓的确认打断。</li>
 * </ul>
 *
 * <p>每次决策都会留痕（{@link #decisions()}），示例与测试据此断言"到底问了几次、批了什么"。</p>
 */
public class WorkdirScopedApprovalHandler implements ApprovalHandler {

    /** 一次审批决策。 */
    public record Decision(String toolName, String sessionId, String target, boolean approved,
                           String reason) {

        public String summary() {
            return (approved ? "批准" : "拒绝") + " " + toolName + " " + target + " —— " + reason;
        }
    }

    private final String fence;
    private final List<Decision> decisions = new ArrayList<>();

    public WorkdirScopedApprovalHandler(String fence) {
        this.fence = fence;
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        if (request == null) {
            return false;
        }
        String tool = request.toolName() == null ? "" : request.toolName().toLowerCase(Locale.ROOT);
        String session = request.sessionId() == null ? "" : request.sessionId();
        Map<String, Object> args = new LinkedHashMap<>(request.arguments());
        String target = String.valueOf(args.getOrDefault("path",
                args.getOrDefault("command", "-")));

        boolean untrusted = session.toLowerCase(Locale.ROOT).contains("untrusted");
        boolean approved;
        String reason;

        if (untrusted && (tool.equals("write") || tool.equals("edit") || tool.equals("bash"))) {
            approved = false;
            reason = "会话 " + session + " 被标记为不可信，拒绝一切副作用操作";
        } else if (tool.equals("bash")) {
            approved = false;
            reason = "命令执行的审批一律交人工；本处理器选择拒绝（沙箱允许 echo 不等于人要点头）";
        } else if (tool.equals("write") || tool.equals("edit")) {
            boolean inside = target.replace('\\', '/').startsWith(fence.replace('\\', '/'))
                    || !target.startsWith("/") && !target.matches("^[A-Za-z]:.*");
            approved = inside;
            reason = inside
                    ? "目标 " + target + " 落在围栏 " + fence + " 内"
                    : "目标 " + target + " 不在围栏 " + fence + " 内";
        } else {
            approved = true;
            reason = "只读 / 内存类工具，无需人工确认";
        }

        synchronized (decisions) {
            decisions.add(new Decision(request.toolName(), session, target, approved, reason));
        }
        return approved;
    }

    public List<Decision> decisions() {
        synchronized (decisions) {
            return List.copyOf(decisions);
        }
    }

    public int approveCount() {
        return decisions().size();
    }

    public void reset() {
        synchronized (decisions) {
            decisions.clear();
        }
    }
}
