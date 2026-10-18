package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.LiftRideS2C;
import io.github.heavyseasmc.mod.ui.LiftRide;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * 坐电梯时的画面：服务端发来 {@link LiftRideS2C}（这一趟多少 tick），这里照 {@link LiftRide} 的曲线盖一层半透明的黑（快捷栏那一层）
 * 并让镜头轻轻上下晃（{@code CameraMixin}）。按墙上的钟算时间，不按客户端 tick：画面每一帧都要平滑，暂停菜单开着也照走
 * （服务端那一边也照走）。
 */
public final class LiftRideEffect {

    private static long startNanos;
    private static int rideTicks = -1;

    private LiftRideEffect() {
    }

    /** 收到「出发」。 */
    public static void start(LiftRideS2C payload) {
        startNanos = System.nanoTime();
        rideTicks = Math.max(0, payload.ticks());
    }

    /** 出发后过了多少 tick（带小数）；没有在坐电梯是 -1。 */
    private static double elapsed() {
        if (rideTicks < 0) {
            return -1;
        }
        double t = (System.nanoTime() - startNanos) / 50_000_000.0;
        if (LiftRide.over(t, rideTicks)) {
            rideTicks = -1;
            return -1;
        }
        return t;
    }

    /** 镜头往上挪多少格（{@code CameraMixin} 每一帧问一次）。 */
    public static double bob() {
        double t = elapsed();
        return t < 0 ? 0 : LiftRide.bob(t, rideTicks);
    }

    /** 快捷栏那一层：整屏盖一层黑，透明度照曲线。 */
    public static void render(DrawContext context, RenderTickCounter counter) {
        double t = elapsed();
        if (t < 0) {
            return;
        }
        int alpha = Math.round(LiftRide.dim(t, rideTicks) * 255);
        if (alpha <= 0) {
            return;
        }
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24);
    }

    /** 断线 / 换服务器：不把上一趟带过去。 */
    public static void reset() {
        rideTicks = -1;
    }
}
