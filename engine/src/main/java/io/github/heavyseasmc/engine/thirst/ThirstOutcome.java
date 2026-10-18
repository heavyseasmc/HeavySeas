package io.github.heavyseasmc.engine.thirst;

/**
 * 一次口渴结算的结果。
 *
 * <p><b>本类只产出伤害数，不施加伤害。</b> 伤害如何变成昏迷或死亡，取决于体型
 * （伤害 = 体型 → 昏迷；伤害 &gt; 体型 → 死亡），那是状态机的事。
 * 把两者切开是为了让口渴规则可以脱离整局状态单测 —— 这个模块的全部测试都不需要构造一局游戏。
 *
 * @param sources          本回合的口渴来源数（{@link ThirstTally#count()}）
 * @param cancelledByCover 被阳伞之类的常驻遮蔽抵掉的次数
 * @param watersSpent      为此弃掉的水的张数
 * @param damage           最终受到的伤害点数，等于没被抵掉也没喝水的那部分
 */
public record ThirstOutcome(int sources, int cancelledByCover, int watersSpent, int damage) {

    public ThirstOutcome {
        if (sources < 0 || cancelledByCover < 0 || watersSpent < 0 || damage < 0) {
            throw new IllegalArgumentException(
                    "口渴结算的四项都不能为负：sources=%d cover=%d water=%d damage=%d"
                            .formatted(sources, cancelledByCover, watersSpent, damage));
        }
        if (cancelledByCover + watersSpent + damage != sources) {
            throw new IllegalArgumentException(
                    "每一次口渴必须恰好有一个去向（遮蔽 / 喝水 / 受伤）：%d + %d + %d != %d"
                            .formatted(cancelledByCover, watersSpent, damage, sources));
        }
    }

    /** 完全没渴，或渴了但全部化解。 */
    public boolean unharmed() {
        return damage == 0;
    }
}
