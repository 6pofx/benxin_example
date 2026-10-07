package com.benxin.exp.database;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 同一套断言，跑在<b>真实的本机 MySQL 8</b> 上。
 *
 * <p>它继承 {@link OrderAssistantTest}，因此一个断言都没重写 ——
 * 这正是示例想证明的事：<b>「默认 H2、切 MySQL」的代价只有一行 profile</b>。
 * schema.sql / data.sql / 工作流 YAML / Java 代码全都不用动。</p>
 *
 * <h2>运行前提</h2>
 * <ol>
 *   <li>本机 MySQL 8 已启动（Windows 服务名 {@code MySQL80}）；</li>
 *   <li>账号 {@code root / 123456}（可用环境变量 {@code MYSQL_USERNAME} /
 *       {@code MYSQL_PASSWORD} 覆盖，见 {@code application-mysql.yml}）；</li>
 *   <li>库 {@code benxin_exp_demo} 会被自动创建
 *       （连接串上带了 {@code createDatabaseIfNotExist=true}）。</li>
 * </ol>
 *
 * <p>数据库相关的前提不满足时，本类会<b>失败</b>而不是跳过：示例的价值就在于
 * "真的在两种库上都跑通了"，一个静默跳过的测试等于没有验证。
 * 如果你只想跑 H2 那一份，用 {@code mvn -o test -Dtest=OrderAssistantTest}。</p>
 *
 * <h2>为什么这里要显式写 {@code @DynamicPropertySource}（而不是只加一个 profile）</h2>
 * <p>第一版只写了 {@code @ActiveProfiles("mysql")}，注释里还写着"子类的
 * {@code @SpringBootTest} 注解会整体替换掉父类的，因此这里天然用的是 MySQL"。<b>这个假设是错的。</b>
 * 实测：{@code @SpringBootTest} 与 {@code @TestPropertySource} 一样，
 * 会与父类注解上的 {@code properties} <b>合并</b>而不是替换，父类那几行 H2 覆盖因此照样生效 ——
 * 于是"MySQL 那一份"其实一直<b>静默跑在 H2 上并全绿</b>，
 * 示例最核心的那句承诺（同一套代码在两种库上都能跑）变成了一句空话。</p>
 *
 * <p>现在用 {@code @DynamicPropertySource} 显式把连接串钉到 MySQL。
 * 它是 Spring Framework 5.2.5 起提供的机制，优先级<b>高于</b>注解里的 {@code properties}，
 * 因此能真正覆盖父类那几行。{@link OrderAssistantTest#dataSourceMatchesTheExpectedProduct()}
 * 会再断言一次"这一份跑的是 MySQL"，防止将来又悄悄退回去。</p>
 */
@ActiveProfiles("mysql")
@DisplayName("示例 10 · 数据库（MySQL）")
class OrderAssistantMysqlTest extends OrderAssistantTest {

    /**
     * 把数据源钉到本机 MySQL。
     *
     * <p>连接串与 {@code application-mysql.yml} 里那份保持一致（那三个参数都不是可有可无的：
     * {@code createDatabaseIfNotExist} 让示例能自己建库，{@code useSSL=false}&amp;
     * {@code allowPublicKeyRetrieval=true} 让 MySQL 8 默认的 {@code caching_sha2_password}
     * 能在没有证书的本机开发环境里取到公钥，时区与字符集避免乱码）。这里的意义只是
     * "优先级够高，能盖住父类的 H2 覆盖"。</p>
     */
    @DynamicPropertySource
    static void mysqlDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:3306/benxin_exp_demo?createDatabaseIfNotExist=true"
                        + "&useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
                        + "&rewriteBatchedStatements=true");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("MYSQL_PASSWORD", "123456"));
    }
}
