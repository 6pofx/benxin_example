package com.benxin.exp.models.demo;

import com.benxin.exp.common.demo.Demo;
import com.benxin.exp.models.agent.DemoAssistant;
import com.benxin.exp.models.support.WireDump;
import com.benxin.exp.models.wire.WireScenario;
import com.benxin.exp.models.wire.WireScenario.StreamResult;
import com.benxin.exp.models.wire.WireScenario.WireResult;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpTransport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 线级协议演示 —— 本模块的主体。
 *
 * <p>每一节都做同样三件事：</p>
 * <ol>
 *   <li>给本地桩服务注册一条路由，回复<b>对应协议的报文原文</b>（由 {@code exp-common}
 *       里逐字段对照插件解码器写成的 Wire 类构造）；</li>
 *   <li>用插件的真实传输层（{@code JdkHttpTransport}，外面套了一层录制装饰器）打过去，
 *       于是插件<b>真的</b>发了一次 HTTP；</li>
 *   <li>把桩服务抓到的请求与它回放的响应美化打印出来，并给出解码结果的结论。</li>
 * </ol>
 *
 * <p>打印的是<b>报文</b>而不是"配置"，因为协议这件事只有在字节层面才是确定的：
 * 系统提示放在哪一层、工具声明叫什么名字、输出上限字段是 {@code max_tokens} 还是
 * {@code maxOutputTokens} —— 这些差异一旦只停留在代码里就看不出区别了。</p>
 *
 * <p>本类与 {@code ProtocolWireTest} 共用 {@link WireScenario}，因此"演示里看到的"
 * 与"测试断言住的"永远是同一件事。</p>
 */
@Component
@ConditionalOnProperty(prefix = "exp.demo", name = "wire", havingValue = "true", matchIfMissing = true)
public class ProtocolWireDemo implements ApplicationRunner {

    private final ModelRegistry models;
    private final HttpTransport transport;
    private final DemoAssistant demoAssistant;

    public ProtocolWireDemo(ModelRegistry models, HttpTransport transport, DemoAssistant demoAssistant) {
        this.models = models;
        this.transport = transport;
        this.demoAssistant = demoAssistant;
    }

    @Override
    public void run(ApplicationArguments args) {
        WireScenario scenario = new WireScenario(models, transport);
        // 进入演示前清一次桩服务（含路由）：上一个演示类留下的路由会截胡本节的请求
        scenario.resetStub();

        Demo demo = Demo.begin("示例 07 · 四种协议 / 传输层 / 模型装配 —— 对着真实 HTTP 报文演示");
        demo.banner("模型注册表: %s（默认 %s）", models.names(), models.defaultName());
        demo.banner("桩服务: %s（只监听回环地址，绝不访问外网）", scenario.stub().baseUrl());
        demo.banner("传输层: %s", transport.getClass().getSimpleName());

        demo.guard("① OpenAI Chat Completions", () -> openAi(demo, scenario));
        demo.guard("② Anthropic Messages", () -> anthropic(demo, scenario));
        demo.guard("③ Google Gemini", () -> gemini(demo, scenario));
        demo.guard("④ OpenAI Responses API", () -> responses(demo, scenario));
        demo.guard("⑤ 工具结果回灌的四种落点", () -> toolResults(demo, scenario));
        demo.guard("⑥ 多模态：同一张图，四种编码", () -> images(demo, scenario));
        demo.guard("⑦ 流式：结束语义与分片拼装", () -> streaming(demo, scenario));
        demo.guard("⑧ 自定义协议端到端", () -> customProtocol(demo, scenario));

        demo.done();
    }

    // ------------------------------------------------------------------ ① OpenAI

