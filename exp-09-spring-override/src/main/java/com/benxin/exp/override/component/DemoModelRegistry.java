package com.benxin.exp.override.component;

import com.benxin.llm.core.annotation.LlmModelDef;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.spring.LlmProperties;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切面 3 的自定义实现：{@code ModelRegistry} 的"自己搭表"子类。
 *
 * <p>为什么用继承而不是重新实现一遍：{@code ModelRegistry} 是<b>普通类</b>，
 * 而 {@code @ConditionalOnMissingBean} 是按<b>类型</b>判断的 ——
 * 子类 is-a 父类，于是自动配置那份 {@code llmModelRegistry()} 会照样让位。
 * "换实现"不等于"必须从零写"，这一点在 15 个切面里通用。</p>
 *
 * <p><b>但它有一个必须自己补上的义务</b>：自动配置那份 bean 方法体里做的事
 * （把 {@code llm.models.*} 变成模型、把 {@code LlmModel} bean 按 {@code @LlmModelDef}
 * 或 {@code name()} 登记、再定默认），现在<b>全归你了</b>。{@link #seed(LlmProperties, HttpTransport,
 * ObjectProvider)} 把这套算法原样搬了过来 —— 示例特意这么写，是为了让"替换一个切面
 * 到底接过了多少责任"这件事可见，而不是藏在一个满意的默认值后面。</p>
 *
 * <p><b>注意本类不能标 {@code @LlmModelDef}</b>：标了就会被
 * {@code ObjectProvider<LlmModel>} 当成模型 bean 收进去（那会 ClassCastException）。</p>
 */
public class DemoModelRegistry extends ModelRegistry {

    private final String marker = "DemoModelRegistry";

    /**
     * 按"配置 → 领域对象 → 注册表"三段把模型表搭起来，规则与
     * {@code LlmAutoConfiguration#llmModelRegistry} 逐条对应：
     * <ol>
     *   <li>{@code llm.models.<key>.*} 先登记（key 才是 {@code @LlmAgent(model = "...")} 里写的名字）；</li>
     *   <li>容器里的 {@code LlmModel} bean 后登记，因此<b>同名即覆盖</b>配置项；</li>
     *   <li>默认模型：{@code llm.default-model} 优先，否则取标了
     *       {@code @LlmModelDef(defaultModel = true)} 的 bean（按 order 取最小），
     *       否则取第一个 bean。</li>
     * </ol>
     */
    public DemoModelRegistry seed(LlmProperties properties,
                                  HttpTransport transport,
                                  ObjectProvider<LlmModel> modelBeans) {
        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        properties.getModels().forEach((name, model) -> configs.put(name, model.toModelConfig(name)));

        String configured = properties.getDefaultModel();
        String fromProperties = configured == null
                ? properties.getModels().entrySet().stream()
                        .filter(e -> e.getValue().isPrimary())
                        .sorted(Comparator.comparingInt(e -> e.getValue().getOrder()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null)
                : (properties.getModels().containsKey(configured) ? configured : null);

        registerAll(ModelFactory.createAll(configs, transport));

        String annotatedDefault = null;
        int annotatedOrder = Integer.MAX_VALUE;
        for (LlmModel model : modelBeans.orderedStream().toList()) {
            Class<?> target = AopUtils.getTargetClass(model);
            LlmModelDef annotation = target.getAnnotation(LlmModelDef.class);
            String name = annotation != null && !annotation.value().isBlank() ? annotation.value() : model.name();
            // bean 后登记 ⇒ 覆盖同名的配置项。这就是"配置不够用时用代码接管"的那条通道。
            register(name, model);
            if (annotation != null && annotation.defaultModel() && annotation.order() < annotatedOrder) {
                annotatedDefault = name;
                annotatedOrder = annotation.order();
            }
        }

        if (configured != null && names().contains(configured)) {
            setDefault(configured);
        } else if (fromProperties != null) {
            setDefault(fromProperties);
        } else if (annotatedDefault != null) {
            setDefault(annotatedDefault);
        } else if (!names().isEmpty()) {
            setDefault(names().iterator().next());
        }
        return this;
    }

    public String marker() {
        return marker;
    }

    public String describe() {
        return marker + "（模型 " + names() + "，默认 " + defaultName() + "）";
    }
}
