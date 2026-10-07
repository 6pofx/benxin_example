package com.benxin.exp.context.config;

import com.benxin.exp.common.db.SimpleDataSource;
import com.benxin.exp.context.memory.JdbcMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 数据源与记忆后端装配 —— 本模块最想说明的一句话就在这里：
 * <b>换掉一个 {@code MemoryStore} bean，所有声明式 Agent 的记忆后端就换了</b>。
 *
 * <p>插件的 {@code LlmAutoConfiguration} 用 {@code @ConditionalOnMissingBean} 提供默认的
 * {@code InMemoryMemoryStore}；本类定义了自己的 {@code MemoryStore} bean，
 * 于是默认实现自动让位（不需要任何开关、也不需要排包）。</p>
 *
 * <p>数据源刻意用 {@code exp-common} 的 {@link SimpleDataSource} 而不是连接池：
 * 本示例要证明的是"记忆可以接任意数据库"，而不是"示例必须引入连接池"，
 * 每次取连接向 {@code DriverManager} 要一条反而让代码更好读。</p>
 */
@Configuration
public class MemoryStoreConfiguration {

    /**
     * H2 文件库，落在 {@code target/} 下。
     *
     * <p>用文件库而不是内存库是有意的：只有真的落到磁盘上，
     * "换一个 DataSource 重新读还能读到同一份历史"才能被验证。</p>
     */
    @Bean
    public DataSource exp06DataSource(@Value("${exp.store.url}") String url,
                                      @Value("${exp.store.username}") String username,
                                      @Value("${exp.store.password}") String password) {
        return new SimpleDataSource(url, username, password);
    }

    /**
     * {@code spring-boot-starter-jdbc} 提供 {@code JdbcTemplate} 自动配置，
     * 但这里显式声明一个：示例的意图是"演示怎么写 JDBC 存储"，显式比隐式好读。
     */
    @Bean
    public JdbcTemplate exp06JdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 记忆后端：自定义 JDBC 实现。返回类型写成具体类而不是接口，
     * 是为了让"到底装的是哪一个实现"在 IDE 与测试里都一目了然。
     */
    @Bean
    public JdbcMemoryStore memoryStore(JdbcTemplate exp06JdbcTemplate) {
        return new JdbcMemoryStore(exp06JdbcTemplate);
    }
}
