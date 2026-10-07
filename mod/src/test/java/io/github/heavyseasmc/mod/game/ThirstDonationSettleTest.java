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
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代打水那一窗什么时候收（用户 2026-10-07：「无限时间后，有的阶段会卡住」）。
 *
 * <p>局面：替身（kid）口渴、一张水都没有，自己定不了；真人（captain）手上两张水。
 * 演示局里这一窗不限时，所以「该不该接着等」只能由这一条判断说了算 —— 判错一次就永远等下去。
 */
class ThirstDonationSettleTest {

    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId KID = CharacterId.of("kid");

    @Test
    @DisplayName("真人手上还有水、又没表过态：接着等他")
    void waitsForDonorWithWater() {
        Fixture f = new Fixture(2);
        assertFalse(ThirstPhase.donationsSettled(f.session, f.component, f.prompt));
    }

    @Test
    @DisplayName("给过一张、手上还有：仍然等他（他可能还要再给）")
    void donorWhoGaveButStillHoldsWaterIsStillDeciding() {
        Fixture f = new Fixture(2);
        f.component.addThirstDonor(CAPTAIN);
        assertFalse(ThirstPhase.donationsSettled(f.session, f.component, f.prompt));
    }

    @Test
    @DisplayName("把手上的水全打出去了：不再等，当场结算 —— 原先这里没人去问，那一面自己收了，窗口一直等下去")
    void donorWhoRanOutEndsTheWindow() {
        Fixture f = new Fixture(2);
        f.component.addThirstDonor(CAPTAIN);
        f.component.addThirstDonor(CAPTAIN);
        assertTrue(ThirstPhase.donationsSettled(f.session, f.component, f.prompt));
    }

    @Test
    @DisplayName("说了「不给」：不再等")
    void declinedDonorEndsTheWindow() {
        Fixture f = new Fixture(2);
        f.component.markDecided(CAPTAIN);
        assertTrue(ThirstPhase.donationsSettled(f.session, f.component, f.prompt));
    }

    @Test
    @DisplayName("口渴的人自己定得了：一直等他本人，旁人给没给都一样")
    void waitsForRecipientWhoCanDecide() {
        Fixture f = new Fixture(2);
        f.session.dealFromPile(KID, Session.WATER);
        f.component.markDecided(CAPTAIN);
        assertFalse(ThirstPhase.donationsSettled(f.session, f.component, f.prompt));
    }

    /** 两人局：船长是真人、小孩是替身；牌堆里有水。 */
    private static final class Fixture {
        final Session session;
        final GameComponent component = new GameComponent(null);
        final Session.ThirstPrompt prompt;

        Fixture(int captainWaters) {
            Roster roster = new Roster(List.of(
                    new Survivor(CAPTAIN, 1, 7, 5, "base", new Ability.None()),
                    new Survivor(KID, 8, 3, 9, "base", new Ability.None())));
            List<NavigationCard> cards = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                cards.add(new NavigationCard("settle_" + i, 0, new Selector.Nobody(), new Selector.Nobody(),
                        false, false));
            }
            Provisions provisions = new Provisions(List.of(new Provision(Session.WATER, Provision.Category.CONSUMABLE, 8,
                    new ProvisionEffect.PreventThirst(1, "thirst_source", ProvisionEffect.Target.ANY_CHARACTER, true,
                            "thirst_resolution", true, false, false, false, false))));
            session = new Session("thirst-settle-test", roster,
                    new Table(new NavigationDeck(cards, new Random(1)), provisions, new Random(2)));
            component.begin(session, Map.of(
                    CAPTAIN, new GameComponent.Occupant(UUID.randomUUID(), "Tester"),
                    KID, new GameComponent.Occupant(null, "kid")));
            for (int i = 0; i < captainWaters; i++) {
                session.dealFromPile(CAPTAIN, Session.WATER);
            }
            prompt = new Session.ThirstPrompt(KID, ThirstTally.of(ThirstSource.ROWED), 0, 0, 2, 0, 1);
        }
    }
}
