package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.TelescopeSight.Exit;
import io.github.heavyseasmc.mod.ui.TelescopeSight.Frame;
import io.github.heavyseasmc.mod.ui.TelescopeSight.UseGate;
import io.github.heavyseasmc.mod.ui.TelescopeSight.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 望远镜柜的望远镜视角（ADR-0109 ③）的纯规则：每一种「该出去了」都要真的判得出来，平平常常站着看时一种都不许误判；
 * 右键的闸分得清「进来那一下还按着」与「再按一下」；拉近与灵敏度的数。游戏里的接线（mixin · 每 tick 拍帧）在实测里看。
 */
final class TelescopeSightTest {

    /** 站在瞭望台上、看着柜子的平常一帧：什么都不该判。 */
    private static Frame calm() {
        return new Frame(true, true, true, true, false, false, false, true, 1.6, 0.0, true);
    }

    private static final double LIMIT = TelescopeSight.leaveLimit(1.6);

    @Test
    @DisplayName("平常站着看、在瞭望台上小步挪动：不退出（正向对照：判据不是什么都判退）")
    void calmFrameStays() {
        assertNull(TelescopeSight.exit(calm(), LIMIT));
        assertNull(TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, true, 2.4, 0.2, true), LIMIT),
                "在界线以内走一步不算走开");
    }

    @Test
    @DisplayName("每一种退出理由各有一帧把它判出来，而且只改那一项就够")
    void everyExitIsReachable() {
        Frame c = calm();
        assertEquals(Exit.DISCONNECTED, TelescopeSight.exit(new Frame(false, c.sameWorld(), c.samePlayer(), c.alive(), c.screenOpen(),
                c.sneaking(), c.inVoyage(), c.cabinetThere(), c.distance(), c.step(), c.firstPerson()), LIMIT));
        assertEquals(Exit.WORLD_CHANGED, TelescopeSight.exit(new Frame(true, false, true, true, false, false, false, true, 1.6, 0, true), LIMIT));
        assertEquals(Exit.DIED, TelescopeSight.exit(new Frame(true, true, false, true, false, false, false, true, 1.6, 0, true), LIMIT),
                "重生之后换了一个玩家对象");
        assertEquals(Exit.DIED, TelescopeSight.exit(new Frame(true, true, true, false, false, false, false, true, 1.6, 0, true), LIMIT));
        assertEquals(Exit.SCREEN, TelescopeSight.exit(new Frame(true, true, true, true, true, false, false, true, 1.6, 0, true), LIMIT));
        assertEquals(Exit.SNEAK, TelescopeSight.exit(new Frame(true, true, true, true, false, true, false, true, 1.6, 0, true), LIMIT));
        assertEquals(Exit.VOYAGE, TelescopeSight.exit(new Frame(true, true, true, true, false, false, true, true, 1.6, 0, true), LIMIT));
        assertEquals(Exit.CABINET_GONE, TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, false, 1.6, 0, true), LIMIT));
        assertEquals(Exit.TELEPORTED, TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, true, 1.6, 4.5, true), LIMIT),
                "被传送到柜子旁边（距离照样在界线以内）也要退");
        assertEquals(Exit.LEFT, TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, true, 2.6, 0.2, true), LIMIT));
        assertEquals(Exit.PERSPECTIVE, TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, true, 1.6, 0, false), LIMIT));
        // 这张表里每一种理由（除了右键：它不经过帧，走 UseGate）都有上面的一帧：加了新理由却没人判得出来，这里红
        Set<Exit> covered = EnumSet.of(Exit.DISCONNECTED, Exit.WORLD_CHANGED, Exit.DIED, Exit.SCREEN, Exit.SNEAK, Exit.VOYAGE,
                Exit.CABINET_GONE, Exit.TELEPORTED, Exit.LEFT, Exit.PERSPECTIVE);
        assertEquals(EnumSet.complementOf(EnumSet.of(Exit.USE_KEY)), covered);
    }

    @Test
    @DisplayName("开局：被送上艇那一下既是位置突变、又是这个人有了对局 —— 两条里哪条都够退出")
    void voyageStartExitsEitherWay() {
        // 开局时同一维度（船与对局都在 mist_sea），不会换世界；人被 teleport 到艇首、HUD 收到对局
        assertEquals(Exit.TELEPORTED, TelescopeSight.exit(new Frame(true, true, true, true, false, false, false, true, 300, 300, true), LIMIT));
        assertEquals(Exit.VOYAGE, TelescopeSight.exit(new Frame(true, true, true, true, false, false, true, true, 300, 0, true), LIMIT));
        assertFalse(TelescopeSight.canEnter(false, false, true), "对局里不进");
    }

    @Test
    @DisplayName("走开的界线：至少 2.5 格；隔得远右键进来的人以进来时的距离再宽 0.75")
    void leaveLimit() {
        assertEquals(TelescopeSight.LEAVE_DISTANCE, TelescopeSight.leaveLimit(1.0));
        assertEquals(4.0 + TelescopeSight.LEAVE_SLACK, TelescopeSight.leaveLimit(4.0), 1e-9);
        // 交互距离 4.5 格处右键进来：一进来不判走开
        Frame far = new Frame(true, true, true, true, false, false, false, true, 4.5, 0, true);
        assertNull(TelescopeSight.exit(far, TelescopeSight.leaveLimit(4.5)));
        // 走路、跑、掉落一 tick 都挪不到传送那一档
        assertTrue(TelescopeSight.TELEPORT_STEP > 3.92, "最快的掉落一 tick 3.92 格，不能当成传送");
    }

    @Test
    @DisplayName("潜行着、开着界面时不进")
    void canEnter() {
        assertTrue(TelescopeSight.canEnter(false, false, false));
        assertFalse(TelescopeSight.canEnter(true, false, false));
        assertFalse(TelescopeSight.canEnter(false, true, false));
    }

    @Test
    @DisplayName("右键的闸：进来那一下按着不放不算再按；松开再按才退出；退出后还按着不会又进来")
    void useGate() {
        UseGate g = new UseGate();
        assertEquals(Verdict.PASS, g.use(false), "没在看：右键照常");
        g.entered();
        // 进来那一下还按着：游戏每 4 tick 再「用」一次，全吞掉，不退出
        g.tick(true, true);
        assertEquals(Verdict.SWALLOW, g.use(true));
        g.tick(true, true);
        assertEquals(Verdict.SWALLOW, g.use(true));
        // 松开 → 再按一下 = 退出
        g.tick(false, true);
        assertEquals(Verdict.EXIT, g.use(true));
        // 退出之后右键还按着：再「用」到柜子上也吞掉（不然 4 tick 后又进来了）
        g.tick(true, false);
        assertEquals(Verdict.SWALLOW, g.use(false));
        g.tick(false, false);
        assertEquals(Verdict.PASS, g.use(false), "松开之后右键照常");
    }

    @Test
    @DisplayName("右键的闸：快点一下（同一 tick 里按下又松开）进来，下一下就能退出")
    void useGateQuickClick() {
        UseGate g = new UseGate();
        g.entered();
        g.tick(false, true);
        assertEquals(Verdict.EXIT, g.use(true));
    }

    @Test
    @DisplayName("右键的闸：被别的理由退出（潜行、界面……）之后，下一下右键照常（不吞）")
    void useGateAfterOtherExit() {
        UseGate g = new UseGate();
        g.entered();
        g.tick(true, true);
        g.exitedOtherwise();
        assertEquals(Verdict.PASS, g.use(false));
    }

    @Test
    @DisplayName("拉近：几 tick 到位、退出时回到 1；视野 70° 拉到 14°")
    void zoomEases() {
        double p = 0;
        int ticks = 0;
        while (p < 1) {
            p = TelescopeSight.approach(p, true);
            ticks++;
            assertTrue(ticks < 20, "拉不到底");
        }
        assertTrue(ticks >= 3 && ticks <= 8, "既不是一下跳过去，也不拖：" + ticks + " tick");
        assertEquals(TelescopeSight.ZOOM, TelescopeSight.fovFactor(p), 1e-9);
        assertEquals(14.0, 70 * TelescopeSight.fovFactor(1), 1e-9);
        while (p > 0) {
            p = TelescopeSight.approach(p, false);
        }
        assertEquals(1.0, TelescopeSight.fovFactor(p), "退出后视野回到原样，不剩一丝");
        assertEquals(1.0, TelescopeSight.fovFactor(-1), "夹住");
    }

    @Test
    @DisplayName("灵敏度：按半角正切之比降；不拉近时是 1；拉近越多降得越多")
    void sensitivity() {
        assertEquals(1.0, TelescopeSight.sensitivityScale(70, 1), 1e-9);
        double s = TelescopeSight.sensitivityScale(70, TelescopeSight.ZOOM);
        assertEquals(Math.tan(Math.toRadians(7)) / Math.tan(Math.toRadians(35)), s, 1e-9);
        assertTrue(s > 0.15 && s < 0.2, "70° → 14° 约 0.175：" + s);
        assertTrue(TelescopeSight.sensitivityScale(110, 0.2) < TelescopeSight.sensitivityScale(110, 0.5));
    }

    @Test
    @DisplayName("暗角：清晰的圆 < 渐暗的圈 < 全黑，都在屏幕短边一半以内")
    void rimRadii() {
        assertTrue(0 < TelescopeSight.CLEAR_RADIUS);
        assertTrue(TelescopeSight.CLEAR_RADIUS < TelescopeSight.RIM_INNER);
        assertTrue(TelescopeSight.RIM_INNER < TelescopeSight.RIM_OUTER);
        assertTrue(TelescopeSight.RIM_OUTER <= 0.5f, "暗角的外沿超出屏幕短边，上下两头就切掉了、看不出是一个圆");
        assertTrue(TelescopeSight.RIM_INNER_ALPHA > 0 && TelescopeSight.RIM_INNER_ALPHA < 1);
    }
}
