package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.net.SkyS2C;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 谁的屏幕上画什么天（ADR-0054 §9.8 D12 第 4 条 (a)）。画出来对不对，靠两个客户端同时进服实拍。 */
final class PlayerSkyRulesTest {

    private static final String SEA = "heavyseas:mist_sea";
    private static final FogTable.Entry STORM = new FogTable.Entry(5, 25, true, true, 14000);
    private static final FogTable.Entry GALE = new FogTable.Entry(12, 60, false, false, 6000);

    @Test
    void outsideTheSeaNothingIsTakenOver() {
        assertFalse(PlayerSky.Rules.desired(null, false, null).active());
        assertFalse(PlayerSky.Rules.desired(null, true, STORM).active(), "局里的人回了主世界（掉线恢复等）也不接管");
    }

    @Test
    void onTheLinerItIsAlwaysThatNight() {
        assertEquals(new SkyS2C(SEA, PlayerSky.LINER_NIGHT, 0f, 0f), PlayerSky.Rules.desired(SEA, false, null));
        assertEquals(new SkyS2C(SEA, PlayerSky.LINER_NIGHT, 0f, 0f), PlayerSky.Rules.desired(SEA, false, GALE),
                "不在这一局里的人，不管那一局今天是什么天");
    }

    @Test
    void inTheVoyageItFollowsTodaysWeather() {
        assertEquals(new SkyS2C(SEA, 14000, 1f, 1f), PlayerSky.Rules.desired(SEA, true, STORM));
        assertEquals(new SkyS2C(SEA, 6000, 0f, 0f), PlayerSky.Rules.desired(SEA, true, GALE));
    }

    @Test
    void debugOverrideReplacesOnlyWhatWasFixedAndOnlyInTheSea() {
        // ADR-0060：/seas debug sky time | rain —— 在雾海里的人都看指定的那一项，没指定的那一项照旧
        assertEquals(new SkyS2C(SEA, 6000, 1f, 1f), PlayerSky.Rules.desired(SEA, true, STORM, 6000L, null));
        assertEquals(new SkyS2C(SEA, 14000, 0f, 1f), PlayerSky.Rules.desired(SEA, true, STORM, null, 0f));
        assertEquals(new SkyS2C(SEA, 1000, 0.5f, 0f), PlayerSky.Rules.desired(SEA, false, GALE, 1000L, 0.5f),
                "北辰号上的人也在雾海里：一样改");
        assertEquals(PlayerSky.Rules.desired(SEA, true, GALE), PlayerSky.Rules.desired(SEA, true, GALE, null, null),
                "什么都没指定：与不带指定的那一版完全相同");
        assertFalse(PlayerSky.Rules.desired(null, true, STORM, 6000L, 1f).active(), "不在雾海里：照旧不接管");
    }

    @Test
    void beforeTheFirstWeatherIsDrawnTheVoyageStillSeesTheNight() {
        // 刚上艇、天候还没翻出来的那几秒：沿用那一夜，而不是闪一下主世界的钟
        assertEquals(new SkyS2C(SEA, PlayerSky.LINER_NIGHT, 0f, 0f), PlayerSky.Rules.desired(SEA, true, null));
    }
}
