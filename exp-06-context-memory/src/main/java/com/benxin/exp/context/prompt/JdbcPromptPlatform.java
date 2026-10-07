package com.benxin.exp.context.prompt;

import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "提示词管理平台"风格的 {@link SystemPromptProvider}：系统提示词存在数据库里，按
 * {@link AgentSpec#name()} 取。
 *
 * <p>插件默认的实现是 {@link TemplateSystemPromptProvider}（模板来自
 * {@code @LlmAgent(systemPrompt = ...)}，做 {@code {变量}} 插值）。本实现换掉的是
 * <b>"模板从哪来"</b>：注解里写的提示词退化成兜底，平台里配了就用平台的那份 ——
 * 于是"改一句提示词不必重新发版"这件事在插件里是配置问题，不是代码问题。</p>
 *
 * <p>三条刻意的设计取舍：</p>
 * <ol>
 *   <li><b>每次都查库，不做本地缓存</b>：平台侧改一行，下一次请求就生效。
 *       真实项目要加缓存的话，缓存失效键应当是 {@code (agentName, version)}。</li>
 *   <li><b>表里没有该 Agent 时回落到注解</b>：接入平台是"渐进"的，
 *       一个没登记的 Agent 不应该因为平台没数据就丢掉自己的人设（返回 null 会让模型裸奔）。</li>
 *   <li><b>渲染规则与内置模板一致</b>：直接复用 {@link TemplateSystemPromptProvider#render}，
 *       于是 {@code {agentName}} / {@code {loop}} / {@code {tools}} / {@code {today}} /
 *       {@code {cwd}} / {@code {os}} 这些内置变量以及 {@code @Ctx} 变量在平台提示词里同样可用；
 *       拼错的占位符保持原样，方便一眼看出是笔误而不是"渲染成了空串"。</li>
 * </ol>
 */
public class JdbcPromptPlatform implements SystemPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcPromptPlatform.class);

    public static final String TABLE = "exp06_prompt";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS exp06_prompt (
                agent_name VARCHAR(200) PRIMARY KEY,
                template   CLOB         NOT NULL,
                version    INT          NOT NULL,
                updated_at TIMESTAMP    NOT NULL
            )""";

    private final JdbcTemplate jdbc;
    private final TemplateSystemPromptProvider renderer = new TemplateSystemPromptProvider();

    /** 被调用次数与最近一次请求的 Agent 名：用来断言"哪些调用真的走到了 provider"。 */
    private final java.util.concurrent.atomic.AtomicInteger invocations =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<String> lastAgent =
            new java.util.concurrent.atomic.AtomicReference<>();

    public JdbcPromptPlatform(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.execute(DDL);
    }

    @Override
    public String systemPrompt(AgentSpec spec, Map<String, Object> vars) {
        invocations.incrementAndGet();
        String agentName = spec == null ? null : spec.name();
        lastAgent.set(agentName);
        Optional<Prompt> stored = find(agentName);
        if (stored.isEmpty()) {
            // 平台里没有 → 回到插件默认行为（渲染注解里的提示词；注解也没有就返回 null）
            return renderer.systemPrompt(spec, vars);
        }
        Prompt prompt = stored.get();
        log.debug("[exp06] Agent [{}] 的系统提示词来自提示词平台 v{}", agentName, prompt.version());
        return renderer.render(prompt.template(), spec, vars);
    }

    /** {@code systemPrompt()} 被调用了几次。 */
    public int invocations() {
        return invocations.get();
    }

    /** 最近一次被请求提示词的 Agent 名。 */
    public String lastAgent() {
        return lastAgent.get();
    }

    public void resetStats() {
        invocations.set(0);
        lastAgent.set(null);
    }

    // ------------------------------------------------------------------
    // 平台侧"管理接口"：示例用它们演示"改一行就换提示词"
    // ------------------------------------------------------------------

    /** 写入/更新一条提示词，版本号自增。 */
    public int put(String agentName, String template) {
        int version = version(agentName) + 1;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int updated = jdbc.update("UPDATE " + TABLE
                        + " SET template = ?, version = ?, updated_at = ? WHERE agent_name = ?",
                template, version, now, agentName);
        if (updated == 0) {
            jdbc.update("INSERT INTO " + TABLE
                            + " (agent_name, template, version, updated_at) VALUES (?, ?, ?, ?)",
                    agentName, template, version, now);
        }
        return version;
    }

    public int delete(String agentName) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE agent_name = ?", agentName);
    }

    public int clear() {
        return jdbc.update("DELETE FROM " + TABLE);
    }

    public Optional<Prompt> find(String agentName) {
        if (agentName == null) {
            return Optional.empty();
        }
        List<Prompt> rows = jdbc.query("SELECT agent_name, template, version FROM " + TABLE
                        + " WHERE agent_name = ?",
                (rs, rowNum) -> new Prompt(rs.getString("agent_name"), rs.getString("template"),
                        rs.getInt("version")), agentName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Prompt> findAll() {
        return jdbc.query("SELECT agent_name, template, version FROM " + TABLE + " ORDER BY agent_name",
                (rs, rowNum) -> new Prompt(rs.getString("agent_name"), rs.getString("template"),
                        rs.getInt("version")));
    }

    private int version(String agentName) {
        return find(agentName).map(Prompt::version).orElse(0);
    }

    public String describe() {
        return "JdbcPromptPlatform(" + TABLE + " 表驱动，共 " + findAll().size() + " 条提示词)";
    }

    /** 一条平台提示词。 */
    public record Prompt(String agentName, String template, int version) {
    }
}
