package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试口「海鸥定为 n 只」（ADR-0060）：走引擎的合法入口、照常过自检；凑够 4 只就是靠岸，与夹具靠岸同一个局面。
 */
class DebugGullsTest {

    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");

    private static Session session() {
        List<NavigationCard> nav = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nav.add(new NavigationCard("syn_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        Roster roster = new Roster(List.of(
                new Survivor(MATE, 1, 8, 4, "base", new Ability.None()),
                new Survivor(KID, 2, 3, 9, "base", new Ability.None())));
        return new Session("debug-gulls", roster,
                new Table(new NavigationDeck(nav, new Random(1)), TestProvisions.minimal()));
    }

    @Test
    @DisplayName("停在半路：海鸥 0–3 只，这一局不结束；往下调也合法")
    void midwayDoesNotEndTheGame() {
        Session s = session();
        s.debugSetGulls(3);
        assertEquals(3, s.state().gulls());
        assertFalse(s.state().isOver());
        s.debugSetGulls(1);
        assertEquals(1, s.state().gulls(), "往下调是合法的（信号枪也会去掉海鸥）");
        assertFalse(s.state().isOver());
    }

    @Test
    @DisplayName("凑够 4 只：当场靠岸，与夹具靠岸是同一个结局")
    void reachingTheThresholdLands() {
        Session debug = session();
        debug.debugSetGulls(GameState.GULLS_TO_LAND);
        Session fixture = session();
        fixture.landForFixture();
        assertEquals(GameState.Outcome.LANDED, debug.state().outcome().orElseThrow());
        assertEquals(fixture.state().outcome(), debug.state().outcome());
        assertEquals(fixture.state().gulls(), debug.state().gulls());
    }

    @Test
    @DisplayName("负数拒绝；已经结束的局拒绝 —— 都是一句话，不是改了一半")
    void rejectsNegativeAndFinished() {
        Session s = session();
        assertThrows(IllegalArgumentException.class, () -> s.debugSetGulls(-1));
        assertEquals(0, s.state().gulls(), "拒绝时什么也不改");
        s.debugSetGulls(GameState.GULLS_TO_LAND);
        IllegalStateException over = assertThrows(IllegalStateException.class, () -> s.debugSetGulls(2));
        assertTrue(over.getMessage().contains("结束"), over.getMessage());
        assertEquals(GameState.GULLS_TO_LAND, s.state().gulls());
    }
}
