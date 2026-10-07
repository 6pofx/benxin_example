package com.benxin.exp.context;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 06 —— 记忆 / 上下文 / 压缩 / 分词估算 / 提示词来源 / 多模态消息。
 *
 * <p>本模块演示的全部是"平时看不见、但决定了成本与效果"的切面：一次模型调用真正下发的
 * 消息序列是怎么被组装出来的、会话历史存在哪里、超窗时谁来压缩、系统提示词从哪里来。
 * 这些在插件里都是<b>可替换的 bean</b>（{@code MemoryStore} / {@code ContextManager} /
 * {@code ContextCompactor} / {@code TokenEstimator} / {@code SystemPromptProvider}），
 * 本模块把每一个都换成自己的实现，再用 {@code RecordingModel} 把"真正发出去的东西"断言住。</p>
 *
 * <p>两点与 exp-01 一致的约定：</p>
 * <ol>
 *   <li>{@link EnableLlmAgents} 必写，否则一个 {@code @LlmAgent} 接口都不会注册。</li>
 *   <li>模型全部是离线的脚本化模型，{@code mvn spring-boot:run} 立刻能看到完整输出，
 *       且<b>不消耗任何 token</b>。</li>
 * </ol>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.context")
public class ContextMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContextMemoryApplication.class, args);
    }
}
