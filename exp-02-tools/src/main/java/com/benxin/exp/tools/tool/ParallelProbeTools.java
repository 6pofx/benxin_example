package com.benxin.exp.tools.tool;

import com.benxin.exp.tools.support.ConcurrencyRecorder;
import com.benxin.llm.core.annotation.LlmTool;
import com.benxin.llm.core.annotation.LlmToolParam;

/**
 * 并行安全探针（注解路径）。
 *
 * <p>注解工具<b>永远</b>是 {@code parallelSafe() == true}：{@code @LlmTool} 没有这个属性，
 * {@code MethodToolCallback} 也没覆写它，于是只剩 {@code ToolCallback} 的默认实现 true。
 * 想声明"我不能并行"必须走程序化 {@code ToolCallback}（见
 * {@code config/ProgrammaticToolConfiguration} 里的 {@code serial_probe}）。</p>
 *
 * <p>并行判定在 {@code DefaultLoopContext.executeTools}：
 * <b>同一轮里所有</b>工具都 parallelSafe 才并行，只要有一个说不安全，
 * 整轮退化成串行 —— 是"全真才并行"，不是"逐个决定"。</p>
 */
public class ParallelProbeTools {

    private final ConcurrencyRecorder recorder;

    public ParallelProbeTools(ConcurrencyRecorder recorder) {
        this.recorder = recorder;
    }

    @LlmTool(name = "quick_probe",
            description = "睡 millis 毫秒后返回（注解工具默认 parallelSafe=true，可并行）")
    public String quick(@LlmToolParam(value = "id", description = "本次调用的标记") String id,
                        @LlmToolParam(value = "millis", description = "睡多久", required = false,
                                defaultValue = "300") long millis) {
        return recorder.record(id, millis, () -> "quick_probe(" + id + ") 在 "
                + Thread.currentThread().getName() + " 上完成");
    }
}
