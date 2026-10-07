package io.github.heavyseasmc.engine.seat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会动脑的替身到底是不是更聪明 —— <b>拿终局计分量，不拿它自己的估分量</b>。
 *
 * <h2>为什么这样量</h2>
 * 替身的每个决定都按自己的估分（{@code Outlook}）挑最高的；拿同一个估分去证明它「做对了」，等于让被检查对象自己证明自己。
 * 这里只看结果：混坐一桌（一位被测，七位随机席位），被测的那一位轮流坐遍 8 个角色、每个角色同一段种子，
 * 最后用规则第十一章的计分器（{@code Scorer}）算分，比平均。
 *
 * <h2>红测：同一把尺子要量得出「做反了」</h2>
 * <ul>
 *   <li><b>估分整个反过来</b>的替身（盼自己死、盼爱的人死、盼恨的人活）必须<b>明显低于</b>随机席位；</li>
 *   <li><b>爱恨对调</b>的替身：它照样会保命、会捡财宝，所以总分未必输给随机席位（实测仍高出不少 ——
 *       说明大头是保命与财宝）；但「爱的人活着」「恨的人死了」这两项必须明显低于正常的替身、也低于随机席位。</li>
 * </ul>
 * 量不出这两样的尺子，量出来的「更聪明」也不可信。
 */
class SmarterTest {

    /** 每个角色几局。8 个角色 × 150 = 1200 局：标准误约 0.2 分，下面的门槛都在十个标准误以外。 */
    private static final int SEEDS = 150;

    private static final SeatPolicySettings LAYER1 = SeatPolicySettings.DEFAULTS.withoutSearch();

    @Test
    @DisplayName("❗混坐：按处境打分的替身明显比随机席位拿分多；估分反过来的明显更少；爱恨对调的输在爱恨两项上")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void heuristicBeatsRandom() {
        MixedTable.Tally base = MixedTable.baseline(SEEDS, List.of());
        MixedTable.Tally normal = MixedTable.measure("按处境打分",
                SeatPolicies.all(new HeuristicSeatPolicy(LAYER1)), SEEDS, List.of());
        MixedTable.Tally swapped = MixedTable.measure("爱恨对调（红测）",
                SeatPolicies.all(new HeuristicSeatPolicy(LAYER1, Outlook.Objective.SWAPPED)), SEEDS, List.of());
        MixedTable.Tally inverted = MixedTable.measure("估分反过来（红测）",
                SeatPolicies.all(new HeuristicSeatPolicy(LAYER1, Outlook.Objective.INVERTED)), SEEDS, List.of());
        System.out.println("混坐（一位被测、七位随机席位；被测的那一位轮流坐遍 8 个角色，每个角色 " + SEEDS + " 局）：");
        for (MixedTable.Tally t : List.of(base, normal, swapped, inverted)) {
            System.out.println("  " + t.line());
        }
        for (MixedTable.Tally t : List.of(base, normal, swapped, inverted)) {
            assertEquals(0, t.fallbacks(), t.label() + " 交回了不合法的答案");
            assertEquals(SEEDS * 8, t.games());
        }

        // 量具本身：全随机时「那一位」与同桌其余人是同一种人，平均分应当几乎一样（差在一分以内）
        assertTrue(Math.abs(base.seatTotal() - base.othersTotal()) < 1.0,
                "全随机桌上那一位与别人差了 %.2f 分：量具偏了".formatted(base.seatTotal() - base.othersTotal()));

        double margin = normal.seatTotal() - normal.othersTotal();
        assertTrue(margin > 5.0, "按处境打分只比同桌随机席位多 %.2f 分".formatted(margin));
        assertTrue(normal.seatTotal() > base.seatTotal() + 5.0,
                "按处境打分 %.2f 分，只比随机席位坐同一批位子（%.2f）多一点".formatted(normal.seatTotal(), base.seatTotal()));

        assertTrue(inverted.seatTotal() < base.seatTotal() - 1.0,
                "红测没红：估分反过来的替身 %.2f 分，并不明显低于随机席位 %.2f 分"
                        .formatted(inverted.seatTotal(), base.seatTotal()));

        assertTrue(swapped.seatAffinity() < normal.seatAffinity() - 1.5,
                "红测没红：爱恨对调后爱恨两项 %.2f 分，没有明显低于正常的 %.2f 分"
                        .formatted(swapped.seatAffinity(), normal.seatAffinity()));
        assertTrue(swapped.seatAffinity() < base.seatAffinity(),
                "红测没红：爱恨对调后爱恨两项 %.2f 分，竟不低于随机席位的 %.2f 分"
                        .formatted(swapped.seatAffinity(), base.seatAffinity()));
    }

    @Test
    @DisplayName("混坐的结果同一个种子跑两次一模一样（被测的那一位也只从这一局的随机流拿随机数）")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void mixedTablesAreReproducible() {
        MixedTable.Tally a = MixedTable.measure("一", SeatPolicies.all(new HeuristicSeatPolicy(LAYER1)), 12, List.of());
        MixedTable.Tally b = MixedTable.measure("二", SeatPolicies.all(new HeuristicSeatPolicy(LAYER1)), 12, List.of());
        assertEquals(a.byCharacter(), b.byCharacter());
        assertEquals(a.seatTotal(), b.seatTotal());
        assertEquals(a.othersTotal(), b.othersTotal());
    }
}
