package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 被小孩偷手牌的人在挑牌那一刻不能喝手里的酒（审查 2026-10-07 C1，引擎复现原文见审查报表）。
 *
 * <p>喝酒会先把那瓶亮到面前：他手里只有这一瓶时，小孩（只能拿手牌）就什么都挑不了，到点的默认挑牌在引擎里抛，
 * 原先一路冒到每 tick 的计时里、崩服。局面用正式数据照第一轮的引擎探针搭（8 人预设，第一天，小孩偷大副）。
 */
final class DrinkDuringPickTest {

    private static final CharacterId KID = CharacterId.of("kid");
    private static final CharacterId MATE = CharacterId.of("mate");

    @Test
    @DisplayName("挑牌那一刻：被抢的人不能喝手里的酒；别人能喝；不在挑牌时他自己也能喝")
    void victimCannotDrinkFromHandDuringPick() {
        Session s = session();
        s.advancePhase();                                   // 物资 → 行动（这一回合没人留牌，探针同样这么搭）
        for (CharacterId id : s.state().bySeat()) {
            if (id.equals(KID)) {
                break;
            }
            s.markActed(id);
        }
        s.dealFromPile(MATE, "rum");
        assertFalse(ActionPhase.drinkRefusedDuringPick(s, MATE, "rum"), "对照：还没挨抢时照喝");

        s.declare(KID, Contest.Kind.STEAL, MATE);
        Contest c = s.contest().orElseThrow();
        assertEquals(Contest.Stage.PICK, c.stage(), "前提：小孩的偷窃直接进挑牌");
        assertTrue(c.handOnly(), "前提：只能拿手牌");
        assertTrue(ActionPhase.drinkRefusedDuringPick(s, MATE, "rum"),
                "挑牌那一刻放行了被抢方喝手里的酒：手里空了，小孩什么都挑不了，到点的默认挑牌在引擎里抛");
        assertFalse(ActionPhase.drinkRefusedDuringPick(s, KID, "rum"), "对照：被抢的不是他");
    }

    private static Session session() {
        Path data = dataDir();
        RosterData rd = RosterLoader.load(data.resolve("roster").resolve("default.json"));
        Provisions prov = ProvisionLoader.loadCatalog(data.resolve("provisions").resolve("default.json"));
        List<NavigationCard> deck = NavigationLoader.load(data.resolve("navigation").resolve("default.json"),
                rd.ids(), prov.ids());
        Roster roster = rd.preset(8);
        Random rng = new Random(1);
        Session s = new Session("drink-during-pick", roster, new Table(new NavigationDeck(deck, rng), prov, rng));
        s.dealAffinities(Affinities.random(roster, rng));
        assertTrue(s.stealsUncontested(KID), "前提：小孩的偷窃没有预告、只能拿手牌");
        return s;
    }

    private static Path dataDir() {
        for (Path candidate : List.of(Path.of("data"), Path.of("..", "data"))) {
            if (Files.isRegularFile(candidate.resolve("roster").resolve("default.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到仓库 data/ —— 没在测，不是通过");
    }
}
