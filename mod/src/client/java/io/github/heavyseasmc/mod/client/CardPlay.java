package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.CatalogS2C;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.text.Text;

/**
 * 一张物资牌此刻能不能「打出」、不能的话为什么 —— 手牌一面与座位面板（自己面前那一排）共用这一份（ADR-0095 A3）。
 *
 * <p>❗此前座位面板自己写了一份：只问「行动阶段 · 能行动」，于是没轮到你时按下去被服务端静默丢掉，
 * 已经撑开的伞还能再「撑」一次、白花一个行动 —— 与 ADR-0094 修好的手牌一面同一类，只是在另一面上。
 * 两面各写一份判据，迟早又分家，所以收到这里。判据与服务端 {@code ActionPhase#onUseProvision} 同源：
 * 牌的种类取自目录（{@link CatalogS2C.Play}，服务端从引擎同一张牌算），时机取自投影。
 */
final class CardPlay {

    private CardPlay() {
    }

    /** 这张牌的「打出」什么时候行得通；没收到目录时当作「轮到你时」，由服务端裁决。 */
    static CatalogS2C.Play when(String card) {
        CatalogS2C.Provisions entry = Catalog.provision(card);
        return entry == null ? CatalogS2C.Play.TURN : entry.playWhen();
    }

    /**
     * 此刻按「打出」行不行得通。手牌一面（手里与面前两排）与座位面板（自己面前那一排）都问这一处。
     *
     * <p>❗还有几条服务端的判据投影里没有、这里判不了，按下去照样会被拒（拒绝那句由 {@link ActionBarEcho} 显出来）：
     * 这一回合已经喝过的酒（引擎 {@code usedThisTurn}）· 医疗箱要船上有伤员 · 绝境要有尸体（目录不说一张牌是哪种效果）。
     * 根上的改法是服务端把每张牌「此刻打不打得出」直接写进投影（审查 2026-10-07 U15，已报给服务端那一块）。
     */
    static boolean playableNow(HudView view, String card) {
        return playableNow(view, card, when(card));
    }

    /** 同上，牌的种类由调用方给（单测用；界面走上面那个，从目录取）。 */
    static boolean playableNow(HudView view, String card, CatalogS2C.Play when) {
        if (view.endgame().active()) {
            return false;                     // 终局翻牌时酒也不再能喝（服务端那一支此时静默跳过，ADR-0095 A8）
        }
        return switch (when) {
            // 已经撑开的伞再「撑」一次只是白花一个行动（服务端 ActionPhase 那一句 already_open）
            case TURN -> view.myTurnToAct() && !alreadyOpen(view, card);
            // 被抢、挑牌那一刻的被抢方不能喝（审查 2026-10-07 C1）：喝酒把手里那瓶挪到面前，等于把它从这一抢里躲开 ——
            // 那瓶要是他手上唯一一张，小孩（只拿手牌）就什么都挑不了，超时那一下在服务端抛异常。服务端补了同一道门，
            // 这里不给按钮：与「亮出」那一道（HandScreen#canReveal）同一个判据
            case ANYTIME -> view.active() && view.seated() && view.condition().canAct() && !pickedFrom(view);
            default -> false;
        };
    }

    /**
     * 这张是不是已经撑开（投影里自己面前那一排带着「撑开」标记）。❗按<b>牌 id</b> 认，与服务端 {@code isOpen(cardId)} 同一个口径：
     * 面前那把撑开了，手里同名的那一张服务端也当它「已经撑开」。
     */
    static boolean alreadyOpen(HudView view, String card) {
        return view.front().stream().anyMatch(f -> f.id().equals(card) && f.open());
    }

    /** 一场抢夺停在挑牌那一刻，而被抢的是我（规则 §5：那一刻不能亮牌，也不能喝酒）。 */
    static boolean pickedFrom(HudView view) {
        var contest = view.contest();
        return contest.active() && contest.stage() == io.github.heavyseasmc.engine.play.Contest.Stage.PICK
                && view.character().equals(contest.target());
    }

    /** 打不出时说一句为什么（位置与服务端的拒绝相同，{@link ActionBarEcho}）。 */
    static Text whyNot(HudView view, String card, Text cardName) {
        CatalogS2C.Play when = when(card);
        if (when == CatalogS2C.Play.TURN && alreadyOpen(view, card)) {
            return Text.translatable("heavyseas.command.already_open", cardName);
        }
        if (view.endgame().active() && when.playable()) {
            return Text.translatable("heavyseas.card_action.rejected");
        }
        return switch (when) {
            case TURN -> Text.translatable("heavyseas.hand.not_your_turn");
            case ANYTIME -> Text.translatable("heavyseas.card_action.rejected");
            case OTHER -> Text.translatable("heavyseas.command.not_special", cardName);
            // 不是打出的牌：说它在哪儿用（「闷棍这些也打不出」—— 武器只在打架时押）
            default -> Text.translatable("heavyseas.hand.used." + when.name().toLowerCase(java.util.Locale.ROOT), cardName);
        };
    }
}
