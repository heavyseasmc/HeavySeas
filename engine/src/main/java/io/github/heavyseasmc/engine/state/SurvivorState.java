package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;

import java.util.Objects;

/**
 * 一名角色<b>会变的</b>那一半。不变的那一半在 {@link io.github.heavyseasmc.engine.model.Survivor}。
 *
 * <p>刻意<b>不</b>把体型抄进来：体型整局不变，抄一份就有了两个真相源。
 * 需要判定生死时由 {@link GameState} 把两半配起来，那里只有一处配对逻辑。
 *
 * <p>同理刻意<b>不</b>存 {@link Condition} —— 它由伤害与体型推导，理由见那个枚举。
 *
 * @param id          角色主键
 * @param seat        当前座位。换座位会改它，所以它在「会变的一半」里
 * @param damage      累计伤害。只增不减，唯一的例外是医疗箱（-1）
 * @param thirst      本回合累积的口渴来源。航海阶段结束时清空
 * @param actedThisTurn 本回合是否已经行动过。行动阶段按「最靠船头且未行动」取人
 */
public record SurvivorState(
        CharacterId id,
        int seat,
        int damage,
        ThirstTally thirst,
        boolean actedThisTurn
) {

    public SurvivorState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(thirst, "thirst");
        if (seat < 1) {
            throw new IllegalArgumentException("座位号从 1 起（船头），实际: " + seat);
        }
        if (damage < 0) {
            throw new IllegalArgumentException("伤害不能为负，实际: " + damage);
        }
    }

    /** 开局状态：未受伤、未渴、未行动。 */
    public static SurvivorState fresh(CharacterId id, int seat) {
        return new SurvivorState(id, seat, 0, ThirstTally.none(), false);
    }

    public SurvivorState withDamage(int newDamage) {
        return newDamage == damage ? this : new SurvivorState(id, seat, newDamage, thirst, actedThisTurn);
    }

    /** 受伤。{@code points} 为 0 时原样返回，省掉一次无意义的分配。 */
    public SurvivorState hurt(int points) {
        if (points < 0) {
            throw new IllegalArgumentException("受伤点数不能为负，实际: " + points);
        }
        return withDamage(damage + points);
    }

    /**
     * 医疗箱：减 1 点伤害。
     *
     * <p>伤害为 0 时拒绝 —— 「治疗一个没受伤的人」几乎一定是调用方算错了，
     * 静默当成 0 会把那个错误藏起来。规则上医疗箱也只用来救醒昏迷者。
     */
    public SurvivorState heal(int points) {
        if (points < 1) {
            throw new IllegalArgumentException("治疗点数必须为正，实际: " + points);
        }
        if (damage == 0) {
            throw new IllegalArgumentException("对未受伤的 " + id + " 使用治疗，调用方多半算错了");
        }
        return withDamage(Math.max(0, damage - points));
    }

    public SurvivorState withSeat(int newSeat) {
        return newSeat == seat ? this : new SurvivorState(id, newSeat, damage, thirst, actedThisTurn);
    }

    public SurvivorState thirstFrom(ThirstSource source) {
        ThirstTally next = thirst.with(source);
        return next == thirst ? this : new SurvivorState(id, seat, damage, next, actedThisTurn);
    }

    public SurvivorState markActed() {
        return actedThisTurn ? this : new SurvivorState(id, seat, damage, thirst, true);
    }

    /**
     * 航海阶段结束时的清理：清除划船与战斗标记，并把行动标记复位。
     *
     * <p>❗<b>开放项 O7 正是在问这件事</b>：天候「无风」跳过整个航海阶段时，
     * 这一步清不清。口渴是跨天累积的，不清就会让昨天划过船的人今天平白口渴。
     * 本方法只负责「被调用时清干净」，<b>何时调用由阶段推进决定</b>，
     * 这样 O7 定案时只改一处调用点，不用动状态模型。
     */
    public SurvivorState endOfTurn() {
        return thirst.isEmpty() && !actedThisTurn
                ? this
                : new SurvivorState(id, seat, damage, ThirstTally.none(), false);
    }
}
