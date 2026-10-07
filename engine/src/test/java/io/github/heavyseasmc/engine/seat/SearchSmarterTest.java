package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 往前推演的替身（第二层）是不是比只按处境打分的（第一层）更聪明 —— 同 {@code SmarterTest}，<b>拿终局计分量</b>。
 *
 * <h2>怎么量</h2>
 * 混坐一桌：一位被测，同桌七位都是第一层（模组里一桌替身就是这样坐的）；被测的那一位轮流坐遍 8 个角色、每个角色同一段种子。
 * 同一批（角色, 种子）下，「第一层坐那个位子」「随机席位坐那个位子」也各跑一遍：三档逐局成对比（{@link MixedTable#paired}）。
 *
 * <h2>红测：推演的估分反过来</h2>
 * 剪枝照常、只把推演出来的分数反过来 —— 它在第一层留下的几个候选里专挑推演出来最差的。它必须<b>明显输给</b>第一层：
 * 量不出这一条，「推演比第一层强」那一条就分不清是推演起了作用，还是别的什么。
 *
 * <h2>预算</h2>
 * 不限时（可复现，结果与机器快慢无关），每步 {@value #ROLLOUTS} 局、看两天 —— 比默认（每步 128 局、限时 250 ms）小：
 * 默认那一档跑 200 局要十来分钟，放不进每次构建。默认预算下的数字由 {@code SearchBenchTest} 手动量。
 *
 * <h2>同桌是随机席位时</h2>
 * 不在这里判。推演把同桌当第一层来推（模组里也只能这么猜），同桌真是随机席位时，推出来的「别人会怎么做」是错的：
 * 2026-10-07 实测，小预算（每步 48–64 局）下推演与第一层打平（逐局差 −0.4 ~ +1.6 分，都在两个标准误以内）；
 * 默认预算（每步 128 局）下 +2.31 ± 0.57 分（400 局，{@code SearchBenchTest}）—— 赢得比同桌是第一层时（+4.14）少。
 */
class SearchSmarterTest {

    /** 每个角色几局。8 × 25 = 200 局：逐局差的标准误约 0.7 分。 */
    private static final int SEEDS = 25;
    /** 红测每个角色几局（它输得很多，用不着那么多局）。 */
    private static final int SEEDS_RED = 10;
    static final int ROLLOUTS = 48;
    static final SeatPolicySettings BUDGET = SeatPolicySettings.DEFAULTS.withBudget(ROLLOUTS, 0, 4, 2);

    /** 一组推演替身：每局每座造出来的都留着，事后数推演了几次、出没出错。 */
    record Recorded(SeatPolicies seats, ConcurrentLinkedQueue<SearchSeatPolicy> made) {

        int failures() {
            return made.stream().mapToInt(SearchSeatPolicy::failures).sum();
        }

        String stats(int games) {
            long decisions = made.stream().mapToLong(p -> p.decisionNanos().size()).sum();
            long rollouts = made.stream().mapToLong(SearchSeatPolicy::rollouts).sum();
            return "推演过的决定 %d 个（每局 %.1f 个，每个平均 %.0f 局）· 出错 %d".formatted(decisions,
                    decisions / (double) games, rollouts / (double) Math.max(1, decisions), failures());
        }
    }

    static Recorded recorded(SeatPolicySettings settings, SearchSeatPolicy.Objective objective) {
        ConcurrentLinkedQueue<SearchSeatPolicy> made = new ConcurrentLinkedQueue<>();
        SeatPolicies inner = SearchSeatPolicy.seats(settings, LocalData.roster().treasureScoring(), objective);
        return new Recorded((seat, seed) -> {
            SearchSeatPolicy p = (SearchSeatPolicy) inner.forSeat(seat, seed);
            made.add(p);
            return p;
        }, made);
    }

    @Test
    @DisplayName("❗混坐（同桌七位第一层）：推演 > 第一层 > 随机席位；推演的估分反过来就明显输给第一层（红测）")
    @Timeout(value = 1200, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void searchBeatsLayer1() {
        SeatPolicies layer1 = SeatPolicies.all(new HeuristicSeatPolicy(SeatPolicySettings.DEFAULTS.withoutSearch()));
        MixedTable.Tally random = MixedTable.measure("随机席位", SeatPolicies.all(RandomSeatPolicy.indifferent()),
                layer1, "第一层", SEEDS, List.of());
        MixedTable.Tally base = MixedTable.measure("第一层", layer1, layer1, "第一层", SEEDS, List.of());
        Recorded good = recorded(BUDGET, SearchSeatPolicy.Objective.NORMAL);
        MixedTable.Tally search = MixedTable.measure("推演", good.seats(), layer1, "第一层", SEEDS, List.of());
        MixedTable.Tally baseRed = MixedTable.measure("第一层", layer1, layer1, "第一层", SEEDS_RED, List.of());
        Recorded bad = recorded(BUDGET, SearchSeatPolicy.Objective.INVERTED);
        MixedTable.Tally inverted = MixedTable.measure("推演估分反过来（红测）", bad.seats(), layer1, "第一层", SEEDS_RED,
                List.of());

        MixedTable.Paired gain = MixedTable.paired(search, base);
        MixedTable.Paired l1 = MixedTable.paired(base, random);
        MixedTable.Paired loss = MixedTable.paired(inverted, baseRed);
        System.out.printf("混坐（一位被测、七位第一层；被测的那一位轮流坐遍 8 个角色；推演每步 %d 局、看 2 天、留 4 个、不限时）：%n",
                ROLLOUTS);
        for (MixedTable.Tally t : List.of(random, base, search, inverted)) {
            System.out.println("  " + t.line());
        }
        System.out.println("  " + l1.line("第一层", "随机席位"));
        System.out.println("  " + gain.line("推演", "第一层") + "｜" + good.stats(search.games()));
        System.out.println("  " + loss.line("推演估分反过来", "第一层") + "｜" + bad.stats(inverted.games()));

        for (MixedTable.Tally t : List.of(random, base, search, baseRed, inverted)) {
            assertEquals(0, t.fallbacks(), t.label() + " 交回了不合法的答案");
        }
        assertEquals(0, good.failures() + bad.failures(), "推演出过错");
        // 量具本身：全是第一层时，「那一位」与同桌是同一种人
        assertTrue(Math.abs(base.seatTotal() - base.othersTotal()) < 1e-9, "全第一层的桌上那一位与别人不一样：量具偏了");

        assertTrue(l1.mean() > 3.0 && l1.mean() > 3 * l1.se(),
                "第一层没有明显强过随机席位：" + l1.line("第一层", "随机席位"));
        assertTrue(gain.mean() > 1.0 && gain.mean() > 2.5 * gain.se(),
                "推演没有明显强过第一层：" + gain.line("推演", "第一层"));
        assertTrue(loss.mean() < -2.0 && loss.mean() < -3 * loss.se(),
                "红测没红：推演的估分反过来，却没有明显输给第一层：" + loss.line("推演估分反过来", "第一层"));
    }
}
