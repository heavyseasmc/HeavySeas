package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class NotificationHistoryTest {
    @Test
    void everyPacketIsMergedEvenWhenNoFrameWasRenderedBetweenThem() {
        NotificationHistory<String> history = new NotificationHistory<>();
        UUID game = UUID.randomUUID();
        for (int i = 1; i <= 80; i++) {
            history.accept(game, i, i, List.of("same message"));
        }
        assertEquals(80, history.entries().size());
        history.accept(game, 65, 80, java.util.Collections.nCopies(16, "same message"));
        assertEquals(80, history.entries().size(), "重送的重叠部分不能重复进历史");
        List<String> before = history.entries();
        history.accept(game, 81, 80, List.of());
        assertSame(before, history.entries(), "没有新播报时复用文本对象与历史");
        assertEquals(0, history.missing());
    }

    @Test
    void aDifferentGameWithTheSameSequenceReplacesOldHistory() {
        NotificationHistory<String> history = new NotificationHistory<>();
        history.accept(UUID.randomUUID(), 1, 1, List.of("first game"));
        history.accept(UUID.randomUUID(), 1, 1, List.of("second game"));
        assertEquals(List.of("second game"), history.entries());
    }

    @Test
    void retentionIsBoundedAndExpiredGapsAreReported() {
        NotificationHistory<Integer> history = new NotificationHistory<>();
        UUID epoch = UUID.randomUUID();
        history.accept(epoch, 101, 500, IntStream.rangeClosed(101, 500).boxed().toList());
        assertEquals(100, history.missing());
        history.accept(epoch, 501, 501, List.of(501));
        assertEquals(NotificationHistory.LIMIT, history.entries().size());
        assertEquals(102, history.entries().getFirst());
    }

    @Test
    void packetValidationCanDiscardAWorkingCopyWithoutChangingPublishedState() {
        NotificationHistory<String> history = new NotificationHistory<>();
        UUID epoch = UUID.randomUUID();
        history.accept(epoch, 1, 1, List.of("accepted"));
        NotificationHistory<String> working = history.copy();
        working.accept(epoch, 2, 2, List.of("not committed"));
        assertEquals(List.of("accepted"), history.entries());
        assertThrows(IllegalArgumentException.class, () -> working.accept(epoch, 4, 6, List.of("wrong size")));
    }
}
