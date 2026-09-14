package io.github.heavyseasmc.engine.data;

import java.util.Objects;

/**
 * 一份数值数据，连同它<b>自称</b>的身份。
 *
 * <h2>为什么要把 id 带出来</h2>
 * 三份数据（角色 · 物资 · 航海牌）是一套配平，必须来自同一个变体。加载器各读各的文件，
 * 谁也不知道另外两份是从哪儿来的 —— 于是「角色表被换成了另一套、另外两份没换」这种局面，
 * 四层校验<b>一层都不会红</b>：每份文件单看都合法。
 *
 * <p>文件里的 {@code id} 本来就是为这件事留的（M0 期间三个加载器都放行它而不读）。
 * 把它带出来之后，调用方才有可能做那条<b>跨文件</b>的检查：三份的 id 必须相同。
 * 这类性质用单份数据根本表达不了 —— 与「死人不该带着行动标记」是同一种东西。
 *
 * @param id    文件顶层的 {@code id}，形如 {@code heavyseas:default}
 * @param value 读出来的内容
 */
public record DataDocument<T>(String id, T value) {

    public DataDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
    }
}
