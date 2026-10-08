package io.github.heavyseasmc.mod.state;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 同一线程内合并重复同步请求；专用包需要先发投影时可以显式 flush。 */
final class SyncBatcher<K> {
    private final Set<K> dirty = new LinkedHashSet<>();

    void mark(K key) {
        dirty.add(key);
    }

    void flush(K key, Consumer<K> writer) {
        // 先移除：写入过程中新增的变化留到下一轮，不递归也不吞掉。
        if (dirty.remove(key)) {
            writer.accept(key);
        }
    }

    void forget(Predicate<K> expired) {
        dirty.removeIf(expired);
    }
}
