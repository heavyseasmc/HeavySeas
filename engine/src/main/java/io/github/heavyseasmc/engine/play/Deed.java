package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.Objects;

/**
 * 公开记事里的一条：<b>谁对谁做了什么</b>，全船都看得见的那一种。
 *
 * <h2>为什么要有它</h2>
 * 替身要「记得谁帮过我、谁害过我」（用户 2026-10-07：「要记得谁帮过我之类的」）。
 * 引擎原先没有任何历史 —— 状态只有「此刻」，于是「刚才是谁抢了我」只能由驱动者自己记，
 * 模拟器记一份、模组记一份，两份迟早分家。所以由 {@link Session} 在规则动作发生的那一刻自己记下来：
 * 模拟器与模组看到的是同一份。
 *
 * <h2>只记公开的事</h2>
 * 每一条都是桌上人人看得见的：宣告抢谁、拒绝没有、站在哪边、谁输了挨打、谁送了谁一张（张数是公开的）、
 * 谁递了水、谁治了谁、谁扔了救生圈、谁扔了血饵、舵手挑的牌把谁送下了水。
 * <b>不记牌是什么</b>：从手里拿走、送出去的是哪一张，旁人看不见 —— 记事里也就没有。
 *
 * <p>❗<b>记事不解释好坏</b>。「抢我」算不算仇、「替我站队」算多大的情，是读的那一方（席位策略）的事；
 * 这里只陈述发生了什么。把好坏写进规则层，等于替每个读者做了同一个决定。
 *
 * @param turn   第几天
 * @param kind   什么事
 * @param actor  做事的人
 * @param target 被做的人（站队时是那一边的主将，打输时是挨打的人……见各常量）
 * @param amount 多少：递了几张水、挨了几点伤、划船堆里当时有几张；其余的事恒为 1
 */
public record Deed(int turn, Kind kind, CharacterId actor, CharacterId target, int amount) {

    public Deed {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        if (turn < 1) {
            throw new IllegalArgumentException("记事的天数从 1 起，实际: " + turn);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("记事的数量不能为负，实际: " + amount);
        }
    }

    /** 记事的种类。每一种写明 actor 与 target 各是谁。 */
    public enum Kind {
        /** actor 宣告要抢 target（不论成不成、对方清不清醒）。 */
        STEAL_DECLARED,
        /** actor 宣告要和 target 换座位。 */
        SWAP_DECLARED,
        /** 被指的 actor 拒绝了发起的 target，打起来了。 */
        REFUSED,
        /** 被指的 actor 自己点了同意（昏迷、尸体、小孩的偷窃那种「视为同意」不记）。 */
        CONCEDED,
        /** actor 反对 target 打出的分食，打起来了。 */
        OBJECTED,
        /** actor 加入了 target 那一边（target 是那一边的主将：发起的人或被指的人）。 */
        SIDED_WITH,
        /** actor 加入了与 target 对立的那一边（target 是对面那一边的主将）。 */
        SIDED_AGAINST,
        /** 一架打完：赢的那一边的主将 actor，输的那一边的 target 挨了 amount 点伤。 */
        BEAT,
        /** actor 抢到了 target 的一张牌。 */
        TOOK,
        /** actor 与 target 换了座位。 */
        SWAPPED,
        /** actor 送了 target 一张牌。 */
        GAVE,
        /** actor 在 target 口渴结算时递了 amount 张水。 */
        WATERED,
        /** actor 给 target 回了 amount 点体力（医疗箱、分食）。 */
        HEALED,
        /** 落海那一刻 actor 把救生圈扔给了 target。 */
        RING,
        /** 落海那一刻 actor 打出血饵，同一批落海的 target 多挨一点。每个落海者记一条。 */
        BAITED,
        /** 舵手 actor 挑中的牌点了 target 落海；amount 是他挑的时候划船堆里有几张（只有一张时谈不上「挑」）。 */
        STEERED
    }
}
