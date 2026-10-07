package com.benxin.exp.override.component;

import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 切面 8 的自定义实现：{@code MemoryStore}。
 *
 * <p>真实项目换它多半是为了 Redis / JDBC —— README 里给的就是那段匿名类示例。
 * 本类换成"记账 + 委派"的形态，因为示例必须可以离线跑：
 * 底层还是 {@link InMemoryMemoryStore}，但每一次 {@code load/save/clear} 都被记了下来。</p>
 *
 * <p>为什么这比"直接返回空列表"更有价值：会话记忆是<b>隐式</b>生效的，
 * 看不见它就容易把"记忆没生效"和"模型没记住"混为一谈。
 * 有了这三个计数，测试可以直接断言"这一轮读了 0 条、写了 2 条"。</p>
 */
public class RecordingMemoryStore implements MemoryStore {

    private final MemoryStore delegate = new InMemoryMemoryStore();
    private final List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Integer> savesBySession = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> load(String sessionId) {
        List<ChatMessage> history = delegate.load(sessionId);
        operations.add("load(" + sessionId + ") → " + history.size() + " 条");
        return history;
    }

    @Override
    public void save(String sessionId, List<ChatMessage> history) {
        delegate.save(sessionId, history);
        int size = history == null ? 0 : history.size();
        savesBySession.merge(sessionId == null ? "" : sessionId, 1, Integer::sum);
        operations.add("save(" + sessionId + ") → " + size + " 条");
    }

    @Override
    public void clear(String sessionId) {
        delegate.clear(sessionId);
        operations.add("clear(" + sessionId + ")");
    }

    @Override
    public boolean exists(String sessionId) {
        return delegate.exists(sessionId);
    }

    /** 依次记录每次读写；示例与测试都靠它证明"记忆真的被写回了"。 */
    public List<String> operations() {
        return List.copyOf(operations);
    }

    public int count() {
        return operations.size();
    }

    /** 某个会话被写回过几次。 */
    public int savesOf(String sessionId) {
        return savesBySession.getOrDefault(sessionId == null ? "" : sessionId, 0);
    }

    public void reset() {
        operations.clear();
        savesBySession.clear();
    }

    @Override
    public String toString() {
        return "RecordingMemoryStore(委派给 InMemoryMemoryStore，已记录 " + operations.size() + " 次读写)";
    }
}
