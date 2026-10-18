package io.github.heavyseasmc.mod.command;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /seas} 牌参数的 Tab 补全不给坐在这一局里的人看别人的手牌（审查 2026-10-07 L3，D4 的同一条判据）。
 */
final class HeldCardsSuggestTest {

    private static final CharacterId ME = CharacterId.of("mate");
    private static final CharacterId OTHER = CharacterId.of("kid");

    @Test
    @DisplayName("坐在这一局里：只补自己的手牌与全船面前的牌；控制台（不在局里）照旧全给")
    void inGameAdminSeesOnlyOwnHand() {
        Session session = new Session("held-cards", new Roster(List.of(
                new Survivor(ME, 1, 8, 4, "base", new Ability.None()),
                new Survivor(OTHER, 2, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(
                                card("mine"), card("theirs"), card("shown"))), new Random(1)));
        session.dealFromPile(ME, "mine");
        session.dealFromPile(OTHER, "theirs");
        session.dealFromPile(OTHER, "shown");
        session.reveal(OTHER, "shown");
        UUID me = UUID.randomUUID();
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(ME, new GameComponent.Occupant(me, "admin"));
        seats.put(OTHER, new GameComponent.Occupant(UUID.randomUUID(), "other"));
        component.begin(session, seats);

        assertEquals(Set.of("mine", "shown"), SeasCommand.suggestableCards(session, component, me),
                "坐在局里的管理员按一下 Tab 就看见别人手里有什么（D4 只拦了 inspect 与 dump）");
        assertEquals(Set.of("mine", "theirs", "shown"), SeasCommand.suggestableCards(session, component, null),
                "对照：控制台 / RCON 不在局里，照旧全给");
    }

    private static Provision card(String id) {
        return new Provision(id, Provision.Category.TREASURE, 1, new ProvisionEffect.ScoreFlat(1, ""));
    }
}
