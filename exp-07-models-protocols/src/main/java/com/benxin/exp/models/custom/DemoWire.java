package com.benxin.exp.models.custom;

import com.benxin.exp.common.wire.StubHttpServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * demo 协议自己的报文构造器 —— 与四种内置协议的 Wire 类完全同构。
 *
 * <p>它存在的意义不只是"测试要造数据"：四个内置协议之所以能被演示，
 * 靠的是 {@code exp-common} 里那四个逐字段对照插件解码器写成的 Wire 类；
 * 自定义协议要走通同一条链路，就必须补上"我的报文长什么样"这一份可执行记录。
 * 于是"加一种协议"的成本被如实呈现出来：<b>一个 Codec（生产侧）+ 一个 Wire（契约侧）</b>。</p>
 *
 * <p>字段名刻意取得与四家都不同（{@code out[].value}、{@code event}、{@code spend.in}…），
 * 这样"报文真的被整套换掉了"一眼可见。</p>
 *
 * <p>与那四个 Wire 类同一取舍：<b>用独立的 ObjectMapper</b>，不复用插件的
 * {@code Json} 门面 —— 否则构造与解析会按同一套错误规则工作，契约测试会被抵消成永远通过。</p>
 */
public final class DemoWire {

    /** 桩模型 id。 */
    public static final String MODEL = "demo-stub-1";

    /** 固定响应 id。 */
    public static final String RESPONSE_ID = "demoresp-001";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DemoWire() {
    }

    // ------------------------------------------------------------------ 非流式响应

    /** 纯文本响应。 */
    public static String textResponse(String text, int in, int out) {
        return envelope("done", in, out).out("text", text).json();
    }

    /** 思考 + 正文：demo 协议里思考是 {@code out[].type = "muse"}。 */
    public static String musingResponse(String muse, String text, int in, int out) {
        return envelope("done", in, out).out("muse", muse).out("text", text).json();
    }

    /** 工具调用：参数在 demo 协议里是 {@code args} JSON 对象。 */
    public static String skillResponse(String callId, String skill, String argsJson, int in, int out) {
        return envelope("needs_skill", in, out).skill(callId, skill, argsJson).json();
    }

    /** 被长度截断。 */
    public static String truncatedResponse(String text, int in, int out) {
        return envelope("truncated", in, out).out("text", text).json();
    }

    /** 错误信封：HTTP 200 也可能带它（与四个内置协议的 errorEnvelope 同一个用意）。 */
    public static String errorEnvelope(String message) {
        return Builder.create().problem(message).json();
    }

    // ------------------------------------------------------------------ 流式帧

    /** {@code data: …} 帧。 */
    public static String frame(String payloadJson) {
        return "data: " + payloadJson + "\n\n";
    }

    public static String startFrame() {
        return frame(Builder.create().event("start").json());
    }

    public static String sayFrame(String delta) {
        return frame(Builder.create().event("say").value(delta).json());
    }

    public static String museFrame(String delta) {
        return frame(Builder.create().event("muse").value(delta).json());
    }

    public static String skillFrame(String callId, String skill, String argsJson) {
        return frame(Builder.create().event("skill").skill(callId, skill, argsJson).json());
    }

    public static String usageFrame(int in, int out) {
        return frame(Builder.create().event("usage").spend(in, out).json());
    }

    public static String doneFrame(String outcome) {
        return frame(Builder.create().event("done").outcome(outcome).json());
    }

    public static String problemFrame(String message) {
        return frame(Builder.create().event("problem").problem(message).json());
    }

    /** 一条完整的文本流：start → say* → usage → done。 */
    public static List<String> streamingText(int in, int out, String... deltas) {
        List<String> frames = new ArrayList<>();
        frames.add(startFrame());
        for (String delta : deltas) {
            frames.add(sayFrame(delta));
        }
        frames.add(usageFrame(in, out));
        frames.add(doneFrame("done"));
        return frames;
    }

    /** 一条工具调用流：参数一次性给全。 */
    public static List<String> streamingSkill(String callId, String skill, String argsJson, int in, int out) {
        List<String> frames = new ArrayList<>();
        frames.add(startFrame());
        frames.add(skillFrame(callId, skill, argsJson));
        frames.add(usageFrame(in, out));
        frames.add(doneFrame("needs_skill"));
        return frames;
    }

    /** 包成桩服务的 SSE 回复。 */
    public static Reply replySse(List<String> frames) {
        return Reply.sseFrames(frames);
    }

    /** 包成桩服务的 JSON 回复。 */
    public static Reply replyJson(String body) {
        return Reply.json(body);
    }

    // ------------------------------------------------------------------ 构造器

    private static Builder envelope(String outcome, int in, int out) {
        return Builder.create().id(RESPONSE_ID).model(MODEL).outcome(outcome).spend(in, out);
    }

    /** 极小的报文构造器；字段名就是本协议的契约，改动它会同时打破 Codec 的读法。 */
    public static final class Builder {

        private final ObjectNode root = MAPPER.createObjectNode();

        private Builder() {
        }

        static Builder create() {
            return new Builder();
        }

        public Builder id(String value) {
            root.put("id", value);
            return this;
        }

        public Builder model(String value) {
            root.put("model", value);
            return this;
        }

        public Builder outcome(String value) {
            root.put("outcome", value);
            return this;
        }

        public Builder event(String value) {
            root.put("event", value);
            return this;
        }

        public Builder value(String value) {
            root.put("value", value);
            return this;
        }

        public Builder spend(int in, int outTokens) {
            ObjectNode spend = root.putObject("spend");
            spend.put("in", in);
            spend.put("out", outTokens);
            return this;
        }

        public Builder out(String type, String value) {
            outArray().addObject().put("type", type).put("value", value);
            return this;
        }

        public Builder skill(String callId, String skill, String argsJson) {
            ObjectNode item = outArray().addObject();
            item.put("type", "skill");
            item.put("callId", callId);
            item.put("skill", skill);
            // 协议里 args 是 JSON 对象；这里按对象放，Codec 侧读到后再序列化成 argumentsJson
            JsonNode parsed = parse(argsJson);
            item.set("args", parsed == null ? MAPPER.createObjectNode() : parsed);
            return this;
        }

        public Builder problem(String message) {
            root.putObject("problem").put("message", message);
            return this;
        }

        public String json() {
            try {
                return MAPPER.writeValueAsString(root);
            } catch (Exception e) {
                throw new IllegalStateException("demo 报文序列化失败", e);
            }
        }

        private ArrayNode outArray() {
            JsonNode existing = root.get("out");
            return existing instanceof ArrayNode array ? array : root.putArray("out");
        }

        private static JsonNode parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return MAPPER.readTree(text);
            } catch (Exception e) {
                throw new IllegalArgumentException("demo 报文参数不是合法 JSON: " + text, e);
            }
        }

        @Override
        public String toString() {
            return json();
        }
    }
}
