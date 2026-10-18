package io.github.heavyseasmc.mod.ui;

/**
 * 播报从上一次同步到这一次新到了几条。
 *
 * <p>服务端给这一局的每条播报一个只增不减的序号（{@code GameComponent#notificationSeq}，开局从 0 数），
 * 投影里带着「到现在一共播了几条」，新到的就是两次之差。
 *
 * <p>❗不再比内容（审查 2026-10-07 U13）。原先找「上一帧的尾巴接上这一帧的开头」，两种局面数错而且不报错：
 * 一模一样的几条接连来（几场打架各一句「败方每人受 1 点伤害」），满了之后新来一条与什么都没来<b>内容一样</b>；
 * 一帧之内来的超过投影留的条数，最早那几条在发出去之前就掉了。再往前（2026-09-30）是比条数，满了之后条数不变 ——
 * 那一次实拍是开局不久右栏就再也不自己滑出来了。
 */
public final class NotificationArrivals {

    private NotificationArrivals() {
    }

    /**
     * @param lastSeq 上一次见到的序号
     * @param seq     这一次的序号；比上一次小 = 换了一局（序号从 0 重数），这一局播过的全算新到的
     * @param size    这一次投影里带着几条 —— 新到的再多也只拿得到这几条
     */
    public static int count(long lastSeq, long seq, int size) {
        long arrived = seq >= lastSeq ? seq - lastSeq : seq;
        return (int) Math.min(arrived, size);
    }
}
