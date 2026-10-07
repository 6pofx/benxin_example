package com.benxin.exp.context;

import com.benxin.exp.common.model.RecordingModel;
import com.benxin.exp.context.agent.ClaudeCodeAssistant;
import com.benxin.exp.context.agent.CompactAssistant;
import com.benxin.exp.context.agent.MemoryAssistant;
import com.benxin.exp.context.agent.PromptAssistant;
import com.benxin.exp.context.agent.StatelessAssistant;
import com.benxin.exp.context.context.Exp06ContextManager;
import com.benxin.exp.context.context.Exp06TokenEstimator;
import com.benxin.exp.context.memory.JdbcMemoryStore;
import com.benxin.exp.context.prompt.JdbcPromptPlatform;
import com.benxin.exp.context.tool.LongDocTools;
import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.Role;
import com.benxin.llm.spring.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 各测试类共用的脚手架：注入容器里真实装配好的那些 bean，并在每个用例前把共享状态清干净。
 *
 * <p><b>为什么必须清</b>：{@code @SpringBootTest} 会在同一个 ApplicationContext 里跑完所有用例，
 * 而 Agent 默认开着记忆、{@code RecordingModel} 会把每一次请求都留档 ——
 * 不清就会变成"前一个用例的对话成为后一个用例的上下文"，
 * 这类耦合的失败往往表现为莫名其妙的条数差 1。</p>
 */
@SpringBootTest
abstract class AbstractContextMemoryTest {

    @Autowired
    protected AgentRegistry agentRegistry;

    @Autowired
    protected JdbcMemoryStore memoryStore;

    @Autowired
    protected Exp06ContextManager contextManager;

    @Autowired
    protected Exp06TokenEstimator tokenEstimator;

    @Autowired
    protected ContextCompactor contextCompactor;

    @Autowired
    protected JdbcPromptPlatform promptPlatform;

    @Autowired
    protected MemoryAssistant memoryAssistant;

    @Autowired
    protected PromptAssistant promptAssistant;

    @Autowired
    protected CompactAssistant compactAssistant;

    @Autowired
    protected ClaudeCodeAssistant claudeCodeAssistant;

    @Autowired
    protected StatelessAssistant statelessAssistant;

    @Autowired
    protected LongDocTools longDocTools;

    @Autowired
    @Qualifier("mockLlmModel")
    protected RecordingModel mockModel;

    @Autowired
    @Qualifier("tinyLlmModel")
    protected RecordingModel tinyModel;

    @Autowired
    @Qualifier("visionLlmModel")
    protected RecordingModel visionModel;

    @BeforeEach
    void resetSharedState() {
        mockModel.reset();
        tinyModel.reset();
        visionModel.reset();
        longDocTools.reset();
        contextManager.resetStats();
        promptPlatform.resetStats();
        promptPlatform.clear();
        // JDBC 记忆在测试里也要清表：文件库会跨测试类、跨 mvn 进程留数据
        memoryStore.clearAll();
        // 走 names() + find()：@LlmAgent 接口在注册表里是惰性条目，
        // resolved() 只会列出已经被解析过的那些，直接用它遍历一个都清不掉。
        agentRegistry.names().forEach(name -> agentRegistry.find(name)
                .ifPresent(agent -> agent.clearHistory(agent.name() + "-default")));
    }

    // ------------------------------------------------------------------

    /** 真正的 Agent 实例（不是声明式代理）：需要看它内部装配了什么时用。 */
    protected Agent agent(String name) {
        return agentRegistry.get(name);
    }

    protected static List<ChatMessage> nonSystem(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() != Role.SYSTEM).toList();
    }

    protected static List<ChatMessage> systemMessages(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() == Role.SYSTEM).toList();
    }

    /** 在会话里说话，返回最后一次真正下发的请求。 */
    protected ChatRequest lastRequestAfter(Runnable call) {
        call.run();
        return mockModel.lastRequest();
    }
}
