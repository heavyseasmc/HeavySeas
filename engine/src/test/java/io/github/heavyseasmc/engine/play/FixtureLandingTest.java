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

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 夹具靠岸前先问「有没有一步还等着人定」：问到的与 {@link Session#landForFixture} 实际拒不拒绝是同一回事。
 *
 * <p>2026-10-13 截图验收：口渴结算中 {@code /seas land} 把引擎的异常原样冒到了聊天里。
 */
class FixtureLandingTest {
    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId LAST = CharacterId.of("kid");

    private static Session session() {
        Roster roster = new Roster(List.of(
                new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(LAST, 3, 3, 9, "base", new Ability.None())));
        return new Session("fixture-landing", roster, new Table(
                new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                TestProvisions.counting(Map.of("water", 4)), new Random(1)));
    }

    @Test
    @DisplayName("口渴没定完：问得出「在等人定」，而且靠岸确实被拒；定完之后两边一起放行")
    void thirstInProgressBlocksLanding() {
        Session s = session();
        s.dealFromPile(LAST, "water");
        s.advancePhase();
        s.advancePhase();
        s.beginNavigate(new NavigationCard("thirst", 0, new Selector.Nobody(),
                new Selector.Only(Set.of(LAST)), false, false));
        assertTrue(s.thirstInProgress(), "对照：这时该有人在定喝不喝水");
        assertTrue(s.decisionInProgress(), "口渴结算中却说没有人在等");
        assertThrows(IllegalStateException.class, s::landForFixture, "对照：引擎这时确实拒绝靠岸");

        s.decideThirst(List.of());
        assertFalse(s.thirstInProgress(), "对照：定完了");
        assertFalse(s.decisionInProgress(), "口渴已经定完却仍说在等");
        s.landForFixture();
        assertEquals(GameState.Outcome.LANDED, s.state().outcome().orElseThrow());
    }

    @Test
    @DisplayName("划船抽到的牌没选完：同样在等人定")
    void rowInProgressBlocksLanding() {
        Session s = session();
        s.advancePhase();
        s.beginRow(FIRST);
        assertTrue(s.decisionInProgress(), "划船选牌中却说没有人在等");
        assertThrows(IllegalStateException.class, s::landForFixture);
    }
}
