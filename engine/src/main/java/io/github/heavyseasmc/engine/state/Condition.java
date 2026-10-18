package io.github.heavyseasmc.engine.state;

/**
 * 一名角色的生死状态。
 *
 * <p><b>永远由「伤害 vs 体型」推导，绝不单独存一份。</b>
 * 存一份就会有两个真相源，而它们只在「刚受完伤还没来得及更新状态」那一瞬间不一致 ——
 * 那种低频分叉能一路活到发布之后（本作已在憎恨/厌世的谓词上吃过一次同款，见 ADR-0002）。
 *
 * <p>体型的四个用途里，这是第二个：血量上限。注意<b>战斗力不受伤害影响</b>，
 * 永远按满体型算 —— 「攻击力 = 剩余血量」是村规，不是本作规则。
 */
public enum Condition {

    /** 伤害 &lt; 体型。能行动、能拒绝、能参战。 */
    CONSCIOUS,

    /**
     * 伤害 = 体型。不能行动、不能拒绝换位或抢夺、不能参战，
     * <b>但仍然会口渴</b>（自己不能打水，别人可以替他打）。医疗箱可救醒。
     */
    UNCONSCIOUS,

    /** 伤害 &gt; 体型。不可复生。尸体留在船上时财宝仍算作遗产，落海则彻底退出。 */
    DEAD;

    /**
     * 船上的判定。
     *
     * @throws IllegalArgumentException 体型非正，或伤害为负
     */
    public static Condition onBoat(int damage, int size) {
        checkArgs(damage, size);
        if (damage < size) {
            return CONSCIOUS;
        }
        return damage == size ? UNCONSCIOUS : DEAD;
    }

    /**
     * 落海结算<b>结束时</b>的判定 —— 与船上<b>不同</b>，这不是笔误。
     *
     * <p>船上「伤害 = 体型」只是昏迷；而在水里，这个数值<b>没有救生圈就是死</b>。
     * 于是有一条只在水里成立的死法：<b>一个清醒角色在「再受一点就满」时落水且无救生圈，
     * 直接死亡，不经过昏迷</b>。
     *
     * <p><b>这一条不是从普通规则涌现的，必须单独写。</b> 值得说明是因为本作有过反例：
     * 计分那边的三种特殊身份里有两种不需要特例代码、从四项独立涌现（见 Scorer）。
     * 那里的教训是「先确认它是不是真特例」，这里确认的结果是 —— <b>是</b>。
     *
     * <p>另一条容易搞反的：水手的落海免伤<b>要求清醒</b>。所以昏迷的水手落水照样受伤，
     * 伤害超过体型而死，不走本方法的「等于」分支。
     *
     * @param damage            落海伤害<b>已经结算完</b>之后的总伤害
     * @param hasLifePreserver  是否有救生圈。❗必须在航海阶段之前就拿到，
     *                          昏迷者落水时别人不能临时替他亮
     */
    public static Condition inWater(int damage, int size, boolean hasLifePreserver) {
        checkArgs(damage, size);
        if (damage > size) {
            return DEAD;
        }
        if (damage == size) {
            return hasLifePreserver ? UNCONSCIOUS : DEAD;
        }
        return CONSCIOUS;
    }

    /** 能否行动、拒绝换位与抢夺、发起或加入战斗。 */
    public boolean canAct() {
        return this == CONSCIOUS;
    }

    /**
     * 是否仍会口渴。
     *
     * <p>❗昏迷者<b>会</b>口渴 —— 这条最容易写错，因为「昏迷 = 什么都不能做」很顺口。
     * 他不能自己打水，但别人可以替他打；没人替他打就继续掉血。
     */
    public boolean suffersThirst() {
        return this != DEAD;
    }

    /** 是否计入「存活且清醒」—— 物资阶段的抽牌数与行动顺序都按这个数。 */
    public boolean countsForProvisioning() {
        return this == CONSCIOUS;
    }

    private static void checkArgs(int damage, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("体型必须为正，实际: " + size);
        }
        if (damage < 0) {
            throw new IllegalArgumentException("伤害不能为负，实际: " + damage);
        }
    }
}
