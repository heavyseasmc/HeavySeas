package io.github.heavyseasmc.mod.world.skiff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 艇上那几样东西的纯规则（ADR-0057 §4）。世界里的那一半（右键、按天、划船）在游戏里实测。 */
final class SkiffPropsRulesTest {

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

    @Test
    void roughWeatherKeepsTheSailDown() {
        assertFalse(SkiffProps.Rules.mayRaise("storm"));
        assertFalse(SkiffProps.Rules.mayRaise("huge_wave"));
        assertTrue(SkiffProps.Rules.mayRaise("gale"), "狂风天能升，只是鼓满");
        assertTrue(SkiffProps.Rules.mayRaise(null), "不在对局里（演习艇）随便升");
    }

    @Test
    void sailBellyFollowsTheWind() {
        assertEquals(0, SkiffProps.Rules.bellyFor("becalmed"));
        assertEquals(2, SkiffProps.Rules.bellyFor("gale"));
        assertEquals(1, SkiffProps.Rules.bellyFor("clear_skies"));
        assertEquals(1, SkiffProps.Rules.bellyFor(null));
    }

    @Test
    void theSameCardAlwaysTurnsTheRudderTheSameWay() {
        assertEquals(SkiffProps.Rules.turnFor("nav_07"), SkiffProps.Rules.turnFor("nav_07"));
        assertTrue(SkiffProps.Rules.turnFor("x") != SkiffTurn.NONE);
    }
}
