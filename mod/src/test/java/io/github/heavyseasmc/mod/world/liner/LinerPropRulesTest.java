package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 灯与家具（ADR-0063）的纯规则：整件往哪边长、搭档在哪一格、镜像换哪一块。放下去、拆掉、开关在游戏里实测。 */
final class LinerPropRulesTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final Set<LinerProp.Part> QUAD = EnumSet.of(LinerProp.Part.NW, LinerProp.Part.NE, LinerProp.Part.SW, LinerProp.Part.SE);
    private static final Set<LinerProp.Part> PAIR = EnumSet.of(LinerProp.Part.WEST, LinerProp.Part.EAST);
    private static final Set<LinerProp.Part> TALL = EnumSet.of(LinerProp.Part.LOWER, LinerProp.Part.UPPER);

    @Test
    void modelCellsTurnTheSameWayAsBlockStateRotation() {
        // 模型正面朝北：北 (0, −1) 转一格到东 (1, 0)、两格到南、三格到西（与方块状态 y 旋转同向）
        assertArrayEquals(new int[]{0, -1}, LinerProp.Rules.toWorld(0, -1, 0));
        assertArrayEquals(new int[]{1, 0}, LinerProp.Rules.toWorld(0, -1, 1));
        assertArrayEquals(new int[]{0, 1}, LinerProp.Rules.toWorld(0, -1, 2));
        assertArrayEquals(new int[]{-1, 0}, LinerProp.Rules.toWorld(0, -1, 3));
        assertArrayEquals(new int[]{0, 1}, LinerProp.Rules.toWorld(1, 0, 1), "模型里的东（x + 1）在朝东时落到南");
    }

    @Test
    void sofaAndTableGrowToThePlacersRightAndAwayFromThePlacer() {
        // 人站在南边、面朝北摆：沙发正面朝着人（朝南）。点中的那一格是「人左手那一块」，整件往人的右手长
        //   面朝北的人，右手是东 —— 另一块在东边一格
        BlockPos at = new BlockPos(-3, 64, -7);
        Direction facing = Direction.SOUTH;
        LinerProp.Part a = LinerProp.Rules.anchor(LinerProp.Kind.SOFA);
        assertEquals(at.east(), LinerProp.Rules.offset(at, a, LinerProp.Part.WEST, facing));
        // 大桌：另三块在人的右手与远处（面朝北的人：东、北）
        LinerProp.Part t = LinerProp.Rules.anchor(LinerProp.Kind.GRAND_TABLE);
        Set<BlockPos> table = new HashSet<>();
        for (LinerProp.Part p : QUAD) {
            table.add(LinerProp.Rules.offset(at, t, p, facing));
        }
        assertEquals(Set.of(at, at.east(), at.north(), at.east().north()), table);
        // 灯：上面那一格
        assertEquals(at.up(), LinerProp.Rules.offset(at, LinerProp.Part.LOWER, LinerProp.Part.UPPER, Direction.EAST));
    }

    @Test
    void everyPartFindsEachPartnerExactlyWhereOffsetPutsIt() {
        for (Set<LinerProp.Part> parts : List.of(QUAD, PAIR, TALL)) {
            for (Direction facing : HORIZONTAL) {
                for (LinerProp.Part here : parts) {
                    int partners = 0;
                    for (Direction d : Direction.values()) {
                        LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (p != null) {
                            partners++;
                            assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing),
                                    here + " 朝 " + facing + " 时，" + d + " 那一格的搭档 " + p);
                        }
                    }
                    // 两格的件一个搭档；2 × 2 的大桌每一块挨着两块（斜对角那一块不挨着，靠一格传一格）
                    assertEquals(parts == QUAD ? 2 : 1, partners, here + " 朝 " + facing);
                }
            }
        }
        assertNull(LinerProp.Rules.partnerAt(LinerProp.Part.LOWER, Direction.DOWN, Direction.NORTH, TALL), "灯的下面一格不是搭档");
    }

    @Test
    void mirroringSwapsLeftAndRightAndKeepsThePieceWhole() {
        assertEquals(LinerProp.Part.EAST, LinerProp.Rules.mirrored(LinerProp.Part.WEST));
        assertEquals(LinerProp.Part.SE, LinerProp.Rules.mirrored(LinerProp.Part.SW));
        assertEquals(LinerProp.Part.UPPER, LinerProp.Rules.mirrored(LinerProp.Part.UPPER));
        // 整件照镜子（世界里 x 取反）之后，每一块换成 mirrored 的那一块、朝向照镜子转 —— 各块之间的相对位置必须还对得上
        for (Direction facing : HORIZONTAL) {
            Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            for (Set<LinerProp.Part> parts : List.of(QUAD, PAIR)) {
                for (LinerProp.Part a : parts) {
                    for (LinerProp.Part b : parts) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        BlockPos flipped = new BlockPos(-d.getX(), d.getY(), d.getZ());
                        assertEquals(flipped, LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Rules.mirrored(a),
                                LinerProp.Rules.mirrored(b), mirroredFacing), a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
    }
}
