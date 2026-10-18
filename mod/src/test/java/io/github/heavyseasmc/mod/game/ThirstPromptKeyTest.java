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
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身的口渴接力认的是「问到的是哪一轮」，不是「还要化解几次」（审查 2026-10-07 K2）。
 *
 * <p>局面照第一轮审查员写好没跑的那支复现（Repro2）：陪酒女是替身、正轮到她算口渴（这一段不开窗、没有超时），
 * 真人船长在航海阶段喝了一口酒 —— 她被带上酒后口渴，「还要化解几次」加一。原先这个数是身份的一部分：这一手被当成
 * 「决定已经不在了」作废，而这一段没人再往下推，整局停住。
 */
final class ThirstPromptKeyTest {

    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId HOSTESS = CharacterId.of("hostess");
    private static final CharacterId SAILOR = CharacterId.of("sailor");
    private static final CharacterId MATE = CharacterId.of("mate");

    private record Fixture(Session session, GameComponent component) {
    }

    private static Fixture navigatingWithHostessThirsty() {
        List<Provision> cards = List.of(
                new Provision("water", Provision.Category.CONSUMABLE, 3,
                        new ProvisionEffect.PreventThirst(1, "thirst_source",
                                ProvisionEffect.Target.ANY_CHARACTER, true, "thirst_resolution",
                                true, false, false, false, false)),
                new Provision("rum", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.BuffSize(3, "turn",
                                ProvisionEffect.BuffSize.THIRST_AT_END_OF_TURN, true, true, false)));
        List<NavigationCard> nav = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // 只点陪酒女口渴；没人落海、没有海鸥
            nav.add(new NavigationCard("syn_" + i, 0, new Selector.Nobody(), new Selector.Only(Set.of(HOSTESS)), false, false));
        }
        Roster roster = new Roster(List.of(
                new Survivor(CAPTAIN, 1, 7, 5, "base", new Ability.None()),
                new Survivor(HOSTESS, 2, 4, 6, "base", new Ability.ShareEffect(List.of("water", "rum"), true, true,
                        Map.of("water", true, "rum", false))),
                new Survivor(SAILOR, 3, 6, 6, "base", new Ability.None()),
                new Survivor(MATE, 4, 8, 4, "base", new Ability.None())));
        Session s = new Session("thirst-key", roster, new Table(new NavigationDeck(nav, new Random(1)),
                new Provisions(cards), new Random(1)));
        s.beginProvision();
        for (String keep : List.of("rum", "water", "water", "water")) {   // 船长留酒，其余三人各留一张水
            s.provisionKeep(keep);
        }
        s.advancePhase();                                   // → 行动
        for (CharacterId id : List.of(CAPTAIN, HOSTESS, SAILOR, MATE)) {
            s.markActed(id);
        }
        s.advancePhase();                                   // → 航海
        s.prepareRowStack();
        s.beginNavigation(s.takeCardForNavigation(null));
        if (s.overboardPending().isPresent()) {
            s.finishOverboard();
        }
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(CAPTAIN, new GameComponent.Occupant(UUID.randomUUID(), "human"));
        seats.put(HOSTESS, new GameComponent.Occupant(null, "dummy"));
        seats.put(SAILOR, new GameComponent.Occupant(null, "dummy"));
        seats.put(MATE, new GameComponent.Occupant(null, "dummy"));
        component.begin(s, seats);
        return new Fixture(s, component);
    }

    @Test
    @DisplayName("替身在想喝几张时有人喝了一口酒（陪酒女多渴一次）：还是同一轮口渴，接力不作废")
    void rumDuringTheStandInsThoughtKeepsTheSamePrompt() {
        Fixture f = navigatingWithHostessThirsty();
        Session.ThirstPrompt before = f.session().thirstPending().orElseThrow();
        assertEquals(HOSTESS, before.who(), "前提：轮到陪酒女算口渴");
        StandInMinds.ThirstKey key = StandInMinds.thirstKey(f.session(), before);
        assertTrue(StandInMinds.samePrompt(f.component(), f.session(), key, false), "前提：开始想的那一刻是同一轮");

        f.session().drinkRum(CAPTAIN, "rum");
        Session.ThirstPrompt after = f.session().thirstPending().orElseThrow();
        assertNotEquals(before.remaining(), after.remaining(), "前提：喝了一口酒，陪酒女多渴了一次（局面真的变了）");
        assertTrue(StandInMinds.samePrompt(f.component(), f.session(), key, false),
                "账变了就把这一轮当成「不在了」：替身那一手作废，这一段没人再往下推，整局停住");
    }

    @Test
    @DisplayName("对照：这一轮结了（问到了下一位 / 这张牌的口渴算完）就不再是同一轮")
    void settledPromptIsNoLongerTheSame() {
        Fixture f = navigatingWithHostessThirsty();
        StandInMinds.ThirstKey key = StandInMinds.thirstKey(f.session(), f.session().thirstPending().orElseThrow());
        f.session().decideThirst(List.of());
        assertFalse(StandInMinds.samePrompt(f.component(), f.session(), key, false), "这一轮已经结了还认它");
    }

    @Test
    @DisplayName("对照：给真人开了窗的那一段不是替身自己接力的那一段")
    void humanWindowIsAnotherStretch() {
        Fixture f = navigatingWithHostessThirsty();
        StandInMinds.ThirstKey key = StandInMinds.thirstKey(f.session(), f.session().thirstPending().orElseThrow());
        f.component().openThirstWindow(20_000L);
        assertFalse(StandInMinds.samePrompt(f.component(), f.session(), key, false));
        assertTrue(StandInMinds.samePrompt(f.component(), f.session(), key, true), "窗口里那一段（R4）认同一轮");
    }
}
