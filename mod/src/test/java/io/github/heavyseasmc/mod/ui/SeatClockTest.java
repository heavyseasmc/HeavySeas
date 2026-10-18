package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.HudView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 座位轨上「那一圈就是钟」的算法（2026-10-13 样张 A，{@link SeatClock}）。 */
final class SeatClockTest {

    private static final long NOW = 1_000_000L;

    @Test
    @DisplayName("扇形比例：剩余 / 总长；12 点起顺时针吃掉的度数；减少动效时按整秒跳")
    void fractionAndSweep() {
        assertEquals(0.3f, SeatClock.remaining(NOW + 6_000, 20_000, NOW, false), 1e-6);
        assertEquals(0.37f, SeatClock.remaining(NOW + 7_400, 20_000, NOW, false), 1e-6);
        assertEquals(0.4f, SeatClock.remaining(NOW + 7_400, 20_000, NOW, true), 1e-6, "减少动效：7.4 秒画 8 秒那一格");
        assertEquals(0f, SeatClock.remaining(NOW - 10, 20_000, NOW, false), "过了到点：吃光");
        assertEquals(1f, SeatClock.remaining(NOW + 30_000, 20_000, NOW, false), "客户端钟稍慢：不超过满格");
        assertEquals(144f, SeatClock.eatenDegrees(0.6f), 1e-4);
        assertEquals(0f, SeatClock.eatenDegrees(1f), 1e-6);
        assertEquals(360f, SeatClock.eatenDegrees(0f), 1e-6);
    }

    @Test
    @DisplayName("不限时（演示局等真人，服务端给一年）与没开窗口：不画钟")
    void unlimitedDrawsNoClock() {
        long year = 365L * 24 * 3600 * 1000;
        assertEquals(SeatClock.NONE, SeatClock.remaining(NOW + year, year, NOW, false));
        assertEquals(SeatClock.NONE, SeatClock.remaining(0L, 0L, NOW, false));
        assertEquals(SeatClock.NONE, SeatClock.remaining(NOW + 5_000, 0L, NOW, false));
    }

    @Test
    @DisplayName("剩下的那一块扇面：从 12 点倒着走回吃到的那一度，四角一个不漏、点都在贴图方框里")
    void sectorStaysInTheBox() {
        float cx = 50;
        float cy = 50;
        float h = 32;
        float[] p = SeatClock.sector(cx, cy, h, SeatClock.eatenDegrees(0.75f), 360f, 4f);
        assertEquals(cx, p[0], 1e-4);
        assertEquals(cy, p[1], 1e-4);
        assertEquals(cx, p[2], 1e-3, "第一个边点在 12 点");
        assertEquals(cy - h, p[3], 1e-3);
        assertEquals(cx + h, p[p.length - 2], 1e-3, "最后一个边点在 3 点钟");
        assertEquals(cy, p[p.length - 1], 1e-3);
        assertTrue(hasPoint(p, cx + h, cy + h) && hasPoint(p, cx - h, cy + h) && hasPoint(p, cx - h, cy - h),
                "剩下三个角都在点列里");
        assertTrue(!hasPoint(p, cx + h, cy - h), "已经吃掉的右上角不在");
        for (int i = 2; i < p.length; i += 2) {
            assertTrue(p[i] >= cx - h - 1e-3 && p[i] <= cx + h + 1e-3 && p[i + 1] >= cy - h - 1e-3
                    && p[i + 1] <= cy + h + 1e-3, "纹理坐标出了 0–1");
        }
        assertEquals(0, SeatClock.sector(cx, cy, h, 360f, 360f, 4f).length, "吃光了：不画");
    }

    @Test
    @DisplayName("扇面的每一个三角形都与 fill 同绕向（界面开着背面剔除，2026-10-13 第二轮验收：反绕的钟一次也没画出来），合起来正好盖住剩下的角度")
    void sectorFacesTheScreen() {
        float cx = 20;
        float cy = 20;
        float h = 26;
        int checked = 0;
        for (float left : new float[]{1f, 0.99f, 0.75f, 0.5f, 0.37f, 0.1f, 0.01f}) {
            float from = SeatClock.eatenDegrees(left);
            float[] p = SeatClock.sector(cx, cy, h, from, 360f, 4f);
            double swept = 0;
            for (int i = 2; i + 3 < p.length; i += 2) {
                double c = ShapeMesh.cross(p[0], p[1], p[i], p[i + 1], p[i + 2], p[i + 3]);
                assertTrue(c < 0, "剩 " + left + " 的扇面第 " + (i / 2) + " 个三角形是背面（叉积 " + c + "），会被剔掉");
                swept += angleBetween(p[0], p[1], p[i], p[i + 1], p[i + 2], p[i + 3]);
                checked++;
            }
            assertEquals(360.0 - from, swept, 0.05, "剩 " + left + "：三角形合起来该盖住 " + (360 - from) + " 度");
        }
        assertTrue(checked > 50, "正向对照：真的量到了三角形（" + checked + "）");
    }

