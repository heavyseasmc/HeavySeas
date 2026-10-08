package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.CatalogS2C;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.HudView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「打出」那一枚给不给（{@link CardPlay}）：界面给出一件必然被服务端拒的事，比不给更糟（审查 2026-10-07 C1 · U15）。
 */
class CardPlayTest {

    private static final String ME = "kid";

    private static HudView view(boolean myTurn, ContestView contest, List<String> hand, List<HudView.FrontCard> front) {
        return new HudView(true, 2, Phase.ACTION, 0, "", false, HudView.Fog.NONE, List.of(), 0L,
                List.of(ME, "captain"), List.of(), myTurn ? ME : "captain", HudView.Sea.NONE, HudView.Thirst.NONE,
                HudView.Endgame.NONE, contest, true, ME, 5, 5, Condition.CONSCIOUS, 0, "", "",
                myTurn, 0L, 0L, false, 0L, "", List.of(), 0, hand, front, HudView.Score.NONE);
    }

    private static ContestView pickFrom(String target) {
        return new ContestView(Contest.Kind.STEAL, "captain", target, Contest.Stage.PICK, 20_000L, 20_000L,
                List.of("captain"), List.of(target), 7, 3, List.of(), 0, List.of(), 1);
    }

    @Test
    @DisplayName("被抢、挑牌那一刻的被抢方：酒不给「打出」（喝了就把它从这一抢里躲开了，服务端也拒）")
    void noDrinkWhileBeingPickedFrom() {
        assertTrue(CardPlay.playableNow(view(false, ContestView.NONE, List.of("rum"), List.of()), "rum",
                CatalogS2C.Play.ANYTIME), "平时酒随时能喝");
        assertFalse(CardPlay.playableNow(view(false, pickFrom(ME), List.of("rum"), List.of()), "rum",
                CatalogS2C.Play.ANYTIME), "挑牌窗开着、被抢的是我：还给了「打出」");
        assertTrue(CardPlay.playableNow(view(false, pickFrom("captain"), List.of("rum"), List.of()), "rum",
                CatalogS2C.Play.ANYTIME), "被抢的是别人：我照样能喝");
    }

    @Test
    @DisplayName("面前那把伞已经撑开：同名的这一张（手里或面前）再「撑」只是白花一个行动")
    void openParasolIsNotPlayableAgain() {
        List<HudView.FrontCard> open = List.of(new HudView.FrontCard("parasol", true));
        assertFalse(CardPlay.playableNow(view(true, ContestView.NONE, List.of(), open), "parasol",
                CatalogS2C.Play.TURN), "已撑开的伞还给了「打出」");
        assertFalse(CardPlay.playableNow(view(true, ContestView.NONE, List.of("parasol"), open), "parasol",
                CatalogS2C.Play.TURN), "服务端按牌 id 认「撑开了没有」：手里同名的那张也一样");
        assertTrue(CardPlay.playableNow(view(true, ContestView.NONE, List.of(),
                List.of(new HudView.FrontCard("parasol", false))), "parasol", CatalogS2C.Play.TURN), "收着的伞轮到你时能撑");
    }
}
