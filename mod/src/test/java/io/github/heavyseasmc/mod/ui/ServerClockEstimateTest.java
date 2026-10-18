package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerClockEstimateTest {
    @Test void latencyIsAccountedForAndClientWallClockCannotMoveTheCountdown() {
        ServerClockEstimate clock = new ServerClockEstimate();
        assertTrue(clock.sample(1, 1_000_000_000L, 100_100, 1_200_000_000L));
        assertEquals(101_200, clock.now(2_200_000_000L, 900_000));
        assertEquals(101_200, clock.now(2_200_000_000L, 1));
        assertEquals(18_800, 120_000 - clock.now(2_200_000_000L, 123));
    }
    @Test void oldInvalidAndVerySlowSamplesCannotReplaceTheClock() {
        ServerClockEstimate clock = new ServerClockEstimate();
        clock.sample(2, 1_000_000_000L, 100_050, 1_100_000_000L);
        assertFalse(clock.sample(1, 0, 1, 2_000_000_000L));
        assertFalse(clock.sample(3, 3_000_000_000L, 1, 2_000_000_000L));
        assertFalse(clock.sample(4, 0, 1, 6_000_000_000L));
        assertFalse(clock.sample(5, 1_000_000_000L, 1, 2_000_000_000L));
        assertEquals(101_000, clock.now(2_000_000_000L, 0));
    }
    @Test void aNewConnectionCanSeedAnEntirelyDifferentServerClock() {
        ServerClockEstimate clock = new ServerClockEstimate();
        clock.seed(900_000, 100);
        clock.seed(1, 200);
        assertEquals(900_000, clock.now(200, 0));
        clock.reset();
        assertFalse(clock.ready());
        clock.seed(5_000, 1_000_000_000L);
        assertEquals(6_000, clock.now(2_000_000_000L, 999_999));
    }
    @Test void staleLatencyHistoryExpiresSoNewNetworkConditionsCanBeMeasured() {
        ServerClockEstimate clock = new ServerClockEstimate();
        clock.sample(1, 0, 10, 20_000_000L);
        assertTrue(clock.sample(2, 130_000_000_000L, 130_500, 131_000_000_000L));
        assertEquals(131_000, clock.now(131_000_000_000L, 0));
    }
}
