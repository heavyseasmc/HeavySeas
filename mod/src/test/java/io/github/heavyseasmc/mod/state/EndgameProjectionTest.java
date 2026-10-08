package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Affinities;
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
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终局投影不提前说出名次与胜者（审查 2026-10-07 L4）。
 *
 * <p>翻牌次序就是总分从低到高，胜者旗标就是名次 —— 原先从靠岸那一幕起整张表连同旗标就发给所有人，
 * 改过的客户端在船靠岸时就知道结果。局面挑的是「胜者排在最后、还没翻到他」：两种实现给出的答案在这里分叉。
 */
final class EndgameProjectionTest {

    private static final CharacterId LOW = CharacterId.of("mate");
    private static final CharacterId HIGH = CharacterId.of("kid");

    static GameComponent component(UUID human, EndgameProgress.Stage stage, int flipped) {
        Roster roster = new Roster(List.of(
                new Survivor(LOW, 1, 8, 4, "base", new Ability.None()),
                new Survivor(HIGH, 2, 3, 9, "base", new Ability.None())));
        Session session = new Session("endgame-projection", roster,
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        session.dealAffinities(Affinities.random(roster, new Random(3)));
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(LOW, new GameComponent.Occupant(human, "p"));
        seats.put(HIGH, new GameComponent.Occupant(null, "dummy"));
        component.begin(session, seats);
        component.clearFog();
        Map<CharacterId, ScoreSheet> scores = new LinkedHashMap<>();
        scores.put(LOW, new ScoreSheet(1, 0, 0, 0));
        scores.put(HIGH, new ScoreSheet(9, 0, 0, 0));
        component.setEndgame(new EndgameProgress(GameState.Outcome.LANDED, 3, 2, List.of(LOW, HIGH), stage, flipped, scores));
        return component;
    }

    private static HudView.Endgame project(GameComponent component, UUID recipient) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            component.writeViewFor(buf, recipient);
            return GameComponent.readViewFrom(buf).endgame();
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("靠岸那一幕：翻牌次序与胜者一行都不发（客户端那一幕不开翻牌面）")
    void arrivalSendsNoOrder() {
        UUID human = UUID.randomUUID();
        HudView.Endgame e = project(component(human, EndgameProgress.Stage.ARRIVAL, 0), human);
        assertEquals(EndgameProgress.Stage.ARRIVAL, e.stage(), "前提：终局在靠岸那一幕");
        assertTrue(e.entries().isEmpty(), "靠岸时就发了翻牌次序（= 名次）：" + e.entries());
    }

    @Test
    void revealCarriesOnlyPastAndCurrentlyNamedIdentities() {
        UUID human = UUID.randomUUID();
        for (EndgameProgress.Stage stage : List.of(EndgameProgress.Stage.HATE, EndgameProgress.Stage.LOVE)) {
            HudView.Endgame first = project(component(human, stage, 0), human);
            assertEquals(LOW.value(), first.entries().getFirst().who(), "当前正在点名的人是公开的");
            assertEquals("", first.entries().get(1).who(), "未来的排序位置不能提前带角色 id");
            assertEquals("", first.entries().get(1).target());
            assertFalse(first.entries().get(1).winner());
            assertEquals(-1, first.entries().get(1).total());
        }
    }

    @Test
    @DisplayName("翻牌只公开已翻与当前点名的身份，胜者旗标只给已经翻开的那几张")
    void winnerFlagOnlyForFlippedCards() {
        UUID human = UUID.randomUUID();
        HudView.Endgame first = project(component(human, EndgameProgress.Stage.HATE, 1), human);
        assertEquals(2, first.entries().size(), "翻牌两轮里次序要照发：客户端的座位轨按它排");
        assertFalse(first.entries().get(0).winner(), "低分那一位不是胜者");
        assertFalse(first.entries().get(1).winner(), "胜者还没翻到，旗标就发出去了");

        HudView.Endgame done = project(component(human, EndgameProgress.Stage.HATE, 2), human);
        assertTrue(done.entries().get(1).winner(), "对照：翻到他时旗标要在（翻的那一下按胜者的慢节奏演）");
    }

    @Test
    @DisplayName("计分阶段：合计与胜者全给")
    void scoresStageCarriesEverything() {
        UUID human = UUID.randomUUID();
        HudView.Endgame e = project(component(human, EndgameProgress.Stage.SCORES, 0), human);
        assertEquals(2, e.entries().size());
        assertTrue(e.entries().get(1).winner());
        assertEquals(9, e.entries().get(1).total());
    }
}
