package com.benxin.exp.workflow.hook;

import com.benxin.llm.core.hook.AgentListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自定义事件监听器：把工作流引擎广播的每一个事件收下来。
 *
 * <p>工作流最值得看的东西之一是<b>它的轨迹</b>。{@code WorkflowRun} 给的是汇总
 * （访问了哪些节点、执行几次、是否被截断），而"每一步发生了什么"要靠事件：</p>
 *
 * <table border="1">
 *   <caption>工作流事件表</caption>
 *   <tr><th>事件</th><th>什么时候发</th><th>负载里的关键字段</th></tr>
 *   <tr><td>{@code workflow.start}</td><td>开跑前</td><td>{@code entry} / {@code nodes}</td></tr>
 *   <tr><td>{@code workflow.node}</td><td>每个节点执行完（<b>含硬失败的那次</b>）</td>
 *       <td>{@code node} / {@code type} / {@code executions} / {@code output} / {@code failed}</td></tr>
 *   <tr><td>{@code workflow.node-retry}</td><td>一次尝试失败、还有重试机会时</td>
 *       <td>{@code node} / {@code attempt} / {@code error}</td></tr>
 *   <tr><td>{@code workflow.node-error}</td><td>重试用尽，节点判定失败</td>
 *       <td>{@code node} / {@code error} / {@code continueOnError}</td></tr>
 *   <tr><td>{@code workflow.loop-guard}</td><td>单节点访问次数触顶（死循环护栏）</td>
 *       <td>{@code node} / {@code visits}</td></tr>
 *   <tr><td>{@code workflow.truncated}</td><td>步数预算耗尽</td>
 *       <td>{@code node} / {@code executions}</td></tr>
 *   <tr><td>{@code workflow.dead-end}</td><td>没有出边 / 没有条件成立的出边</td>
 *       <td>{@code node} / {@code reason}</td></tr>
 *   <tr><td>{@code workflow.end}</td><td>正常结束</td>
 *       <td>{@code executions} / {@code visited} / {@code output}</td></tr>
 * </table>
 *
 * <p>本类同时是"<b>自定义监听器顶掉内置日志监听器</b>"的示范：
 * starter 里的 {@code llmLoggingListener} 用的是 {@code @ConditionalOnMissingBean(AgentListener.class)}，
 * 容器里只要有任意一个 {@code AgentListener} bean 它就让位。所以这里不需要任何开关。</p>
 *
 * <p>事件可能来自工具执行线程，因此内部用并发安全的容器。</p>
 */
@Component
public class WorkflowEventCollector implements AgentListener {

    /** 收到的全部事件，按时间顺序。 */
    private final List<Recorded> events = new CopyOnWriteArrayList<>();

    @Override
    public void onCustomEvent(String type, Object payload) {
        events.add(new Recorded(type, payload));
    }

    /** 一条事件。 */
    public record Recorded(String type, Object payload) {
    }

    // ------------------------------------------------------------------
    // 给演示与测试用的查询口径
    // ------------------------------------------------------------------

    public List<Recorded> events() {
        return List.copyOf(events);
    }

    /** 某一类事件的条数。 */
    public int count(String type) {
        return (int) events.stream().filter(e -> e.type().equals(type)).count();
    }

    public boolean has(String type) {
        return count(type) > 0;
    }

    /** 某一类事件的全部负载。 */
    public List<Object> payloads(String type) {
        return events.stream().filter(e -> e.type().equals(type)).map(Recorded::payload).toList();
    }

    /**
     * 按 {@code node} 字段取出事件里提到过的节点 id，按首次出现顺序去重。
     *
     * <p>这份清单与 {@code WorkflowRun.visited()} 应该一致 —— 两者口径不同的那天，
     * 就是"审计结论依赖哪个可观测面"开始含糊的那天。示例里直接断言了这一点。</p>
     */
    public List<String> nodesFromEvents(String type) {
        List<String> nodes = new ArrayList<>();
        for (Object payload : payloads(type)) {
            if (payload instanceof Map<?, ?> map) {
                Object node = map.get("node");
                if (node != null && !nodes.contains(String.valueOf(node))) {
                    nodes.add(String.valueOf(node));
                }
            }
        }
        return nodes;
    }

    /** 某一类事件的 {@code node} 字段取值序列（含重复），便于看"循环转了几圈"。 */
    public List<String> nodeSequence(String type) {
        List<String> nodes = new ArrayList<>();
        for (Object payload : payloads(type)) {
            if (payload instanceof Map<?, ?> map) {
                Object node = map.get("node");
                if (node != null) {
                    nodes.add(String.valueOf(node));
                }
            }
        }
        return nodes;
    }

    /** 打印成"事件名(关键字段)"的多行文本，演示里直接 output 出来。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Recorded event : events) {
            sb.append(event.type());
            if (event.payload() instanceof Map<?, ?> map) {
                Map<String, Object> picked = new LinkedHashMap<>();
                for (String key : List.of("node", "type", "attempt", "visits", "executions",
                        "entry", "reason", "error", "continueOnError", "output")) {
                    if (map.containsKey(key)) {
                        picked.put(key, map.get(key));
                    }
                }
                if (!picked.isEmpty()) {
                    sb.append(' ').append(picked);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void reset() {
        events.clear();
    }
}
