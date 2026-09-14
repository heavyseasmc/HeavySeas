package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O1 的测量台：真实 31 张航海牌跑出来的<b>落水 / 口渴频率对体型的分布</b>。
 *
 * <h2>它不判定什么</h2>
 * 本类<b>不断言任何具体数值</b>。数值是结论，写进断言就成了「拿被检查对象自己证明自己」；
 * 它只断言结构性的东西（每个角色都有观测、频率落在 0..1、对照组与静态张数一致），
 * 把表打出来，由人写进 ADR。断言一旦写上「相关系数应当大于 0.4」，下次数据改了就只会被顺手改掉。
 *
 * <h2>两个策略一起跑，因为曲线测的一半是牌、一半是舵手</h2>
 * <ul>
 *   <li><b>无所谓</b>（对照组）：留不留、挑哪张全看运气。它应当与静态张数比几乎重合 ——
 *       这条重合本身就是「统计口径没写错」的正向对照。</li>
 *   <li><b>自保</b>：舵手尽量不挑淋自己的牌。两条曲线的差，就是「舵手会挑牌」这件事
 *       对分布的实际影响 —— 也就是 O1 说的「静态张数 ≠ 实际落水频率」到底差多少。</li>
 * </ul>
 *
 * <p>只能本地跑：航海牌数据在 O1 定案前不入库，缺席时出声跳过。
 */
class NavigationDeckStatsTest {

    /** 局数。2 万局在本机是秒级，而 O1 要的是分布形状，不是三位有效数字。 */
    private static final int GAMES = 20_000;

    private record Measured(Map<CharacterId, Exposure> exposure, double averageTurns,
                            Map<String, Integer> outcomes) {
    }

    private static Measured measure(Roster roster, List<NavigationCard> deck, NavigationPolicy policy) {
        Simulator simulator = new Simulator(roster, deck, policy);
        Map<CharacterId, Exposure> total = new LinkedHashMap<>();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        long turns = 0;
        for (long seed = 0; seed < GAMES; seed++) {
            Simulator.Result result = simulator.run(seed);
            result.exposure().forEach((id, e) -> total.merge(id, e, Exposure::plus));
            outcomes.merge(result.outcome().name(), 1, Integer::sum);
            turns += result.turns();
        }
        return new Measured(total, (double) turns / GAMES, outcomes);
    }

