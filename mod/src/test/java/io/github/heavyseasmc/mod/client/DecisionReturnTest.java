package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.HudView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DecisionReturnTest {
    @Test
    void oldGameAndOldStageCannotReviveAnEarlierDecision() {
        var origin = new DecisionNavigation.Context("game-one", "fight-weapons", null);
        assertFalse(origin.valid(HudView.IDLE));
        assertFalse(origin.valid(view("game-two", "fight-weapons")));
        assertFalse(origin.valid(view("game-one", "fight-pick")));
        assertTrue(origin.valid(view("game-one", "fight-weapons")));
    }

    @Test
    void identicalDecisionRemainsValidAcrossProjectionUpdates() {
        var origin = new DecisionNavigation.Context("game-one", "fight-weapons", null);
        assertTrue(origin.valid(view("game-one", "fight-weapons", 99_000)));
        assertTrue(origin.valid(view("game-one", "fight-weapons", 150_000)));
        assertTrue(origin.valid(view("game-one", "fight-weapons", 100_000)));
        assertFalse(new DecisionNavigation.Context("game-one", "", null).valid(view("game-one", "")));
    }

    /** 2026-10-13 验收 09/10：行动超时之后，手牌一面的导航条还写「G 返回决定」。过期的决定不给入口。 */
    @Test
    void expiredDecisionOffersNoReturnEntry() {
        Object child = new Object();
        var origin = new DecisionNavigation.Context("game-one", "game-one/1/ACTION/action/mate", null);
        assertTrue(DecisionNavigation.offersReturn(origin, child, view("game-one", "game-one/1/ACTION/action/mate")));
        assertFalse(DecisionNavigation.offersReturn(origin, child, view("game-one", "")),
                "超时之后 decisionId 已空，还给了返回入口");
        assertFalse(DecisionNavigation.offersReturn(origin, child, view("game-one", "game-one/1/ACTION/action/doctor")));
        assertFalse(DecisionNavigation.offersReturn(null, child, view("game-one", "x")));
        // 那个决定自己那一面不给「返回」（owner 为 null 时，以 null 当「这一面」就是它自己）
        assertFalse(DecisionNavigation.offersReturn(origin, null, view("game-one", "game-one/1/ACTION/action/mate")));
    }

    /** 2026-10-13 第二轮验收 B08 · L06：进行中的对局里导航小签多了一枚「最近一局」。只在没有对局时给。 */
    @Test
    void lastResultTagOnlyWithoutAGame() {
        var last = new ExperienceView.Result("game-zero", 5, "LANDED", 6, List.of(), "mate", List.of(), 0, List.of());
        assertFalse(DecisionNavigation.offersLastResult(view(true, last), false), "对局进行中不给「最近一局」");
        assertTrue(DecisionNavigation.offersLastResult(view(false, last), false), "正向对照：没有对局时给");
        assertFalse(DecisionNavigation.offersLastResult(view(false, last), true), "计分面板自己有入口");
        assertFalse(DecisionNavigation.offersLastResult(view(false, ExperienceView.Result.NONE), false), "没有上一局");
    }

    /**
     * 2026-10-13 验收 47：行动超时后，只读的行动一面上按 R，先被「收起 → 去开欠着的决定」吃掉，重开的还是行动一面。
     * 过期之后照样留着的那种面（行动一面）不走这一步；别的决策面照旧。
     */
    @Test
    void faceThatOutlivesItsDecisionKeepsTheKey() {
        var origin = new DecisionNavigation.Context("game-one", "game-one/1/ACTION/action/mate", null);
        HudView expired = view("game-one", "");
        HudView live = view("game-one", "game-one/1/ACTION/action/mate");
        assertTrue(DecisionNavigation.redirects(origin, null, false, expired), "普通决策面过期后不再先收起");
        assertFalse(DecisionNavigation.redirects(origin, null, true, expired), "行动一面过期后按键被吃掉、又开了一遍自己");
        assertFalse(DecisionNavigation.redirects(origin, null, false, live), "决定还在就不该收起");
        assertFalse(DecisionNavigation.redirects(origin, new Object(), false, expired), "二级页不归这一条");
        assertFalse(DecisionNavigation.redirects(null, null, false, expired));
    }

    private static HudView view(String match, String decision) {
        return view(match, decision, 99_000);
    }

    private static HudView view(boolean active, ExperienceView.Result last) {
        var h = HudView.IDLE;
        var extra = new ExperienceView("", "", List.of(), List.of(), List.of(), 0, List.of(),
                ExperienceView.BattleReport.NONE, List.of(), List.of(), last, 0);
        return new HudView(active, h.turn(), h.phase(), h.gulls(), h.weather(), h.canRow(), h.fog(), h.notifications(),
                h.notificationSeq(), h.seats(), h.removed(), h.actor(), h.sea(), h.thirstPrompt(), h.endgame(), h.contest(),
                h.seated(), h.character(), h.health(), h.maxHealth(), h.condition(), h.thirst(), h.love(), h.hate(),
                h.yourTurn(), 0, 0, h.designating(), h.designateUntil(), h.provisionTargetCard(),
                h.provisionTargets(), h.myDonatedWater(), h.hand(), h.front(), h.myScore(), extra);
    }

    private static HudView view(String match, String decision, long deadline) {
        var h = HudView.IDLE;
        var extra = new ExperienceView(match, decision, List.of(), List.of(), List.of(), 0, List.of(),
                ExperienceView.BattleReport.NONE, List.of(), List.of(), ExperienceView.Result.NONE, 0);
        return new HudView(true, h.turn(), h.phase(), h.gulls(), h.weather(), h.canRow(), h.fog(), h.notifications(),
                h.notificationSeq(), h.seats(), h.removed(), h.actor(), h.sea(), h.thirstPrompt(), h.endgame(), h.contest(),
                h.seated(), h.character(), h.health(), h.maxHealth(), h.condition(), h.thirst(), h.love(), h.hate(),
                h.yourTurn(), deadline, 20_000, h.designating(), h.designateUntil(), h.provisionTargetCard(),
                h.provisionTargets(), h.myDonatedWater(), h.hand(), h.front(), h.myScore(), extra);
    }
}
