package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.SyntheticTable;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.sim.Simulator;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全船都是按处境打分的替身：几千局照样打到终局，不卡死、不出非法状态、不交回不合法的答案，同一个种子跑两次一模一样。
 *
 * <p>不变量由模拟器每个阶段核对（{@code Invariants}），这里负责把局数跑够、把几种桌面都跑到：
 * 真实 8 人、带天候的真实 8 人（风平浪静不能划船、狂风多翻一张、酷热一次两张水……都只在这里走得到）、
 * 合成的五人阵容（十二种物资效果一种不少）。
 */
class SeatSimulationTest {

    private static Simulator heuristicTable(Roster roster, List<io.github.heavyseasmc.engine.navigation.NavigationCard> deck,
                                            io.github.heavyseasmc.engine.model.Provisions provisions,
                                            List<io.github.heavyseasmc.engine.weather.WeatherCard> weather,
                                            SeatPolicySettings settings, boolean scoring) {
        return new Simulator(roster, deck, provisions, scoring ? LocalData.roster().treasureScoring() : null,
                SeatPolicies.all(new HeuristicSeatPolicy(settings)), weather);
    }

    private static void runMany(String label, Simulator sim, int games) {
        long t0 = System.nanoTime();
        List<Simulator.Result> results = LongStream.range(0, games).parallel().mapToObj(sim::run).toList();
        long t1 = System.nanoTime();
        Map<GameState.Outcome, Integer> outcomes = new TreeMap<>();
        long turns = 0;
        for (Simulator.Result r : results) {
            assertEquals(0, r.fallbacks(), "seed=" + r.seed() + " 按处境打分的替身交回了不合法的答案");
            assertTrue(r.turns() >= 1 && r.turns() <= Simulator.TURN_LIMIT, "seed=" + r.seed());
            outcomes.merge(r.outcome(), 1, Integer::sum);
            turns += r.turns();
        }
        // ❗两种终局都要出现：只出现一种说明状态空间没铺开，「几千局全过」只证明了走通一条路
        assertTrue(outcomes.getOrDefault(GameState.Outcome.LANDED, 0) > 0, label + " 一局都没靠岸：" + outcomes);
        System.out.printf("%-26s %5d 局全部打完 · 平均 %.2f 天 · 终局 %s · %.1f 秒（并行）%n", label, games,
                (double) turns / games, outcomes, (t1 - t0) / 1e9);
    }

    @Test
    @DisplayName("全船按处境打分：真实 8 人 2000 局、带天候 1000 局、合成五人 1000 局，全部打到终局、零退回")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void allHeuristicTablesFinish() {
        SeatPolicySettings layer1 = SeatPolicySettings.DEFAULTS.withoutSearch();
        runMany("真实 8 人 · 温度 0.25", heuristicTable(LocalData.roster().preset(8), LocalData.navigationDeck(),
                LocalData.provisions(), List.of(), layer1, true), 2000);
        runMany("真实 8 人 · 天候", heuristicTable(LocalData.roster().preset(8), LocalData.navigationDeck(),
                LocalData.provisions(), LegalTest.weather(), layer1, true), 1000);
        runMany("真实 6 人 · 天候 · 温度 0", heuristicTable(LocalData.roster().preset(6), LocalData.navigationDeck(),
                LocalData.provisions(), LegalTest.weather(), layer1.withTemperature(0), true), 500);
        runMany("合成五人", heuristicTable(SyntheticTable.roster(), SyntheticTable.deck(), TestProvisions.synthetic(),
                List.of(), layer1, false), 1000);
    }

    @Test
    @DisplayName("同一个种子跑两次结果一模一样（温度不为 0 时随机数也只从这一局的随机流拿）")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void deterministicPerSeed() {
        Simulator sim = heuristicTable(LocalData.roster().preset(8), LocalData.navigationDeck(), LocalData.provisions(),
                LegalTest.weather(), SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(1.0), true);
        for (long seed : new long[]{0, 1, 42, 4242, -7}) {
            assertEquals(sim.run(seed), sim.run(seed), "seed=" + seed + " 不可复现");
        }
        // 正向对照：温度会改走向 —— 否则「可复现」可能只是因为它根本没用随机数
        Simulator cold = heuristicTable(LocalData.roster().preset(8), LocalData.navigationDeck(),
                LocalData.provisions(), LegalTest.weather(), SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(0),
                true);
        boolean differs = false;
        for (long seed = 0; seed < 20 && !differs; seed++) {
            differs = !sim.run(seed).equals(cold.run(seed));
        }
        assertTrue(differs, "温度 1 与温度 0 在 20 个种子上走出的局全都一样：温度没起作用");
    }
}
