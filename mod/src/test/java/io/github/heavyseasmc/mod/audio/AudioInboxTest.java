package io.github.heavyseasmc.mod.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioInboxTest {
    private static final String DIMENSION = "heavyseas:mist_sea";
    private static final String OTHER_DIMENSION = "minecraft:overworld";
    private final AudioInbox inbox = new AudioInbox();

    @Test
    void firstSameDimensionEventBeforeAnyProjectionIsNotDiscarded() {
        AudioInbox.Event event = event("a", 1);
        inbox.offer(event, 0);
        assertEquals(List.of(event), inbox.drain(DIMENSION, "a", true, 0));
        assertTrue(inbox.drain(DIMENSION, "a", true, 0).isEmpty());
    }

    @Test
    void initialInactiveProjectionCanHoldUnknownFutureEpochUntil499Ms() {
        AudioInbox.Event event = event("future", 1);
        inbox.offer(event, 0);
        assertTrue(inbox.drain(DIMENSION, null, false, 0).isEmpty());
        assertTrue(inbox.drain(DIMENSION, "unprojected", false, 400).isEmpty());
        assertEquals(List.of(event), inbox.drain(DIMENSION, "future", true, 499));
    }

    @Test
    void ttlExpiresExactlyAt500MsAndSnapshotsDoNotReplayIt() {
        inbox.offer(event("a", 1), 0);
        assertTrue(inbox.drain(DIMENSION, null, false, 499).isEmpty());
        assertTrue(inbox.drain(DIMENSION, "a", true, 500).isEmpty());
        assertTrue(inbox.drain(DIMENSION, "a", true, 501).isEmpty());
    }

    @Test
    void eachEventHasItsOwnTtl() {
        AudioInbox.Event fresh = event("a", 2);
        inbox.offer(event("a", 1), 0);
        inbox.offer(fresh, 1);
        assertEquals(List.of(fresh), inbox.drain(DIMENSION, "a", true, 500));
    }

    @Test
    void duplicatePendingOffersDoNotRefreshTtlOrReplacePayload() {
        AudioInbox.Event original = event("a", 1);
        inbox.offer(original, 0);
        inbox.offer(new AudioInbox.Event(DIMENSION, "a", 1, AudioCue.THIRST, "different"), 499);
        assertEquals(List.of(original), inbox.drain(DIMENSION, "a", true, 499));
        inbox.clear();
        inbox.offer(original, 0);
        inbox.offer(original, 499);
        assertTrue(inbox.drain(DIMENSION, "a", true, 500).isEmpty());
    }

    @Test
    void drainsStrictlyIncreasingSequenceInArrivalOrderWithoutReorderingBacklog() {
        AudioInbox.Event first = event("a", 5);
        AudioInbox.Event second = event("a", 6);
        inbox.offer(first, 0);
        inbox.offer(event("a", 4), 0);
        inbox.offer(first, 0);
        inbox.offer(second, 0);
        assertEquals(List.of(first, second), inbox.drain(DIMENSION, "a", true, 0));
        inbox.offer(event("a", 5), 1);
        inbox.offer(event("a", 6), 1);
        assertTrue(inbox.drain(DIMENSION, "a", true, 1).isEmpty());
    }

    @Test
    void firstSequenceCanBeZeroAndLongMaximumCannotWrapHighWater() {
        AudioInbox.Event zero = event("a", 0);
        AudioInbox.Event maximum = event("a", Long.MAX_VALUE);
        inbox.offer(zero, 0);
        inbox.offer(maximum, 0);
        assertEquals(List.of(zero, maximum), inbox.drain(DIMENSION, "a", true, 0));
        inbox.offer(event("a", 0), 1);
        inbox.offer(maximum, 1);
        assertTrue(inbox.drain(DIMENSION, "a", true, 1).isEmpty());
    }

    @Test
    void futureEpochWaitsForMatchingProjectionAndResetsSequence() {
        inbox.offer(event("a", 100), 0);
        assertEquals(1, inbox.drain(DIMENSION, "a", true, 0).size());
        AudioInbox.Event future = event("b", 1);
        inbox.offer(future, 1);
        assertTrue(inbox.drain(DIMENSION, "a", true, 100).isEmpty());
        assertEquals(List.of(future), inbox.drain(DIMENSION, "b", true, 499));
    }

    @Test
    void endingActiveEpochDiscardsItsEventsButPreservesFutureEpoch() {
        inbox.drain(DIMENSION, "a", true, 0);
        inbox.offer(event("a", 1), 1);
        AudioInbox.Event future = event("b", 0);
        inbox.offer(future, 1);
        assertTrue(inbox.drain(DIMENSION, "a", false, 2).isEmpty());
        inbox.offer(event("a", 2), 3);
        assertTrue(inbox.drain(DIMENSION, null, false, 3).isEmpty());
        assertTrue(inbox.drain(DIMENSION, "a", true, 4).isEmpty());
        assertEquals(List.of(future), inbox.drain(DIMENSION, "b", true, 4));
    }

    @Test
    void inactiveNullEpochStillRetiresPreviouslyActiveEpoch() {
        inbox.drain(DIMENSION, "a", true, 0);
        inbox.drain(DIMENSION, null, false, 1);
        inbox.offer(event("a", 50), 2);
        assertTrue(inbox.drain(DIMENSION, "a", true, 2).isEmpty());
    }

    @Test
    void replacementRetiresOldEpochBeforeLateEventsCanEvictCurrentEvents() {
        inbox.drain(DIMENSION, "a", true, 0);
        inbox.drain(DIMENSION, "b", true, 0);
        AudioInbox.Event useful = event("b", 1);
        inbox.offer(useful, 0);
        for (int i = 0; i < 100; i++) {
            inbox.offer(event("a", i), 1);
        }
        assertEquals(List.of(useful), inbox.drain(DIMENSION, "b", true, 1));
    }

    @Test
    void staleRetiredProjectionDoesNotResetCurrentEpochHighWater() {
        inbox.drain(DIMENSION, "a", true, 0);
        inbox.offer(event("b", 10), 0);
        assertEquals(1, inbox.drain(DIMENSION, "b", true, 0).size());
        assertTrue(inbox.drain(DIMENSION, "a", true, 1).isEmpty());
        inbox.offer(event("b", 9), 1);
        AudioInbox.Event next = event("b", 11);
        inbox.offer(next, 1);
        assertEquals(List.of(next), inbox.drain(DIMENSION, "b", true, 1));
    }

    @Test
    void wrongDimensionIsDroppedRatherThanSavedForLaterVisit() {
        AudioInbox.Event wrong = new AudioInbox.Event(OTHER_DIMENSION, "a", 1, AudioCue.DRINK, "");
        AudioInbox.Event right = event("a", 1);
        inbox.offer(wrong, 0);
        inbox.offer(right, 0);
        assertEquals(List.of(right), inbox.drain(DIMENSION, "a", true, 0));
        assertTrue(inbox.drain(OTHER_DIMENSION, "a", true, 1).isEmpty());
    }

    @Test
    void inactiveDrainAlsoDropsWrongDimension() {
        inbox.offer(new AudioInbox.Event(OTHER_DIMENSION, "a", 1, AudioCue.DRINK, ""), 0);
        inbox.drain(DIMENSION, null, false, 0);
        assertTrue(inbox.drain(OTHER_DIMENSION, "a", true, 1).isEmpty());
    }

    @Test
    void dimensionChangeKeepsFirstMatchingPacketAndScopesRetirementByDimension() {
        inbox.drain(DIMENSION, "same-epoch", true, 0);
        AudioInbox.Event next = new AudioInbox.Event(OTHER_DIMENSION, "same-epoch", 0, AudioCue.DRINK, "");
        inbox.offer(next, 1);
        assertEquals(List.of(next), inbox.drain(OTHER_DIMENSION, "same-epoch", true, 1));
        inbox.offer(event("same-epoch", 1), 2);
        assertTrue(inbox.drain(DIMENSION, "same-epoch", true, 2).isEmpty());
    }

    @Test
    void capacityKeepsNewest16AndEvictsOldestPendingEvent() {
        for (int i = 0; i < 17; i++) {
            inbox.offer(event("a", i), 0);
        }
        List<AudioInbox.Event> result = inbox.drain(DIMENSION, "a", true, 0);
        assertEquals(16, result.size());
        for (int i = 0; i < 16; i++) {
            assertEquals(event("a", i + 1), result.get(i));
        }
    }

    @Test
    void pendingDuplicatesCannotEvictOtherEventsAtCapacity() {
        for (int i = 0; i < 16; i++) {
            inbox.offer(event("a", i), 0);
        }
        for (int i = 0; i < 100; i++) {
            inbox.offer(event("a", 15), 1);
        }
        List<AudioInbox.Event> result = inbox.drain(DIMENSION, "a", true, 1);
        assertEquals(16, result.size());
        assertEquals(event("a", 0), result.getFirst());
    }

    @Test
    void consumedSequencesCannotEvictUnknownFutureEpoch() {
        inbox.offer(event("a", 100), 0);
        inbox.drain(DIMENSION, "a", true, 0);
        AudioInbox.Event future = event("b", 1);
        inbox.offer(future, 1);
        for (int i = 0; i <= 100; i++) {
            inbox.offer(event("a", i), 1);
        }
        assertEquals(List.of(future), inbox.drain(DIMENSION, "b", true, 1));
    }

    @Test
    void retiredHistoryIsBoundedToMostRecent16AndRejectedOffersDoNotRefreshAge() {
        inbox.drain(DIMENSION, "epoch-0", true, 0);
        for (int i = 1; i <= 16; i++) {
            inbox.drain(DIMENSION, "epoch-" + i, true, 0);
        }
        inbox.offer(event("epoch-0", 1), 0); // Still one of the 16 retired epochs.
        inbox.drain(DIMENSION, "epoch-17", true, 0); // Retires 16; forgets oldest (0).
        inbox.offer(event("epoch-1", 1), 0); // Still retired; must be discarded.
        assertTrue(inbox.drain(DIMENSION, "epoch-1", true, 0).isEmpty());
        AudioInbox.Event forgotten = event("epoch-0", 1);
        inbox.offer(forgotten, 0);
        assertEquals(List.of(forgotten), inbox.drain(DIMENSION, "epoch-0", true, 0));
    }

    @Test
    void clearDropsPendingEventsAndAllLifecycleAndClockHistory() {
        inbox.offer(event("a", 100), 100);
        inbox.drain(DIMENSION, "a", true, 100);
        inbox.drain(DIMENSION, "b", true, 101);
        inbox.offer(event("b", 1), 102);
        inbox.clear();
        inbox.clear();
        assertTrue(inbox.drain(DIMENSION, "b", true, 0).isEmpty());
        AudioInbox.Event fresh = event("a", 0);
        inbox.offer(fresh, 0);
        assertEquals(List.of(fresh), inbox.drain(DIMENSION, "a", true, 0));
    }

    @Test
    void drainedResultIsImmutableAndLaterOperationsDoNotChangeIt() {
        AudioInbox.Event event = event("a", 1);
        inbox.offer(event, 0);
        List<AudioInbox.Event> result = inbox.drain(DIMENSION, "a", true, 0);
        assertThrows(UnsupportedOperationException.class, () -> result.add(event));
        inbox.clear();
        assertEquals(List.of(event), result);
    }

    @Test
    void ttlNearLongMaximumDoesNotOverflow() {
        AudioInbox.Event event = event("a", 1);
        inbox.offer(event, Long.MAX_VALUE - 499);
        assertEquals(List.of(event), inbox.drain(DIMENSION, "a", true, Long.MAX_VALUE));
        inbox.clear();
        inbox.offer(event, Long.MAX_VALUE - 500);
        assertTrue(inbox.drain(DIMENSION, "a", true, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void clockMustBeNonnegativeAndMonotonicAcrossBothOperations() {
        assertThrows(IllegalArgumentException.class, () -> inbox.offer(event("a", 1), -1));
        assertThrows(IllegalArgumentException.class, () -> inbox.drain(DIMENSION, null, false, -1));
        inbox.offer(event("a", 1), 10);
        assertThrows(IllegalArgumentException.class, () -> inbox.drain(DIMENSION, "a", true, 9));
        inbox.drain(DIMENSION, "a", true, 11);
        assertThrows(IllegalArgumentException.class, () -> inbox.offer(event("a", 2), 10));
        inbox.offer(event("a", 2), 11);
        assertEquals(List.of(event("a", 2)), inbox.drain(DIMENSION, "a", true, 11));
    }

    @Test
    void validatesEventAndProjectionInputsButAllowsEmptySpeakerAndInactiveNullEpoch() {
        assertThrows(NullPointerException.class, () -> inbox.offer(null, 0));
        assertThrows(NullPointerException.class,
                () -> new AudioInbox.Event(null, "a", 1, AudioCue.DRINK, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioInbox.Event(" ", "a", 1, AudioCue.DRINK, ""));
        assertThrows(NullPointerException.class,
                () -> new AudioInbox.Event(DIMENSION, null, 1, AudioCue.DRINK, ""));
        assertThrows(IllegalArgumentException.class, () -> event(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> event("a", -1));
        assertThrows(NullPointerException.class,
                () -> new AudioInbox.Event(DIMENSION, "a", 1, null, ""));
        assertThrows(NullPointerException.class,
                () -> new AudioInbox.Event(DIMENSION, "a", 1, AudioCue.DRINK, null));
        assertThrows(NullPointerException.class, () -> inbox.drain(null, null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> inbox.drain(" ", null, false, 0));
        assertThrows(NullPointerException.class, () -> inbox.drain(DIMENSION, null, true, 0));
        assertThrows(IllegalArgumentException.class, () -> inbox.drain(DIMENSION, " ", true, 0));
        assertTrue(inbox.drain(DIMENSION, null, false, 0).isEmpty());
        AudioInbox.Event event = event("a", 0);
        inbox.offer(event, 0);
        assertEquals(List.of(event), inbox.drain(DIMENSION, "a", true, 0));
    }

    private static AudioInbox.Event event(String epoch, long sequence) {
        return new AudioInbox.Event(DIMENSION, epoch, sequence, AudioCue.DRINK, "");
    }
}