    @Test
    @DisplayName("真实牌堆：落水/口渴频率对体型的分布（本地限定，只打印不判定）")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void distribution() {
        List<NavigationCard> deck = LocalData.navigationDeckOrSkip();
        Roster roster = LocalData.roster().preset(8);

        List<NavigationPolicy> policies = List.of(
                NavigationPolicy.INDIFFERENT,
                NavigationPolicy.SELF_INTERESTED,
                NavigationPolicy.SELF_INTERESTED_NO_RUSH);
        List<Measured> measured = policies.stream().map(p -> measure(roster, deck, p)).toList();

        System.out.println();
        System.out.println("=== O1 测量台 ===");
        System.out.printf("%d 局 × %d 个策略，%d 人阵容，%d 张真实航海牌%n",
                GAMES, policies.size(), roster.size(), deck.size());
        for (int i = 0; i < policies.size(); i++) {
            System.out.printf("  [%d] %-28s 平均 %5.2f 回合，终局 %s%n",
                    i, policies.get(i).label(), measured.get(i).averageTurns(), measured.get(i).outcomes());
        }

        List<Double> sizes = new ArrayList<>();
        List<Double> seats = new ArrayList<>();
        Map<String, List<Double>> columns = new LinkedHashMap<>();
        columns.put("落水·静态", new ArrayList<>());
        columns.put("口渴·静态", new ArrayList<>());
        for (int i = 0; i < policies.size(); i++) {
            columns.put("落水·[" + i + "]", new ArrayList<>());
            columns.put("口渴·[" + i + "]", new ArrayList<>());
        }

        for (Survivor survivor : roster.survivors()) {
            CharacterId id = survivor.id();
            sizes.add((double) survivor.size());
            seats.add((double) survivor.seat());
            columns.get("落水·静态").add(staticShare(deck, id, roster, true));
            columns.get("口渴·静态").add(staticShare(deck, id, roster, false));
            for (int i = 0; i < policies.size(); i++) {
                Exposure e = measured.get(i).exposure().get(id);
                assertTrue(e != null && e.overboardTurns() > 0 && e.thirstTurns() > 0,
                        id + " 在策略 [" + i + "] 下没有观测，这份统计不可用");
                double over = e.overboardRate().orElseThrow();
                double thirst = e.thirstRate().orElseThrow();
                assertTrue(over >= 0 && over <= 1 && thirst >= 0 && thirst <= 1,
                        id + " 的频率越界: " + over + " / " + thirst);
                columns.get("落水·[" + i + "]").add(over);
                columns.get("口渴·[" + i + "]").add(thirst);
            }
        }

        for (String what : List.of("落水", "口渴")) {
            System.out.println();
            System.out.printf("%-10s %3s %3s | %8s", what, "座位", "体型", "静态");
            for (int i = 0; i < policies.size(); i++) {
                System.out.printf(" %8s", "[" + i + "]");
            }
            System.out.println();
            List<Survivor> survivors = roster.survivors();
            for (int row = 0; row < survivors.size(); row++) {
                Survivor survivor = survivors.get(row);
                System.out.printf("%-10s %3d %3d | %8.3f",
                        survivor.id(), survivor.seat(), survivor.size(),
                        columns.get(what + "·静态").get(row));
                for (int i = 0; i < policies.size(); i++) {
                    System.out.printf(" %8.3f", columns.get(what + "·[" + i + "]").get(row));
                }
                System.out.println();
            }
            System.out.printf("%-10s %3s %3s | %8.3f", "r vs 体型", "", "", pearson(sizes, columns.get(what + "·静态")));
            for (int i = 0; i < policies.size(); i++) {
                System.out.printf(" %+8.3f", pearson(sizes, columns.get(what + "·[" + i + "]")));
            }
            System.out.println();
            System.out.printf("%-10s %3s %3s | %8.3f", "r vs 座位", "", "", pearson(seats, columns.get(what + "·静态")));
            for (int i = 0; i < policies.size(); i++) {
                System.out.printf(" %+8.3f", pearson(seats, columns.get(what + "·[" + i + "]")));
            }
            System.out.println();
            for (int i = 1; i < policies.size(); i++) {
                System.out.printf("  [%d] 与对照组 [0] 比：曲线相关 r = %+.3f，最大逐项差 %.3f%n", i,
                        pearson(columns.get(what + "·[0]"), columns.get(what + "·[" + i + "]")),
                        maxGap(columns.get(what + "·[0]"), columns.get(what + "·[" + i + "]")));
            }
        }
        System.out.println();

        // 唯一的数值断言，而且它判的不是 O1 的结论，是「这份统计有没有在数正确的东西」：
        // 无所谓的舵手不挑牌，实际频率本该与牌面张数比几乎重合。差得离谱就是口径写错了。
        double control = pearson(columns.get("落水·[0]"), columns.get("落水·静态"));
        assertTrue(control > 0.9,
                "对照组与静态张数几乎不相关（r=%.3f），多半是统计口径写错了，不是牌的问题".formatted(control));
    }

    /** 静态口径：31 张牌里有多大比例会点到他。全员在场、不考虑死亡与挑牌。 */
    private static double staticShare(List<NavigationCard> deck, CharacterId who, Roster roster, boolean overboard) {
        Set<CharacterId> everyone = Set.copyOf(roster.survivors().stream().map(Survivor::id).toList());
        int hits = 0;
        for (NavigationCard card : deck) {
            Selector selector = overboard ? card.overboard() : card.thirst();
            // conditional 的条件在静态口径下一律不成立（没人喝过东西），与模拟器的做法一致。
            if (selector.select(everyone, (condition, id) -> false).contains(who)) {
                hits++;
            }
        }
        return (double) hits / deck.size();
    }

    private static double maxGap(List<Double> xs, List<Double> ys) {
        double worst = 0;
        for (int i = 0; i < xs.size(); i++) {
            worst = Math.max(worst, Math.abs(xs.get(i) - ys.get(i)));
        }
        return worst;
    }

    private static double pearson(List<Double> xs, List<Double> ys) {
        assertEquals(xs.size(), ys.size());
        int n = xs.size();
        double meanX = xs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double meanY = ys.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}
