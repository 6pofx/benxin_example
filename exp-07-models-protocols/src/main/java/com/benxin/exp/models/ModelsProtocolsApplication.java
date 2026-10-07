package com.benxin.exp.models;

import com.benxin.exp.models.support.StubRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 07 —— 模型装配与协议。
 *
 * <h2>启动顺序里藏着一条约定</h2>
 *
 * <p>{@link #main(String[])} 在 {@code SpringApplication.run} <b>之前</b>先拉起本地 HTTP 桩服务，
 * 并把它写入系统属性 {@code exp.stub.base-url}。原因是 {@code application.yml} 里
 * 四条协议通道的 {@code base-url} 都写着 {@code ${exp.stub.base-url}} ——
 * 配置在启动时解析，而端口只有桩服务起来之后才知道。</p>
 *
 * <p>这条约定的意义不是"绕一圈"，而是把<b>完全离线</b>变成结构上的事实：
 * 本模块里<b>不存在</b>任何指向真实厂商地址的 base-url，所有模型端点都只有两个可能 ——
 * 本地桩服务，或者"没配"（此时任何调用都会立刻因未配置 baseUrl 而失败，
 * 而不是悄悄连到公网去）。</p>
 *
 * <h2>演示在哪</h2>
 *
 * <p>跑起来之后由 {@code ModelConfigurationDemo}（{@code ApplicationRunner}）打印结论；
 * 线级的报文演示在 {@code ProtocolWireDemo}，它复用与测试同一套
 * {@code WireScenario} 编排，因此"看到的"与"断言住的"是同一件事。</p>
 */
@SpringBootApplication
public class ModelsProtocolsApplication {

    public static void main(String[] args) {
        // ① 先起桩服务：端口必须在配置绑定之前确定
        StubRegistry.stub();
        StubRegistry.registerShutdownHook();

        // ② 再把控制权交给 Spring
        SpringApplication.run(ModelsProtocolsApplication.class, args);

        // ③ 演示跑完就退出。
        // 为什么必须显式退出：桩服务用的是 JDK 自带的 com.sun.net.httpserver，
        // 它的 HTTP-Dispatcher 线程是【非 daemon】的，且不受 setExecutor 影响 ——
        // 演示（ApplicationRunner）跑完之后 JVM 不会自己结束，`java -jar` 会一直挂在那儿。
        // System.exit 会触发上面注册的 shutdown hook 关掉桩服务，于是"跑完即退出"成立。
        System.exit(0);
    }
}
