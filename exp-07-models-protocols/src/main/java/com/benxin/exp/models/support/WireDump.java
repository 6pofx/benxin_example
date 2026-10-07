package com.benxin.exp.models.support;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.common.wire.StubHttpServer.Recorded;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.chat.Usage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolUsePart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "线级"报文的打印与摘要。
 *
 * <p>本模块的演示主体就是<b>把插件真正发出去的请求、真正收回来的响应摊开给人看</b>，
 * 因此打印格式本身就是交付物的一部分：路径、认证头、请求体、响应体必须一眼能对上
 * 四个协议的差异表。</p>
 */
public final class WireDump {

    /** 请求体/响应体打印的最大行数，避免一段 SSE 帧列表把控制台淹掉。 */
    private static final int MAX_BODY_LINES = 60;

    private WireDump() {
    }

    /** 打印一笔抓到的请求：方法 + 路径（含查询串）+ 认证相关请求头 + 美化后的请求体。 */
    public static void request(Demo demo, Recorded recorded) {
        if (recorded == null) {
            demo.warn("没有抓到请求");
            return;
        }
        String target = recorded.path() + (recorded.query() == null || recorded.query().isBlank()
                ? "" : "?" + recorded.query());
        demo.info("→ %s %s", recorded.method(), target);
        demo.info("  请求头: %s", describedHeaders(recorded.headers()));
        demo.info("  请求体:");
        body(demo, recorded.body());
    }

    /** 打印一段响应体（协议原文）。 */
    public static void response(Demo demo, String body) {
        demo.info("  响应体（协议原文）:");
        body(demo, body);
    }

    /** 打印任意 JSON 文本，带缩进与行数上限。 */
    public static void body(Demo demo, String json) {
        if (json == null || json.isBlank()) {
            demo.info("     (空)");
            return;
        }
        List<String> lines = new ArrayList<>(List.of(PrettyJson.format(json).split("\n", -1)));
        boolean trimmed = lines.size() > MAX_BODY_LINES;
        if (trimmed) {
            lines = lines.subList(0, MAX_BODY_LINES);
        }
        lines.forEach(line -> demo.info("     %s", line));
        if (trimmed) {
            demo.info("     …（已截断，完整报文见 README 里的协议对照表）");
        }
    }

    /** 把统一响应摘要成一行结论：正文 / 结束原因 / 用量 / 工具调用。 */
    public static String summary(ChatResponse response) {
        if (response == null) {
            return "(null)";
        }
        Usage usage = response.usage();
        return String.format("text=%s | finishReason=%s | usage(in=%d,out=%d,cached=%d,reasoning=%d) | tools=%s",
                quote(response.text()), response.finishReason(), usage.inputTokens(), usage.outputTokens(),
                usage.cachedInputTokens(), usage.reasoningTokens(), describeToolUses(response));
    }

    /** 只列认证/内容协商相关头，别的头（User-Agent / Content-Length…）对协议演示是噪音。 */
    public static Map<String, String> describedHeaders(Map<String, List<String>> headers) {
        Map<String, String> picked = new LinkedHashMap<>();
        if (headers == null) {
            return picked;
        }
        List<String> interesting = List.of("authorization", "x-api-key", "x-goog-api-key",
                "anthropic-version", "content-type", "accept");
        headers.forEach((name, values) -> {
            if (interesting.contains(name.toLowerCase()) && !values.isEmpty()) {
                picked.put(name, values.get(0));
            }
        });
        return picked;
    }

    /** 压成一行，便于在结论行里嵌一小段报文。 */
    public static String oneLine(String json, int maxChars) {
        if (json == null) {
            return "(null)";
        }
        String flat = json.replaceAll("\\s+", " ").strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    /** 工具调用的可读形式：{@code 名字(id)=参数}。 */
    public static String describeToolUses(ChatResponse response) {
        if (response == null || response.message() == null) {
            return "[]";
        }
        return response.message().toolUses().stream()
                .map(WireDump::describe)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    public static String describe(ToolUsePart use) {
        return use.name() + "(" + use.id() + ")=" + PrettyJson.oneLine(use.argumentsJson(), 80);
    }

    /** 按内容块类型列出响应里的块，用于验证"思考 → 正文 → 工具"的顺序。 */
    public static String describeParts(ChatResponse response) {
        if (response == null || response.message() == null) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (ContentPart part : response.message().parts()) {
            if (part instanceof ThinkingPart thinking) {
                parts.add("thinking[" + PrettyJson.oneLine(thinking.text(), 24)
                        + (thinking.signature() == null ? "" : " signature=" + thinking.signature()) + "]");
            } else {
                parts.add(part.type());
            }
        }
        return parts.toString();
    }

    private static String quote(String text) {
        if (text == null || text.isEmpty()) {
            return "\"\"";
        }
        String flat = text.replace("\n", "\\n");
        return '"' + (flat.length() <= 60 ? flat : flat.substring(0, 60) + "…") + '"';
    }
}
