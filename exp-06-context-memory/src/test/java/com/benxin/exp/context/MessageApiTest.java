package com.benxin.exp.context;

import com.benxin.exp.context.memory.MessageJson;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ContentPart;
import com.benxin.llm.core.message.ImagePart;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.core.message.TextPart;
import com.benxin.llm.core.message.ThinkingPart;
import com.benxin.llm.core.message.ToolResultPart;
import com.benxin.llm.core.message.ToolUsePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ChatMessage} / {@code ContentPart} 全谱的逐条断言。
 *
 * <p>这个类<b>不起 Spring</b>：消息结构是纯数据，验证它不需要容器，
 * 这样跑得快，也说明这些 API 是可以在任何地方用的。</p>
 */
@DisplayName("示例 06 · ChatMessage 与 ContentPart")
class MessageApiTest {

    // ------------------------------------------------------------------
    // 静态工厂
    // ------------------------------------------------------------------

    @Test
    @DisplayName("六种静态构造：system / user / assistant / toolResult / of / builder")
    void staticFactories() {
        ChatMessage system = ChatMessage.system("系统提示词");
        assertThat(system.role()).isEqualTo(Role.SYSTEM);
        assertThat(system.parts()).containsExactly(new TextPart("系统提示词"));

        ChatMessage user = ChatMessage.user("用户问题");
        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.name()).isNull();
        assertThat(user.toolCallId()).isNull();

        ChatMessage assistant = ChatMessage.assistant("回答");
        assertThat(assistant.role()).isEqualTo(Role.ASSISTANT);

        ChatMessage withToolUse = ChatMessage.assistant("准备调用",
                List.of(new ToolUsePart("call_1", "read_document", "{}")));
        assertThat(withToolUse.parts()).containsExactly(
                new TextPart("准备调用"), new ToolUsePart("call_1", "read_document", "{}"));
        // 文本为空时不会塞一个空 TextPart 进去
        assertThat(ChatMessage.assistant("", List.of(new ToolUsePart("call_1", "t", "{}"))).parts())
                .containsExactly(new ToolUsePart("call_1", "t", "{}"));

        ChatMessage fromList = ChatMessage.assistant(List.of(new ThinkingPart("想一想"), new TextPart("结论")));
        assertThat(fromList.parts()).hasSize(2);
        assertThat(fromList.text()).isEqualTo("结论");

        ChatMessage tool = ChatMessage.toolResult("call_1", "read_document", "结果内容", false);
        assertThat(tool.role()).isEqualTo(Role.TOOL);
        assertThat(tool.name()).isEqualTo("read_document");
        assertThat(tool.toolCallId()).isEqualTo("call_1");
        assertThat(tool.text()).isEqualTo("结果内容");

        ChatMessage byRole = ChatMessage.of(Role.ASSISTANT, "按角色构造");
        assertThat(byRole.role()).isEqualTo(Role.ASSISTANT);
        assertThat(byRole.text()).isEqualTo("按角色构造");

