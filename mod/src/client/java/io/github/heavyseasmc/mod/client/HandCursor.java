package io.github.heavyseasmc.mod.client;

/**
 * 手牌一面的选中：<b>手牌与面前两排共用一个下标</b>（审查 2026-10-07 Z1）。下标 {@code [0, hand)} 是手牌，
 * {@code [hand, hand + front)} 是面前那一排；← → 跨排走，鼠标两排都点得中。
 *
 * <p>❗此前面前那一排「只画不选」（注释写着「不进高亮与命中」），而服务端是认面前的牌的（{@code ActionPhase#holds}
 * = 手里或面前）—— 于是牌一旦到了面前，手牌一面就再也打不出它：第二回合起喝不了面前的酒，医生用过的医疗箱用不了，
 * 亮出去的伞撑不开、信号枪打不出（用户 2026-10-07 实玩报）。只能绕道「行动一面 → 右键自己的头像 → 座位面板」。
 *
 * <p>面前的牌没有「亮出」这回事：第二枚按钮换成「赠送」（{@link Second}）。
 */
final class HandCursor {

    /** 第二枚按钮：手牌是「亮在面前」，面前的牌是「赠送」。第一枚恒为「打出」（打不出时那一格空着占位，Z2）。 */
    enum Second { REVEAL, GIVE }

    private HandCursor() {
    }

    /** 往左（{@code -1}）/ 往右（{@code +1}）挪一张；两排连成一条，走到头就停。 */
    static int step(int index, int delta, int hand, int front) {
        return clamp(index + delta, hand, front);
    }

    /** 两排的张数变了（亮出一张、打出一张、收到一张）：下标夹回两排合起来的范围里；两排都空时是 0。 */
    static int clamp(int index, int hand, int front) {
        return Math.max(0, Math.min(Math.max(0, hand + front - 1), index));
    }

    /** 这个下标指的是面前那一排吗。 */
    static boolean inFront(int index, int hand) {
        return index >= hand;
    }

    /** 第二枚按钮是什么。 */
    static Second second(int index, int hand) {
        return inFront(index, hand) ? Second.GIVE : Second.REVEAL;
    }
}