    private void openAi(Demo demo, WireScenario scenario) {
        demo.section("OpenAI Chat Completions —— POST {base}/v1/chat/completions");

        WireResult text = scenario.openAiText();
        demo.info("请求（桩服务抓到的原样报文）：");
        WireDump.request(demo, text.recorded());
        demo.ok("认证头 Authorization = %s", text.header("Authorization"));
        demo.ok("请求体：系统提示在 messages[] 的 system 角色里；tools 用 tools[].function.name；"
                + "输出上限叫 max_tokens");
        WireDump.response(demo, text.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(text.response()));

        demo.info("── 工具调用（请求侧声明 + 响应侧调用）：");
        WireResult tool = scenario.openAiTool();
        demo.info("   tools[0].function.name = get_weather 出现在请求体里：%s",
                contains(tool.requestBody(), "get_weather"));
        demo.ok("   解码结果：%s", WireDump.summary(tool.response()));
    }

    // ------------------------------------------------------------------ ② Anthropic

    private void anthropic(Demo demo, WireScenario scenario) {
        demo.section("Anthropic Messages —— POST {base}/v1/messages");

        WireResult text = scenario.anthropicText();
        WireDump.request(demo, text.recorded());
        demo.ok("认证头 x-api-key = %s，anthropic-version = %s",
                text.header("x-api-key"), text.header("anthropic-version"));
        demo.ok("请求体：system 是【顶层字段】而不是一条消息；max_tokens 必填；tools 用 input_schema 而不是 parameters");
        WireDump.response(demo, text.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(text.response()));

        demo.info("── 工具调用（响应侧是 tool_use 内容块，input 是 JSON 对象而不是字符串）：");
        WireResult tool = scenario.anthropicTool();
        demo.info("   请求体里 input_schema 存在：%s", contains(tool.requestBody(), "input_schema"));
        demo.ok("   解码结果：%s", WireDump.summary(tool.response()));
        demo.info("   内容块顺序：%s", WireDump.describeParts(tool.response()));
    }

    // ------------------------------------------------------------------ ③ Gemini

    private void gemini(Demo demo, WireScenario scenario) {
        demo.section("Google Gemini —— POST {base}/v1beta/models/{model}:generateContent");

        WireResult text = scenario.geminiText();
        WireDump.request(demo, text.recorded());
        demo.ok("认证头 x-goog-api-key = %s（密钥不在 URL 上 —— 查询串会进网关日志与代理缓存）",
                text.header("x-goog-api-key"));
        demo.ok("路径把模型名拼在 URL 上：%s", text.path());
        demo.ok("请求体：systemInstruction.parts[].text；contents[].role = user/model；"
                + "输出上限叫 generationConfig.maxOutputTokens");
        WireDump.response(demo, text.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(text.response()));

        demo.info("── 函数调用（functionCall 没有 id，插件按 call_<名字>_<序号> 合成）：");
        WireResult tool = scenario.geminiTool();
        demo.ok("   解码结果：%s", WireDump.summary(tool.response()));
    }

    // ------------------------------------------------------------------ ④ Responses

    private void responses(Demo demo, WireScenario scenario) {
        demo.section("OpenAI Responses API —— POST {base}/v1/responses");

        WireResult text = scenario.responsesText();
        WireDump.request(demo, text.recorded());
        demo.ok("认证头 Authorization = %s（与 Chat Completions 相同）", text.header("Authorization"));
        demo.ok("请求体：系统提示走【顶层 instructions】；对话在 input[] item 列表里；"
                + "输出上限叫 max_output_tokens");
        WireDump.response(demo, text.responseBody());
        demo.ok("解码结果：%s（结束原因来自 status=completed，而不是 finish_reason）",
                WireDump.summary(text.response()));

        demo.info("── 函数调用：响应侧是独立的 function_call item，键叫 call_id（不是 id）；"
                + "请求侧 tools[] 是扁平的，没有那层 function 包装");
        WireResult tool = scenario.responsesTool();
        demo.ok("   解码结果：%s", WireDump.summary(tool.response()));
    }

    // ------------------------------------------------------------------ ⑤ 工具结果

