package com.benxin.exp.common.tool;

import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;
import com.benxin.llm.core.tool.ToolContext;
import com.benxin.llm.core.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多个子项目共用的基础工具集：天气、时间、计算器、回声。
 *
 * <p>放在 exp-common 里而不是每个模块各写一份，是为了让模块之间的差异集中在
 * <b>真正要演示的那个切面</b>上：exp-02 关心参数类型推导，exp-03 关心沙箱，
 * exp-08 关心拦截器 —— 它们用同一套工具反而更容易对照。</p>
 *
 * <p>注意它是<b>普通类</b>，不是 {@code @Component}：各子项目用
 * {@code @Bean} 把它交给容器（这样它才会被 {@code LlmToolCatalog} 扫到），
 * 顺带示范"工具来源完全由使用者决定"。</p>
 */
public class CommonTools {

    private static final Map<String, String> CONDITIONS = Map.of(
            "北京", "晴",
            "上海", "多云",
            "广州", "雷阵雨",
            "深圳", "阴",
            "杭州", "小雨");

    private int callCount;

    @LlmTool(name = "get_weather", description = "查询指定城市的当前天气与气温")
    public ObjectNode getWeather(
            @LlmToolParam(value = "city", description = "城市名称，例如：北京") String city,
            @LlmToolParam(value = "unit", description = "温度单位：celsius 或 fahrenheit",
                    required = false, defaultValue = "celsius") String unit) {
        callCount++;
        int celsius = 18 + Math.abs(city == null ? 0 : city.hashCode()) % 15;
        int value = "fahrenheit".equalsIgnoreCase(unit) ? celsius * 9 / 5 + 32 : celsius;
        ObjectNode node = Json.object();
        node.put("city", city);
        node.put("condition", CONDITIONS.getOrDefault(city, "未知"));
        node.put("temperature", value);
        node.put("unit", unit == null ? "celsius" : unit);
        node.put("observedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return node;
    }

    @LlmTool(name = "get_current_time", description = "获取服务器当前时间")
    public String currentTime() {
        callCount++;
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @LlmTool(name = "calculate", description = "计算一个简单的四则运算表达式，例如 (12+5)*3")
    public String calculate(
            @LlmToolParam(value = "expression", description = "只含数字与 + - * / ( ) 的表达式") String expression) {
        callCount++;
        if (expression == null || !expression.matches("[0-9+\\-*/(). ]+")) {
            return "错误：表达式只允许出现数字与 + - * / ( ) 符号";
        }
        try {
            return expression + " = " + new ExpressionEvaluator(expression).parse();
        } catch (RuntimeException e) {
            return "计算失败：" + e.getMessage();
        }
    }

    /**
     * 演示 {@code ToolContext} 注入：工具可以拿到"当前是谁在调用、第几步、会话是谁"，
     * 因此能写审计、能读运行属性、也能反过来派生子代理。
     */
    @LlmTool(name = "describe_context", description = "描述当前这次工具调用所处的运行上下文（Agent/会话/步数）")
    public Map<String, Object> describeContext(ToolContext ctx) {
        callCount++;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("agent", ctx.agentName());
        info.put("session", ctx.sessionId());
        info.put("step", ctx.step());
        info.put("canSpawnSubAgent", ctx.canSpawnSubAgent());
        info.put("attributes", ctx.attributes().keySet());
        return info;
    }

    /** 累计被调用的次数，示例里用来证明"工具真的被执行了"。 */
    public int callCount() {
        return callCount;
    }

    public void reset() {
        callCount = 0;
    }

    /** 一个不依赖任何第三方库的极简四则运算求值器，仅用于示例。 */
    private static final class ExpressionEvaluator {
        private final String text;
        private int pos = -1;
        private int ch;

        ExpressionEvaluator(String text) {
            this.text = text;
        }

        double parse() {
            nextChar();
            double value = parseExpression();
            if (pos < text.length()) {
                throw new IllegalArgumentException("存在无法解析的字符: " + (char) ch);
            }
            return value;
        }

        private void nextChar() {
            ch = ++pos < text.length() ? text.charAt(pos) : -1;
        }

        private boolean eat(int expected) {
            while (ch == ' ') {
                nextChar();
            }
            if (ch == expected) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (eat('+')) {
                    value += parseTerm();
                } else if (eat('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                if (eat('*')) {
                    value *= parseFactor();
                } else if (eat('/')) {
                    value /= parseFactor();
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            double value;
            int start = pos;
            if (eat('(')) {
                value = parseExpression();
                eat(')');
            } else {
                while (ch >= '0' && ch <= '9' || ch == '.') {
                    nextChar();
                }
                value = Double.parseDouble(text.substring(start, pos));
            }
            return value;
        }
    }
}
