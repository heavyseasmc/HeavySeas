package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.sim.Simulator;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布快照：全船随机席位 vs 全船按处境打分。<b>只打印，不判定</b>（除了「真的跑了」这一类正向对照）。
 *
 * <p>与 {@code DistributionDumpTest} 同一个用意：数字写进断言，下一次合理的改动就会把它变成噪音。
 * 这里印的是给人看的：一局多长、多少局靠岸、谁死得多、分数怎么分布；外加两张给改估值的人看的表 ——
 * 估值的<b>校准表</b>（每一位自估「我会死」与最后死没死），与<b>每个决定花多久</b>。
 */
class SeatDistributionTest {

    private static final int GAMES = 2000;

    private record Summary(double turns, int landed, int allDead, double fights, Map<CharacterId, double[]> perSeat,
                           List<Integer> totals) {
    }

    private static Summary run(SeatPolicies seats) {
        Roster roster = LocalData.roster().preset(8);
        Simulator sim = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(),
                LocalData.roster().treasureScoring(), seats, List.of());
        List<Simulator.Result> results = LongStream.range(0, GAMES).parallel().mapToObj(sim::run).toList();
        long turns = 0;
        long fights = 0;
        int landed = 0;
        int allDead = 0;
        // 每个角色：[得分合计, 拿到最高分的局数, 死了几局（不含恨自己的那几局 —— 他们的生存分恒为 0，看不出死活）, 看得出死活的局数]
        Map<CharacterId, double[]> perSeat = new LinkedHashMap<>();
        roster.survivors().forEach(s -> perSeat.put(s.id(), new double[4]));
        List<Integer> totals = new ArrayList<>();
        for (Simulator.Result r : results) {
            turns += r.turns();
            fights += r.fights();
            if (r.outcome() == GameState.Outcome.LANDED) {
                landed++;
            } else {
                allDead++;
            }
            int best = r.scores().values().stream().mapToInt(ScoreSheet::total).max().orElse(0);
            for (Map.Entry<CharacterId, ScoreSheet> e : r.scores().entrySet()) {
                double[] acc = perSeat.get(e.getKey());
                ScoreSheet sh = e.getValue();
                acc[0] += sh.total();
                if (sh.total() == best) {
                    acc[1]++;
                }
                totals.add(sh.total());
            }
        }
        return new Summary((double) turns / GAMES, landed, allDead, (double) fights / GAMES, perSeat, totals);
    }

    private static int percentile(List<Integer> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * sorted.size())));
    }

    @Test
    @DisplayName("分布快照：全随机 vs 全按处境打分（真实 8 人 2000 局，只打印不判定）")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dump() {
        Summary random = run(SeatPolicies.all(RandomSeatPolicy.indifferent()));
        Summary smart = run(SeatPolicies.all(new HeuristicSeatPolicy(SeatPolicySettings.DEFAULTS.withoutSearch())));
        System.out.printf("分布快照（%d 局 · 真实 8 人 · 不翻天候）%n", GAMES);
        System.out.printf("                    %12s %12s%n", "全随机", "全按处境打分");
        System.out.printf("  平均天数            %12.2f %12.2f%n", random.turns(), smart.turns());
        System.out.printf("  靠岸                %11.1f%% %11.1f%%%n", 100.0 * random.landed() / GAMES,
                100.0 * smart.landed() / GAMES);
        System.out.printf("  全灭                %11.1f%% %11.1f%%%n", 100.0 * random.allDead() / GAMES,
                100.0 * smart.allDead() / GAMES);
        System.out.printf("  每局打架            %12.2f %12.2f%n", random.fights(), smart.fights());
        List<Integer> rs = new ArrayList<>(random.totals());
        List<Integer> ss = new ArrayList<>(smart.totals());
        Collections.sort(rs);
        Collections.sort(ss);
        System.out.printf("  个人得分 p10/p50/p90  %4d/%3d/%3d  %4d/%3d/%3d%n", percentile(rs, 0.1), percentile(rs, 0.5),
                percentile(rs, 0.9), percentile(ss, 0.1), percentile(ss, 0.5), percentile(ss, 0.9));
        System.out.println("  按角色：平均得分 · 拿到最高分的局数（并列都算）");
        random.perSeat().forEach((id, acc) -> {
            double[] s = smart.perSeat().get(id);
            System.out.printf("    %-10s %6.2f 分 %5d 局   %6.2f 分 %5d 局%n", id.value(), acc[0] / GAMES, (int) acc[1],
                    s[0] / GAMES, (int) s[1]);
        });
        // 正向对照：两边都真的打了、真的算了分
        assertTrue(random.turns() > 1 && smart.turns() > 1);
        assertTrue(rs.size() == GAMES * 8 && ss.size() == GAMES * 8, "有的局没有计分");
    }

    // ---------------------------------------------------------------- 校准与耗时

    /** 套在按处境打分外面：记下每次行动时自估的「我会死」，以及每个决定花了多久。 */
    static final class Probe implements SeatPolicy {
        final HeuristicSeatPolicy inner;
        final List<Double> predictions = Collections.synchronizedList(new ArrayList<>());
        boolean selfHate;
        final Map<String, List<Long>> nanos;

        Probe(HeuristicSeatPolicy inner, Map<String, List<Long>> nanos) {
            this.inner = inner;
            this.nanos = nanos;
        }

        private <T> T timed(String what, Supplier<T> call) {
            long t0 = System.nanoTime();
            T out = call.get();
            nanos.computeIfAbsent(what, k -> Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime() - t0);
            return out;
        }

        @Override
        public String keepProvision(SeatView v, List<String> o, Random r) {
            return timed("补给箱留牌", () -> inner.keepProvision(v, o, r));
        }

        @Override
        public Optional<String> reveal(SeatView v, List<String> o, Random r) {
            return timed("亮牌", () -> inner.reveal(v, o, r));
        }

        @Override
        public Optional<String> drink(SeatView v, List<String> o, Random r) {
            return timed("喝酒", () -> inner.drink(v, o, r));
        }

        @Override
        public Optional<Gift> give(SeatView v, List<Gift> o, Random r) {
            return timed("送牌", () -> inner.give(v, o, r));
        }

        @Override
        public ActionChoice act(SeatView v, List<ActionChoice> o, Random r) {
            Outlook out = inner.outlook(v);
            predictions.add(out.pDie(out.me));
            selfHate = v.hate().map(v.self()::equals).orElse(false);
            return timed("行动", () -> inner.act(v, o, r));
        }

        @Override
        public int keepRowCard(SeatView v, List<NavigationCard> o, Random r) {
            return timed("划船留牌", () -> inner.keepRowCard(v, o, r));
        }

        @Override
        public boolean refuse(SeatView v, Random r) {
            return timed("表态", () -> inner.refuse(v, r));
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView v, Random r) {
            return timed("站队", () -> inner.joinStance(v, r));
        }

        @Override
        public Optional<String> drinkForFight(SeatView v, List<String> o, Random r) {
            return timed("打架前喝酒", () -> inner.drinkForFight(v, o, r));
        }

        @Override
        public List<String> commitWeapons(SeatView v, List<String> o, Random r) {
            return timed("押武器", () -> inner.commitWeapons(v, o, r));
        }

        @Override
        public PickChoice pick(SeatView v, List<PickChoice> o, Random r) {
            return timed("挑牌", () -> inner.pick(v, o, r));
        }

        @Override
        public NavigationCard steer(SeatView v, List<NavigationCard> o, Random r) {
            return timed("舵手挑牌", () -> inner.steer(v, o, r));
        }

        @Override
        public Optional<Session.OverboardPlay> overboard(SeatView v, List<Session.OverboardPlay> o, Random r) {
            return timed("落海时打牌", () -> inner.overboard(v, o, r));
        }

        @Override
        public WaterPlan drinkWater(SeatView v, int u, Random r) {
            return timed("喝水", () -> inner.drinkWater(v, u, r));
        }

        @Override
        public int donateWater(SeatView v, CharacterId d, int s, int m, boolean h, int ds, Random r) {
            return timed("递水", () -> inner.donateWater(v, d, s, m, h, ds, r));
        }

        @Override
        public String label() {
            return inner.label();
        }
    }

    @Test
    @DisplayName("估值的校准表与每个决定花多久（全按处境打分 400 局，只打印不判定）")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void calibrationAndTiming() {
        Roster roster = LocalData.roster().preset(8);
        Map<String, List<Long>> nanos = new ConcurrentHashMap<>();
        double[][] bins = new double[10][3];
        int predictions = 0;
        for (long seed = 0; seed < 400; seed++) {
            Map<CharacterId, Probe> probes = new LinkedHashMap<>();
            Simulator sim = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(),
                    LocalData.roster().treasureScoring(), (seat, s) -> probes.computeIfAbsent(seat,
                    k -> new Probe(new HeuristicSeatPolicy(SeatPolicySettings.DEFAULTS.withoutSearch()), nanos)),
                    List.of());
            Simulator.Result r = sim.run(seed);
            for (Map.Entry<CharacterId, Probe> e : probes.entrySet()) {
                if (e.getValue().selfHate) {
                    continue;                                   // 恨自己的人生存分恒为 0，看不出死活
                }
                boolean died = r.scores().get(e.getKey()).selfSurvival() == 0;
                for (double p : e.getValue().predictions) {
                    int b = Math.min(9, (int) (p * 10));
                    bins[b][0] += p;
                    bins[b][1] += died ? 1 : 0;
                    bins[b][2]++;
                    predictions++;
                }
            }
        }
        System.out.println("估值校准：每一位每天行动时自估的「我会死」（分十档） vs 他最后死没死");
        for (double[] bin : bins) {
            if (bin[2] > 0) {
                System.out.printf("  估 %.2f  实际 %.2f  （%d 次）%n", bin[0] / bin[2], bin[1] / bin[2], (int) bin[2]);
            }
        }
        System.out.println("每个决定花多久（微秒；单线程，按处境打分）：");
        new TreeMap<>(nanos).forEach((what, list) -> {
            List<Long> sorted = new ArrayList<>(list);
            Collections.sort(sorted);
            System.out.printf("  %-8s p50 %7.1f  p95 %7.1f  最长 %8.1f  （%d 次）%n", what,
                    sorted.get(sorted.size() / 2) / 1e3, sorted.get((int) (sorted.size() * 0.95)) / 1e3,
                    sorted.getLast() / 1e3, sorted.size());
        });
        assertTrue(predictions > 1000, "校准表只有 " + predictions + " 个点：探针没接上");
    }
}
