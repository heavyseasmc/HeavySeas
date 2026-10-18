package io.github.heavyseasmc.mod.game;

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
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事件来源（{@link GameFlow#actorSource} · {@link GameFlow#defaultedSource}）：掉线的人被替着定，侧栏要说「离线」，不说「超时」。
 *
 * <p>守的缺陷：口渴、表态这几处到点替人定时一律标「超时」，人其实是掉线了。
 */
final class EventSourceTest {
    private static final CharacterId HUMAN = CharacterId.of("mate");
    private static final CharacterId STAND_IN = CharacterId.of("kid");

    @Test
    @DisplayName("真人自己按 · 替身 · 掉线的真人各归各的；到点替他定：掉线算离线，在线才算超时")
    void sourcesFollowWhoActuallyDecided() {
        Session session = new Session("event-source", new Roster(List.of(
                new Survivor(HUMAN, 1, 8, 4, "base", new Ability.None()),
                new Survivor(STAND_IN, 2, 6, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0, new Selector.Nobody(),
                        new Selector.Nobody(), false, false)), new Random(1)), new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                        new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        GameComponent c = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(HUMAN, new GameComponent.Occupant(UUID.randomUUID(), "p"));
        seats.put(STAND_IN, new GameComponent.Occupant(null, "dummy"));
        c.begin(session, seats);

        assertEquals(ExperienceView.Source.PLAYER, GameFlow.actorSource(c, HUMAN));
        assertEquals(ExperienceView.Source.STAND_IN, GameFlow.actorSource(c, STAND_IN));
        assertEquals(ExperienceView.Source.TIMEOUT, GameFlow.defaultedSource(c, HUMAN), "对照：在线的人到点没定才是超时");

        session.setOffline(HUMAN, true);
        assertEquals(ExperienceView.Source.DISCONNECT, GameFlow.actorSource(c, HUMAN));
        assertEquals(ExperienceView.Source.DISCONNECT, GameFlow.defaultedSource(c, HUMAN), "掉线的人被替着定，标成了超时");
    }
}
