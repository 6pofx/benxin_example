package com.benxin.exp.overridefixture.ctx;

import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一份"只记不存"的 {@link MemoryStore}：从零实现接口，不继承 {@code InMemoryMemoryStore}。
 *
 * <p>它让"记忆被写回"这件事变得不可能悄悄发生：写进去的历史读出来永远是空的 ——
 * 于是"记忆这条通道有没有真的走通"变成一个可以断言的事实。bean 名与外层示例的
 * {@code recordingMemoryStore} <b>相同</b>，所以这确实是一次覆盖，而不是"并存"。</p>
 *
 * <p><b>为什么体放到 overridefixture 包里，而不是嵌套在测试类里</b>：
 * 嵌套的 {@code @Configuration} 会被 {@code ConfigurationClassPostProcessor} 自动注册 ——
 * 只要外层测试类被当成配置类处理一次，它就跟着进容器，于是与
 * {@code DefaultOverrideConfiguration.recordingMemoryStore()} 同名相撞，
 * 让<b>别的</b>测试类（任何引用 {@code SpringOverrideApplication} 的
 * {@code ApplicationContextRunner} 实验）抛 {@code BeanDefinitionOverrideException}。
 * 搬出来之后，只有显式 {@code @ContextConfiguration(classes = ...)} 引用它的那个测试看得见它。</p>
 */
@TestConfiguration
public class ReplaceMemoryStore {

    @Bean
    public MemoryStore recordingMemoryStore() {
        return new MemoryStore() {
            private final List<String> operations = new CopyOnWriteArrayList<>();

            @Override
            public List<ChatMessage> load(String sessionId) {
                operations.add("load(" + sessionId + ")");
                return List.of();
            }

            @Override
            public void save(String sessionId, List<ChatMessage> history) {
                operations.add("save(" + sessionId + ", " + (history == null ? 0 : history.size()) + " 条)");
            }

            @Override
            public void clear(String sessionId) {
                operations.add("clear(" + sessionId + ")");
            }

            @Override
            public String toString() {
                return "EphemeralMemoryStore" + operations;
            }
        };
    }
}