package io.github.heavyseasmc.mod.ui;

/**
 * 坐电梯时画面的样子（{@code LiftRideS2C} 之后）：渐暗、轻轻上下晃，到站之后再亮回来。与怎么画无关，有单测（{@code LiftRideTest}）。
 *
 * <h2>为什么贯穿整趟，而不是一下就过去</h2>
 * 服务端每过一层 20 tick（1 秒），E 甲板到艇甲板要走好几秒；换层（把人送到目标层的同一个位置）发生在这一趟的最后一 tick。
 * 只暗一下的话，人在亮着的静止轿厢里干等几秒，换层那一下电梯厅从栅格外面一闪就变了 —— 读成「瞬移」。
 * 所以：开头 {@link #FADE_TICKS} 暗下去、整趟保持半暗并轻晃、到站之后 {@link #FADE_TICKS} 亮回来，换层那一下落在最暗的时候。
 * 暗是半暗（{@link #MAX_DIM}），不是黑屏：看得见轿厢里，像轿厢里的灯随着走动暗了一档。
 */
public final class LiftRide {

    /** 暗下去 / 亮回来各用多少 tick（半秒）。 */
    public static final int FADE_TICKS = 10;
    /** 最暗时盖一层多不透明的黑（0–1）。 */
    public static final float MAX_DIM = 0.45f;
    /** 上下晃的幅度（格）与周期（tick）：一秒多一点晃一个来回，幅度不到一个像素。 */
    public static final double BOB_BLOCKS = 0.025;
    public static final double BOB_PERIOD_TICKS = 24;

    private LiftRide() {
    }

    /** 出发后 {@code t} tick（可带小数）这一趟的强度（0–1）：渐入 · 保持 · 到站后渐出；{@code ride} 是这一趟的 tick 数。 */
    public static double envelope(double t, int ride) {
        if (t <= 0 || t >= ride + FADE_TICKS) {
            return 0;
        }
        if (t < FADE_TICKS) {
            return t / FADE_TICKS;
        }
        if (t <= ride) {
            return 1;
        }
        return 1 - (t - ride) / FADE_TICKS;
    }

    /** 盖多黑（0–{@link #MAX_DIM}）。 */
    public static float dim(double t, int ride) {
        return (float) (MAX_DIM * envelope(t, ride));
    }

    /** 镜头上下挪多少格：只在走的那一段晃（到站之后亮回来的那半秒不晃 —— 已经停了）。 */
    public static double bob(double t, int ride) {
        if (t <= 0 || t >= ride) {
            return 0;
        }
        return BOB_BLOCKS * envelope(t, ride) * Math.sin(2 * Math.PI * t / BOB_PERIOD_TICKS);
    }

    /** 这一趟的效果放完了没有。 */
    public static boolean over(double t, int ride) {
        return t >= ride + FADE_TICKS;
    }
}
