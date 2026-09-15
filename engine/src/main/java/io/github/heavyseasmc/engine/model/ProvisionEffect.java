package io.github.heavyseasmc.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一张物资牌的效果。数据里 {@code effect.kind} 有 12 种，这里就有 12 个 record。
 *
 * <h2>为什么一个 kind 一个 record</h2>
 * 与 {@link Ability} 同一条理由：<b>穷尽性由编译器保证</b>。新增一种 kind 时，
 * 所有 switch 都会编译失败并指出遗漏处 —— 而「漏了一种效果」的运行时表现是那张牌安静地没用，
 * 不报错、不掉线。
 *
 * <p>❗<b>刻意不按「卡」分</b>：水和阳伞在数据里是同一个 kind（{@code prevent_thirst}），
 * 这里就是同一个 record，差别只在字段（阳伞 {@code persistent + requires_open}，水 {@code discard_on_use}）。
 * 拆成「水」和「阳伞」两个 record 读起来更顺，但那是**发明了数据里没有的区分**：
 * 数据包再加一张挡口渴的牌就无处安放，而 {@code kind} 会从主键退化成注释。
 *
 * <h2>三种时机，而且一张牌可以占两种</h2>
 * 「**亮出**」对每一张牌都成立（规则 §5.2：任何时候都可以把物资正面朝上放到自己面前），
 * 所以它不是效果的属性，不在 {@link Timing} 里。剩下三种由 kind 自己回答：
 *
 * <ul>
 *   <li>{@link Timing#SPECIAL_ACTION} —— 行动阶段，占掉那一个行动（医疗箱 · 撑伞 · 信号枪当信号 · 绝境）；</li>
 *   <li>{@link Timing#ON_RESOLUTION} —— 某一步结算时打出，不占行动（水 · 诱饵 · 武器 · 酒）；</li>
 *   <li>{@link Timing#PASSIVE} —— 亮在面前就一直生效，不需要任何操作（救生圈 · 指南针 · 船桨多抽）。</li>
 * </ul>
 *
 * <p>❗<b>信号枪同时是 {@code SPECIAL_ACTION} 与 {@code ON_RESOLUTION}</b>（当信号用要花行动，当武器用不花），
 * <b>船桨同时是 {@code ON_RESOLUTION} 与 {@code PASSIVE}</b>（武器 + 划船多抽）。
 * 所以这里给的是 {@link Set} 而不是单值 —— 写成单值就必须在两者里挑一个丢掉，
 * 而丢掉的那一半会变成「这张牌有一半用不了」。
 */
public sealed interface ProvisionEffect {

    /** 效果生效的时机。「亮出」不在其中：它对每一张牌都成立，见类注释。 */
    enum Timing {
        /** 行动阶段的特殊行动，占掉那一个行动。 */
        SPECIAL_ACTION,
        /** 某一步结算时打出（口渴 / 落海 / 战斗中），不占行动。 */
        ON_RESOLUTION,
        /** 亮在面前就生效，不需要任何操作。 */
        PASSIVE
    }

    /** 效果的目标范围。 */
    enum Target {
        /** 只能用在自己身上（阳伞）。 */
        SELF,
        /** 任何角色，但不含自己 —— 水按这一种。 */
        ANY_CHARACTER,
        /** 任何角色，含自己（医疗箱）。 */
        ANY_CHARACTER_INCLUDING_SELF
    }

    /** 这张牌什么时候能用。见类注释：可能有两种。 */
    Set<Timing> timings();

    /** 战斗加值；不是武器时为 0。 */
    default int weaponPower() {
        return 0;
    }

    /** 这张牌用过之后弃不弃。装备与财宝留在面前，所以默认不弃。 */
    default boolean discardOnUse() {
        return false;
    }

    /**
     * 挡口渴（水 · 阳伞）。
     *
     * @param amount            抵掉几次
     * @param unit              抵的单位，数据里只有 {@code thirst_source}
     * @param target            能用在谁身上
     * @param mayTargetOthers   能不能打给别人喝
     * @param resolvedDuring    在哪一步结算；空串表示数据没写
     * @param discardOnUse      用后弃掉（水）
     * @param persistent        常驻（阳伞）
     * @param requiresOpen      要先撑开才算数（阳伞）
     * @param costsActionToOpen 撑开占一个行动
     * @param lostWhenOverboard 落水被冲走
     */
    record PreventThirst(int amount, String unit, Target target, boolean mayTargetOthers,
                         String resolvedDuring, boolean discardOnUse, boolean persistent,
                         boolean requiresOpen, boolean costsActionToOpen,
                         boolean lostWhenOverboard) implements ProvisionEffect {

        public PreventThirst {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(resolvedDuring, "resolvedDuring");
            if (amount < 1) {
                throw new IllegalArgumentException("挡口渴的次数必须为正，实际: " + amount);
            }
        }

        @Override
        public Set<Timing> timings() {
            // 要花行动撑开的（阳伞）是特殊行动，撑开之后常驻生效；水是口渴结算时打出的。
            return costsActionToOpen ? Set.of(Timing.SPECIAL_ACTION, Timing.PASSIVE) : Set.of(Timing.ON_RESOLUTION);
        }
    }

    /**
     * 治疗（医疗箱）。
     *
     * @param amount       回几点
     * @param target       能治谁
     * @param costsAction  占一个行动
     * @param discardOnUse 用后弃掉
     * @param noDiscardFor 这些角色用后不弃（医生）
     */
    record Heal(int amount, Target target, boolean costsAction, boolean discardOnUse,
                List<String> noDiscardFor) implements ProvisionEffect {

        public Heal {
            Objects.requireNonNull(target, "target");
            noDiscardFor = List.copyOf(Objects.requireNonNull(noDiscardFor, "noDiscardFor"));
            if (amount < 1) {
                throw new IllegalArgumentException("治疗点数必须为正，实际: " + amount);
            }
        }

        /** 这个角色用过之后弃不弃。医生的医疗箱不弃（{@code no_discard_for}）。 */
        public boolean discardedBy(CharacterId who) {
            return discardOnUse && !noDiscardFor.contains(who.value());
        }

        @Override
        public Set<Timing> timings() {
            return costsAction ? Set.of(Timing.SPECIAL_ACTION) : Set.of(Timing.ON_RESOLUTION);
        }
    }

    /**
     * 水里的额外伤害（诱饵）。
     *
     * @param amount            额外几点
     * @param stacks            多张叠不叠加（诱饵：不叠）
     * @param perOverboardPhase 每个落海阶段独立计算（天候扩充的暴风雨有两次落海）
     * @param bypasses          穿透哪些免伤。数据里是 {@code sailor_swim_immunity}（技能标记）
     *                          与 {@code life_preserver}（物资 id）—— <b>两个命名空间混在一个数组里</b>，
     *                          所以校验在 {@code DataConsistency} 里按两边分别核对
     */
    record DamageInWater(int amount, boolean stacks, boolean perOverboardPhase,
                         List<String> bypasses) implements ProvisionEffect {

        /** {@code bypasses} 里代表「水手的落水免伤」的那个标记。它不是物资 id。 */
        public static final String SWIM_IMMUNITY = "sailor_swim_immunity";

        public DamageInWater {
            bypasses = List.copyOf(Objects.requireNonNull(bypasses, "bypasses"));
            if (amount < 1) {
                throw new IllegalArgumentException("鲨鱼伤害必须为正，实际: " + amount);
            }
        }

        public boolean bypassesSwimImmunity() {
            return bypasses.contains(SWIM_IMMUNITY);
        }

        public boolean bypasses(String provisionId) {
            return bypasses.contains(provisionId);
        }

        @Override
        public Set<Timing> timings() {
            // 落海结算时打出，或者本就亮在面前 —— 规则原文两种都算。
            return Set.of(Timing.ON_RESOLUTION, Timing.PASSIVE);
        }
    }

    /**
     * 全体回血（绝境）。
     *
     * @param amount        每人回几点
     * @param targets       回给谁，数据里是 {@code conscious_only}
     * @param requiresCorpse 船上必须有尸体 —— ❗<b>昏迷的不算</b>
     * @param contestable   别人可以反对（进入战斗）
     * @param costsAction   占一个行动
     * @param discardOnUse  用后弃掉
     */
    record HealAll(int amount, String targets, boolean requiresCorpse, boolean contestable,
                   boolean costsAction, boolean discardOnUse) implements ProvisionEffect {

        public HealAll {
            Objects.requireNonNull(targets, "targets");
            if (amount < 1) {
                throw new IllegalArgumentException("治疗点数必须为正，实际: " + amount);
            }
        }

        @Override
        public Set<Timing> timings() {
            return costsAction ? Set.of(Timing.SPECIAL_ACTION) : Set.of(Timing.ON_RESOLUTION);
        }
    }

    /**
     * 临时加体型（酒）。
     *
     * @param amount          加几点
     * @param duration        持续多久，数据里是 {@code turn}（一整个大回合）
     * @param sideEffect      副作用，数据里是 {@code thirst_at_end_of_turn}
     * @param persistsInFront 喝过仍留在面前（不是消耗品）
     * @param oncePerTurn     每回合最多喝一次
     * @param stacks          多张叠不叠加
     */
    record BuffSize(int amount, String duration, String sideEffect, boolean persistsInFront,
                    boolean oncePerTurn, boolean stacks) implements ProvisionEffect {

        /** {@code side_effect} 里代表「回合结束时口渴」的值。 */
        public static final String THIRST_AT_END_OF_TURN = "thirst_at_end_of_turn";

        public BuffSize {
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(sideEffect, "sideEffect");
            if (amount < 1) {
                throw new IllegalArgumentException("加值必须为正，实际: " + amount);
            }
        }

        public boolean causesThirst() {
            return THIRST_AT_END_OF_TURN.equals(sideEffect);
        }

        @Override
        public Set<Timing> timings() {
            // 喝酒不占行动（数据里没有 costs_action），战斗前后随时可以喝；喝过留在面前。
            return Set.of(Timing.ON_RESOLUTION, Timing.PASSIVE);
        }
    }

    /**
     * 舵手挑牌前多抽（指南针）。
     *
     * @param amount 多抽几张
     * @param into   抽进哪里，数据里是 {@code row_stack}
     * @param timing 什么时候抽，数据里是 {@code before_navigator_chooses}
     */
    record NavigatorExtraDraw(int amount, String into, String timing) implements ProvisionEffect {

        public NavigatorExtraDraw {
            Objects.requireNonNull(into, "into");
            Objects.requireNonNull(timing, "timing");
            if (amount < 1) {
                throw new IllegalArgumentException("多抽张数必须为正，实际: " + amount);
            }
        }

        @Override
        public Set<Timing> timings() {
            return Set.of(Timing.PASSIVE);
        }
    }

    /**
     * 挡落水伤害（救生圈）。
     *
     * @param transferable            可以转赠
     * @param keptByReceiver          转赠后归对方
     * @param survivesOverboard       落水不会被冲走 —— 全副牌里唯一一张
     * @param receiverMustBeConscious 接收者必须清醒
     * @param mustBeGivenInAdvance    必须在航海阶段之前交到他手上，不能临时替他亮
     */
    record PreventOverboardDamage(boolean transferable, boolean keptByReceiver, boolean survivesOverboard,
                                  boolean receiverMustBeConscious,
                                  boolean mustBeGivenInAdvance) implements ProvisionEffect {

        @Override
        public Set<Timing> timings() {
            return Set.of(Timing.PASSIVE);
        }
    }

    /**
     * 武器或特殊行动，二选一（信号枪）。
     *
     * @param power                战斗加值
     * @param exclusiveChoice      两种用法只能选一种
     * @param special              当信号用时做什么
     * @param discardAfterAnyUse   ❗<b>两种用法都弃掉</b> —— 全副唯一一张用后即弃的武器
     */
    record WeaponOrSpecial(int power, boolean exclusiveChoice, Signal special,
                           boolean discardAfterAnyUse) implements ProvisionEffect {

        public WeaponOrSpecial {
            Objects.requireNonNull(special, "special");
            if (power < 0) {
                throw new IllegalArgumentException("战斗加值不能为负，实际: " + power);
            }
        }

        @Override
        public int weaponPower() {
            return power;
        }

        @Override
        public boolean discardOnUse() {
            return discardAfterAnyUse;
        }

        @Override
        public Set<Timing> timings() {
            return Set.of(Timing.SPECIAL_ACTION, Timing.ON_RESOLUTION);
        }

        /**
         * 当信号用：抽几张航海牌、只结算什么、抽完放回哪里。
         *
         * @param kind               数据里是 {@code draw_and_resolve_gulls}
         * @param draw               抽几张
         * @param resolveOnly        只结算什么，数据里是 {@code gulls}
         * @param includesGullRemoval 抽到「去掉一只海鸥」照样算 —— 会让全船倒退一格
         * @param returnTo           抽完的牌放回哪里，数据里是 {@code deck_bottom}
         */
        public record Signal(String kind, int draw, String resolveOnly, boolean includesGullRemoval,
                             String returnTo) {

            public Signal {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(resolveOnly, "resolveOnly");
                Objects.requireNonNull(returnTo, "returnTo");
                if (draw < 1) {
                    throw new IllegalArgumentException("抽牌数必须为正，实际: " + draw);
                }
            }
        }
    }

    /**
     * 纯武器（鱼叉 · 小刀 · 短棍）。
     *
     * @param power 战斗加值
     */
    record Weapon(int power) implements ProvisionEffect {

        public Weapon {
            if (power < 0) {
                throw new IllegalArgumentException("战斗加值不能为负，实际: " + power);
            }
        }

        @Override
        public int weaponPower() {
            return power;
        }

        @Override
        public Set<Timing> timings() {
            return Set.of(Timing.ON_RESOLUTION);
        }
    }

    /**
     * 武器兼划船多抽（船桨）。
     *
     * @param power           战斗加值
     * @param rowExtraDraw    划船时多抽几张
     * @param exclusiveChoice 两种用法是不是二选一（船桨：不是，两种同时有效）
     * @param stacks          多张叠不叠加（船桨：叠）
     */
    record WeaponAndRowBonus(int power, int rowExtraDraw, boolean exclusiveChoice,
                             boolean stacks) implements ProvisionEffect {

        public WeaponAndRowBonus {
            if (power < 0) {
                throw new IllegalArgumentException("战斗加值不能为负，实际: " + power);
            }
            if (rowExtraDraw < 0) {
                throw new IllegalArgumentException("划船多抽张数不能为负，实际: " + rowExtraDraw);
            }
        }

        @Override
        public int weaponPower() {
            return power;
        }

        @Override
        public Set<Timing> timings() {
            return Set.of(Timing.ON_RESOLUTION, Timing.PASSIVE);
        }
    }

    /**
     * 固定分值的财宝（现金 · 美术品）。
     *
     * @param points    几分
     * @param doubledBy 哪个角色让它翻倍（角色 id）
     */
    record ScoreFlat(int points, String doubledBy) implements ProvisionEffect {

        public ScoreFlat {
            Objects.requireNonNull(doubledBy, "doubledBy");
            if (points < 0) {
                throw new IllegalArgumentException("分值不能为负，实际: " + points);
            }
        }

        @Override
        public Set<Timing> timings() {
            return Set.of();          // 财宝不「用」，只在终局计分 —— 空集合是它的准确描述
        }
    }

    /**
     * 套组计分的财宝（珠宝）。
     *
     * @param setTotals          持有 1 / 2 / 3 张时的套组总分，下标 0 对应 1 张
     * @param doubledBy          哪个角色让它翻倍
     * @param doublingAppliesTo  翻的是套组总分还是面值，数据里是 {@code set_total}
     */
    record ScoreSet(List<Integer> setTotals, String doubledBy,
                    String doublingAppliesTo) implements ProvisionEffect {

        public ScoreSet {
            setTotals = List.copyOf(Objects.requireNonNull(setTotals, "setTotals"));
            Objects.requireNonNull(doubledBy, "doubledBy");
            Objects.requireNonNull(doublingAppliesTo, "doublingAppliesTo");
            if (setTotals.isEmpty()) {
                throw new IllegalArgumentException("套组表不能为空");
            }
        }

        @Override
        public Set<Timing> timings() {
            return Set.of();
        }
    }
}
