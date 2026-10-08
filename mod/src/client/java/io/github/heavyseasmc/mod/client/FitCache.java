package io.github.heavyseasmc.mod.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个小的「最近用过」缓存（LRU），给 {@link GuiText} 存量字的结果（审查 2026-10-07 P4）。
 *
 * <p>已经到手、不会再变的字（航海日志里那几十条、按钮上的字）原先每帧都重新折行、一个字一个字地量宽：
 * 日志拉伸时英文每帧约 831 次量宽。结果只取决于（字 · 框宽 · 字号 · 粗细 · 行数 · 策略），存下来下一帧直接拿。
 * 满了挤掉最久没用的那一个。只在渲染线程上用，不加锁。
 */
final class FitCache<K, V> {

    private final Map<K, V> entries;

    FitCache(int capacity) {
        this.entries = new LinkedHashMap<>(Math.max(16, capacity * 4 / 3 + 1), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /** 有就拿存着的（并记作刚用过），没有就算一次存下。 */
    V get(K key, Function<K, V> compute) {
        V hit = entries.get(key);
        if (hit != null) {
            return hit;
        }
        V value = compute.apply(key);
        entries.put(key, value);
        return value;
    }

    int size() {
        return entries.size();
    }

    /** 字体换了（资源重载、换语言）：存着的宽度全作废。 */
    void clear() {
        entries.clear();
    }
}
