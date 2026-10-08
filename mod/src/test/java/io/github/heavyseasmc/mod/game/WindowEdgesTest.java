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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 几扇窗口的边（审查 2026-10-07）：落海那一窗等不等还在想的替身（R3）· 站队 / 押武器的软倒计时只延不缩（R11）·
 * 口渴无人化解那一句不念出他手上有几张水（L2）。
 */
final class WindowEdgesTest {

    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId KID = CharacterId.of("kid");

    private static Session session(int captainWaters) {
        Provisions provisions = new Provisions(List.of(new Provision(Session.WATER, Provision.Category.CONSUMABLE, 8,
                new ProvisionEffect.PreventThirst(1, "thirst_source", ProvisionEffect.Target.ANY_CHARACTER, true,
                        "thirst_resolution", true, false, false, false, false))));
        Session session = new Session("window-edges", new Roster(List.of(
                new Survivor(CAPTAIN, 1, 7, 5, "base", new Ability.None()),
                new Survivor(KID, 8, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        provisions, new Random(2)));
        for (int i = 0; i < captainWaters; i++) {
            session.dealFromPile(CAPTAIN, Session.WATER);
        }
        return session;
    }

    private static GameComponent component(Session session) {
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(CAPTAIN, new GameComponent.Occupant(UUID.randomUUID(), "Tester"));
        seats.put(KID, new GameComponent.Occupant(null, "dummy"));
        component.begin(session, seats);
        return component;
    }

    @Test
    @DisplayName("R3：落海那一窗里没有真人还在选，但替身还在想 —— 不收窗；替身想完才收")
    void overboardWaitsForThinkingStandIns() {
        Session session = session(0);
        GameComponent component = component(session);
        assertTrue(CardActions.overboardSettled(session, component), "前提：没有真人有牌可出、也没有替身在想，可以收");
        component.setStandInsOverboard(true);
        assertFalse(CardActions.overboardSettled(session, component),
                "真人先说完就收窗：替身那一手落地时窗口已经没了，记成「作废」（用户报过）");
        component.openOverboardWindow(20_000L);
        assertFalse(component.standInsOverboard(), "新开的一窗从「没有替身在想」开始");
    }

    @Test
    @DisplayName("R11：站队里有人加入，真人剩下的比追加时长多就不砍；剩得少才补到追加时长")
    void bumpOnlyExtends() {
        GameComponent component = component(session(0));
        long now = System.currentTimeMillis();
        component.openContestWindow(20_000L);
        long deadline = component.contestDeadline();
        ContestPhase.bump(component, 8_000L, now);
        assertEquals(deadline, component.contestDeadline(), "替身一加入就把真人的 20 秒砍成 8 秒");
        assertEquals(20_000L, component.contestWindow(), "总长跟着变了：倒计时条会从一半开始走");

        component.openContestWindow(3_000L);
        ContestPhase.bump(component, 8_000L, System.currentTimeMillis());
        assertTrue(component.contestDeadline() - System.currentTimeMillis() > 7_000L, "对照：只剩 3 秒时照旧补到 8 秒");
        assertEquals(8_000L, component.contestWindow());

        component.clearContest();
        ContestPhase.bump(component, 8_000L, System.currentTimeMillis());
        assertEquals(8_000L, component.contestWindow(), "没开窗的那一段照旧开一扇追加时长的窗（与原先一样）");
    }

    @Test
    @DisplayName("L2：口渴无人化解那一句与他手上有几张水无关 —— 0 张、1 张念出来的是同一句")
    void untendedNoticeDoesNotTellTheWaterCount() {
        Session.ThirstPrompt prompt = new Session.ThirstPrompt(CAPTAIN, ThirstTally.of(ThirstSource.ROWED), 0, 0, 2, 0, 2);
        Session none = session(0);
        Session one = session(1);
        assertEquals(ThirstPhase.untendedNotice(none, prompt), ThirstPhase.untendedNotice(one, prompt),
                "全船看得见的播报随他手上有几张水而变：投影刻意不发的张数，播报念了出来");
    }
}
