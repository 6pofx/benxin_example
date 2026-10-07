package com.benxin.exp.common.db;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 建库 / 建表 / 查询的小工具（不依赖 Spring）。
 *
 * <p>示例里到处都要"把表建好、把脚本跑一遍、把结果读回来看一眼"，
 * 这些琐事集中在这里，业务示例才能只留下与本心插件相关的代码。</p>
 */
public final class Db {

    private Db() {
    }

    /** 行映射器；刻意不用 Spring 的 {@code RowMapper}，让本类在裸 Java 场景也能用。 */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /**
     * 建库（不存在才建）。
     *
     * <p>先连到服务器本身（不带库名），再 {@code CREATE DATABASE IF NOT EXISTS} ——
     * 这是示例工程"允许子项目自己建库"的落地方式。</p>
     */
    public static void createDatabase(String jdbcServerUrl, String username, String password, String database) {
        try (Connection conn = username == null
                ? java.sql.DriverManager.getConnection(jdbcServerUrl)
                : java.sql.DriverManager.getConnection(jdbcServerUrl, username, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + database
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("建库失败 [" + database + "]：" + e.getMessage(), e);
        }
    }

    /** 执行一条 SQL（可带参数）。 */
    public static int update(DataSource dataSource, String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败：" + sql + " → " + e.getMessage(), e);
        }
    }

    /** 查询多行。 */
    public static <T> List<T> query(DataSource dataSource, String sql, RowMapper<T> mapper, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql + " → " + e.getMessage(), e);
        }
    }

    /** 查询单值。 */
    public static Object scalar(DataSource dataSource, String sql, Object... args) {
        List<Object> values = query(dataSource, sql, rs -> rs.getObject(1), args);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 查询单值并转成 int。 */
    public static int count(DataSource dataSource, String table, String where, Object... args) {
        Object value = scalar(dataSource, "SELECT COUNT(*) FROM " + table
                + (where == null || where.isBlank() ? "" : " WHERE " + where), args);
        return value == null ? 0 : ((Number) value).intValue();
    }

    /** 按分号切分并逐条执行一段脚本。 */
    public static void executeScript(DataSource dataSource, String script) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : splitScript(script)) {
                st.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行脚本失败：" + e.getMessage(), e);
        }
    }

    /** 执行 classpath 下的一份 .sql 脚本。 */
    public static void executeClasspathScript(DataSource dataSource, String resource) {
        try (InputStream in = Db.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到脚本：" + resource);
            }
            executeScript(dataSource, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取脚本失败：" + resource, e);
        }
    }

    /** 执行文件系统上的一份 .sql 脚本。 */
    public static void executeFileScript(DataSource dataSource, Path file) {
        try {
            executeScript(dataSource, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取脚本失败：" + file, e);
        }
    }

    /**
     * 按分号切分脚本。
     *
     * <p>只做示例需要的程度：跳过 {@code --} 注释行，并忽略切分出来的空语句。
     * 示例脚本里没有存储过程，因此不需要处理 DELIMITER。</p>
     */
    public static List<String> splitScript(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : script.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("#")) {
                continue;
            }
            current.append(rawLine).append('\n');
            if (line.endsWith(";")) {
                String statement = current.toString().strip();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }
}
