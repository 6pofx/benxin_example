package com.benxin.exp.sandbox;

import com.benxin.llm.spring.EnableLlmAgents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 03 —— 内置工具 + 沙箱 + 审批。
 *
 * <p>这个子项目只做一件事：把「模型想动本地文件/本地命令」这条最危险的路径，
 * 从最外面一层皮剥到最里面一层瓤：</p>
 *
 * <pre>
 *   llm.tools.builtin-enabled / allow-write / allow-exec   ← 三重开关决定「工具表里有什么」
 *   └─ PathSandbox / CommandSandbox                        ← 两道机器级围栏决定「能不能落地」
 *      └─ ApprovalPolicy + ApprovalHandler                 ← 人的点头决定「要不要执行」
 *         └─ 每个内置工具内部的再次自查                     ← 纵深防御：沙箱被换掉也守得住
 * </pre>
 *
 * <p>与 exp-01 一样：模型是 {@code exp-common} 的离线脚本化模型，
 * {@code mvn spring-boot:run} 立刻能看到全部结论，且<b>不联网、不消耗一个 token</b>。
 * 所有文件操作都落在本模块的 {@code target/sandbox} 之内，{@code bash} 只跑 {@code echo}。</p>
 */
@SpringBootApplication
@EnableLlmAgents(basePackages = "com.benxin.exp.sandbox")
public class BuiltinSandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuiltinSandboxApplication.class, args);
    }
}