    @Test
    @DisplayName("主画面与各面同一个算法：你压过被指，被指压过在等；在等才走钟；没钟可走时只有铜绿那一圈呼吸；小圆片只给被指与拿着补给箱的人")
    void markForEachSeat() {
        HudView.Rail rail = new HudView.Rail(List.of(
                new HudView.Rail.Wait("captain", NOW + 10_000, 20_000),
                new HudView.Rail.Wait("doctor", 0L, 0L)), "kid");
        HudView v = view("captain", rail);
        var waiting = SeatClock.waiting(v);
        SeatClock.Mark captain = SeatClock.mark(v, "captain", waiting, "doctor", NOW, false);
        assertEquals(SeatClock.Ring.WAITED, captain.ring());
        assertEquals(0.5f, captain.clock(), 1e-6);
        assertTrue(!captain.breathes(), "走钟时不再呼吸");
        SeatClock.Mark doctor = SeatClock.mark(v, "doctor", waiting, "doctor", NOW, false);
        assertEquals(SeatClock.Ring.AIMED, doctor.ring(), "被指的人：朱砂圈压过在等");
        assertEquals(SeatClock.Chip.AIM, doctor.chip());
        assertTrue(!doctor.breathes(), "朱砂圈不呼吸");
        SeatClock.Mark kid = SeatClock.mark(v, "kid", waiting, "doctor", NOW, false);
        assertEquals(SeatClock.Chip.CRATE, kid.chip(), "补给箱在他手上");
        assertEquals(SeatClock.Ring.PLAIN, kid.ring());
        assertEquals(SeatClock.NONE, kid.clock());

        HudView fallback = view("mate", HudView.Rail.NONE);
        SeatClock.Mark mate = SeatClock.mark(fallback, "mate", SeatClock.waiting(fallback), "", NOW, false);
        assertEquals(SeatClock.Ring.WAITED, mate.ring());
        assertTrue(mate.breathes(), "没开窗口：那一圈呼吸、不画钟");
    }

    /** 从圆心看 a 到 b 张了几度。 */
    private static double angleBetween(float cx, float cy, float ax, float ay, float bx, float by) {
        double a = Math.atan2(ay - cy, ax - cx);
        double b = Math.atan2(by - cy, bx - cx);
        double d = Math.toDegrees(Math.abs(b - a));
        return d > 180 ? 360 - d : d;
    }

    @Test
    @DisplayName("多座同时被等（站队段）：每一座各有一只钟，按投影里的座次；投影里没有时退回只等轮到的那一位、不画钟")
    void severalSeatsAtOnce() {
        HudView.Rail rail = new HudView.Rail(List.of(
                new HudView.Rail.Wait("captain", NOW + 8_000, 15_000),
                new HudView.Rail.Wait("doctor", NOW + 8_000, 15_000)), "");
        var waiting = SeatClock.waiting(view("mate", rail));
        assertEquals(List.of("captain", "doctor"), List.copyOf(waiting.keySet()));
        assertEquals(15_000, waiting.get("doctor").windowMs());

        var fallback = SeatClock.waiting(view("mate", HudView.Rail.NONE));
        assertEquals(List.of("mate"), List.copyOf(fallback.keySet()), "没有窗口：退回轮到行动的那一位");
        assertEquals(SeatClock.NONE, SeatClock.remaining(fallback.get("mate").deadlineMs(),
                fallback.get("mate").windowMs(), NOW, false), "退回的那一位不画钟");
    }

    private static boolean hasPoint(float[] p, float x, float y) {
        for (int i = 2; i < p.length; i += 2) {
            if (Math.abs(p[i] - x) < 1e-3 && Math.abs(p[i + 1] - y) < 1e-3) {
                return true;
            }
        }
        return false;
    }

    private static HudView view(String actor, HudView.Rail rail) {
        return new HudView(true, 2, Phase.ACTION, 0, "", false, HudView.Fog.NONE, List.of(), 0L,
                List.of("mate", "kid", "captain", "doctor"), List.of(), actor, HudView.Sea.NONE, HudView.Thirst.NONE,
                HudView.Endgame.NONE, ContestView.NONE, false, "", 0, 0, Condition.CONSCIOUS, 0, "", "",
                false, 0L, 0L, false, 0L, "", List.of(), 0, List.of(), List.of(), HudView.Score.NONE,
                ExperienceView.EMPTY, rail);
    }
}
