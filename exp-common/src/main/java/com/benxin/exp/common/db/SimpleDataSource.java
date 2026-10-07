package com.benxin.exp.common.db;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * 极简 {@link DataSource}：每次取连接时向 {@link DriverManager} 要一条。
 *
 * <p>存在的理由有两条：</p>
 * <ol>
 *   <li>exp-common 刻意不依赖 Spring，因此不能用 Spring 的
 *       {@code DriverManagerDataSource}；</li>
 *   <li>示例要证明的是"本心的 {@code MemoryStore} / 工具可以接任意数据库"，
 *       而不是"示例必须引入连接池"。没有连接池反而让代码更好读。</li>
 * </ol>
 *
 * <p>子项目里如果已经引入了 Spring JDBC，直接把它交给 {@code JdbcTemplate} 即可。</p>
 */
public final class SimpleDataSource implements DataSource {

    private final String url;
    private final String username;
    private final String password;
    private PrintWriter logWriter;
    private int loginTimeout;

    public SimpleDataSource(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public SimpleDataSource(String url) {
        this(url, null, null);
    }

    public String url() {
        return url;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return username == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(String user, String pass) throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.benxin.exp.common.db");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("无法解包为 " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public String toString() {
        return "SimpleDataSource(" + url + ")";
    }
}
