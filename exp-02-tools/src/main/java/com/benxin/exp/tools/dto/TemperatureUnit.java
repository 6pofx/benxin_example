package com.benxin.exp.tools.dto;

/**
 * 温度单位：演示"枚举参数 → JSON Schema 里的 {@code enum} 取值清单"。
 *
 * <p>绑定走的是 Jackson（{@code MethodToolCallback#bind} → {@code Json.convert}），
 * 因此模型给过来的字符串必须与<b>常量名完全一致</b>（大小写敏感，默认不开启
 * {@code ACCEPT_CASE_INSENSITIVE_ENUMS}）。{@code defaultValue} 也遵守同一条规则。</p>
 */
public enum TemperatureUnit {
    CELSIUS,
    FAHRENHEIT
}
