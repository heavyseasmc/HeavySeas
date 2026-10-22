package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.AXIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记着板走向（{@link LinerBlocks#AXIS}）的方块，结构整体转 90° 时走向要跟着换 —— 不换的话，转过的船上柚木甲板的板缝横着走，不报错。
 * 2026-10-19 补：CURRENT_STATUS 一直挂着「rotate 不转柚木甲板的 axis」，查下来 11eca035 早已改掉，只是从没有判据守着。
 */
final class LinerAxisRotationTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    @DisplayName("带 AXIS 的每一种：转 90° 换走向、转 180° 与镜像不换")
    void axisFollowsRotation() {
        Map<String, Block> blocks = new LinkedHashMap<>(LinerBlocks.all());
        blocks.putAll(LinerShapes.all());
        List<String> checked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Block> e : blocks.entrySet()) {
            BlockState base = e.getValue().getDefaultState();
            if (!base.contains(AXIS)) {
                continue;
            }
            checked.add(e.getKey());
            for (Direction.Axis a : AXIS.getValues()) {
                BlockState s = base.with(AXIS, a);
                Direction.Axis other = a == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
                expect(problems, e.getKey(), a, "顺转 90°", s.rotate(BlockRotation.CLOCKWISE_90).get(AXIS), other);
                expect(problems, e.getKey(), a, "逆转 90°", s.rotate(BlockRotation.COUNTERCLOCKWISE_90).get(AXIS), other);
                expect(problems, e.getKey(), a, "转 180°", s.rotate(BlockRotation.CLOCKWISE_180).get(AXIS), a);
                expect(problems, e.getKey(), a, "前后镜像", s.mirror(BlockMirror.FRONT_BACK).get(AXIS), a);
                expect(problems, e.getKey(), a, "左右镜像", s.mirror(BlockMirror.LEFT_RIGHT).get(AXIS), a);
            }
        }
        // 正向对照：柚木甲板与柚木台阶一定在里面 —— 「0 种带 AXIS」与「没在查」输出一样
        assertTrue(checked.contains("liner_teak_deck") && checked.contains("liner_teak_slab"), "该查的没查到：" + checked);
        assertEquals(List.of(), problems, "查了 " + checked.size() + " 种：" + checked);
    }

    private static void expect(List<String> problems, String id, Direction.Axis from, String how, Direction.Axis got, Direction.Axis want) {
        if (got != want) {
            problems.add(id + " axis=" + from + " " + how + " 得 " + got + "，应为 " + want);
        }
    }
}
