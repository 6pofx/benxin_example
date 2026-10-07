package com.benxin.exp.sandbox.config;

import com.benxin.exp.sandbox.workspace.SandboxWorkspace;
import com.benxin.llm.core.sandbox.PathSandbox;
import com.benxin.llm.core.sandbox.SandboxConfig;
import com.benxin.llm.core.sandbox.ToolSandbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把"运行期真正生效的那份沙箱配置"暴露成一个 {@link SandboxConfig} bean。
 *
 * <p><b>这不是本心提供的 bean</b> —— 插件只暴露 {@link ToolSandbox}（默认
 * {@code CompositeSandbox(PathSandbox, CommandSandbox)}）。示例额外把它内部那份
 * {@link SandboxConfig} 取出来给容器，好处是：演示与测试可以直接注入"正在生效的配置"，
 * 而不是各自再读一遍 yml 手算一遍（手算出来的那份一旦与装配结果不一致，
 * 演示就会变成"看起来对、其实不是运行期那一份"）。</p>
 */
@Configuration
public class SandboxConfigViewConfiguration {

    @Bean
    public SandboxConfig sandboxConfig(ToolSandbox sandbox) {
        PathSandbox pathSandbox = SandboxWorkspace.pathSandboxOf(sandbox);
        return pathSandbox == null ? SandboxConfig.defaults() : pathSandbox.config();
    }
}
