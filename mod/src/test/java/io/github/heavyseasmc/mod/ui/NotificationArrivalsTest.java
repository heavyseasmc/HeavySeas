package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationArrivalsTest {

    private static final List<String> FULL = List.of("a", "b", "c", "d", "e", "f", "g", "h");

    @Test
    void nothingNewIsZero() {
        assertEquals(0, NotificationArrivals.count(FULL, FULL));
        assertEquals(0, NotificationArrivals.count(List.of(), List.of()));
    }

    @Test
    void growingListCountsTheAppendedOnes() {
        assertEquals(2, NotificationArrivals.count(List.of("a"), List.of("a", "b", "c")));
        assertEquals(1, NotificationArrivals.count(List.of(), List.of("a")));
    }

    /**
     * ❗这一条是它存在的理由：满了之后条数不变，新来一条与什么都没来条数一样。
     * 只比条数的写法在这里给 0（2026-09-30 实拍：开局不久右栏就再也不自己滑出来）。
     */
    @Test
    void aFullListThatScrolledStillCountsTheNewOne() {
        List<String> next = List.of("b", "c", "d", "e", "f", "g", "h", "i");
        assertEquals(1, NotificationArrivals.count(FULL, next));
        List<String> twoMore = List.of("c", "d", "e", "f", "g", "h", "i", "j");
        assertEquals(2, NotificationArrivals.count(FULL, twoMore));
    }

    @Test
    void anUnrelatedListIsAllNew() {
        assertEquals(3, NotificationArrivals.count(FULL, List.of("x", "y", "z")));
    }
}
