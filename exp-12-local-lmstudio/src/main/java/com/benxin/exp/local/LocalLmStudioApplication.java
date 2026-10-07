package com.benxin.exp.local;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 12 —— 本地小模型（LM Studio 上的 Qwen3-VL-8B）。
 *
 * <p>与 exp-11（真实 DeepSeek）的区别只有一处，但很值得单独演示：</p>
 * <ul>
 *   <li>端点在本机（{@code http://127.0.0.1:1234/v1}），因此<b>不花钱、不出网</b>，
 *       可以把演示开得更足：真实对话、真实 function calling、真实流式，
 *       以及 exp-07 只能在报文层验证的<b>真实视觉（多模态）</b>；</li>
 *   <li>但"本机端点"同样是"可能不在的外部依赖"，所以开关与探测一样不能省 ——
 *       {@code exp.local.mode=auto} 会在启动时探一次 {@code GET /v1/models}，
 *       可达才跑真实演示，不可达就打印原因并只跑离线演示。</li>
 * </ul>
 *
 * <p>业务代码里<b>没有一个字</b>提到模型：这几个 {@code @LlmAgent} 接口都没有写
 * {@code model} 属性，换模型靠 {@code Agent.toBuilder().model("local-lmstudio").build()}
 * 派生一个副本（见 {@code LocalLmStudioDemo}）。</p>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.local")
public class LocalLmStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalLmStudioApplication.class, args);
    }
}
