package com.benxin.exp.tools.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 并发观测器：回答"这一轮里的工具到底是并行的还是串行的"。
 *
 * <p>为什么不能靠"看总耗时"下结论：耗时受机器负载影响，测试里容易变成偶发失败。
 * 这里改用两个确定性证据：</p>
 * <ul>
 *   <li>{@link #maxInFlight()} —— 同时在执行中的工具数量的<b>峰值</b>。串行永远是 1，
 *       并行（两个都睡一会儿）必然摸到 2；</li>
 *   <li>{@link #completionOrder()} —— 完成顺序。它和 {@code AgentResult.toolCalls()}
 *       的<b>声明顺序</b>对照，才能证明"乱序完成、按声明顺序记账"。</li>
 * </ul>
 */
@Component
public class ConcurrencyRecorder {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<String> completionOrder = new CopyOnWriteArrayList<>();
    private final Map<String, Long> durationsMillis = new ConcurrentHashMap<>();
    private final Map<String, String> threadNames = new ConcurrentHashMap<>();

    /** 跑一段"工具逻辑"，期间统计并发度与耗时。 */
    public <T> T record(String label, long sleepMillis, Supplier<T> body) {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        threadNames.put(label, Thread.currentThread().getName());
        long start = System.nanoTime();
        try {
            sleep(sleepMillis);
            return body.get();
        } finally {
            durationsMillis.put(label, (System.nanoTime() - start) / 1_000_000);
            completionOrder.add(label);
            inFlight.decrementAndGet();
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void reset() {
        inFlight.set(0);
        maxInFlight.set(0);
        completionOrder.clear();
        durationsMillis.clear();
        threadNames.clear();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public List<String> completionOrder() {
        return List.copyOf(completionOrder);
    }

    public Map<String, Long> durationsMillis() {
        return Map.copyOf(durationsMillis);
    }

    public Map<String, String> threadNames() {
        return Map.copyOf(threadNames);
    }
}
