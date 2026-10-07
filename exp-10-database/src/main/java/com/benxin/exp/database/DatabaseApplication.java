package com.benxin.exp.database;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 10 —— 数据库：把「记忆落库 + 工具查库 + 运行审计 + 写库审批 + 工作流驱动审批」
 * 串成一个端到端的智能订单助手。
 *
 * <p>这是整个示例工程里复杂度最高的一个子项目，复杂度全部来自一件事：
 * <b>把本心的六个可插拔点接到同一张真实的数据库上</b>，并且让它们互相印证 ——</p>
 *
 * <ol>
 *   <li>{@code MemoryStore} → {@code agent_memory}：会话记忆落到表里，跨进程重启续得上；</li>
 *   <li>{@code @LlmTool} → {@code customer / order_item}：模型决策真的变成了 SQL 查询；</li>
 *   <li>{@code AgentListener} → {@code agent_run}：每次运行落一行审计；</li>
 *   <li>{@code AgentInterceptor} → {@code tool_audit}：每次工具调用落一行审计，含被短路的那种；</li>
 *   <li>{@code ApprovalHandler} → {@code approval_request}：写库工具被拒时留下待人工处理的单子；</li>
 *   <li>声明式工作流 → 直接用工具查出来的真实数据做分支条件。</li>
 * </ol>
 *
 * <p><b>零配置可跑</b>：默认数据源是 H2 文件库（{@code ./target/exp10-db}），
 * 不需要本机装任何数据库；加 {@code --spring.profiles.active=mysql} 即可切到本机 MySQL 8，
 * 而 schema.sql / data.sql / 工作流 YAML / Java 代码一行都不用改。</p>
 *
 * <p><b>完全离线</b>：模型是 exp-common 的脚本化模型，不联网、不消耗 token。</p>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.database")
public class DatabaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseApplication.class, args);
    }
}
