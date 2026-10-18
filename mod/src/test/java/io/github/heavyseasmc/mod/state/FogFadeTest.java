package io.github.heavyseasmc.mod.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 雾的渐变数学（ADR-0034 §5.1.3）：两端平、中间快、单调、没有过冲。 */
final class FogFadeTest {

    @Test
    void smoothstepStartsAtZeroEndsAtOneAndNeverOvershoots() {
        assertEquals(0f, FogFade.progress(0, 4000), 1e-6f);
        assertEquals(1f, FogFade.progress(4000, 4000), 1e-6f);
        assertEquals(1f, FogFade.progress(9000, 4000), 1e-6f, "过了时长要停在 1，不能继续涨");
        assertEquals(0.5f, FogFade.progress(2000, 4000), 1e-6f);
        float previous = 0f;
        for (long ms = 0; ms <= 4000; ms += 50) {
            float now = FogFade.progress(ms, 4000);
            assertTrue(now >= previous && now <= 1f, "在 " + ms + " ms 处不单调或越界");
            previous = now;
        }
        assertEquals(1f, FogFade.progress(0, 0), "时长为 0 时直接到位");
    }

    @Test
    void lerpIsPlain() {
        assertEquals(12f, FogFade.lerp(12f, 96f, 0f), 1e-6f);
        assertEquals(96f, FogFade.lerp(12f, 96f, 1f), 1e-6f);
        assertEquals(54f, FogFade.lerp(12f, 96f, 0.5f), 1e-6f);
    }

    @Test
    void vanillaFogFollowsTheGameFormula() {
        // 渲染距离 96：clamp(9.6, 4, 64) = 9.6 → 起点 86.4；32：clamp(3.2, 4, 64) = 4 → 起点 28。
        assertEquals(86.4f, FogFade.vanilla(96f)[0], 1e-4f);
        assertEquals(96f, FogFade.vanilla(96f)[1], 1e-6f);
        assertEquals(28f, FogFade.vanilla(32f)[0], 1e-6f);
    }
}
