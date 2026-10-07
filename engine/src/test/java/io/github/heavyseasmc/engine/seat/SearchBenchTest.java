package io.github.heavyseasmc.engine.seat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/**
 * 默认预算下的推演到底强多少 —— <b>手动量具，只打印不判定</b>（判定在 {@code SearchSmarterTest}，用的是小一号的预算）。
 *
 * <p>每步 128 局、看 2 天、留 4 个（默认预算），<b>不限时</b>：默认的 250 ms 限时让结果取决于机器快慢、
 * 几十个线程一起跑时每步推演的局数也比真玩时少；单线程时限时那一档平均每步约 84 局（{@code SearchSeatPolicyTest} 打印）。
 * 两种同桌各 400 局，十几分钟（32 线程的机器上）。
 *
 * <p>平时不跑：要跑时
 * <pre>HEAVYSEAS_SEARCH_BENCH=1 ./gradlew :engine:test --tests io.github.heavyseasmc.engine.seat.SearchBenchTest</pre>
 * ❗没设那个环境变量时 Gradle 只打一行 SKIPPED，不说为什么（ENGINEERING.md 证伪表）—— 理由就是这一段。
 */
@EnabledIfEnvironmentVariable(named = "HEAVYSEAS_SEARCH_BENCH", matches = "1")
class SearchBenchTest {

    private static final int SEEDS = 50;
    private static final int SEEDS_RED = 25;

    @Test
    @DisplayName("默认预算的推演：同桌第一层 / 同桌随机席位，各 400 局（只打印）")
    @Timeout(value = 7200, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void bench() {
        SeatPolicySettings budget = SeatPolicySettings.DEFAULTS.withBudget(SeatPolicySettings.DEFAULT_ROLLOUTS, 0,
                SeatPolicySettings.DEFAULT_WIDTH, SeatPolicySettings.DEFAULT_HORIZON);
        SeatPolicies layer1 = SeatPolicies.all(new HeuristicSeatPolicy(SeatPolicySettings.DEFAULTS.withoutSearch()));
        SeatPolicies random = SeatPolicies.all(RandomSeatPolicy.indifferent());
        System.out.printf("推演每步 %d 局 · 看 %d 天 · 留 %d 个 · 不限时%n", budget.rollouts(), budget.horizonDays(),
                budget.width());
        for (String field : List.of("第一层", "随机席位")) {
            SeatPolicies rest = field.equals("第一层") ? layer1 : random;
            MixedTable.Tally randomSeat = MixedTable.measure("随机席位", random, rest, field, SEEDS, List.of());
            MixedTable.Tally base = MixedTable.measure("第一层", layer1, rest, field, SEEDS, List.of());
            SearchSmarterTest.Recorded good = SearchSmarterTest.recorded(budget, SearchSeatPolicy.Objective.NORMAL);
            MixedTable.Tally search = MixedTable.measure("推演", good.seats(), rest, field, SEEDS, List.of());
            System.out.println("同桌七位" + field + "：");
            for (MixedTable.Tally t : List.of(randomSeat, base, search)) {
                System.out.println("  " + t.line());
            }
            System.out.println("  " + MixedTable.paired(base, randomSeat).line("第一层", "随机席位"));
            System.out.println("  " + MixedTable.paired(search, base).line("推演", "第一层") + "｜"
                    + good.stats(search.games()));
            if (field.equals("第一层")) {
                MixedTable.Tally baseRed = MixedTable.measure("第一层", layer1, rest, field, SEEDS_RED, List.of());
                SearchSmarterTest.Recorded bad = SearchSmarterTest.recorded(budget,
                        SearchSeatPolicy.Objective.INVERTED);
                MixedTable.Tally inverted = MixedTable.measure("推演估分反过来（红测）", bad.seats(), rest, field,
                        SEEDS_RED, List.of());
                System.out.println("  " + inverted.line());
                System.out.println("  " + MixedTable.paired(inverted, baseRed).line("推演估分反过来", "第一层") + "｜"
                        + bad.stats(inverted.games()));
            }
        }
    }
}
