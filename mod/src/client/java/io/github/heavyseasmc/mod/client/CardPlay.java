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
     * 此刻按「打出」行不行得通。
     *
     * @param alreadyOpen 这张是面前那把已经撑开的伞（只有座位面板会传 {@code true}）：再撑一次只是白花一个行动
     */
    static boolean playableNow(HudView view, String card, boolean alreadyOpen) {
        if (alreadyOpen || view.endgame().active()) {
            return false;                     // 终局翻牌时酒也不再能喝（服务端那一支此时静默跳过，ADR-0095 A8）
        }
        return switch (when(card)) {
            case TURN -> view.myTurnToAct();
            case ANYTIME -> view.active() && view.seated() && view.condition().canAct();
            default -> false;
        };
    }

    /** 打不出时说一句为什么（位置与服务端的拒绝相同，{@link ActionBarEcho}）。 */
    static Text whyNot(HudView view, String card, boolean alreadyOpen, Text cardName) {
        if (alreadyOpen) {
            return Text.translatable("heavyseas.command.already_open", cardName);
        }
        CatalogS2C.Play when = when(card);
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
