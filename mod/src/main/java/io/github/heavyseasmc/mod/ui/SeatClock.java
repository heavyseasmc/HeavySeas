package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.state.HudView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 座位轨上「正在等谁、还剩多久」（ADR-0095 B4，用户 2026-10-13 选样张 A）：被等的那一座，头像那一圈铜绿就是钟 ——
 * 从 12 点起顺时针被吃掉，剩下的比例 = 剩余时间 / 这一窗本来多长。版面不动，不另加横杠。
 *
 * <p>这里只放算法（与 {@link HudLayout} 同一个理由：不碰 Minecraft 客户端类，单测直接对它）；画在 {@code GameHud#drawRail}。
 */
public final class SeatClock {

    /** 不画钟：窗口没开，或演示局等真人不限时（服务端给一年，客户端照「一天以上」认，与倒计时条同一条线）。 */
    public static final float NONE = -1f;

    /** 窗口长过这么久就算不限时（{@code GameComponent.UNLIMITED_MS} = 一年；与 {@code GameScreen.UNLIMITED_THRESHOLD_MS} 同值）。 */
    public static final long UNLIMITED_THRESHOLD_MS = 24L * 3600 * 1000;

    private SeatClock() {
    }

    /**
     * 这一座此刻剩下几成（0–1），或 {@link #NONE}。
     *
     * @param nowMs   服务端时钟的「现在」（客户端用 {@code GameClock.now()}）
     * @param stepped 减少动效（O46）：按整秒跳，不连续地扫 —— 剩 7.4 秒画 8 秒那一格，一秒才动一下
     */
    public static float remaining(long deadlineMs, long windowMs, long nowMs, boolean stepped) {
        if (deadlineMs <= 0 || windowMs <= 0 || windowMs >= UNLIMITED_THRESHOLD_MS) {
            return NONE;
        }
        long left = Math.max(0L, deadlineMs - nowMs);
        if (left >= UNLIMITED_THRESHOLD_MS) {
            return NONE;
        }
        if (stepped) {
            left = (left + 999L) / 1000L * 1000L;
        }
        return (float) Math.min(1.0, left / (double) windowMs);
    }

    /** 已经被吃掉多少度：从 12 点起顺时针。 */
    public static float eatenDegrees(float remaining) {
        float r = Math.max(0f, Math.min(1f, remaining));
        return 360f * (1f - r);
    }

    /**
     * 此刻在等哪几座（船头到船尾），各自的钟。投影里有 {@link HudView.Rail#waits()} 就认它；
     * 没有（没开窗口的那一段 · 全是替身由排程推）就退回原先那一位：口渴被问的人 → 这一场的发起者 → 正轮到行动的人，
     * 这时只画在等圈、不画钟（到点时刻给 0）。
     */
    public static Map<String, HudView.Rail.Wait> waiting(HudView view) {
        Map<String, HudView.Rail.Wait> out = new LinkedHashMap<>();
        for (HudView.Rail.Wait w : view.rail().waits()) {
            out.putIfAbsent(w.who(), w);
        }
        if (!out.isEmpty()) {
            return out;
        }
        String who = view.thirstPrompt().active() ? view.thirstPrompt().who()
                : view.contest().active() ? view.contest().attacker() : view.actor();
        if (!who.isEmpty()) {
            out.put(who, new HudView.Rail.Wait(who, 0L, 0L));
        }
        return out;
    }

    /** 被指的人：进行中的那一场换座位 / 抢夺指的是谁（公开，{@code ContestView#target}）；没有就是空串。 */
    public static String aimed(HudView view) {
        return view.contest().active() ? view.contest().target() : "";
    }

    /** 头像那一圈是哪一种：金 = 你 · 朱砂 = 被指的人 · 铜绿 = 在等他 · 平常那一圈。身份压过「在等」：在等只决定走不走钟。 */
    public enum Ring { YOU, AIMED, WAITED, PLAIN }

    /** 头像右上那枚小圆片：被指的人（朱砂准星）· 补给箱在他手上 · 没有。 */
    public enum Chip { NONE, AIM, CRATE }

    /**
     * 一座此刻的样子：哪一圈 · 钟还剩几成（{@link #NONE} = 不画钟）· 挂哪枚小圆片。
     * 主画面的座位轨与对局各面的座位轨都由 {@link #mark} 算 —— 两处长得一样，算法只此一份（用户 2026-10-13：
     * 「全屏面座位轨也加钟与小标记」）。
     */
    public record Mark(Ring ring, float clock, Chip chip) {

        /** 在等他、却没有钟可画（不限时 · 没开窗口）：那一圈呼吸。 */
        public boolean breathes() {
            return ring == Ring.WAITED && clock == NONE;
        }
    }

    /**
     * 这一座此刻的样子。
     *
     * @param waiting {@link #waiting} 的结果（一帧算一次，各座共用）
     * @param aimed   {@link #aimed} 的结果
     */
    public static Mark mark(HudView view, String id, Map<String, HudView.Rail.Wait> waiting, String aimed,
                            long nowMs, boolean stepped) {
        HudView.Rail.Wait wait = waiting.get(id);
        Ring ring = id.equals(view.character()) && view.seated() ? Ring.YOU
                : id.equals(aimed) ? Ring.AIMED
                : wait != null ? Ring.WAITED : Ring.PLAIN;
        float clock = wait == null ? NONE : remaining(wait.deadlineMs(), wait.windowMs(), nowMs, stepped);
        Chip chip = id.equals(aimed) ? Chip.AIM
                : id.equals(view.rail().boxHolder()) ? Chip.CRATE : Chip.NONE;
        return new Mark(ring, clock, chip);
    }

    /**
     * 钟还剩下的那一块：一个扇面，裁在头像那一圈贴图的方框里（纹理坐标不出 0–1）。画成三角扇：
     * 第一个点是圆心，后面沿方框边从 {@code toDeg} <b>倒着</b>走回 {@code fromDeg}（角度是 12 点起顺时针量的），
     * 方框四角一定在点列里。
     *
     * <p>❗倒着走是为了绕向：屏幕上逆时针排点才与 {@code DrawContext.fill} 同绕向，界面这一遍开着背面剔除。
     * 2026-10-13 第一版顺着走，整块扇面被剔掉，第二轮真机截图里钟一次也没画出来，被等的那一圈（金 / 朱砂 / 铜绿）
     * 也跟着没了 —— 只剩底下那一圈平常的（{@link ShapeMesh}）。
     *
     * @return {x0, y0, x1, y1, ...}；扇面为空时是空数组
     */
    public static float[] sector(float cx, float cy, float half, float fromDeg, float toDeg, float stepDeg) {
        if (toDeg - fromDeg <= 0.01f || half <= 0) {
            return new float[0];
        }
        List<Float> angles = new ArrayList<>();
        for (float a = fromDeg; a < toDeg; a += stepDeg) {
            angles.add(a);
        }
        for (float corner = 45f; corner < 360f; corner += 90f) {
            if (corner > fromDeg && corner < toDeg) {
                angles.add(corner);
            }
        }
        angles.add(toDeg);
        angles.sort((a, b) -> Float.compare(b, a));        // 倒着：屏幕上逆时针
        float[] out = new float[2 + 2 * angles.size()];
        out[0] = cx;
        out[1] = cy;
        int i = 2;
        for (float a : angles) {
            double rad = Math.toRadians(a);
            double sx = Math.sin(rad);
            double sy = -Math.cos(rad);
            double r = half / Math.max(Math.abs(sx), Math.abs(sy));
            out[i++] = (float) (cx + sx * r);
            out[i++] = (float) (cy + sy * r);
        }
        return out;
    }
}
