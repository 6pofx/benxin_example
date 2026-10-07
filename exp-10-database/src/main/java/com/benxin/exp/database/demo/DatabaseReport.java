package com.benxin.exp.database.demo;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用的小工具：把"数据真的落库了"变成控制台上看得见的东西。
 *
 * <p>它做三件事：列出所有业务表、打印每张表的行数、报告当前连的是哪种数据库。
 * 最后一项对示例特别重要 —— 因为同一份代码既跑 H2 又跑 MySQL，
 * "我到底连的是哪个库"必须一眼看得出来，否则截图与日志会互相打架。</p>
 */
public final class DatabaseReport {

    /** 本示例建的全部业务表，顺序按"从主数据到审计"排，读起来顺。 */
    public static final List<String> TABLES = List.of(
            "customer", "order_item", "agent_run", "tool_audit", "agent_memory", "approval_request");

    private DatabaseReport() {
    }

    /** 当前数据库的自述（产品名 + 版本 + URL）。 */
    public static String describe(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion()
                    + " @ " + meta.getURL();
        } catch (SQLException e) {
            return "无法读取数据库元信息：" + e.getMessage();
        }
    }

    /** 每张表的行数，保持 {@link #TABLES} 的顺序。 */
    public static Map<String, Integer> rowCounts(JdbcTemplate jdbc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, count(jdbc, table));
        }
        return counts;
    }

    /**
     * 统计单表行数。
     *
     * <p>表名是常量（{@link #TABLES} 里写死的），因此这里的字符串拼接没有注入面；
     * 换成用户输入就必须走参数绑定 —— 但表名无法用 {@code ?} 绑定，
     * 那种场合应当用白名单校验。</p>
     */
    public static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /** 把行数渲染成一行紧凑的文本，方便直接塞进演示输出。 */
    public static String formatRowCounts(JdbcTemplate jdbc) {
        StringBuilder sb = new StringBuilder();
        rowCounts(jdbc).forEach((table, count) -> {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(table).append('=').append(count);
        });
        return sb.toString();
    }
}
