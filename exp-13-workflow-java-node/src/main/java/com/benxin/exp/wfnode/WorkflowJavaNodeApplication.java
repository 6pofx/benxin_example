package com.benxin.exp.wfnode;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 13 —— 工作流的 <b>Java 节点</b>（{@code type: java} + {@code @LlmNode}）。
 *
 * <p>七种节点里，{@code start / agent / tool / branch / set / end} 都在 exp-05 演示过了。
 * 第七种 {@code java} 补的是它们都填不上的那个洞：{@code set} 只能写静态模板、
 * {@code branch} 只能按表达式选边、而 {@code tool} 是"给模型用的能力"（模型可见、进 Schema、
 * 被沙箱与审批管着）。这里要的是<b>给图用的逻辑</b> —— 读一批值、按业务规则算、吐出结构化结果。</p>
 *
 * <p>本模块的两个选择，都是为了把这件事演示清楚：</p>
 * <ul>
 *   <li><b>只用本机 LM Studio，不提供离线 mock 模型</b>。因为 Java 节点最有力的理由就是
 *       "数数、判合规这类事模型做得不稳，本地代码每次都一样" —— 换成脚本模型，这个对比就假了。
 *       端点不在时，演示会如实说明并只跑不依赖模型的那张图（{@code java-fault}）。</li>
 *   <li><b>一张图演示"该模型做的"</b>（摘要、压缩），<b>另一张图演示"该本地做的"</b>
 *       （失败重试、补偿），后一张图里一个 agent 节点都没有，因此零模型调用。</li>
 * </ul>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.wfnode")
public class WorkflowJavaNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowJavaNodeApplication.class, args);
    }
}