    private void toolResults(Demo demo, WireScenario scenario) {
        demo.section("工具结果回灌 —— 四个协议把「结果」放在四个不同的位置");

        for (String protocol : WireScenario.builtinProtocolNames()) {
            WireResult round = scenario.toolResultRoundTrip(protocol);
            demo.info("── %s：%s", protocol, round.path());
            demo.info("   请求体里带上了工具结果：%s", wireFragmentOf(round.requestBody()));
            demo.ok("   解码结果：%s", WireDump.summary(round.response()));
        }
        demo.info("四种落点对照：");
        demo.info("   OpenAI    → role:\"tool\" 的独立消息，用 tool_call_id 关联");
        demo.info("   Anthropic → 紧随 assistant 的 user 消息里的 tool_result 内容块");
        demo.info("   Gemini    → user 侧 parts[].functionResponse{name,response.result}");
        demo.info("   Responses → 独立的 function_call_output item，用 call_id 关联");
    }

    // ------------------------------------------------------------------ ⑥ 多模态

    private void images(Demo demo, WireScenario scenario) {
        demo.section("多模态 —— 同一张图，四种编码");

        for (String protocol : WireScenario.builtinProtocolNames()) {
            WireResult inline = scenario.imageRoundTrip(protocol, true);
            WireResult url = scenario.imageRoundTrip(protocol, false);
            demo.info("── %s", protocol);
            demo.ok("   内联 base64 → %s", markerOf(protocol, inline.requestBody()));
            demo.ok("   外链 URL   → %s", markerOf(protocol, url.requestBody()));
        }
        demo.info("一处必须知道的差异：Gemini 不吃外链图片（inlineData 只收 base64，fileData 只认 Google File API），");
        demo.info("因此它会降级成一段文字说明 —— 代码与测试里都有对应的断言。");
    }

    // ------------------------------------------------------------------ ⑦ 流式

    private void streaming(Demo demo, WireScenario scenario) {
        demo.section("流式（SSE）—— 结束语义与工具参数分片");

        printStream(demo, scenario.openAiStream(),
                "OpenAI：无名的 data: 帧，收到 [DONE] 立即收尾");
        printStream(demo, scenario.anthropicStream(),
                "Anthropic：强类型事件（每条都带 event:），message_stop 收尾");
        printStream(demo, scenario.geminiStream(),
                "Gemini：没有 [DONE]，靠连接关闭 → 由解码器的 finish() 兜底收尾");
        printStream(demo, scenario.responsesStream(),
                "Responses：命名事件，response.completed 收尾，同样没有 [DONE]");

        StreamResult reasoning = scenario.openAiReasoningStream();
        demo.info("── DeepSeek 形态的 reasoning_content：");
        demo.ok("   思维链累积 = %s", reasoning.thinking());
        demo.ok("   正文累积 = %s", reasoning.text());
        demo.ok("   用量 = in %d / out %d（来自 stream_options.include_usage 的末帧，choices 为空数组）",
                reasoning.usage().inputTokens(), reasoning.usage().outputTokens());

        StreamResult thinking = scenario.anthropicThinkingStream();
        demo.info("── Anthropic 的 thinking 块 + signature：");
        demo.ok("   思考累积 = %s", thinking.thinking());
        demo.ok("   正文累积 = %s", thinking.text());
        demo.ok("   用量 = in %d / out %d —— message_delta 给的是【累计】output_tokens(23)，"
                        + "message_start 已报过 1，插件换算成增量 22 后再回调，"
                        + "于是 StreamCollector 累加得到的正好是累计值 23",
                thinking.usage().inputTokens(), thinking.usage().outputTokens());

        demo.info("── 工具参数分片（三个协议三种切法）：");
        StreamResult openaiTools = scenario.openAiToolArgumentsStream();
        demo.ok("   OpenAI    index 分片 → %s",
                openaiTools.toolUses().stream().map(WireDump::describe).toList());
        StreamResult anthropicTools = scenario.anthropicToolArgumentsStream();
        demo.ok("   Anthropic block index 的 partial_json 分片 → %s",
                anthropicTools.toolUses().stream().map(WireDump::describe).toList());
        StreamResult responsesTools = scenario.responsesToolArgumentsStream();
        demo.ok("   Responses output_index 分片，最终以 output_item.done 的完整值为准 → %s",
                responsesTools.toolUses().stream().map(WireDump::describe).toList());
        StreamResult geminiTools = scenario.geminiFunctionCallStream();
        demo.ok("   Gemini    不分片，functionCall 一次性给全 → %s",
                geminiTools.toolUses().stream().map(WireDump::describe).toList());
    }

