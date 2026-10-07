package com.benxin.exp.models.support;

/**
 * 极简 JSON 美化器：把桩服务抓到的报文原文缩进后打印出来。
 *
 * <p>为什么不直接用 Jackson 的 {@code writerWithDefaultPrettyPrinter()}？
 * 因为 {@code jackson-databind} 在本模块里是<b>传递依赖</b>（插件带的），
 * 本模块没有任何一处 {@code import com.fasterxml.jackson.*}，就没必要把"打印"
 * 这件小事绑在某个具体 JSON 库的版本上 —— 协议演示要看的只是"字段在哪一层、
 * 叫什么名字"，一个 40 行的缩进器足够了。</p>
 *
 * <p>它不是通用 JSON 库：只做缩进与换行，遇到非法 JSON 时原样返回（打印不该抛异常）。</p>
 */
public final class PrettyJson {

    private PrettyJson() {
    }

    /**
     * 缩进美化；不是合法 JSON 时原样返回。
     *
     * @param json   待美化的文本
     * @param indent 每层缩进的空格数
     */
    public static String format(String json, int indent) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String unit = " ".repeat(Math.max(1, indent));
        StringBuilder out = new StringBuilder(json.length() + 128);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        try {
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    out.append(c);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                switch (c) {
                    case '"' -> {
                        inString = true;
                        out.append(c);
                    }
                    case '{', '[' -> {
                        // 空对象/空数组不换行：{"input":{}} 比铺开成三行更好读
                        char close = c == '{' ? '}' : ']';
                        int next = nextNonSpace(json, i + 1);
                        if (next >= 0 && json.charAt(next) == close) {
                            out.append(c);
                            break;
                        }
                        depth++;
                        out.append(c).append('\n').append(unit.repeat(depth));
                    }
                    case '}', ']' -> {
                        int prev = prevNonSpace(json, i - 1);
                        if (prev < 0 || json.charAt(prev) == '{' || json.charAt(prev) == '[') {
                            out.append(c);
                            break;
                        }
                        depth = Math.max(0, depth - 1);
                        out.append('\n').append(unit.repeat(depth)).append(c);
                    }
                    case ',' -> out.append(c).append('\n').append(unit.repeat(depth));
                    case ':' -> out.append(": ");
                    default -> {
                        if (!Character.isWhitespace(c)) {
                            out.append(c);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            return json;
        }
        return out.toString();
    }

    /** 默认两空格缩进。 */
    public static String format(String json) {
        return format(json, 2);
    }

    /** 压成一行（用于"结论"行里嵌一段报文）。 */
    public static String oneLine(String json, int maxChars) {
        if (json == null) {
            return "";
        }
        String flat = json.replaceAll("\\s+", " ").strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    private static int nextNonSpace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int prevNonSpace(String text, int from) {
        for (int i = from; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
