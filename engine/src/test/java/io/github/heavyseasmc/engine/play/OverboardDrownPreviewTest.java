package io.github.heavyseasmc.engine.play;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 落海那一窗的「会淹死：谁」（{@link Session#overboardDrownsIf}，ADR-0043 §11 第五组）：
 * 每一种出法（含不出）预告的名单，都要与真结算之后被大海带走的人一模一样。
 */
class OverboardDrownPreviewTest {
    /** 体型 3：落水那 1 点不致命，再加 3 点的强诱饵才淹死。 */
    private static final CharacterId BIG = CharacterId.of("mate");
    /** 体型 1：落水那 1 点就让体力正好到 0 —— 没有救生圈就淹死。 */
    private static final CharacterId SMALL = CharacterId.of("kid");

    @Test
    @DisplayName("每一种出法（救生圈给谁 · 哪一种诱饵 · 不出）：预告的淹死名单 = 真结算后被移出的人")
    void everyChoiceMatchesTheRealResolution() {
        Session session = session();
        session.dealFromPile(BIG, "life_preserver");
        session.dealFromPile(BIG, "bait_bucket");
        session.dealFromPile(BIG, "surge_flask");
        begin(session);

        List<Optional<Session.OverboardPlay>> choices = new ArrayList<>();
        choices.add(Optional.empty());
        session.overboardPlays(BIG).forEach(p -> choices.add(Optional.of(p)));
        assertTrue(choices.size() >= 4, "前提：救生圈两个目标 + 两种诱饵 + 不出");

        Set<Set<CharacterId>> outcomes = new HashSet<>();
        for (Optional<Session.OverboardPlay> choice : choices) {
            List<CharacterId> predicted = session.overboardDrownsIf(BIG, choice);
            Session resolved = session.copy();
            choice.ifPresent(p -> resolved.playOverboardCard(BIG, p.target(), p.card(),
                    resolved.overboardPending().orElseThrow().token()));
            resolved.finishOverboard();
            Set<CharacterId> actual = new HashSet<>();
            for (CharacterId id : List.of(BIG, SMALL)) {
                if (resolved.state().isRemoved(id)) {
                    actual.add(id);
                }
            }
            assertEquals(actual, Set.copyOf(predicted), "出法 " + choice + " 的预告与结算对不上");
            outcomes.add(actual);
        }
        // 正向对照：这一局面里不同出法确实给出不同结局（否则「全对」什么也没证明）
        assertTrue(outcomes.contains(Set.of(SMALL)), "不出牌时小孩应当淹死");
        assertTrue(outcomes.contains(Set.of()), "救生圈给小孩时应当没人淹死");
        assertTrue(outcomes.contains(Set.of(BIG, SMALL)), "强诱饵应当连大副一起淹死");
    }

    /**
     * 救生圈在这里只管「体力正好落到 0 时不淹死」：小孩面前亮着圈（免了落水那 1 点），大副打血饵 —— 鲨鱼那 1 点圈挡不住，
     * 小孩体力正好到 0，有圈就只是昏过去。这是「看不看救生圈」两种算法给出不同答案的那个局面。
     */
    @Test
    @DisplayName("面前有救生圈、被鲨鱼咬到体力正好 0：不淹死（预告与结算一致）")
    void ringSavesAtExactlyZero() {
        Session session = session();
        session.dealFromPile(SMALL, "life_preserver");
        session.reveal(SMALL, "life_preserver");
        session.dealFromPile(BIG, "bait_bucket");
        begin(session);
        Session.OverboardPlay bait = session.overboardPlays(BIG).stream()
                .filter(p -> p.card().equals("bait_bucket")).findFirst().orElseThrow();
        assertEquals(List.of(), session.overboardDrownsIf(BIG, Optional.of(bait)));
        Session resolved = session.copy();
        resolved.playOverboardCard(BIG, bait.target(), "bait_bucket", resolved.overboardPending().orElseThrow().token());
        resolved.finishOverboard();
        assertFalse(resolved.state().isRemoved(SMALL), "前提：结算里他确实活下来了");
        assertEquals(1, resolved.state().stateOf(SMALL).damage(), "前提：体力正好落到 0（体型 1、受 1 点）");
    }

    @Test
    @DisplayName("预告不改动对局")
    void previewLeavesTheSessionAlone() {
        Session session = session();
        session.dealFromPile(BIG, "surge_flask");
        begin(session);
        var state = session.state();
        var prompt = session.overboardPending().orElseThrow();
        int bait = session.overboardBait();
        Session.OverboardPlay surge = session.overboardPlays(BIG).getFirst();
        session.overboardDrownsIf(BIG, Optional.of(surge));
        session.overboardDrownsIf(BIG, Optional.empty());
        assertSame(state, session.state());
        assertEquals(prompt, session.overboardPending().orElseThrow());
        assertEquals(bait, session.overboardBait());
    }

    private static void begin(Session session) {
        session.advancePhase();
        session.markActed(BIG);
        session.markActed(SMALL);
        session.advancePhase();
        session.beginNavigation(new NavigationCard("swell", 0, new Selector.Everyone(),
                new Selector.Nobody(), false, false));
        assertEquals(List.of(BIG, SMALL), session.overboardPending().orElseThrow().swimmers());
    }

    private static Session session() {
        Roster roster = new Roster(List.of(
                new Survivor(BIG, 1, 3, 4, "base", new Ability.None()),
                new Survivor(SMALL, 2, 1, 9, "base", new Ability.None())));
        Provisions provisions = new Provisions(List.of(
                new Provision("bait_bucket", Provision.Category.CONSUMABLE, 1,
                        new ProvisionEffect.DamageInWater(1, false, true, List.of("life_preserver"))),
                new Provision("surge_flask", Provision.Category.CONSUMABLE, 1,
                        new ProvisionEffect.DamageInWater(3, false, true, List.of("life_preserver"))),
                new Provision("life_preserver", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.PreventOverboardDamage(true, true, true, true, false))));
        return new Session("overboard-drown", roster,
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0, new Selector.Nobody(),
                        new Selector.Nobody(), false, false)), new Random(1)), provisions, new Random(1)));
    }
}