    private void printStream(Demo demo, StreamResult result, String note) {
        demo.info("── %s", note);
        demo.ok("   路径 %s | 增量拼接 = 「%s」 | usage = in %d / out %d | 结束原因 = %s | 错误 = %s",
                result.path(), result.text(),
                result.usage().inputTokens(), result.usage().outputTokens(),
                result.response().finishReason(),
                result.error() == null ? "无" : result.error().getMessage());
        if (result.recorded() != null) {
            demo.info("   请求体里 stream 相关字段：%s",
                    wireFragmentOf(result.recorded().body()));
        }
    }

    // ------------------------------------------------------------------ ⑧ 自定义协议

    private void customProtocol(Demo demo, WireScenario scenario) {
        demo.section("自定义协议 demo —— 换协议名，业务代码一行不改");

        WireResult direct = scenario.demoProtocolText();
        WireDump.request(demo, direct.recorded());
        demo.ok("认证头 X-Demo-Key = %s，X-Demo-Api = %s",
                direct.header("X-Demo-Key"), direct.header("X-Demo-Api"));
        WireDump.response(demo, direct.responseBody());
        demo.ok("解码结果：%s", WireDump.summary(direct.response()));

        StreamResult stream = scenario.demoProtocolStream();
        demo.ok("流式：增量拼接 = 「%s」，结束原因 = %s，用量 = in %d / out %d",
                stream.text(), stream.response().finishReason(),
                stream.usage().inputTokens(), stream.usage().outputTokens());

        demo.info("── 端到端：直接调用 @LlmAgent 接口（它声明 model = \"demo\"）");
        // 桩服务要能接住 Agent 那一轮请求：这里重新注册一条路由（reset 只清录制与队列，不动路由）
        String answer = demoAssistant.ask("demo 协议能用吗？");
        demo.ok("   DemoAssistant.ask(...) = %s", answer);
        demo.info("   整条链路上没有任何一处 import 了 demo 协议的类 —— 只是 ProtocolRegistry 里多了一个条目");
        demo.info("   验证：Protocol.of(\"demo\") 不是内置协议（内置只有 %s）",
                WireScenario.builtinProtocolNames());
    }

    // ------------------------------------------------------------------ 小工具

    private static String markerOf(String protocol, String requestBody) {
        String marker = switch (protocol) {
            case "openai" -> "image_url";
            case "anthropic" -> "\"type\":\"image\"";
            case "gemini" -> "inlineData";
            case "responses" -> "input_image";
            default -> "?";
        };
        return marker + " → " + (contains(requestBody, marker) ? "存在 ✔" : "缺失 ✘");
    }

    /** 从请求体里挑出与流式/工具相关的片段，避免整段报文再打印一遍。 */
    private static String wireFragmentOf(String requestBody) {
        if (requestBody == null) {
            return "(没抓到)";
        }
        for (String marker : new String[]{"\"stream\"", "\"stream_options\"", "\"tool_result\"",
                "functionResponse", "function_call_output", "\"role\":\"tool\""}) {
            if (requestBody.contains(marker)) {
                return marker + " ✔";
            }
        }
        return WireDump.oneLine(requestBody, 100);
    }

    private static boolean contains(String text, String fragment) {
        return text != null && text.contains(fragment);
    }
}
