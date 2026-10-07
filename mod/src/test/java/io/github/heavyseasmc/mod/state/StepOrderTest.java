package io.github.heavyseasmc.mod.state;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程按到期时刻取（ADR-0095 F1）。原先先进先出、只看队头：替身站队、押武器一次排好几步、各带随机延迟，
 * 队头那一步晚到就堵住后面早到的，等它一到后面几步连着做完 —— 用户 2026-10-07 实拍「加入防守、加入进攻没有延迟」。
 */
class StepOrderTest {

    @Test
    @DisplayName("晚到的一步排在前面，也不堵住早到的；同一时刻到期的按排进来的先后")
    void earliestDueFirst() {
        GameComponent component = new GameComponent(null);
        component.begin(session(), Map.of());
        long t0 = 1_000_000L;
        List<String> done = new ArrayList<>();
        component.schedule(t0 + 6_000, "late", () -> done.add("late"));
        component.schedule(t0 + 1_500, "early-a", () -> done.add("early-a"));
        component.schedule(t0 + 1_500, "early-b", () -> done.add("early-b"));

        assertTrue(component.pollDueStep(t0 + 1_000).isEmpty(), "还没到点：一步都不取");
        component.pollDueStep(t0 + 2_000).orElseThrow().action().run();
        component.pollDueStep(t0 + 2_000).orElseThrow().action().run();
        assertTrue(component.pollDueStep(t0 + 2_000).isEmpty(),
                "晚到的那一步 2 秒时还不该做 —— 原先的先进先出在这里一步都取不到（被它堵着）");
        component.pollDueStep(t0 + 6_000).orElseThrow().action().run();
        assertEquals(List.of("early-a", "early-b", "late"), done);
    }

    private static Session session() {
        Roster roster = new Roster(List.of(
                new Survivor(CharacterId.of("captain"), 1, 7, 5, "base", new Ability.None()),
                new Survivor(CharacterId.of("kid"), 8, 3, 9, "base", new Ability.None())));
        List<NavigationCard> cards = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            cards.add(new NavigationCard("step_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        Provisions provisions = new Provisions(List.of(new Provision("score", Provision.Category.TREASURE, 1,
                new ProvisionEffect.ScoreFlat(1, ""))));
        return new Session("step-order-test", roster, new Table(new NavigationDeck(cards, new Random(1)), provisions));
    }
}
