package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布快照：同一段种子跑一遍，把回合数与存活数印出来。<b>只打印，不判定。</b>
 *
 * <h2>为什么要有它</h2>
 * 引擎改动分两种：
 * <ul>
 *   <li><b>不该改变任何一局</b>的（把规则从模拟器搬进 {@code Session} 那次）——
 *       判据是 {@code SimulatorTest} 里那张钉死的种子表，逐局逐字节比。</li>
 *   <li><b>一定会改变每一局</b>的（ADR-0021 的物资建模：此前模拟器喝的是不存在的水、
 *       打的是凭空造出来的武器）—— 那张种子表必然全红，<b>而全红说明不了任何事</b>。
 *       这时要看的是分布往哪边动，以及动的方向与事先写下的预测是否吻合。</li>
 * </ul>
 *
 * <p>本类是第二种的量具。它不判定，因为「平均回合数应当是 12.4」这种断言
 * 只会在下一次合理改动时变成噪音 —— 而判定一旦成了噪音，就没人再看它。
 *
 * <h2>怎么与旧版本对比</h2>
 * 拿一个干净的旧检出（{@code git worktree add}），把本文件复制进去、按旧构造函数改一行，
 * 两边各跑一次，比印出来的那几行。ADR-0021 §9 记的就是这么来的。
 */
class DistributionDumpTest {

    /** 局数。几千局在本机是秒级，而这里要的是分布形状，不是三位有效数字。 */
    private static final int GAMES = 2_000;

    @Test
    @DisplayName("分布快照：真实数据 8 人局（只打印不判定）")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dump() {
        Roster roster = LocalData.roster().preset(8);
        List<NavigationCard> deck = LocalData.navigationDeck();
        Provisions provisions = LocalData.provisions();

        // 带上分值表：每一局都算一次终局分（ADR-0022）。计分器在终局状态不自洽时会当场抛。
        Simulator sim = new Simulator(roster, deck, provisions, NavigationPolicy.INDIFFERENT,
                LocalData.roster().treasureScoring());
        long turns = 0;
        long alive = 0;
        long fights = 0;
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        // 每个角色：[得分合计, 拿到最高分的局数（并列都算）]
        Map<CharacterId, long[]> scoring = new LinkedHashMap<>();
        for (long seed = 0; seed < GAMES; seed++) {
            Simulator.Result r = sim.run(seed);
            turns += r.turns();
            alive += r.alive();
            fights += r.fights();
            outcomes.merge(r.outcome().name(), 1, Integer::sum);
            int best = r.scores().values().stream().mapToInt(ScoreSheet::total).max().orElse(0);
            r.scores().forEach((id, sheet) -> {
                long[] acc = scoring.computeIfAbsent(id, k -> new long[2]);
                acc[0] += sheet.total();
                if (sheet.total() == best) {
                    acc[1]++;
                }
            });
        }
        System.out.printf("分布快照（%d 局 · 8 人局 · 真实数据）%n", GAMES);
        System.out.printf("  平均回合数 %.3f%n", (double) turns / GAMES);
        System.out.printf("  平均终局存活 %.3f%n", (double) alive / GAMES);
        System.out.printf("  平均打架次数 %.3f%n", (double) fights / GAMES);
        System.out.printf("  靠岸 %d 局 · 全灭 %d 局%n",
                outcomes.getOrDefault(GameState.Outcome.LANDED.name(), 0),
                outcomes.getOrDefault(GameState.Outcome.ALL_DEAD.name(), 0));
        // 正向对照：分值表传进去了却一个分都没有，说明计分那条路根本没走 —— 那不是「分布」，是没在量。
        if (scoring.isEmpty()) {
            throw new IllegalStateException("2000 局里一次终局计分都没有：模拟器没把分值表用上");
        }
        System.out.println("  平均得分 · 拿到最高分的局数（并列都算）：");
        scoring.forEach((id, acc) -> System.out.printf("    %-10s %6.2f 分 · %4d 局%n",
                id.value(), (double) acc[0] / GAMES, acc[1]));
    }
}
