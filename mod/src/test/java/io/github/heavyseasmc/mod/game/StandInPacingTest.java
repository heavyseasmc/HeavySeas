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
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.StandInMind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身的脑子换了之后，「替身会不会动」那几条节奏照旧：问的是「脑子不是什么也不做」，不再是「随机开着」。
 *
 * <p>❗随机替身那几个数是钉死的（回归与演示局都按它走）：这里把它们写成字面量，换脑子的改动若碰了它们，这里就红。
 */
class StandInPacingTest {

    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");

    /** 三个人的一局，打到第一天的行动阶段，船长抢大副、大副拒绝 —— 停在站队段。 */
    private static Session standing() {
        Roster roster = new Roster(List.of(
                new Survivor(CAPTAIN, 1, 7, 5, "base", new Ability.None()),
                new Survivor(MATE, 2, 8, 4, "base", new Ability.None()),
                new Survivor(KID, 3, 3, 9, "base", new Ability.None())));
        List<NavigationCard> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cards.add(new NavigationCard("pace_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        Provisions provisions = new Provisions(List.of(new Provision("score", Provision.Category.TREASURE, 6,
                new ProvisionEffect.ScoreFlat(1, ""))));
        Session s = new Session("pacing", roster,
                new Table(new NavigationDeck(cards, new Random(1)), provisions, new Random(1)));
        s.dealFromPile(MATE, "score");
        while (s.state().phase() != Phase.ACTION) {
            s.advancePhase();
        }
        while (!s.nextActor().orElseThrow().equals(CAPTAIN)) {
            s.markActed(s.nextActor().orElseThrow());
        }
        s.declare(CAPTAIN, Contest.Kind.STEAL, MATE);
        s.consent(true);
        assertEquals(Contest.Stage.STANCES, s.contest().orElseThrow().stage(), "没停在站队段");
        return s;
    }

    private static Map<CharacterId, GameComponent.Occupant> occupants(boolean withHuman) {
        Map<CharacterId, GameComponent.Occupant> out = new LinkedHashMap<>();
        out.put(CAPTAIN, new GameComponent.Occupant(null, "dummy"));
        out.put(MATE, new GameComponent.Occupant(null, "dummy"));
        out.put(KID, withHuman ? new GameComponent.Occupant(UUID.randomUUID(), "steve")
                : new GameComponent.Occupant(null, "dummy"));
        return out;
    }

    /** 该答的都答了之后，这一段收成多长的尾巴。 */
    private static long tail(StandInMind mind, boolean fast) {
        GameComponent c = new GameComponent(null);
        c.begin(standing(), occupants(false));
        c.setDummyMind(mind);
        c.setDummyFast(fast);
        c.openContestWindow(20_000);
        assertTrue(ContestPhase.endIfAllDecided(null, c), "全是替身的一段没被认作「都答完了」");
        return c.contestWindow();
    }

    @Test
    @DisplayName("收尾的尾巴：什么也不做 2 秒；随机照旧慢 7 秒、快 0.6 秒；动脑与随机同一套；大模型那一层关着时也一样")
    void contestTail() {
        assertEquals(2_000, tail(StandInMind.IDLE, false));
        assertEquals(2_000, tail(StandInMind.IDLE, true));
        assertEquals(7_000, tail(StandInMind.RANDOM, false), "随机替身的尾巴变了");
        assertEquals(600, tail(StandInMind.RANDOM, true), "随机替身的快档尾巴变了");
        assertEquals(7_000, tail(StandInMind.SMART, false));
        assertEquals(600, tail(StandInMind.SMART, true));
        assertFalse(StandInSettings.llm().enabled(), "单测里大模型那一层应当是关着的");
        assertEquals(600, tail(StandInMind.LLM, true), "大模型那一层关着时不该为它多等");
    }

    @Test
    @DisplayName("轮到替身之后停的那一拍：只看快慢档（随机慢 4 秒、快 0.3 秒；这一场里的一步慢 1.5 秒、快当场）")
    void beats() {
        GameComponent c = new GameComponent(null);
        c.setDummyFast(false);
        assertEquals(4_000, StandInPlay.beat(c));
        assertEquals(1_500, StandInPlay.step(c));
        c.setDummyFast(true);
        assertEquals(300, StandInPlay.beat(c));
        assertEquals(0, StandInPlay.step(c));
    }

    @Test
    @DisplayName("演示局里真人不限时：替身会动（随机 · 动脑 · 大模型）才成立，什么也不做 / 自动推进关着都不成立")
    void untimedDemoFollowsActingMinds() {
        GameComponent c = new GameComponent(null);
        c.begin(standing(), occupants(true));
        c.setDummyMind(StandInMind.IDLE);
        assertFalse(c.demoNoTimeout(), "什么也不做的替身局也成了不限时");
        for (StandInMind mind : List.of(StandInMind.RANDOM, StandInMind.SMART, StandInMind.LLM)) {
            c.setDummyMind(mind);
            assertTrue(c.demoNoTimeout(), mind + " 的替身局没有不限时");
        }
        c.setDummyAutoplay(false);
        assertFalse(c.demoNoTimeout(), "自动推进关着也成了不限时");
    }

    @Test
    @DisplayName("随机开关只管随机：关它不会冲掉指令设的动脑；开它照旧换成随机")
    void randomSwitchOnlyTouchesRandom() {
        GameComponent c = new GameComponent(null);
        assertEquals(StandInMind.IDLE, c.dummyMind(), "默认不是「什么也不做」");
        c.setDummyRandom(true);
        assertEquals(StandInMind.RANDOM, c.dummyMind());
        assertTrue(c.dummyRandom() && c.standInsAct());
        c.setDummyRandom(false);
        assertEquals(StandInMind.IDLE, c.dummyMind());
        assertFalse(c.standInsAct());
        c.setDummyMind(StandInMind.SMART);
        c.setDummyRandom(false);                      // 起服时按设置「随机关」那一下
        assertEquals(StandInMind.SMART, c.dummyMind(), "随机开关关掉时把动脑也冲掉了");
        assertFalse(c.dummyRandom());
        assertTrue(c.standInsAct());
        c.setDummyRandom(true);
        assertEquals(StandInMind.RANDOM, c.dummyMind());
    }
}