        ChatMessage built = ChatMessage.builder(Role.TOOL)
                .text("片段一")
                .text("片段二")
                .name("read_document")
                .toolCallId("call_9")
                .build();
        assertThat(built.text()).isEqualTo("片段一片段二");
        assertThat(built.name()).isEqualTo("read_document");
        assertThat(built.toolCallId()).isEqualTo("call_9");
    }

    @Test
    @DisplayName("user(ContentPart...) 支持多内容块；assistant(List) 是同一件事的集合版本")
    void userWithParts() {
        ImagePart image = ImagePart.ofBase64("image/png", "AAAA");
        ChatMessage user = ChatMessage.user(new TextPart("看图"), image);

        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.parts()).hasSize(2);
        assertThat(user.parts().get(1)).isEqualTo(image);
        assertThat(user.text()).isEqualTo("看图");       // 非文本块不进 text()
        assertThat(user.allText()).isEqualTo("看图");     // 图片的 asText() 是空串

        List<ContentPart> parts = List.of(new TextPart("看图"), image);
        ChatMessage fromList = ChatMessage.user(parts.toArray(new ContentPart[0]));
        assertThat(fromList).isEqualTo(user);
    }

    // ------------------------------------------------------------------
    // 访问器语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("text() 只拼可见正文（含工具结果），allText() 才是含思维链与工具调用的调试视图")
    void textVersusAllText() {
        ChatMessage message = ChatMessage.assistant(List.of(
                new ThinkingPart("（思考）先读文件"),
                new TextPart("结论：可以"),
                new ToolUsePart("call_1", "read_file", "{\"path\":\"a.java\"}")));

        assertThat(message.text()).isEqualTo("结论：可以");
        assertThat(message.allText())
                .isEqualTo("（思考）先读文件结论：可以[read_file({\"path\":\"a.java\"})]");
        // 工具消息的 text() 是工具结果内容：这是唯一"非 TextPart 也进 text()"的情况
        assertThat(ChatMessage.toolResult("c", "t", "工具输出", false).text()).isEqualTo("工具输出");
    }

    @Test
    @DisplayName("toolUses() / thinking() / hasToolUse() / isTextEmpty() 的判定")
    void extractionHelpers() {
        ChatMessage plain = ChatMessage.assistant("只有文本");
        assertThat(plain.toolUses()).isEmpty();
        assertThat(plain.thinking()).isEmpty();
        assertThat(plain.hasToolUse()).isFalse();
        assertThat(plain.isTextEmpty()).isFalse();

        ChatMessage mixed = ChatMessage.assistant(List.of(
                new ThinkingPart("想"),
                new ToolUsePart("call_1", "read_file", "{}"),
                new ToolUsePart("call_2", "grep", "{}")));
        assertThat(mixed.text()).isEmpty();
        assertThat(mixed.toolUses()).hasSize(2);
        assertThat(mixed.toolUses().get(0).name()).isEqualTo("read_file");
        assertThat(mixed.thinking()).isEqualTo("想");
        assertThat(mixed.hasToolUse()).isTrue();
        // 有工具调用或思维链时都不算"空"
        assertThat(mixed.isTextEmpty()).isFalse();

        assertThat(ChatMessage.assistant(List.of(new TextPart(""))).isTextEmpty()).isTrue();
        assertThat(ChatMessage.assistant("").isTextEmpty()).isTrue();
    }

    @Test
    @DisplayName("withText：文本消息替换 TextPart（思维链/工具调用保留），工具消息替换工具结果内容")
    void withTextKeepsOtherParts() {
        ChatMessage original = ChatMessage.assistant(List.of(
                new ThinkingPart("想"),
                new TextPart("旧文本"),
                new ToolUsePart("call_1", "read_file", "{}")));

        ChatMessage replaced = original.withText("新文本");

        assertThat(replaced.text()).isEqualTo("新文本");
        assertThat(replaced.role()).isEqualTo(Role.ASSISTANT);
        assertThat(replaced.parts()).containsExactly(
                new TextPart("新文本"), new ThinkingPart("想"), new ToolUsePart("call_1", "read_file", "{}"));
        // 原对象不可变
        assertThat(original.text()).isEqualTo("旧文本");

        // 工具消息被改写时 name / toolCallId / error 必须保住（否则回灌协议会对不上号）
        ChatMessage tool = ChatMessage.toolResult("call_1", "read_file", "很长的结果", false);
        ChatMessage shortened = tool.withText("压缩后的占位");
        assertThat(shortened.name()).isEqualTo("read_file");
        assertThat(shortened.toolCallId()).isEqualTo("call_1");
        // 工具消息的"文本"就是工具结果本身，所以 withText 替换的是 ToolResultPart.content，
        // 而不是把新文本块插在最前面再保留旧内容（那样 text() 反而更长，越压越大）。
        assertThat(shortened.parts()).hasSize(1);
        assertThat(shortened.parts().get(0))
                .isEqualTo(new ToolResultPart("call_1", "read_file", "压缩后的占位", false));
        assertThat(shortened.text()).isEqualTo("压缩后的占位");

        // error 标记也要原样保留：失败的工具结果被压缩后仍然必须是"失败"
        ChatMessage failed = ChatMessage.toolResult("call_2", "boom", "炸了", true);
        ToolResultPart failedPart = (ToolResultPart) failed.withText("压缩后的占位").parts().get(0);
        assertThat(failedPart.error()).isTrue();
        assertThat(failedPart.toolUseId()).isEqualTo("call_2");
        assertThat(failedPart.content()).isEqualTo("压缩后的占位");
    }

    @Test
    @DisplayName("parts() 是不可变视图：外部改不动消息内部结构")
    void partsAreImmutable() {
        ChatMessage message = ChatMessage.user("你好");

        assertThat(message.parts()).isUnmodifiable();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> message.parts().add(new TextPart("注入")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("equals/hashCode 按 role + parts + name + toolCallId 判定")
    void equality() {
        assertThat(ChatMessage.user("你好")).isEqualTo(ChatMessage.user("你好"));
        assertThat(ChatMessage.user("你好")).hasSameHashCodeAs(ChatMessage.user("你好"));
        assertThat(ChatMessage.user("你好")).isNotEqualTo(ChatMessage.user("你好 "));
        assertThat(ChatMessage.toolResult("c1", "t", "x", false))
                .isNotEqualTo(ChatMessage.toolResult("c1", "t", "x", true));
        assertThat(ChatMessage.toolResult("c1", "t", "x", false))
                .isNotEqualTo(ChatMessage.toolResult("c2", "t", "x", false));
    }

    // ------------------------------------------------------------------
    // ContentPart / 多模态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ContentPart 的静态工厂：text / toolUse / toolResult / toolError")
    void contentPartFactories() {
        assertThat(ContentPart.text("hi")).isEqualTo(new TextPart("hi"));
        assertThat(ContentPart.toolUse("call_1", "read_file", "{}"))
                .isEqualTo(new ToolUsePart("call_1", "read_file", "{}"));

        ToolResultPart ok = ContentPart.toolResult("call_1", "read_file", "内容");
        ToolResultPart error = ContentPart.toolError("call_1", "read_file", "失败原因");
        assertThat(ok.error()).isFalse();
        assertThat(error.error()).isTrue();
        assertThat(ok.type()).isEqualTo("tool_result");
    }

    @Test
    @DisplayName("ContentPart 的 type() 是协议无关的类型标签，asText() 给出纯文本表示")
    void partTypesAndText() {
        assertThat(new TextPart("a").type()).isEqualTo("text");
        assertThat(new ThinkingPart("b").type()).isEqualTo("thinking");
        assertThat(new ToolUsePart("id", "n", "{}").type()).isEqualTo("tool_use");
        assertThat(new ToolResultPart("id", "n", "c", false).type()).isEqualTo("tool_result");
        assertThat(ImagePart.ofUrl("u").type()).isEqualTo("image");

        // 非文本块的 asText()：图片是空串（它没有文本表示），工具调用是 [名字(参数)]
        assertThat(ImagePart.ofUrl("u").asText()).isEmpty();
        assertThat(new ToolUsePart("id", "read_file", "{\"a\":1}").asText())
                .isEqualTo("[read_file({\"a\":1})]");
        // ToolUsePart 会把空参数补成 {}，避免下游协议收到非法 JSON
        assertThat(new ToolUsePart("id", "read_file", "  ").argumentsJson()).isEqualTo("{}");
        assertThat(new TextPart(null).text()).isEmpty();
        assertThat(new ToolResultPart("id", "n", null, false).content()).isEmpty();
    }

    @Test
    @DisplayName("ImagePart：内联 base64 与外链 URL 的判定与 data URI")
    void imagePart() {
        ImagePart inline = ImagePart.ofBase64("image/png", "AAAA");
        assertThat(inline.isInline()).isTrue();
        assertThat(inline.mediaType()).isEqualTo("image/png");
        assertThat(inline.url()).isNull();
        assertThat(inline.toDataUri()).isEqualTo("data:image/png;base64,AAAA");

        ImagePart remote = ImagePart.ofUrl("https://example.com/a.png");
        assertThat(remote.isInline()).isFalse();
        assertThat(remote.mediaType()).isNull();
        assertThat(remote.url()).isEqualTo("https://example.com/a.png");
        // URL 形式没有 base64 数据，toDataUri 会拼出 data:image/png;base64,null
        assertThat(remote.toDataUri()).isEqualTo("data:image/png;base64,null");

        // 空白 base64 不算内联
        assertThat(ImagePart.ofBase64("image/png", "  ").isInline()).isFalse();
        // mediaType 缺省时 toDataUri 兜底成 image/png
        assertThat(new ImagePart(null, "AAAA", null).toDataUri()).isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    @DisplayName("多模态消息能被自定义 MemoryStore 完整存取（图片块不丢）")
    void multimodalSurvivesJsonRoundTrip() {
        List<ChatMessage> history = List.of(
                ChatMessage.user(new TextPart("描述这张图"), ImagePart.ofBase64("image/png", "AAAA")),
                ChatMessage.assistant("图里是一个红点"),
                ChatMessage.user(ImagePart.ofUrl("https://example.com/b.png")));

        String json = MessageJson.write(history);

        // 存的是原始字段（mediaType / base64Data / url），不是拼好的 data URI ——
        // data:image/png;base64,... 那种形态由 ImagePart.toDataUri() 在协议 Codec 层生成
        assertThat(json).contains("\"type\":\"image\"")
                .contains("\"mediaType\":\"image/png\"")
                .contains("\"base64Data\":\"AAAA\"")
                .contains("\"url\":\"https://example.com/b.png\"");
        assertThat(MessageJson.read(json)).isEqualTo(history);
        assertThat(MessageJson.read(json).get(2).parts().get(0))
                .isEqualTo(ImagePart.ofUrl("https://example.com/b.png"));
    }

    @Test
    @DisplayName("Role：wireName 与 fromWire 的别名容错")
    void roleWireNames() {
        assertThat(Role.SYSTEM.wireName()).isEqualTo("system");
        assertThat(Role.TOOL.wireName()).isEqualTo("tool");
        assertThat(Role.fromWire("ASSISTANT")).isEqualTo(Role.ASSISTANT);
        assertThat(Role.fromWire("model")).isEqualTo(Role.ASSISTANT);
        assertThat(Role.fromWire("developer")).isEqualTo(Role.SYSTEM);
        assertThat(Role.fromWire("function")).isEqualTo(Role.TOOL);
        assertThat(Role.fromWire(null)).isEqualTo(Role.USER);
        assertThat(Role.fromWire("unknown-role")).isEqualTo(Role.USER);
    }
}
