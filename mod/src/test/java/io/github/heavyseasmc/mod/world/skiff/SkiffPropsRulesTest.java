package io.github.heavyseasmc.mod.world.skiff;

import io.github.heavyseasmc.engine.weather.WeatherEffect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 艇上那几样东西的纯规则（ADR-0057 §4）。世界里的那一半（右键、按天、划船）在游戏里实测。 */
final class SkiffPropsRulesTest {

    @Test
    void cooldownDoesNotSurviveDisconnectOrAnotherWorld() {
        var first = java.util.UUID.randomUUID();
        var second = java.util.UUID.randomUUID();
        try {
            assertTrue(SkiffProps.acceptUse(first, 1000));
            assertFalse(SkiffProps.acceptUse(first, 1999));
            assertTrue(SkiffProps.acceptUse(second, 1000));
            SkiffProps.forget(first);
            assertTrue(SkiffProps.acceptUse(first, 1001));
            assertFalse(SkiffProps.acceptUse(second, 1001), "离线只清这个人的记录");
            SkiffProps.reset();
            assertTrue(SkiffProps.acceptUse(second, 1001));
            assertTrue(SkiffProps.acceptUse(second, 2001), "一秒后仍能正常使用");
        } finally {
            SkiffProps.reset();
        }
    }

    @Test
    void lanternOilBurnsOneStepPerDayAfterTheFirst() {
        assertEquals(4, SkiffProps.Rules.oilAtDayStart(4, 1), "第一天灯是满的");
        assertEquals(3, SkiffProps.Rules.oilAtDayStart(4, 2));
        assertEquals(0, SkiffProps.Rules.oilAtDayStart(1, 9));
        assertEquals(0, SkiffProps.Rules.oilAtDayStart(0, 9), "烧干了就是 0，不会变成负的");
        // 满油撑 4 天：第 2、3、4、5 天开头各掉一档，第 5 天开头灭
        int oil = 4;
        for (int turn = 2; turn <= 5; turn++) {
            oil = SkiffProps.Rules.oilAtDayStart(oil, turn);
        }
        assertEquals(0, oil);
    }

    /** 按天候<b>效果</b>判，不按 id（审查 2026-10-07 Q4）：暴风雨 = 划船的落海，巨浪 = 打架的落海。 */
    @Test
    void roughWeatherKeepsTheSailDown() {
        assertFalse(SkiffProps.Rules.mayRaise(WeatherEffect.ROWERS_OVERBOARD), "暴风雨");
        assertFalse(SkiffProps.Rules.mayRaise(WeatherEffect.FIGHTERS_OVERBOARD), "巨浪");
        assertTrue(SkiffProps.Rules.mayRaise(WeatherEffect.EXTRA_NAVIGATION), "狂风天能升，只是鼓满");
        assertTrue(SkiffProps.Rules.mayRaise(null), "不在对局里（演习艇）随便升");
    }

    @Test
    void sailBellyFollowsTheWind() {
        assertEquals(0, SkiffProps.Rules.bellyFor(WeatherEffect.SKIP_NAVIGATION), "无风");
        assertEquals(2, SkiffProps.Rules.bellyFor(WeatherEffect.EXTRA_NAVIGATION), "狂风");
        assertEquals(1, SkiffProps.Rules.bellyFor(WeatherEffect.RESHUFFLE_DISCARD), "晴空");
        assertEquals(1, SkiffProps.Rules.bellyFor(null));
    }

    @Test
    void theSameCardAlwaysTurnsTheRudderTheSameWay() {
        assertEquals(SkiffProps.Rules.turnFor("nav_07"), SkiffProps.Rules.turnFor("nav_07"));
        assertTrue(SkiffProps.Rules.turnFor("x") != SkiffTurn.NONE);
    }
}
