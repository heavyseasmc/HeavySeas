package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.SyntheticTable;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机席位与「搬家之前的模拟器」逐局一致。
 *
 * <h2>它防的是什么</h2>
 * 模拟器原先把每一个决定都写死在自己的私有方法里（随机留牌、随机行动、随机表态……）。
 * 席位策略（{@code engine.seat}）把这些决定搬到了一个可替换的接口后面，而随机那一份必须<b>按原样消费随机数</b> ——
 * 次序差一步，同一个种子就跑出另一局，钉死的种子表与 O1 测量台的每一行数字都会悄悄变掉。
 *
 * <p>{@code SimulatorTest} 那张表只钉了八局的四个数。这里钉的是<b>几千局的全部结果</b>（回合 · 终局 · 存活 · 打架 ·
 * 每人的点名次数与机会数 · 每人的四项计分），压成一个摘要：搬家前跑一次记下，搬家后必须一个比特不差。
 *
 * <p>❗规则<b>有意</b>改动时这张表会红，与种子表同一个处理：照新值重钉，并在提交里写明为什么。
 * 摘要只能说「变了」，说不出「哪一局变了」—— 那时先看种子表与 {@code DistributionDumpTest} 往哪边动。
 *
 * <p>❗{@code Result} 里的两张表用 {@code Map.copyOf} 存，遍历次序每次起 JVM 都不同（盐是随机的），
 * 所以摘要按角色 id 排序后再算，不直接拿 {@code toString}。
 */
class RandomSeatsReproduceTest {

    /** 与 {@code SimulatorTest} 同一套合成阵容（{@link SyntheticTable}）。 */
    static Roster syntheticRoster() {
        return SyntheticTable.roster();
    }

    /** 与 {@code SimulatorTest} 同一副合成航海牌（{@link SyntheticTable}）。 */
    static List<NavigationCard> syntheticDeck() {
        return SyntheticTable.deck();
    }

    /** 一段种子的全部结果压成一个 64 位摘要（FNV-1a）。 */
    static long digest(Simulator sim, int games) {
        long h = 0xcbf29ce484222325L;
        for (long seed = 0; seed < games; seed++) {
            Simulator.Result r = sim.run(seed);
            // 随机席位从合法清单里挑，不该有一次被退回默认：退回了说明清单与它对不上，而摘要可能碰巧没变
            assertEquals(0, r.fallbacks(), "seed=" + seed + " 随机席位交回了不合法的选项");
            StringBuilder line = new StringBuilder();
            line.append(r.seed()).append('|').append(r.turns()).append('|').append(r.outcome())
                    .append('|').append(r.alive()).append('|').append(r.fights());
            new TreeMap<>(r.exposure()).forEach((id, e) -> line.append('|').append(id.value()).append(':')
                    .append(e.overboards()).append(',').append(e.overboardTurns()).append(',')
                    .append(e.thirsts()).append(',').append(e.thirstTurns()));
            new TreeMap<CharacterId, ScoreSheet>(r.scores()).forEach((id, s) -> line.append('|')
                    .append(id.value()).append('=').append(s.selfSurvival()).append(',').append(s.treasure())
                    .append(',').append(s.loved()).append(',').append(s.hated()));
            for (int i = 0; i < line.length(); i++) {
                h ^= line.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= '\n';
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static void check(String label, long expected, Simulator sim, int games) {
        long actual = digest(sim, games);
        System.out.printf("  %-44s 摘要 %016x（钉的是 %016x）%n", label, actual, expected);
        assertEquals(expected, actual, label + " 的 " + games + " 局结果变了");
    }

    @Test
    @DisplayName("❗随机席位与搬家前的模拟器逐局一致：合成阵容 + 真实数据 6 / 7 / 8 人局，几千局摘要钉死")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void digestsArePinned() {
        Provisions synthetic = TestProvisions.synthetic();
        TreasureScoring scoring = LocalData.roster().treasureScoring();
        List<NavigationCard> realDeck = LocalData.navigationDeck();
        Provisions realProvisions = LocalData.provisions();

        System.out.println("随机席位逐局一致（摘要）：");
        check("合成 5 人 · 无所谓 · 2000 局", 0x0abbfd5f4de83ca1L,
                new Simulator(syntheticRoster(), syntheticDeck(), synthetic, NavigationPolicy.INDIFFERENT), 2000);
        check("合成 5 人 · 自保 · 1000 局", 0x1bfea860c820091aL,
                new Simulator(syntheticRoster(), syntheticDeck(), synthetic, NavigationPolicy.SELF_INTERESTED), 1000);
        check("真实 8 人 · 无所谓 · 计分 · 1000 局", 0x458767d985c434b8L,
                new Simulator(LocalData.roster().preset(8), realDeck, realProvisions,
                        NavigationPolicy.INDIFFERENT, scoring), 1000);
        check("真实 7 人 · 自保 · 计分 · 500 局", 0x90aa19510016d83aL,
                new Simulator(LocalData.roster().preset(7), realDeck, realProvisions,
                        NavigationPolicy.SELF_INTERESTED, scoring), 500);
        check("真实 6 人 · 只躲水 · 计分 · 500 局", 0x23b448a9fb5f06c8L,
                new Simulator(LocalData.roster().preset(6), realDeck, realProvisions,
                        NavigationPolicy.SELF_INTERESTED_NO_RUSH, scoring), 500);
    }

    @Test
    @DisplayName("摘要本身分得开不同的局：换一段种子摘要就变（正向对照）")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void digestDistinguishesGames() {
        // 「摘要没变」与「摘要什么都没算进去」输出一样 —— 所以先确认它对局面敏感。
        Simulator sim = new Simulator(syntheticRoster(), syntheticDeck(), TestProvisions.synthetic());
        long a = digest(sim, 50);
        long b = digest(sim, 51);
        assertTrue(a != b, "多跑一局摘要却没变：摘要没把结果算进去");
        assertEquals(a, digest(sim, 50), "同一段种子两次摘要不同：模拟器本身不可复现");
    }
}
