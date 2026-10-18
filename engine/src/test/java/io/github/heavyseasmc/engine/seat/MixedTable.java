package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.sim.Simulator;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

/**
 * 混坐量具：一个座位换成被测的策略，其余座位同一种策略（随机席位，或者第一层）；被测的那一位轮流坐遍 8 个角色，每个角色跑同一段种子。
 *
 * <p>❗角色本身就有强弱（珠宝商随机桌也比医生多一分多），所以每个角色坐的局数一样多 —— 比的是同一批角色的平均。
 * 同一批种子下，「同桌那种策略坐那个位子」也跑一遍作基线（{@link #baseline}）。
 *
 * <h2>成对比较</h2>
 * 两组若用同一批（角色, 种子），第 k 局发的牌、爱恨、天候一样，只差那一位怎么打：
 * 比每一局的差（{@link #paired}）比两组各自的平均干净得多 —— 一局的总分标准差约 9 分，大半来自发牌。
 */
final class MixedTable {

    /** 一组混坐的结果。分数是终局计分（{@code Scorer}），四项分开记。 */
    record Tally(String label, String restLabel, int games, double[] seat, double[] others, int othersCount,
                 Map<CharacterId, Double> byCharacter, int fallbacks, double seconds, double seatSd,
                 double[] totals) {

        /** 那一位平均分的标准误（每局一个总分，按局算）。 */
        double seatSe() {
            return seatSd / Math.sqrt(games);
        }

        double seatTotal() {
            return seat[0] + seat[1] + seat[2] + seat[3];
        }

        double othersTotal() {
            return others[0] + others[1] + others[2] + others[3];
        }

        /** 「爱的人活着」与「恨的人死了」两项之和（爱恨对调的红测看的就是这两项）。 */
        double seatAffinity() {
            return seat[2] + seat[3];
        }

        double othersAffinity() {
            return others[2] + others[3];
        }

        String line() {
            StringBuilder per = new StringBuilder();
            byCharacter.forEach((id, v) -> per.append(String.format(" %s %.1f", id.value(), v)));
            return String.format("%-16s 那一位 %5.2f ± %.2f 分（存活 %.2f · 财宝 %.2f · 所爱 %.2f · 所恨 %.2f）"
                            + "｜同桌%s %5.2f 分（%.2f · %.2f · %.2f · %.2f）｜%d 局 · 退回 %d · %.1f 秒%n"
                            + "                 按角色：%s",
                    label, seatTotal(), seatSe(), seat[0], seat[1], seat[2], seat[3], restLabel, othersTotal(),
                    others[0], others[1], others[2], others[3], games, fallbacks, seconds, per);
        }
    }

    /** 两组同一批（角色, 种子）逐局相减：平均差与它的标准误。 */
    record Paired(double mean, double se, int games) {

        String line(String a, String b) {
            return String.format("%s − %s：逐局差 %+.2f ± %.2f 分（%d 局成对）", a, b, mean, se, games);
        }
    }

    private MixedTable() {
    }

    /** 全船随机席位（基线）：「坐那个位子的」也是随机席位。 */
    static Tally baseline(int seedsPerCharacter, List<WeatherCard> weather) {
        return measure("全随机（基线）", (seat, seed) -> RandomSeatPolicy.indifferent(), seedsPerCharacter, weather);
    }

    /**
     * 同桌七位是随机席位。
     *
     * @param smart 被测的那一位用什么策略（每局每座各造一个，见 {@link SeatPolicies}）
     */
    static Tally measure(String label, SeatPolicies smart, int seedsPerCharacter, List<WeatherCard> weather) {
        return measure(label, smart, SeatPolicies.all(RandomSeatPolicy.indifferent()), "随机席位", seedsPerCharacter,
                weather);
    }

    /**
     * @param rest      同桌另外七位用什么策略
     * @param restLabel 同桌那种策略叫什么（打印用）
     */
    static Tally measure(String label, SeatPolicies smart, SeatPolicies rest, String restLabel,
                         int seedsPerCharacter, List<WeatherCard> weather) {
        Roster roster = LocalData.roster().preset(8);
        var deck = LocalData.navigationDeck();
        var provisions = LocalData.provisions();
        var scoring = LocalData.roster().treasureScoring();
        double[] seat = new double[4];
        double[] others = new double[4];
        int othersCount = 0;
        int games = 0;
        int fallbacks = 0;
        double sum = 0;
        double sumSq = 0;
        double[] totals = new double[roster.size() * seedsPerCharacter];
        Map<CharacterId, Double> byCharacter = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        int k = 0;
        // 8 个角色的局一起丢进线程池（逐个角色并行的话，每一批只有「种子数」那么多局，最慢的那一局拖住整批）；
        // 结果按（角色, 种子）的次序收回来，下面照旧逐个角色累加 —— 与一个角色一个角色跑，加法的次序一模一样
        List<Simulator> sims = roster.survivors().stream()
                .map(s -> new Simulator(roster, deck, provisions, scoring, SeatPolicies.one(s.id(), smart, rest),
                        weather))
                .toList();
        List<Simulator.Result> all = LongStream.range(0, (long) sims.size() * seedsPerCharacter).parallel()
                .mapToObj(i -> sims.get((int) (i / seedsPerCharacter)).run(i % seedsPerCharacter)).toList();
        int c = 0;
        for (Survivor s : roster.survivors()) {
            CharacterId who = s.id();
            List<Simulator.Result> results = all.subList(c * seedsPerCharacter, (c + 1) * seedsPerCharacter);
            c++;
            double mine = 0;
            for (Simulator.Result r : results) {
                games++;
                fallbacks += r.fallbacks();
                for (Map.Entry<CharacterId, ScoreSheet> e : r.scores().entrySet()) {
                    ScoreSheet sh = e.getValue();
                    boolean isSeat = e.getKey().equals(who);
                    double[] t = isSeat ? seat : others;
                    t[0] += sh.selfSurvival();
                    t[1] += sh.treasure();
                    t[2] += sh.loved();
                    t[3] += sh.hated();
                    if (isSeat) {
                        mine += sh.total();
                        sum += sh.total();
                        sumSq += (double) sh.total() * sh.total();
                        totals[k++] = sh.total();
                    } else {
                        othersCount++;
                    }
                }
            }
            byCharacter.put(who, mine / results.size());
        }
        for (int i = 0; i < 4; i++) {
            seat[i] /= games;
            others[i] /= Math.max(1, othersCount);
        }
        double mean = sum / games;
        double sd = Math.sqrt(Math.max(0, sumSq / games - mean * mean));
        return new Tally(label, restLabel, games, seat, others, othersCount, byCharacter, fallbacks,
                (System.nanoTime() - t0) / 1e9, sd, totals);
    }

    /** {@code a} 减 {@code b}，逐局成对（两组必须是同一批角色与种子）。 */
    static Paired paired(Tally a, Tally b) {
        if (a.totals().length != b.totals().length) {
            throw new IllegalArgumentException("两组局数不同，不能成对比：" + a.label() + " / " + b.label());
        }
        int n = a.totals().length;
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double d = a.totals()[i] - b.totals()[i];
            sum += d;
            sumSq += d * d;
        }
        double mean = sum / n;
        double sd = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
        return new Paired(mean, sd / Math.sqrt(n), n);
    }
}
