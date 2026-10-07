package com.benxin.exp.override.component;

import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.spring.LlmProperties;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 切面 4 的自定义实现：{@code LoopRegistry} 的"自己搭表"子类。
 *
 * <p>与 {@link DemoModelRegistry} 同一套路，而且同样要接过原 bean 方法体的全部义务：
 * {@link #seed(LlmProperties, ObjectProvider, ResourceLoader)} 把"内置 Loop +
 * {@code llm.workflows.*} + {@code @LlmLoop} bean + 默认 Loop 怎么定"这四件事原样实现了一遍。</p>
 *
 * <p>两条注册规则值得单独记住：</p>
 * <ul>
 *   <li><b>注册名优先取注解</b>：{@code annotation.value() 非空 ? annotation.value() : loop.name()}。
 *       所以 {@code @LlmLoop("exp-demo-step")} 标在一个 {@code name()} 返回别的东西的类上时，
 *       进表的名字是注解那个（见 {@code DemoAgentLoop}）。</li>
 *   <li><b>默认 Loop 的优先级</b>：{@code llm.agent.default-loop}（且必须在表里）&gt;
 *       {@code @LlmLoop(defaultLoop = true)} &gt; 字面量 {@code "dsh-minimal"}。</li>
 * </ul>
 */
public class DemoLoopRegistry extends LoopRegistry {

    private final String marker = "DemoLoopRegistry";

    public DemoLoopRegistry seed(LlmProperties properties,
                                 ObjectProvider<AgentLoop> loopBeans,
                                 ResourceLoader resourceLoader) {
        BuiltinLoops.registerAll(this);
        registerDeclaredWorkflows(properties, resourceLoader);

        String annotatedDefault = null;
        for (AgentLoop loop : loopBeans.orderedStream().toList()) {
            Class<?> target = AopUtils.getTargetClass(loop);
            LlmLoop annotation = target.getAnnotation(LlmLoop.class);
            String name = annotation != null && !annotation.value().isBlank() ? annotation.value() : loop.name();
            register(name, loop);
            if (annotation != null && annotation.defaultLoop() && annotatedDefault == null) {
                annotatedDefault = name;
            }
        }

        String configured = properties.getAgent().getDefaultLoop();
        if (configured != null && contains(configured)) {
            setDefault(configured);
        } else if (annotatedDefault != null) {
            setDefault(annotatedDefault);
        } else {
            setDefault(BuiltinLoops.DEFAULT_LOOP);
        }
        return this;
    }

    /** 把 {@code llm.workflows.*} 的每个条目注册成 {@code workflow:<key>}。 */
    private void registerDeclaredWorkflows(LlmProperties properties, ResourceLoader resourceLoader) {
        for (Map.Entry<String, LlmProperties.WorkflowProperties> entry
                : properties.getWorkflows().entrySet()) {
            String key = entry.getKey();
            String loopName = WorkflowLoop.NAME + ":" + key;
            WorkflowDefinition definition = load(key, entry.getValue(), resourceLoader);
            register(loopName, WorkflowLoop.named(loopName, definition));
        }
    }

    private WorkflowDefinition load(String key, LlmProperties.WorkflowProperties config,
                                    ResourceLoader resourceLoader) {
        String where = "llm.workflows." + key;
        boolean hasInline = config.getInline() != null && !config.getInline().isBlank();
        boolean hasLocation = config.getLocation() != null && !config.getLocation().isBlank();
        if (hasInline && hasLocation) {
            throw new IllegalStateException(where + " 同时配置了 location 与 inline，请二选一");
        }
        if (!hasInline && !hasLocation) {
            throw new IllegalStateException(where + " 需要配置 location 或 inline 之一");
        }
        try {
            if (hasInline) {
                return WorkflowIo.read(config.getInline());
            }
            Resource resource = resourceLoader.getResource(config.getLocation());
            if (!resource.exists()) {
                throw new IllegalStateException(where + " 指向的资源不存在：" + config.getLocation());
            }
            try (InputStream in = resource.getInputStream()) {
                return WorkflowIo.read(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException(where + " 读取失败：" + e.getMessage(), e);
        }
    }

    public String marker() {
        return marker;
    }

    public String describe() {
        return marker + "（Loop " + names() + "，默认 " + defaultName() + "）";
    }
}
