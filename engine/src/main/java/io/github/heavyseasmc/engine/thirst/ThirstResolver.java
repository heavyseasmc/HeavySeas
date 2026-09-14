package io.github.heavyseasmc.engine.thirst;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 口渴结算。
 *
 * <h2>结算次序</h2>
 * 航海牌按 <b>海鸥 → 落海 → 口渴</b> 结算，次序不可颠倒：先把所有人的落水结算完，
 * 再统一结算口渴。这对本模块有两个后果：
 * <ul>
 *   <li>落水者只要没死就已经回到艇上，<b>照样参与本回合的口渴结算</b>；</li>
 *   <li>撑开的阳伞可能在落水那一步先被冲走，所以「有没有遮蔽」必须由调用方在
 *       落水结算<b>之后</b>再取值，不能提前缓存。</li>
 * </ul>
 *
 * <h2>三个去向，次序固定</h2>
 * 每一次口渴恰好有一个去向：被遮蔽抵掉、弃 1 张水、受 1 点伤害。
 *
 * <p><b>遮蔽一定排在喝水前面，这不是策略选择而是被逼死的：</b>
 * 阳伞是常驻的、结算时不付任何代价（撑开那一次已经花过行动了），
 * 水则是要弃掉的消耗品。没有任何情况下会有人宁可留着伞去喝水，
 * 所以这里不给调用方留决策点。
 *
 * <p><b>喝不喝水反过来是真决策</b>，所以 {@link #resolve} 要求调用方给出弃水张数：
 * 手里只剩一张水时，为一点伤害花掉它未必划算。把这个决策留在引擎外面，
 * 引擎只负责判定合法与算账。
 */
public final class ThirstResolver {

    private ThirstResolver() {
    }

    /**
     * 结算一个人的口渴。
     *
     * @param tally        本回合累积的口渴来源
     * @param coverCharges 常驻遮蔽能抵掉的次数（撑开的阳伞为 1，否则 0）
     * @param watersSpent  决定为此弃掉几张水。可以少于需要的张数 —— 差额转成伤害
     * @throws IllegalArgumentException 弃水张数为负，或多于还需要化解的次数
     */
    public static ThirstOutcome resolve(ThirstTally tally, int coverCharges, int watersSpent) {
        Objects.requireNonNull(tally, "tally");
        if (coverCharges < 0) {
            throw new IllegalArgumentException("遮蔽次数不能为负: " + coverCharges);
        }
        if (watersSpent < 0) {
            throw new IllegalArgumentException("弃水张数不能为负: " + watersSpent);
        }

        int sources = tally.count();
        int cancelled = Math.min(sources, coverCharges);
        int remaining = sources - cancelled;
        if (watersSpent > remaining) {
            // 多弃的水不会变成任何东西，静默吞掉就等于凭空销毁一张牌。
            throw new IllegalArgumentException(
                    "弃水张数多于还需化解的口渴次数：spent=%d, remaining=%d（来源 %d，遮蔽抵掉 %d）"
                            .formatted(watersSpent, remaining, sources, cancelled));
        }
        return new ThirstOutcome(sources, cancelled, watersSpent, remaining - watersSpent);
    }

    /**
     * 「有多少水喝多少」策略。给模拟器与不需要精打细算的调用方用。
     *
     * @param watersAvailable 手上（或别人愿意打给你的）水的张数
     */
    public static ThirstOutcome resolveSpendingUpTo(ThirstTally tally, int coverCharges, int watersAvailable) {
        if (watersAvailable < 0) {
            throw new IllegalArgumentException("可用水量不能为负: " + watersAvailable);
        }
        int remaining = Math.max(0, tally.count() - Math.max(0, coverCharges));
        return resolve(tally, coverCharges, Math.min(remaining, watersAvailable));
    }

    /**
     * 本回合口渴的结算次序。
     *
     * <p>规则里只有一条次序要求：<b>带「最后结算」标记的角色排在所有人之后</b>。
     * 陪酒女（{@code hostess}）靠别人喝水时蹭水，排在最后她才能把这一轮里每个人喝的水都蹭到；
     * 排在中间就只能蹭到排在她前面的那部分 —— 这是个会直接改变数值结果的差异，不是表现层细节。
     *
     * <p>其余角色保持阵容顺序（船头 → 船尾），这样同一局的重放是确定的。
     * 多个角色都带该标记时，它们之间同样按阵容顺序 —— 基础阵容里只有一个，
     * 但排序必须是全序，否则模拟器跑几千局会出现不可复现的差异。
     */
    public static List<Survivor> resolutionOrder(Roster roster) {
        Objects.requireNonNull(roster, "roster");
        List<Survivor> normal = new ArrayList<>();
        List<Survivor> last = new ArrayList<>();
        for (Survivor s : roster.survivors()) {
            (resolvesLast(s) ? last : normal).add(s);
        }
        normal.addAll(last);
        return List.copyOf(normal);
    }

    /** 该角色是否带「口渴最后结算」标记。 */
    public static boolean resolvesLast(Survivor survivor) {
        return survivor.ability() instanceof Ability.ShareEffect share && share.resolvesLastInThirst();
    }
}
