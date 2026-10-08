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
 * 固定种子下的随机席位结果；规则与随机流协议改变时明确重建基线。
 *
 * <h2>它防的是什么</h2>
 * 模拟器原先把每一个决定都写死在自己的私有方法里（随机留牌、随机行动、随机表态……）。
 * 席位策略（{@code engine.seat}）把这些决定搬到了一个可替换的接口后面，每座内部的随机消费次序仍须稳定。
 * 2026-10-09 把席位流与引擎流分开，下面记录的是新协议；旧协议摘要保留在上一版历史中。
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
    @DisplayName("固定种子随机席位：合成阵容 + 真实数据 6 / 7 / 8 人局，摘要钉住当前随机流协议")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void digestsArePinned() {
        Provisions synthetic = TestProvisions.synthetic();
        TreasureScoring scoring = LocalData.roster().treasureScoring();
        List<NavigationCard> realDeck = LocalData.navigationDeck();
        Provisions realProvisions = LocalData.provisions();

        // ❗2026-10-08 整表重钉（代码审查整改），三处规则有意改动，逐局对比归因过（每次只加一处、逐局比摘要那一行）：
        //   ① R1 信号枪靠岸之后，驱动不再问没轮到的人行动（与模组 GameFlow.finishAction 同口径）。
        //      变了的局：合成无所谓 0 / 2000、合成自保 0 / 1000、真实 8 人 7 / 1000、7 人 6 / 500、6 人 3 / 500 ——
        //      每一局都在「旧代码里靠岸之后还有人被问行动」的那几局里（那几局各有 2 · 4 · 24 · 11 · 8 局，其余没改出结果）；
        //   ② R2「今天喝过」按人记，瓶子送人、被抢、被冲走都不收回：变了 217 / 2000 · 129 / 1000 · 2 / 1000 · 1 / 500 · 4 / 500，
        //      每一局都出现过「他今天喝过、瓶子却已不在他面前」（视角扫描 + 引擎里临时装的探针两路核过）；合成牌堆酒多、偷得多，所以变得多；
        //   ③ Q4 自保同分时均匀挑（原先三张同分最后一张拿一半）：只动「自保」三套 —— 7 / 1000 · 8 / 500 · 54 / 500，
        //      每一局都遇到过三张以上同分；两张同分与原先逐次相同，「无所谓」两套一局没变。
        //   同一批的其余改动（C1 挑牌那一刻不能喝酒 · C2 财宝按整张角色表分类 · C9 · Z3 名单保序 · 物资目录保序 · R12）摘要一位没动。
        // 2026-10-09 Q4：每座独立流，策略不能推进引擎/其他席位的流，明确替换 10-08 的共享流基线。
        // 独立性由 SeatRandomIsolationTest 的多取随机数对照验证；续演还比对引擎与全部席位的流状态。
        System.out.println("随机席位独立流协议（摘要）：");
        org.junit.jupiter.api.Assertions.assertAll(
        () -> check("合成 5 人 · 无所谓 · 2000 局", 0x98b12bc9adfa2d13L,
                new Simulator(syntheticRoster(), syntheticDeck(), synthetic, NavigationPolicy.INDIFFERENT), 2000),
        () -> check("合成 5 人 · 自保 · 1000 局", 0x3b048f39654e5eb1L,
                new Simulator(syntheticRoster(), syntheticDeck(), synthetic, NavigationPolicy.SELF_INTERESTED), 1000),
        () -> check("真实 8 人 · 无所谓 · 计分 · 1000 局", 0x04352ce1a71be03bL,
                new Simulator(LocalData.roster().preset(8), realDeck, realProvisions,
                        NavigationPolicy.INDIFFERENT, scoring), 1000),
        () -> check("真实 7 人 · 自保 · 计分 · 500 局", 0xeddbe2a00108b625L,
                new Simulator(LocalData.roster().preset(7), realDeck, realProvisions,
                        NavigationPolicy.SELF_INTERESTED, scoring), 500),
        () -> check("真实 6 人 · 只躲水 · 计分 · 500 局", 0x7a94d0106d403d2fL,
                new Simulator(LocalData.roster().preset(6), realDeck, realProvisions,
                        NavigationPolicy.SELF_INTERESTED_NO_RUSH, scoring), 500));
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
