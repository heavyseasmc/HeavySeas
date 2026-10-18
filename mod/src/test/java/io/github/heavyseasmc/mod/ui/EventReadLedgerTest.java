package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventReadLedgerTest {
    @Test
    void privateSequenceGapsAndOffscreenArrivalsDoNotCountAsRead() {
        EventReadLedger ledger = new EventReadLedger();
        ledger.retain("voyage", List.of(1L, 8L, 15L));
        ledger.visible(8);
        assertEquals(1, ledger.unread(), "1 is older than the seen 8 (caught up); 15 has not been on screen");
        ledger.retain("voyage", List.of(1L, 8L, 15L, 21L));
        assertEquals(2, ledger.unread(), "New offscreen events remain unread while old history is visible");
        ledger.visible(1);
        assertTrue(ledger.unread(21));
        assertTrue(ledger.unread(15));
        assertEquals(2, ledger.unread());
    }

    /**
     * 2026-10-13 总截图：开局那一批是在补给箱开着、日志没钉住时到的，钉住时已经被新播报压到栏底以下 ——
     * 栏头「未读 8」从开局挂到终局，新播报一来就在栏顶被看见，数字一次都不动。
     * 看到了最新的那一条，就是追上了：比它旧、从没露过面的不再算未读。
     */
    @Test
    void seeingTheNewestCatchesUpOnOlderEventsThatNeverReachedTheScreen() {
        EventReadLedger ledger = new EventReadLedger();
        List<Long> opening = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        ledger.retain("voyage", opening);                       // 开局一批：界面开着、日志收着
        assertEquals(8, ledger.unread());
        ledger.retain("voyage", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
        ledger.visible(10);                                      // 钉住：栏顶是最新的 10、9，旧的 8 条在栏底以下
        ledger.visible(9);
        assertEquals(0, ledger.unread(), "看过最新的一条：开局那 8 条不再挂着");
        ledger.retain("voyage", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 12L));
        assertEquals(1, ledger.unread(), "之后来的一条照样算未读，数字会动");
        assertTrue(ledger.unread(12));
        ledger.visible(12);
        assertEquals(0, ledger.unread());
    }

    @Test
    void scrollingThroughLongEventsRequiresEveryLine() {
        EventReadLedger ledger = new EventReadLedger();
        ledger.retain("a", List.of(2L));
        ledger.visiblePart(2, 0, 3);
        ledger.visiblePart(2, 2, 3);
        assertTrue(ledger.unread(2));
        ledger.visiblePart(2, 1, 3);
        assertFalse(ledger.unread(2));
    }

    @Test
    void reflowCannotCombineDifferentPartialLayoutsIntoAReadEvent() {
        EventReadLedger ledger = new EventReadLedger();
        ledger.retain("a", List.of(2L));
        ledger.visiblePart(2, "wide", 0, 2);
        ledger.visiblePart(2, "narrow", 1, 2);
        assertTrue(ledger.unread(2));
        ledger.visiblePart(2, "narrow", 0, 2);
        assertFalse(ledger.unread(2));
    }

    @Test
    void repeatedSnapshotsRetainReadStateButNewMatchesDoNot() {
        EventReadLedger ledger = new EventReadLedger();
        ledger.retain("a", List.of(1L, 3L));
        ledger.visible(1);
        ledger.retain("a", List.of(1L, 3L));
        assertEquals(1, ledger.unread());
        ledger.retain("a", List.of(3L, 4L));
        assertEquals(2, ledger.unread());
        ledger.retain("b", List.of(1L));
        assertEquals(1, ledger.unread());
        ledger.visible(99);
        assertEquals(1, ledger.unread());
    }
}
