package io.github.heavyseasmc.engine.seat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身设置：默认值在范围里，越界的（包括 NaN）当场拒绝 —— 配置文件写错了要说出来，不悄悄夹回边上。
 */
class SeatPolicySettingsTest {

    private static final SeatPolicySettings D = SeatPolicySettings.DEFAULTS;

    @Test
    @DisplayName("默认值就是写在常量里的那几个，且都在范围之内")
    void defaults() {
        assertEquals(SeatPolicySettings.DEFAULT_TEMPERATURE, D.temperature());
        assertEquals(SeatPolicySettings.DEFAULT_GRATITUDE, D.gratitude());
        assertEquals(SeatPolicySettings.DEFAULT_RESENTMENT, D.resentment());
        assertEquals(SeatPolicySettings.DEFAULT_MEMORY_DAYS, D.memoryDays());
        assertTrue(D.search());
        assertEquals(SeatPolicySettings.DEFAULT_ROLLOUTS, D.rollouts());
        assertEquals(SeatPolicySettings.DEFAULT_MILLIS, D.millisPerDecision());
        assertEquals(SeatPolicySettings.DEFAULT_WIDTH, D.width());
        assertEquals(SeatPolicySettings.DEFAULT_HORIZON, D.horizonDays());
        assertFalse(D.withoutSearch().search());
    }

    @Test
    @DisplayName("每个字段越界一格（上下各一）、写成 NaN，都当场拒绝；边界值本身照收")
    void rejectsOutOfRange() {
        List<Supplier<SeatPolicySettings>> bad = List.of(
                () -> D.withTemperature(-0.01), () -> D.withTemperature(5.01), () -> D.withTemperature(Double.NaN),
                () -> D.withMemory(-0.1, 1, 4), () -> D.withMemory(3.1, 1, 4), () -> D.withMemory(1, Double.NaN, 4),
                () -> D.withMemory(1, 1, 0.4), () -> D.withMemory(1, 1, 51), () -> D.withMemory(1, 3.5, 4),
                () -> D.withBudget(7, 250, 4, 2), () -> D.withBudget(20_001, 250, 4, 2),
                () -> D.withBudget(128, -1, 4, 2), () -> D.withBudget(128, 10_001, 4, 2),
                () -> D.withBudget(128, 250, 0, 2), () -> D.withBudget(128, 250, 17, 2),
                () -> D.withBudget(128, 250, 4, -1), () -> D.withBudget(128, 250, 4, 41));
        for (Supplier<SeatPolicySettings> s : bad) {
            assertThrows(IllegalArgumentException.class, s::get);
        }
        assertDoesNotThrow(() -> D.withTemperature(0).withTemperature(5).withMemory(0, 3, 0.5).withMemory(3, 0, 50)
                .withBudget(8, 0, 1, 0).withBudget(20_000, 10_000, 16, 40));
    }
}
