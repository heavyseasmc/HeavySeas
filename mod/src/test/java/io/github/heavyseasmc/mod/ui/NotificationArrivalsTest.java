package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationArrivalsTest {

    @Test
    void nothingNewIsZero() {
        assertEquals(0, NotificationArrivals.count(8, 8, 8));
        assertEquals(0, NotificationArrivals.count(0, 0, 0));
    }

    @Test
    void theDifferenceIsWhatArrived() {
        assertEquals(2, NotificationArrivals.count(1, 3, 3));
        assertEquals(1, NotificationArrivals.count(0, 1, 1));
    }

    /**
     * 满了之后条数不变、内容也可能一模一样 —— 新来一条照样数得出（2026-09-30 实拍：按条数判时开局不久右栏就再也不自己滑出来；
     * 审查 U13：按内容判时一模一样的播报接连来会数成 0）。
     */
    @Test
    void aFullListThatScrolledStillCountsTheNewOne() {
        assertEquals(1, NotificationArrivals.count(40, 41, 16));
        assertEquals(2, NotificationArrivals.count(40, 42, 16));
    }

    @Test
    void moreThanTheProjectionCarriesIsCappedAtWhatCameAlong() {
        assertEquals(16, NotificationArrivals.count(0, 30, 16));
    }

    @Test
    void aNewGameCountsWhatThisGameHasPlayed() {
        assertEquals(3, NotificationArrivals.count(57, 3, 3));
    }
}
