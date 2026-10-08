package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 量字结果的缓存（审查 2026-10-07 P4）：已经到手、不会再变的字不再每帧折行量宽。
 */
class FitCacheTest {

    @Test
    @DisplayName("同一个键只量一次")
    void computesOncePerKey() {
        FitCache<String, Integer> cache = new FitCache<>(4);
        AtomicInteger calls = new AtomicInteger();
        for (int frame = 0; frame < 60; frame++) {
            assertEquals(5, cache.get("hello", s -> {
                calls.incrementAndGet();
                return s.length();
            }));
        }
        assertEquals(1, calls.get(), "60 帧量了 " + calls.get() + " 次");
    }

    @Test
    @DisplayName("满了挤掉最久没用的那一个；刚用过的留着")
    void evictsLeastRecentlyUsed() {
        FitCache<String, Integer> cache = new FitCache<>(2);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<String, Integer> measure = s -> {
            calls.incrementAndGet();
            return s.length();
        };
        cache.get("a", measure);
        cache.get("bb", measure);
        cache.get("a", measure);                     // a 刚用过
        cache.get("ccc", measure);                   // 挤掉 bb
        assertEquals(2, cache.size());
        assertEquals(3, calls.get());
        cache.get("a", measure);
        assertEquals(3, calls.get(), "刚用过的 a 被挤掉了");
        cache.get("bb", measure);
        assertEquals(4, calls.get(), "bb 应当已被挤掉、重新量");
        cache.clear();
        assertEquals(0, cache.size());
    }
}
