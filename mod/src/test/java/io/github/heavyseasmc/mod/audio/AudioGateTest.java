package io.github.heavyseasmc.mod.audio;

import org.junit.jupiter.api.Test;

import static io.github.heavyseasmc.mod.audio.AudioGate.Lane;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioGateTest {
    private final AudioGate gate = new AudioGate();

    @Test
    void everyLaneCanStartAtZeroAndLimitsAreIndependent() {
        for (Lane lane : Lane.values()) {
            assertTrue(gate.admit(request("same-id", lane), 0), lane.name());
        }
        assertTrue(gate.voiceBusy(0));
    }

    @Test
    void eventGlobalLimitIs120MsAndRejectedStartsDoNotExtendIt() {
        assertTrue(gate.admit(request("a", Lane.EVENT), 0));
        assertFalse(gate.admit(request("b", Lane.EVENT), 119));
        assertTrue(gate.admit(request("b", Lane.EVENT), 120));
    }

    @Test
    void eventPerIdLimitIs500MsAndOtherIdsMayProceed() {
        assertTrue(gate.admit(request("a", Lane.EVENT), 0));
        assertTrue(gate.admit(request("b", Lane.EVENT), 120));
        assertFalse(gate.admit(request("a", Lane.EVENT), 499));
        assertTrue(gate.admit(request("a", Lane.EVENT), 500));
    }

    @Test
    void uiGlobalLimitIs75Ms() {
        assertTrue(gate.admit(request("a", Lane.UI), 0));
        assertFalse(gate.admit(request("b", Lane.UI), 74));
        assertTrue(gate.admit(request("b", Lane.UI), 75));
    }

    @Test
    void uiPerIdLimitIs120Ms() {
        assertTrue(gate.admit(request("a", Lane.UI), 0));
        assertFalse(gate.admit(request("a", Lane.UI), 119));
        assertTrue(gate.admit(request("a", Lane.UI), 120));
    }

    @Test
    void alertGlobalLimitAppliesAcrossIdsAndIgnoresPriority() {
        assertTrue(gate.admit(request("a", Lane.ALERT), 0));
        assertFalse(gate.admit(request("b", Lane.ALERT, null, 100, 1), 299));
        assertTrue(gate.admit(request("b", Lane.ALERT), 300));
        assertTrue(gate.admit(request("b", Lane.ALERT), 600));
    }

    @Test
    void ambientHasNoGlobalLimitAndAtLeastFourSecondsPerId() {
        assertTrue(gate.admit(request("a", Lane.AMBIENT), 0));
        assertTrue(gate.admit(request("b", Lane.AMBIENT), 0));
        assertFalse(gate.admit(request("a", Lane.AMBIENT), 3_999));
        assertTrue(gate.admit(request("a", Lane.AMBIENT), 4_000));
    }

    @Test
    void ambientPaddingUsesPreviouslyAdmittedDurationNotIncomingDuration() {
        assertTrue(gate.admit(request("a", Lane.AMBIENT, null, 0, 5_000), 0));
        assertFalse(gate.admit(request("a", Lane.AMBIENT), 5_999));
        assertTrue(gate.admit(request("a", Lane.AMBIENT), 6_000));
        assertFalse(gate.admit(request("a", Lane.AMBIENT), 9_999));
        assertTrue(gate.admit(request("a", Lane.AMBIENT), 10_000));
    }

    @Test
    void priorityCannotBypassNonVoiceLaneLimits() {
        for (Lane lane : new Lane[] {Lane.UI, Lane.EVENT, Lane.ALERT, Lane.AMBIENT}) {
            assertTrue(gate.admit(request("same", lane), 0));
            assertFalse(gate.admit(request("same", lane, null, Integer.MAX_VALUE, 1), 0));
        }
    }

    @Test
    void voiceCooldownIsEightSecondsBetweenStartsNotFinishes() {
        assertTrue(gate.admit(request("a", Lane.VOICE, null, 0, 3_000), 0));
        assertFalse(gate.voiceBusy(3_000));
        assertFalse(gate.admit(request("b", Lane.VOICE), 7_999));
        assertTrue(gate.admit(request("b", Lane.VOICE), 8_000));
    }

    @Test
    void voiceFinishedClearsBusyButRetainsCooldownAndIsIdempotent() {
        gate.voiceFinished();
        assertTrue(gate.admit(request("a", Lane.VOICE), 0));
        gate.voiceFinished();
        gate.voiceFinished();
        assertFalse(gate.voiceBusy(0));
        assertFalse(gate.admit(request("b", Lane.VOICE), 7_999));
        assertTrue(gate.admit(request("b", Lane.VOICE), 8_000));
    }

    @Test
    void priority90BypassesCooldownBut89DoesNotAndUrgentStartRestartsCooldown() {
        assertTrue(gate.admit(request("a", Lane.VOICE), 0));
        gate.voiceFinished();
        assertFalse(gate.admit(request("b", Lane.VOICE, null, 89, 1), 100));
        assertTrue(gate.admit(request("c", Lane.VOICE, null, 90, 1), 100));
        gate.voiceFinished();
        assertFalse(gate.admit(request("d", Lane.VOICE), 8_099));
        assertTrue(gate.admit(request("d", Lane.VOICE), 8_100));
    }

    @Test
    void urgentVoiceNeverInterruptsEvenAfterCooldownHasElapsed() {
        assertTrue(gate.admit(request("a", Lane.VOICE, null, 0, 20_000), 0));
        assertFalse(gate.admit(request("b", Lane.VOICE, null, Integer.MAX_VALUE, 1), 8_000));
        assertFalse(gate.admit(request("c", Lane.VOICE), 19_999));
        assertTrue(gate.voiceBusy(19_999));
        assertTrue(gate.admit(request("d", Lane.VOICE), 20_000));
    }

    @Test
    void rejectedVoicesAreNotQueuedAndDoNotConsumeDedupe() {
        assertTrue(gate.admit(request("a", Lane.VOICE), 0));
        assertFalse(gate.admit(request("b", Lane.VOICE, "dropped", 90, 10_000), 1));
        assertFalse(gate.voiceBusy(200), "Dropped urgent voice must not start on completion");
        assertFalse(gate.admit(request("ordinary", Lane.VOICE, "ordinary-key", 0, 10_000), 201));
        assertFalse(gate.voiceBusy(8_000), "There is no delayed ordinary voice either");
        assertTrue(gate.admit(request("fresh-event", Lane.VOICE, "dropped", 0, 1), 8_000));
        gate.voiceFinished();
        assertTrue(gate.admit(request("new-ordinary-event", Lane.VOICE, "ordinary-key", 0, 1), 16_000));
    }

    @Test
    void voiceBusyTimeClampsShortDurationsTo200Ms() {
        assertTrue(gate.admit(request("a", Lane.VOICE, null, 0, 1), 0));
        assertTrue(gate.voiceBusy(199));
        assertFalse(gate.voiceBusy(200));
        assertTrue(gate.admit(request("b", Lane.VOICE, null, 90, 200), 200));
        assertTrue(gate.voiceBusy(399));
        assertFalse(gate.voiceBusy(400));
    }

    @Test
    void voiceBusyTimeClampsHugeDurationsTo30SecondsWithoutOverflow() {
        assertTrue(gate.admit(request("a", Lane.VOICE, null, 0, Long.MAX_VALUE), 0));
        assertTrue(gate.voiceBusy(29_999));
        assertFalse(gate.voiceBusy(30_000));
        assertTrue(gate.admit(request("b", Lane.VOICE, null, 0, 30_000), 30_000));
        assertTrue(gate.voiceBusy(59_999));
        assertFalse(gate.voiceBusy(60_000));
    }

    @Test
    void expiryIsExclusiveAndExpiredEventsReserveNothing() {
        for (Lane lane : Lane.values()) {
            assertFalse(gate.admit(new AudioGate.Request("a", lane, "key", -1, 100, 1), 0));
            assertFalse(gate.admit(new AudioGate.Request("a", lane, "key", 0, 100, 1), 0));
            assertTrue(gate.admit(new AudioGate.Request("a", lane, "key", 1, 100, 1), 0));
            gate.reset();
        }
    }

    @Test
    void dedupeIsGlobalEvenForUrgentVoicesAndDifferentIds() {
        assertTrue(gate.admit(request("a", Lane.UI, "event-1", 0, 1), 0));
        assertFalse(gate.admit(request("b", Lane.VOICE, "event-1", 100, 1), 0));
        assertFalse(gate.voiceBusy(0));
        assertTrue(gate.admit(request("b", Lane.VOICE, "event-2", 0, 1), 0));
    }

    @Test
    void dedupeTtlIs30SecondsFromAdmissionNotFromLastDeniedAttempt() {
        assertTrue(gate.admit(request("a", Lane.AMBIENT, "key", 0, 1), 0));
        assertFalse(gate.admit(request("b", Lane.EVENT, "key", 0, 1), 29_999));
        assertTrue(gate.admit(request("b", Lane.EVENT, "key", 0, 1), 30_000));
        assertFalse(gate.admit(request("c", Lane.UI, "key", 0, 1), 59_999));
        assertTrue(gate.admit(request("c", Lane.UI, "key", 0, 1), 60_000));
    }

    @Test
    void throttledRequestsDoNotRememberKeysOrTheirOwnIdWindow() {
        assertTrue(gate.admit(request("a", Lane.EVENT), 0));
        assertFalse(gate.admit(request("b", Lane.EVENT, "retry", 0, 1), 119));
        assertTrue(gate.admit(request("b", Lane.EVENT, "retry", 0, 1), 120));
    }

    @Test
    void dedupeRejectedRequestDoesNotStartLaneCooldown() {
        assertTrue(gate.admit(request("a", Lane.AMBIENT, "same", 0, 1), 0));
        assertFalse(gate.admit(request("b", Lane.UI, "same", 0, 1), 0));
        assertTrue(gate.admit(request("b", Lane.UI, "different", 0, 1), 0));
    }

    @Test
    void nullEmptyAndBlankKeysDisableDedupe() {
        for (String key : new String[] {null, "", " \t"}) {
            assertTrue(gate.admit(request("a", Lane.UI, key, 0, 1), 0));
            assertTrue(gate.admit(request("b", Lane.EVENT, key, 0, 1), 0));
            gate.reset();
        }
    }

    @Test
    void dedupeRetains256KeysThenEvictsOldestAdmissionNotLeastRecentlyDenied() {
        for (int i = 0; i < 256; i++) {
            assertTrue(gate.admit(request("ambient-" + i, Lane.AMBIENT, "key-" + i, 0, 1), 0));
        }
        assertFalse(gate.admit(request("probe", Lane.EVENT, "key-0", 0, 1), 0));
        assertTrue(gate.admit(request("ambient-256", Lane.AMBIENT, "key-256", 0, 1), 0));
        assertFalse(gate.admit(request("probe", Lane.EVENT, "key-1", 0, 1), 0));
        assertFalse(gate.admit(request("probe", Lane.EVENT, "key-256", 0, 1), 0));
        assertTrue(gate.admit(request("probe", Lane.EVENT, "key-0", 0, 1), 0));
    }

    @Test
    void rejectedRequestsDoNotEvictAdmittedKeys() {
        for (int i = 0; i < 256; i++) {
            assertTrue(gate.admit(request("ambient-" + i, Lane.AMBIENT, "key-" + i, 0, 1), 0));
        }
        for (int i = 0; i < 300; i++) {
            assertFalse(gate.admit(request("ambient-0", Lane.AMBIENT, "rejected-" + i, 0, 1), 0));
        }
        assertFalse(gate.admit(request("probe", Lane.EVENT, "key-0", 0, 1), 0));
        assertTrue(gate.admit(request("probe", Lane.EVENT, "rejected-0", 0, 1), 0));
    }

    @Test
    void resetClearsAllLaneWindowsVoiceAndDedupeAndAllowsNewClockOrigin() {
        for (Lane lane : Lane.values()) {
            assertTrue(gate.admit(request("same", lane, lane.name(), 0, 10_000), 1_000));
        }
        gate.reset();
        gate.reset();
        assertFalse(gate.voiceBusy(0));
        for (Lane lane : Lane.values()) {
            assertTrue(gate.admit(request("same", lane, lane.name(), 0, 10_000), 0), lane.name());
        }
    }

    @Test
    void malformedRequestsFailFast() {
        assertThrows(NullPointerException.class, () -> request(null, Lane.UI));
        assertThrows(IllegalArgumentException.class, () -> request(" \t", Lane.UI));
        assertThrows(NullPointerException.class, () -> request("id", null));
        assertThrows(NullPointerException.class, () -> gate.admit(null, 0));
        for (Lane lane : Lane.values()) {
            for (long duration : new long[] {0, -1, Long.MIN_VALUE}) {
                assertThrows(IllegalArgumentException.class,
                        () -> request("id", lane, null, 0, duration));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> request("id", Lane.AMBIENT, null, 0, Long.MAX_VALUE - 999));
    }

    @Test
    void largestSafeAmbientDurationDoesNotOverflow() {
        assertTrue(gate.admit(request("a", Lane.AMBIENT, null, 0, Long.MAX_VALUE - 1_000), -1));
        assertFalse(gate.admit(request("a", Lane.AMBIENT), Long.MAX_VALUE - 2));
        assertTrue(gate.admit(request("a", Lane.AMBIENT), Long.MAX_VALUE - 1));
    }

    @Test
    void nearLongMaximumDoesNotWrapVoiceOrCooldownDeadlines() {
        long start = Long.MAX_VALUE - 1_000;
        assertTrue(gate.admit(request("voice", Lane.VOICE, null, 0, 30_000), start));
        assertTrue(gate.voiceBusy(Long.MAX_VALUE - 1));
        assertTrue(gate.admit(request("ui", Lane.UI), start));
        assertFalse(gate.admit(request("ui", Lane.UI), start + 119));
        assertTrue(gate.admit(request("ui", Lane.UI), start + 120));
    }

    @Test
    void elapsedComparisonsHandleLongSubtractionOverflowAndTrimOldKeys() {
        assertTrue(gate.admit(request("a", Lane.VOICE, "old", 0, 30_000), Long.MIN_VALUE));
        assertTrue(gate.admit(request("b", Lane.EVENT), Long.MIN_VALUE));
        assertFalse(gate.voiceBusy(0));
        assertTrue(gate.admit(request("a", Lane.VOICE, "old", 0, 1), 0));
        assertTrue(gate.admit(request("b", Lane.EVENT), 0));
    }

    private static AudioGate.Request request(String id, Lane lane) {
        return request(id, lane, null, 0, 1);
    }

    private static AudioGate.Request request(String id, Lane lane, String key, int priority, long duration) {
        return new AudioGate.Request(id, lane, key, Long.MAX_VALUE, priority, duration);
    }
}