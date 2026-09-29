package io.github.heavyseasmc.mod.ui;

import java.util.List;

/**
 * 播报列表从上一帧到这一帧新到了几条。
 *
 * <p>服务端只留最新的 {@code MAX_NOTIFICATIONS} 条（新的接在末尾、最老的从头上掉）。所以「条数变多了」
 * 只在前几条管用：满了之后条数永远是那个数，新来一条与什么都没来<b>条数一样</b>。
 * 2026-09-30 实拍：一局打到第一回合行动阶段，右栏就再也不会自己滑出来了 —— 而那一面的回归
 * {@code sidebar_test.py} 一直绿，因为它只在开局、还没满八条的时候测过。
 *
 * <p>判法：找最小的 {@code s}，使上一帧去掉头上 {@code s} 条之后，正好是这一帧的开头 ——
 * 这一帧剩下的就是新到的。一条都对不上（换了一局、或者一帧之内来了超过一整本）就算全是新的。
 */
public final class NotificationArrivals {

    private NotificationArrivals() {
    }

    public static int count(List<String> previous, List<String> current) {
        if (previous.equals(current)) {
            return 0;
        }
        for (int s = 0; s <= previous.size(); s++) {
            List<String> tail = previous.subList(s, previous.size());
            if (tail.isEmpty()) {
                break;
            }
            if (current.size() >= tail.size() && current.subList(0, tail.size()).equals(tail)) {
                return current.size() - tail.size();
            }
        }
        return current.size();
    }
}
