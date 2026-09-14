package io.github.heavyseasmc.engine.sim;

import java.util.OptionalDouble;

/**
 * 一个角色在模拟对局里<b>被航海牌点到</b>的次数与机会数。
 *
 * <h2>为什么记的是「次数 / 机会数」而不是一个频率</h2>
 * 体型小的角色死得早，死了就不再被点名。只数次数的话，「不容易落水」与「早就死了」
 * 会得出同一个小数字 —— 而这两件事对 O1 的结论正相反。所以每个计数都配一个分母：
 * 那一步开始时他还活着的回合数。
 *
 * <p>分母也解释了为什么不能拿静态张数当答案：31 张牌里点到谁多少次是印死的，
 * 而实际被点到多少次还取决于他活到第几回合、以及舵手挑了哪张牌。
 *
 * @param overboards     落海点名命中他、且当时他还活着的次数。
 *                       ❗<b>数的是「被点到下水」，不是「因此受伤」</b>：水手落水不受伤，
 *                       但他确实下水了。要谈伤害就得连技能一起看，那是另一个问题
 * @param overboardTurns 落海结算真正执行、且他当时还活着的回合数（{@code overboards} 的分母）。
 *                       第 4 只海鸥当场结束一局时该步不执行，所以那种回合两边都不计
 * @param thirsts        口渴点名命中他的次数。只数<b>牌面点名</b>这一个来源，
 *                       划船与战斗带来的口渴不在内 —— O1 问的是牌上的名单
 * @param thirstTurns    口渴结算执行、且他在候选集里（活着，昏迷也算）的回合数
 */
public record Exposure(int overboards, int overboardTurns, int thirsts, int thirstTurns) {

    public static final Exposure NONE = new Exposure(0, 0, 0, 0);

    public Exposure {
        if (overboards < 0 || overboardTurns < 0 || thirsts < 0 || thirstTurns < 0) {
            throw new IllegalArgumentException("次数不能为负: " + overboards + "/" + overboardTurns
                    + " " + thirsts + "/" + thirstTurns);
        }
        if (overboards > overboardTurns || thirsts > thirstTurns) {
            // 一回合最多被点到一次。次数超过机会数说明分母漏加了，
            // 而漏加分母算出来的频率会大得很像「有信号」。
            throw new IllegalArgumentException(
                    "命中次数多于机会数: 落海 %d/%d，口渴 %d/%d"
                            .formatted(overboards, overboardTurns, thirsts, thirstTurns));
        }
    }

    public Exposure plus(Exposure other) {
        return new Exposure(
                overboards + other.overboards,
                overboardTurns + other.overboardTurns,
                thirsts + other.thirsts,
                thirstTurns + other.thirstTurns);
    }

    /**
     * 每一次落海结算里被点到的概率。
     *
     * <p>❗没有任何观测时返回空，<b>不返回 0</b>：0 的意思是「有机会但一次也没被点到」，
     * 空的意思是「压根没轮到过」。把后者写成 0 会让一个从没上过场的角色看起来运气极好。
     */
    public OptionalDouble overboardRate() {
        return overboardTurns == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) overboards / overboardTurns);
    }

    /** 每一次口渴结算里被牌面点到的概率。没有观测时返回空，理由同上。 */
    public OptionalDouble thirstRate() {
        return thirstTurns == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) thirsts / thirstTurns);
    }
}
