package com.benxin.exp.models.support;

import com.benxin.exp.common.wire.StubHttpServer;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程级共享的本地 HTTP 桩服务。
 *
 * <p>为什么需要一个"注册表"而不是每处 {@code StubHttpServer.start()}：</p>
 * <ol>
 *   <li><b>端口必须能被配置读到</b>。{@code application.yml} 里四个协议的
 *       {@code base-url} 都写着 {@code ${exp.stub.base-url}}，而端口是启动后才确定的，
 *       因此需要在 Spring 绑定配置<b>之前</b>把 URL 放进系统属性。</li>
 *   <li><b>一个端口可以服务四个协议</b>。桩服务是按<b>路径</b>路由的
 *       （{@code /v1/chat/completions}、{@code /v1/messages}、
 *       {@code /v1beta/models/xxx:generateContent}、{@code /v1/responses}），
 *       因此四条通道共用一个端点完全够用，也让"同一份配置换个协议名"这件事更直观。</li>
 *   <li><b>绝不能误连外网</b>。桩服务只监听回环地址的随机端口，
 *       示例里所有模型的 base-url 都指向它 —— 这是"完全离线"这条硬性约定的执行点。</li>
 * </ol>
 */
public final class StubRegistry {

    /** 系统属性名：yml 里的 {@code ${exp.stub.base-url}} 就是它。 */
    public static final String BASE_URL_PROPERTY = "exp.stub.base-url";

    /** 桩服务自己的响应 id 前缀，与配置无关，仅用于日志辨识。 */
    private static final AtomicReference<StubHttpServer> STUB = new AtomicReference<>();

    private StubRegistry() {
    }

    /**
     * 取得（必要时启动）共享桩服务，并把它的 base-url 写进系统属性。
     *
     * <p>只能在一个 JVM 里被调用一次并保持到进程结束：一旦 Spring 已经绑定了配置，
     * 再换端口就会出现"配置指向旧端口"的错位，因此这里不做"关掉再起一个"。</p>
     */
    public static StubHttpServer stub() {
        StubHttpServer existing = STUB.get();
        if (existing != null) {
            return existing;
        }
        synchronized (StubRegistry.class) {
            StubHttpServer again = STUB.get();
            if (again != null) {
                return again;
            }
            StubHttpServer created = StubHttpServer.start();
            STUB.set(created);
            // 让 ${exp.stub.base-url} 解析得到真实端口；yml 里的默认值刻意留空，
            // 于是"没启动桩服务"与"桩服务在这个端口"两种状态在配置里一眼可辨。
            System.setProperty(BASE_URL_PROPERTY, created.baseUrl());
            return created;
        }
    }

    /** 当前 base-url；桩服务还没启动时返回 null。 */
    public static String baseUrl() {
        StubHttpServer current = STUB.get();
        return current == null ? null : current.baseUrl();
    }

    public static boolean running() {
        return STUB.get() != null;
    }

    /**
     * 进程退出时关闭桩服务。
     *
     * <p>桩服务的线程都是 daemon，本可以不关；但显式关掉能让"进程退出即释放端口"
     * 成为一条可被观察的事实，也避免 IDE 里反复重启时留下悬挂的监听端口。</p>
     */
    public static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StubHttpServer current = STUB.getAndSet(null);
            if (current != null) {
                current.close();
            }
        }, "stub-shutdown"));
    }
}
