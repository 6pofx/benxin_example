package com.benxin.exp.models.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议与注册表的"观测快照"。
 *
 * <p>这些类型是 {@link WireScenario} 里那些静态探测方法的返回值：把
 * {@code Protocol} / {@code ProtocolRegistry} 的行为<b>拍成数据</b>，
 * 于是演示只负责打印、测试只负责断言，两边读的是同一个事实。</p>
 */
public final class ProtocolProbes {

    private ProtocolProbes() {
    }

    /** {@code Protocol.of(...)} 在空白入参上的行为记录。 */
    public static final class IllegalArgumentRecords {

        /**
         * "调用成功了" 的占位文本。
         *
         * <p>为什么需要它：{@link java.util.Map#copyOf} <b>不接受 null 值</b>，
         * 而"没有抛异常"在本记录里本来是用 {@code null} 表示的 —— 直接把它放进 map
         * 会让 {@link #messages()} 抛 NPE，掩盖掉真正的问题（这正是本模块演示里踩到的坑：
         * 打印快照时炸了一个看不出原因的 NPE）。</p>
         */
        public static final String NO_EXCEPTION = "(没有抛异常)";

        private final Map<String, String> messages = new LinkedHashMap<>();

        /** @param message 抛出的异常消息；{@code null} 表示这次调用<b>没有</b>抛异常 */
        public void add(String call, String message) {
            messages.put(call, message == null ? NO_EXCEPTION : message);
        }

        /**
         * 各次调用的结果：异常消息，或 {@link #NO_EXCEPTION}。
         *
         * <p>刻意不返回 null 值 —— 见 {@link #NO_EXCEPTION} 的说明。</p>
         */
        public Map<String, String> messages() {
            return Map.copyOf(messages);
        }

        /** 空白入参（null / 空串 / 纯空白）是否都被拒绝了。 */
        public boolean allBlankRejected() {
            return messages.entrySet().stream()
                    .filter(entry -> !entry.getKey().contains("OpenAI"))
                    .allMatch(entry -> !NO_EXCEPTION.equals(entry.getValue()));
        }

        /** 合法的非空 id 是否被接受（没有抛异常）。 */
        public boolean nonBlankAccepted() {
            return messages.entrySet().stream()
                    .filter(entry -> entry.getKey().contains("OpenAI"))
                    .allMatch(entry -> NO_EXCEPTION.equals(entry.getValue()));
        }
    }

    /** {@code Protocol} 标识类型的语义快照。 */
    public record ProtocolIdentity(boolean internedSameInstance,
                                   boolean caseInsensitive,
                                   List<String> builtinIds,
                                   boolean geminiIsBuiltin,
                                   boolean demoIsBuiltin,
                                   String customId) {
    }

    /** 未知协议的装配报错记录。 */
    public record MissingProtocolRecord(List<String> registered, String message) {
    }

    /** 覆盖内置协议的结果记录。 */
    public record OverrideRecord(List<String> before,
                                 List<String> after,
                                 String resolvedClass,
                                 boolean isUserInstance,
                                 boolean isOverrideImplementation) {
    }
}
