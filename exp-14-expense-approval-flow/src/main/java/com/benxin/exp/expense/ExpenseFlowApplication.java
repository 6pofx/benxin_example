package com.benxin.exp.expense;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 14 —— <b>代码编排（{@code flow}）</b>跑一条真实的差旅报销审批流程。
 *
 * <p>这是工作流的第三种写法（前两种见 exp-05 的 YAML 与 exp-13 的 Java 节点）：</p>
 * <ul>
 *   <li><b>YAML</b>：图在配置里，节点行为按名字引用处理器 —— 可热改、可跨进程；</li>
 *   <li><b>代码（本模块）</b>：图直接写成 Java，节点是对象、边是谓词 —— 类型安全、可注入依赖；</li>
 *   <li><b>staged</b>：流程固化在代码里，不走图。</li>
 * </ul>
 *
 * <p>本模块选了一个"规则多、判断也多"的真实业务：差旅报销审批。
 * 规则（金额上限、发票校验位、分级、科目）全部用代码节点，判断（类别、风险、审批结论）
 * 全部交给本机 LM Studio 上的模型 —— 并且用<b>输出契约</b>保证模型只吐枚举值，
 * 让下游的精确分支真的可靠。</p>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.expense")
public class ExpenseFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpenseFlowApplication.class, args);
    }
}
