package io.github.heavyseasmc.engine.play;

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
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OverboardDamagePreviewTest {
    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId LAST = CharacterId.of("kid");

    @Test
    void strongerDamageAlreadyFaceUpIsIncluded() {
        Session session = session();
        deal(session, FIRST, "bait_bucket", false);
        deal(session, LAST, "surge_flask", true);
        begin(session);

        assertPreview(session, "bait_bucket", 3);
    }

    @Test
    void playingFromTheFrontRemovesThatCardBeforeCountingFaceUpDamage() {
        Session session = session();
        deal(session, FIRST, "stacking_flask", true);
        deal(session, LAST, "strong_flask", true);
        begin(session);

        assertPreview(session, "stacking_flask", 4);
    }

    @Test
    void aCopyInHandIsConsumedBeforeAnIdenticalFaceUpCopy() {
        Session session = session();
        deal(session, FIRST, "stacking_flask", true);
        deal(session, FIRST, "stacking_flask", false);
        deal(session, LAST, "strong_flask", true);
        begin(session);

        assertPreview(session, "stacking_flask", 6);
    }

    @Test
    void repeatedAndStackingPlaysMatchTheActualResolution() {
        Session session = session();
        deal(session, FIRST, "bait_bucket", false);
        deal(session, FIRST, "bait_bucket", false);
        deal(session, FIRST, "surge_flask", false);
        deal(session, FIRST, "stacking_flask", false);
        begin(session);

        assertPreview(session, "bait_bucket", 1);
        play(session, "bait_bucket");
        assertPreview(session, "bait_bucket", 1);
        play(session, "bait_bucket");
        assertPreview(session, "surge_flask", 3);
        play(session, "surge_flask");
        assertPreview(session, "stacking_flask", 5);
    }

    /**
     * 名单里有一个已移出游戏的人：预估与正式结算取同一份下水名单（{@code Session#stillInWater}），结果一致。
     *
     * <p>❗这一格<b>不分叉</b>：移出者身上一张牌都没有（{@code Invariants} 拦着），算不算他，血饵加成都一样 ——
     * 所以把预估里的过滤去掉，这一条照样绿。它守的是「有移出者时预估不抛、不偏」；
     * 真正防两边分家的是共用同一个名单函数，而不是这条用例。
     * 局面照图纸拼：正常流程里开窗那一刻已经把移出者滤掉，碰不到这一格。
     */
    @Test
    void aRemovedSwimmerInTheBatchMatchesTheActualResolution() {
        Session live = session();
        live.dealAffinities(new Affinities(Map.of(FIRST, LAST, LAST, FIRST), Map.of(FIRST, LAST, LAST, FIRST)));
        deal(live, FIRST, "bait_bucket", false);
        deal(live, FIRST, "surge_flask", true);
        deal(live, LAST, "stacking_flask", true);
        begin(live);
        GameState removed = live.state().withState(LAST, live.state().stateOf(LAST).withoutAllCards()).withRemoved(LAST);
        List<String> gone = new java.util.ArrayList<>(live.table().removedProvisions());
        gone.addAll(live.state().stateOf(LAST).front());
        Session session = Session.rebuild(new Session.Blueprint("removed-swimmer", removed, live.provisions(),
                live.table().pile().order(), live.table().rowStack(), live.table().rowerHand(),
                live.table().provisionPileOrder(), live.table().provisionDiscard(), gone,
                Optional.empty(), live.provisionOffer(), live.rower(), live.contest(), live.progress(),
                live.affinities().orElseThrow(), live.deeds()));
        assertEquals(List.of(FIRST, LAST), session.overboardPending().orElseThrow().swimmers(),
                "前提：这一批的名单里还挂着已移出游戏的人");
        Session.OverboardPlay choice = session.overboardPlays(FIRST).stream()
                .filter(p -> p.card().equals("bait_bucket")).findFirst().orElseThrow();

        int preview = session.overboardExtraDamageAfter(FIRST, choice);

        Session resolved = session.copy();
        int before = resolved.state().stateOf(FIRST).damage();
        resolved.playOverboardCard(FIRST, choice.target(), "bait_bucket", resolved.overboardPending().orElseThrow().token());
        resolved.finishOverboard();
        assertEquals(3, preview, "手算：面前那瓶 3 点不叠加，打出的 1 点取大");
        assertEquals(preview + 1, resolved.state().stateOf(FIRST).damage() - before,
                "预估与正式结算（外加落海本身那 1 点）对不上");
    }

    @Test
    void previewRequiresALegalDamageCardIntervention() {
        Session session = session();
        deal(session, FIRST, "life_preserver", false);
        assertThrows(IllegalStateException.class, () -> session.overboardExtraDamageAfter(FIRST,
                new Session.OverboardPlay("life_preserver", LAST)));
        begin(session);
        assertThrows(IllegalArgumentException.class, () -> session.overboardExtraDamageAfter(FIRST,
                new Session.OverboardPlay("life_preserver", LAST)));
        assertThrows(IllegalArgumentException.class, () -> session.overboardExtraDamageAfter(FIRST,
                new Session.OverboardPlay("bait_bucket", LAST)));
    }

    private static void assertPreview(Session session, String card, int expected) {
        var state = session.state();
        var pile = List.copyOf(session.table().provisionPileOrder());
        var discard = List.copyOf(session.table().provisionDiscard());
        var deeds = List.copyOf(session.deeds());
        var prompt = session.overboardPending().orElseThrow();
        var choices = session.overboardPlays(FIRST);
        int playedBait = session.overboardBait();
        Session.OverboardPlay choice = choices.stream().filter(p -> p.card().equals(card)).findFirst().orElseThrow();

        for (int i = 0; i < 3; i++) {
            assertEquals(expected, session.overboardExtraDamageAfter(FIRST, choice));
        }
        assertSame(state, session.state(), "Preview must not replace the actual state or expose private cards");
        assertEquals(pile, session.table().provisionPileOrder());
        assertEquals(discard, session.table().provisionDiscard());
        assertEquals(deeds, session.deeds());
        assertEquals(prompt, session.overboardPending().orElseThrow());
        assertEquals(choices, session.overboardPlays(FIRST));
        assertEquals(playedBait, session.overboardBait());

        Session resolved = session.copy();
        resolved.playOverboardCard(FIRST, choice.target(), card, prompt.token());
        resolved.finishOverboard();
        for (CharacterId swimmer : prompt.swimmers()) {
            assertEquals(expected + 1, resolved.state().stateOf(swimmer).damage() - state.stateOf(swimmer).damage(),
                    "Preview must match real damage, beyond the ordinary one point for falling");
            assertFalse(resolved.state().isRemoved(swimmer));
        }
    }

    private static void play(Session session, String card) {
        Session.OverboardPlay choice = session.overboardPlays(FIRST).stream()
                .filter(p -> p.card().equals(card)).findFirst().orElseThrow();
        session.playOverboardCard(FIRST, choice.target(), card, session.overboardPending().orElseThrow().token());
    }

    private static void deal(Session session, CharacterId who, String card, boolean front) {
        session.dealFromPile(who, card);
        if (front) {
            session.reveal(who, card);
        }
    }

    private static void begin(Session session) {
        session.advancePhase();
        session.markActed(FIRST);
        session.markActed(LAST);
        session.advancePhase();
        session.beginNavigation(new NavigationCard("swell", 0, new Selector.Everyone(),
                new Selector.Nobody(), false, false));
        assertEquals(List.of(FIRST, LAST), session.overboardPending().orElseThrow().swimmers());
    }

    private static Session session() {
        Roster roster = new Roster(List.of(
                new Survivor(FIRST, 1, 20, 4, "base", new Ability.None()),
                new Survivor(LAST, 2, 20, 9, "base", new Ability.None())));
        Provisions provisions = new Provisions(List.of(
                new Provision("bait_bucket", Provision.Category.CONSUMABLE, 2,
                        new ProvisionEffect.DamageInWater(1, false, true, List.of("life_preserver"))),
                new Provision("surge_flask", Provision.Category.CONSUMABLE, 1,
                        new ProvisionEffect.DamageInWater(3, false, true, List.of("life_preserver"))),
                new Provision("stacking_flask", Provision.Category.CONSUMABLE, 2,
                        new ProvisionEffect.DamageInWater(2, true, true, List.of("life_preserver"))),
                new Provision("strong_flask", Provision.Category.CONSUMABLE, 1,
                        new ProvisionEffect.DamageInWater(4, true, true, List.of("life_preserver"))),
                new Provision("life_preserver", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.PreventOverboardDamage(true, true, true, true, false))));
        return new Session("overboard-preview", roster,
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0, new Selector.Nobody(),
                        new Selector.Nobody(), false, false)), new Random(1)), provisions, new Random(1)));
    }
}
