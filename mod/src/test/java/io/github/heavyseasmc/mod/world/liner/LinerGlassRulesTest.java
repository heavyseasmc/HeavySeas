package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 穹顶的肋（ADR-0074）：结构转动、镜像时 shape 跟着换对 —— 模板与 y 旋转那一半在游戏里实拍。 */
final class LinerGlassRulesTest {

    private static final LinerGlass.RibShape[] ALL = LinerGlass.RibShape.values();

    @Test
    void quarterTurnsMoveEverySideClockwiseAndSwapTheTwoMainRibs() {
        assertEquals(LinerGlass.RibShape.STEP_E, LinerGlass.RibShape.STEP_N.map(BlockRotation.CLOCKWISE_90::rotate));
        assertEquals(LinerGlass.RibShape.RING_SE, LinerGlass.RibShape.RING_NE.map(BlockRotation.CLOCKWISE_90::rotate));
        assertEquals(LinerGlass.RibShape.CURB_NW, LinerGlass.RibShape.CURB_SW.map(BlockRotation.CLOCKWISE_90::rotate));
        assertEquals(LinerGlass.RibShape.X, LinerGlass.RibShape.Z.map(BlockRotation.CLOCKWISE_90::rotate));
        assertEquals(LinerGlass.RibShape.Z, LinerGlass.RibShape.Z.map(BlockRotation.CLOCKWISE_180::rotate));
        for (LinerGlass.RibShape s : ALL) {
            LinerGlass.RibShape t = s;
            for (int i = 0; i < 4; i++) {
                t = t.map(BlockRotation.CLOCKWISE_90::rotate);
            }
            assertEquals(s, t, s + " 转四次该回到自己");
        }
    }

    @Test
    void mirrorsFlipOneAxisAndAreTheirOwnInverse() {
        assertEquals(LinerGlass.RibShape.STEP_S, LinerGlass.RibShape.STEP_N.map(BlockMirror.LEFT_RIGHT::apply));
        assertEquals(LinerGlass.RibShape.CURB_RIB_W, LinerGlass.RibShape.CURB_RIB_E.map(BlockMirror.FRONT_BACK::apply));
        assertEquals(LinerGlass.RibShape.RING_NE, LinerGlass.RibShape.RING_NW.map(BlockMirror.FRONT_BACK::apply));
        for (BlockMirror m : BlockMirror.values()) {
            for (LinerGlass.RibShape s : ALL) {
                assertEquals(s, s.map(m::apply).map(m::apply), s + " 按 " + m + " 镜像两次该回到自己");
            }
        }
    }

    /** 模板的 y 旋转与 shape 的方向一致：北 0 · 东 90 · 南 180 · 西 270（凹角按「北 + 西」那一块转）。 */
    @Test
    void yawFollowsTheSides() {
        Set<LinerGlass.RibShape> seen = EnumSet.noneOf(LinerGlass.RibShape.class);
        for (LinerGlass.RibShape s : ALL) {
            LinerGlass.RibShape north = s;
            for (int k = 0; k < s.yaw / 90; k++) {
                north = north.map(BlockRotation.COUNTERCLOCKWISE_90::rotate);
            }
            assertEquals(s.template, north.template);
            if (s.kind != LinerGlass.RibShape.Kind.MAIN) {
                assertEquals(0, north.yaw, s + " 倒转回去该是按北画的那一块");
            }
            seen.add(s);
        }
        assertTrue(seen.size() == 34, "34 种都查到了");
    }
}
