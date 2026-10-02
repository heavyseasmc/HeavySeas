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

    private static final Set<LinerProp.Part> GRAND = LinerProp.Rules.parts(LinerProp.Kind.GRAND_CHANDELIER);

    @Test
    void hangingLampsGrowDownFromTheCellUnderTheCeiling() {
        BlockPos at = new BlockPos(5, 70, -2);
        // 黄铜小吊灯：点中的那一格贴天花（上面那一块），灯身在它正下方
        assertEquals(LinerProp.Part.UPPER, LinerProp.Rules.anchor(LinerProp.Kind.CHANDELIER));
        assertEquals(at.down(), LinerProp.Rules.offset(at, LinerProp.Part.UPPER, LinerProp.Part.LOWER, Direction.SOUTH));
        // 水晶大吊灯：吊杆那一格贴天花，下面一层 3 × 3 以它为中心 —— 哪个朝向都一样
        LinerProp.Part crown = LinerProp.Rules.anchor(LinerProp.Kind.GRAND_CHANDELIER);
        assertEquals(LinerProp.Part.CROWN, crown);
        Set<BlockPos> ring = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ring.add(at.add(dx, -1, dz));
            }
        }
        for (Direction facing : HORIZONTAL) {
            Set<BlockPos> got = new HashSet<>();
            for (LinerProp.Part p : GRAND) {
                if (p != crown) {
                    got.add(LinerProp.Rules.offset(at, crown, p, facing));
                }
            }
            assertEquals(ring, got, "朝 " + facing);
            assertEquals(at.down(), LinerProp.Rules.offset(at, crown, LinerProp.Part.RING_C, facing));
        }
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            assertEquals(k == LinerProp.Kind.CEILING_LAMP || k == LinerProp.Kind.CHANDELIER || k == LinerProp.Kind.GRAND_CHANDELIER
                            || k == LinerProp.Kind.CEILING_PAIR || k == LinerProp.Kind.CEILING_QUAD,
                    LinerProp.Rules.hanging(k), k + " 挂不挂在天花下");
        }
    }

    @Test
    void seamLightsSpreadFlatUnderTheCeilingAndLightEveryCell() {
        // 骑缝的吸顶灯（ADR-0068）：两格 / 2 × 2 一件，都在同一层（每一格都贴天花），整件往人的右手与远处长（同沙发、大桌），每一格都发光
        BlockPos at = new BlockPos(5, 64, -3);
        for (Direction facing : HORIZONTAL) {
            LinerProp.Part a = LinerProp.Rules.anchor(LinerProp.Kind.CEILING_PAIR);
            assertEquals(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.SOFA), LinerProp.Part.WEST, facing),
                    LinerProp.Rules.offset(at, a, LinerProp.Part.WEST, facing), "两格的骑缝灯与沙发同一个长法，朝 " + facing);
            Set<BlockPos> quad = new java.util.HashSet<>();
            for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.CEILING_QUAD)) {
                BlockPos q = LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.CEILING_QUAD), p, facing);
                assertEquals(at.getY(), q.getY(), "2 × 2 的骑缝灯四格都在天花下那一层");
                quad.add(q);
            }
            assertEquals(4, quad.size());
        }
        for (LinerProp.Kind k : List.of(LinerProp.Kind.CEILING_PAIR, LinerProp.Kind.CEILING_QUAD)) {
            assertEquals(true, LinerProp.Rules.isLamp(k), k + " 是灯");
            for (LinerProp.Part p : LinerProp.Rules.parts(k)) {
                assertEquals(true, LinerProp.Rules.glows(k, p), k + " · " + p + " 发光");
            }
        }
    }

    @Test
    void everyCellOfTheGrandChandelierFindsItsNeighbours() {
        for (Direction facing : HORIZONTAL) {
            for (LinerProp.Part here : GRAND) {
                int partners = 0;
                for (Direction d : Direction.values()) {
                    LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, GRAND);
                    if (p != null) {
                        partners++;
                        assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing), here + " " + d);
                    }
                }
                // 拆掉任何一格整件跟着没：一格传一格，所以每一格至少挨着一块 —— 正中那一格挨着十字四块 + 上面的吊杆
                int want = switch (here) {
                    case CROWN -> 1;
                    case RING_C -> 5;
                    case RING_N, RING_S, RING_W, RING_E -> 3;
                    default -> 2;
                };
                assertEquals(want, partners, here + " 朝 " + facing);
            }
        }
    }

    @Test
    void grandChandelierLightsOnlyTheCrossOfFiveAndMirrorsWhole() {
        Set<LinerProp.Part> lit = EnumSet.noneOf(LinerProp.Part.class);
        for (LinerProp.Part p : GRAND) {
            if (LinerProp.Rules.glows(LinerProp.Kind.GRAND_CHANDELIER, p)) {
                lit.add(p);
            }
        }
        assertEquals(EnumSet.of(LinerProp.Part.RING_N, LinerProp.Part.RING_W, LinerProp.Part.RING_C, LinerProp.Part.RING_E,
                LinerProp.Part.RING_S), lit);
        assertEquals(true, LinerProp.Rules.glows(LinerProp.Kind.CHANDELIER, LinerProp.Part.LOWER));
        assertEquals(false, LinerProp.Rules.glows(LinerProp.Kind.CHANDELIER, LinerProp.Part.UPPER));
        for (Direction facing : HORIZONTAL) {
            Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            for (LinerProp.Part a : GRAND) {
                for (LinerProp.Part b : GRAND) {
                    BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                    assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN,
                            LinerProp.Rules.mirrored(a), LinerProp.Rules.mirrored(b), mirroredFacing), a + " → " + b + " 朝 " + facing);
                }
            }
        }
    }

    /** 这几个盒子把一格填满了多少（按 16³ 个小格的中心点数）。 */
    private static double filled(List<double[]> boxes) {
        int in = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    double px = x + 0.5;
                    double py = y + 0.5;
                    double pz = z + 0.5;
                    for (double[] b : boxes) {
                        if (b[0] <= px && px < b[3] && b[1] <= py && py < b[4] && b[2] <= pz && pz < b[5]) {
                            in++;
                            break;
                        }
                    }
                }
            }
        }
        return in / 4096.0;
    }

    @Test
    void noPropHasAFullBlockOutlineOrCollisionBox() {
        // 用户 2026-10-02：「异形物品碰撞箱不能是完整的一个方块，需要也是异形的」。碰撞箱就是轮廓（Minecraft 默认），
        //   所以查轮廓的盒子：每一种、每一块都要有盒子、都在这一格里、而且没把这一格填到九成
        // 正向对照：一整块与「差一条边的整块」都要被认成填满 —— 判据认不出整块，下面全绿也什么都没证明
        assertEquals(1.0, filled(List.of(new double[]{0, 0, 0, 16, 16, 16})));
        assertEquals(true, filled(List.of(new double[]{0, 0, 0, 16, 16, 15})) > 0.9);
        int checked = 0;
        for (LinerProp.Kind kind : LinerProp.Kind.values()) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(kind);
            for (LinerProp.Part part : parts.isEmpty() ? java.util.Collections.<LinerProp.Part>singletonList(null) : parts) {
                List<double[]> boxes = LinerPropShapes.boxes(kind, part);
                String who = kind + (part == null ? "" : " · " + part);
                assertEquals(true, !boxes.isEmpty(), who + " 没有轮廓（点不中、也挡不住人）");
                for (double[] b : boxes) {
                    for (int i = 0; i < 3; i++) {
                        assertEquals(true, 0 <= b[i] && b[i] < b[i + 3] && b[i + 3] <= 16, who + " 的盒子越出这一格：" + java.util.Arrays.toString(b));
                    }
                }
                double f = filled(boxes);
                assertEquals(true, f < 0.9, who + " 的轮廓把一格填到了 " + Math.round(f * 100) + "%（读出来就是一整块）");
                checked++;
            }
        }
        // 正向对照：每一种、每一块都过了一遍（落地灯 2 · 台灯 1 · 大桌 4 · 沙发 2 · 椅子 1 · 吸顶灯 1 · 小吊灯 2 · 大吊灯 10
        //   · 骑缝灯两格 2 · 2 × 2 4）
        assertEquals(2 + 1 + 4 + 2 + 1 + 1 + 2 + 10 + 2 + 4, checked);
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
