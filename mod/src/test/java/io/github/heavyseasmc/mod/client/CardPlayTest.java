package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.CatalogS2C;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.ExperienceView;
import net.minecraft.text.Text;
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

    @Test
    void theServerDecisionOverridesTheOldClientGuessAndUnknownCardsStayDisabled() {
        Text reason = Text.literal("Already used today");
        ExperienceView experience = new ExperienceView("match", "decision", List.of(), List.of(
                new ExperienceView.Playability("rum", ExperienceView.Action.PLAY, false, reason),
                new ExperienceView.Playability("custom_card", ExperienceView.Action.PLAY, true, Text.empty())),
                List.of(), 0, List.of(), ExperienceView.BattleReport.NONE, List.of(), List.of(), ExperienceView.Result.NONE, 0);
        HudView view = view(true, ContestView.NONE, List.of("rum", "custom_card"), List.of()).withExperience(experience);
        assertFalse(CardPlay.playableNow(view, "rum"));
        assertTrue(CardPlay.playableNow(view, "custom_card"));
        assertFalse(CardPlay.playableNow(view, "not_projected"));
        org.junit.jupiter.api.Assertions.assertEquals(reason, CardPlay.whyNot(view, "rum", Text.literal("Rum")));
    }

    /** 2026-10-13 验收 C04：双击一把短刀，只给通用句「这张牌现在不能以这种方式使用」，没说为什么。 */
    @Test
    @DisplayName("服务端给通用的「时机不对」、而这张本来就不是打出的牌：换成说它在哪儿用；别的原因原样")
    void genericTimingRejectionSaysWhereTheCardIsUsed() {
        Text generic = Text.translatable(CardPlay.WRONG_TIMING);
        Text name = Text.literal("Knife");
        Text fight = CardPlay.refine(generic, CatalogS2C.Play.FIGHT, name);
        org.junit.jupiter.api.Assertions.assertEquals("heavyseas.hand.used.fight", key(fight), "武器被拒仍是通用句");
        org.junit.jupiter.api.Assertions.assertEquals("heavyseas.hand.used.thirst",
                key(CardPlay.refine(generic, CatalogS2C.Play.THIRST, name)));
        org.junit.jupiter.api.Assertions.assertEquals("heavyseas.command.not_special",
                key(CardPlay.refine(generic, CatalogS2C.Play.OTHER, name)));
        // 打得出的牌被说「时机不对」：服务端那一句更准，不换
        org.junit.jupiter.api.Assertions.assertSame(generic, CardPlay.refine(generic, CatalogS2C.Play.TURN, name));
        // 别的原因（没有伤员）不换
        Text wounded = Text.translatable("heavyseas.ux.reject.nobody_wounded");
        org.junit.jupiter.api.Assertions.assertSame(wounded, CardPlay.refine(wounded, CatalogS2C.Play.FIGHT, name));
    }

    private static String key(Text text) {
        return text.getContent() instanceof net.minecraft.text.TranslatableTextContent t ? t.getKey() : text.getString();
    }

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
