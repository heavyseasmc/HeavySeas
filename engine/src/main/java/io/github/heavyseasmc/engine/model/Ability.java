package io.github.heavyseasmc.engine.model;

import java.util.List;
import java.util.Map;

/**
 * 角色技能。
 *
 * <p>做成 sealed 接口而非枚举 + 参数包，是为了让穷尽性由编译器保证：
 * 新增一种技能时，所有 switch 都会编译失败并指出遗漏处。
 */
public sealed interface Ability {

    /** 无技能。大副是纯战力，这是设计如此，不是占位。 */
    record None() implements Ability {}

    /**
     * 财宝得分加倍。
     *
     * @param appliesTo {@code SET_TOTAL} 先查套组表再翻倍（珠宝）；
     *                  {@code FACE_VALUE} 面值累加后翻倍（现金、美术品）。
     *                  这两者对珠宝会得出不同结果，本作取 SET_TOTAL。
     */
    record ScoreMultiplier(TreasureKind target, int factor, Scope appliesTo) implements Ability {
        public enum Scope { SET_TOTAL, FACE_VALUE }
    }

    /** 别人使用某些物资时一并蹭到效果（厨子）。 */
    record ShareEffect(
            List<String> sources,
            boolean requiresConscious,
            boolean resolvesLastInThirst,
            Map<String, Boolean> stacking
    ) implements Ability {}

    /** 落水不受伤（水手）。注意 {@code notProtectedFrom} —— 鲨鱼伤害穿透它。 */
    record OverboardImmune(boolean requiresConscious, List<String> notProtectedFrom) implements Ability {}

    /** 指定物资用后不弃（医生的医疗箱）。 */
    record NoDiscard(
            String target,
            boolean stillCostsAction,
            boolean staysInFront,
            boolean cannotReturnToHand
    ) implements Ability {}

    /** 抢夺手牌时对方不能拒绝、不触发战斗、不得临时亮牌（小孩）。 */
    record StealUncontested(String zone, boolean triggersFight, boolean noFlashReveal) implements Ability {}
}
