package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客房第二版的卧室道具（2026-10-15）：四柱床 2 × 2 × 3 · 黄铜双人床 2 × 2 · 床头柜（台灯放得上去）· 梳妆台 2 宽 × 2 高 · 屏风两格高。
 * 纯规则（整件往哪边长、搭档、镜像）+ 方块的默认状态 + 台灯在床头柜上（下沉、不挪）。样子与模型的判据在 liner_props_bedroom.py。
 */
final class LinerBedroomPropsTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final Set<LinerProp.Part> FOUR_POSTER = LinerProp.Rules.parts(LinerProp.Kind.FOUR_POSTER_BED);
    private static final Set<LinerProp.Part> DOUBLE = LinerProp.Rules.parts(LinerProp.Kind.DOUBLE_BED);

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void bedsGrowFromTheFootToThePlacersRightTowardTheHeadAndUp() {
        // 人站在南边、面朝北摆（正面 = 床尾朝着人 = 朝南）：点中的那一格是床尾人左手那一块，往人的右手（东）、远处（北，床头）与上面长
        BlockPos at = new BlockPos(7, 64, -3);
        Direction facing = Direction.SOUTH;
        for (LinerProp.Kind k : List.of(LinerProp.Kind.FOUR_POSTER_BED, LinerProp.Kind.DOUBLE_BED)) {
            assertEquals(LinerProp.Part.FOOT_EAST, LinerProp.Rules.anchor(k), k + " 点中的是床尾人左手那一块");
        }
        Set<BlockPos> four = new HashSet<>();
        for (LinerProp.Part p : FOUR_POSTER) {
            four.add(LinerProp.Rules.offset(at, LinerProp.Part.FOOT_EAST, p, facing));
        }
        Set<BlockPos> want = new HashSet<>();
        for (int dy = 0; dy < 3; dy++) {
            for (BlockPos q : List.of(at, at.east(), at.north(), at.east().north())) {
                want.add(q.up(dy));
            }
        }
        assertEquals(12, FOUR_POSTER.size());
        assertEquals(want, four);
        // 床头在床尾的远处（liner_build.HEAD_DIR：床头在模型 +z）；床尾的西半在人的右手
        assertEquals(at.north(), LinerProp.Rules.offset(at, LinerProp.Part.FOOT_EAST, LinerProp.Part.HEAD_EAST, facing));
        assertEquals(at.east(), LinerProp.Rules.offset(at, LinerProp.Part.FOOT_EAST, LinerProp.Part.FOOT_WEST, facing));
        assertEquals(at.east().north().up(2), LinerProp.Rules.offset(at, LinerProp.Part.FOOT_EAST, LinerProp.Part.TOP_HEAD_WEST, facing));
        // 双人床就是四柱床下面那一层
        assertEquals(EnumSet.of(LinerProp.Part.FOOT_WEST, LinerProp.Part.FOOT_EAST, LinerProp.Part.HEAD_WEST, LinerProp.Part.HEAD_EAST), DOUBLE);
        for (LinerProp.Part p : FOUR_POSTER) {
            assertEquals(p.y == 0, DOUBLE.contains(p), p + " 在不在双人床里");
        }
        for (Direction f : HORIZONTAL) {
            assertEquals(FOUR_POSTER, reachable(LinerProp.Part.FOOT_EAST, f, FOUR_POSTER), "四柱床朝 " + f + " 断成了几截");
            assertEquals(DOUBLE, reachable(LinerProp.Part.FOOT_EAST, f, DOUBLE), "双人床朝 " + f + " 断成了几截");
        }
    }

    @Test
    void dressingTableGrowsLikeTheBookcaseAndTheScreenLikeTheWardrobe() {
        BlockPos at = new BlockPos(-2, 70, 11);
        Direction facing = Direction.SOUTH;
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.BOOKCASE), LinerProp.Rules.parts(LinerProp.Kind.DRESSING_TABLE));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.BOOKCASE), LinerProp.Rules.anchor(LinerProp.Kind.DRESSING_TABLE));
        Set<BlockPos> table = new HashSet<>();
        for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.DRESSING_TABLE)) {
            table.add(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.DRESSING_TABLE), p, facing));
        }
        assertEquals(Set.of(at, at.east(), at.up(), at.east().up()), table);
        assertEquals(EnumSet.of(LinerProp.Part.LOWER, LinerProp.Part.UPPER), LinerProp.Rules.parts(LinerProp.Kind.FOLDING_SCREEN));
        assertEquals(LinerProp.Part.LOWER, LinerProp.Rules.anchor(LinerProp.Kind.FOLDING_SCREEN));
        assertEquals(Set.of(), LinerProp.Rules.parts(LinerProp.Kind.NIGHTSTAND), "床头柜一格");
        for (LinerProp.Kind k : List.of(LinerProp.Kind.FOUR_POSTER_BED, LinerProp.Kind.DOUBLE_BED, LinerProp.Kind.NIGHTSTAND,
                LinerProp.Kind.DRESSING_TABLE, LinerProp.Kind.FOLDING_SCREEN)) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertEquals(false, LinerProp.Rules.hanging(k), k + " 不挂天花");
            // ADR-0109 ①：两张床右键坐在床沿（不能睡）；床头柜、梳妆台、屏风右键没有自己的那条路
            boolean bed = k == LinerProp.Kind.FOUR_POSTER_BED || k == LinerProp.Kind.DOUBLE_BED;
            assertEquals(bed ? LinerProp.Use.SIT : LinerProp.Rules.opens(k) ? LinerProp.Use.CABINET : LinerProp.Use.OTHER, LinerProp.Rules.use(k),       // §2：床头柜、梳妆台开合
                    k + (bed ? " 有座位，右键坐在床沿（不能睡）" : " 右键没有自己的那条路"));
        }
    }

    @Test
    void everyBedPartFindsItsPartnersAndMirroringKeepsTheBedWhole() {
        for (Set<LinerProp.Part> parts : List.of(FOUR_POSTER, DOUBLE)) {
            for (Direction facing : HORIZONTAL) {
                for (LinerProp.Part here : parts) {
                    int partners = 0;
                    for (Direction d : Direction.values()) {
                        LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (p != null) {
                            partners++;
                            assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing), here + " 朝 " + facing);
                        }
                    }
                    int touching = 0;
                    for (LinerProp.Part q : parts) {
                        touching += Math.abs(q.x - here.x) + Math.abs(q.y - here.y) + Math.abs(q.z - here.z) == 1 ? 1 : 0;
                    }
                    assertEquals(touching, partners, here + " 朝 " + facing);
                    // 双人床每一块挨着两块；四柱床最下 / 最上一层每块三个搭档、中间一层四个
                    assertEquals(parts == DOUBLE ? 2 : here.y == 1 ? 4 : 3, partners, here + " 朝 " + facing);
                }
                Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
                for (LinerProp.Part a : parts) {
                    assertTrue(parts.contains(LinerProp.Rules.mirrored(a)), a + " 照镜子之后不在这一件里");
                    assertNotEquals(a, LinerProp.Rules.mirrored(a), a + " 照镜子要左右对调");
                    for (LinerProp.Part b : parts) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Rules.mirrored(a),
                                LinerProp.Rules.mirrored(b), mirroredFacing), a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
        assertEquals(LinerProp.Part.CENTER, LinerProp.Rules.mirrored(LinerProp.Part.CENTER), "台灯在床头柜上不挪：照镜子也不挪");
    }

    @Test
    void defaultStatesFaceNorthAndStartFromTheAnchor() {
        // 方块的默认状态（物品栏里拿出来、/setblock 不带属性时就是它）：朝北、点中的那一块；没有灯的属性
        for (LinerProp b : List.of(LinerProps.FOUR_POSTER_BED, LinerProps.DOUBLE_BED, LinerProps.DRESSING_TABLE, LinerProps.FOLDING_SCREEN)) {
            BlockState s = b.getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), b + " 默认朝北");
            assertEquals(LinerProp.Rules.anchor(b.spec().kind()), s.get(EnumPropertyOf.part(s)), b + " 默认是点中的那一块");
            assertEquals(false, s.contains(LinerProp.LIT), b + " 不是灯，没有 lit");
        }
        BlockState stand = LinerProps.NIGHTSTAND.getDefaultState();
        assertEquals(Direction.NORTH, stand.get(LinerProp.FACING));
        assertEquals(2, stand.getProperties().size(), "床头柜只有朝向与开合（ADR-0109 §2）两个属性");
        assertEquals(false, stand.get(LinerProp.OPEN), "床头柜默认关着");
        // 台灯拿出来不在桌上（BooleanProperty 默认 true —— 构造里显式改 false，证伪表 2026-10-07 那一行）
        BlockState lamp = LinerProps.TABLE_LAMP.getDefaultState();
        assertEquals(false, lamp.get(LinerProp.ON_TABLE));
        assertEquals(LinerProp.Part.NW, lamp.get(LinerProp.TABLE_CORNER));
    }

    @Test
    void tableLampSitsCentredOnTheNightstandTop() {
        // 正下方是床头柜：算「在桌上」、不挪（CENTER）；拿走床头柜（正下方是空气）回到不在桌上
        BlockState lamp = LinerProps.TABLE_LAMP.getDefaultState().with(LinerProp.FACING, Direction.EAST);
        BlockState on = LinerProp.onTable(lamp, LinerProps.NIGHTSTAND.getDefaultState());
        assertEquals(true, on.get(LinerProp.ON_TABLE));
        assertEquals(LinerProp.Part.CENTER, on.get(LinerProp.TABLE_CORNER));
        BlockState off = LinerProp.onTable(on, Blocks.AIR.getDefaultState());
        assertEquals(false, off.get(LinerProp.ON_TABLE));
        // 别的家具（书柜）不算桌子
        assertEquals(false, LinerProp.onTable(lamp, LinerProps.BOOKCASE.getDefaultState()).get(LinerProp.ON_TABLE));
        // 下沉多少与柜面一致：床头柜轮廓最高的那一块顶 = 16 − STAND_SINK（台灯模型在 liner_props_bedroom 里由同一个数下沉，判据也核这一行）
        double top = LinerPropShapes.boxes(LinerProp.Kind.NIGHTSTAND, null).stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertEquals(16 - LinerProp.STAND_SINK, top, 1e-9, "台灯放在床头柜上会悬空或陷进柜面");
        // 台灯底座（轮廓最下面那一块）整块落在柜面上
        double[] base = LinerPropShapes.boxes(LinerProp.Kind.TABLE_LAMP, null).get(0);
        double[] slab = LinerPropShapes.boxes(LinerProp.Kind.NIGHTSTAND, null).stream().filter(b -> b[4] == top).findFirst().orElseThrow();
        assertTrue(slab[0] <= base[0] && base[3] <= slab[3] && slab[2] <= base[2] && base[5] <= slab[5], "台灯底座伸出柜面");
    }

    /** 从 from 那一块起，经「搭档在哪一格」一格传一格走得到的块（同 LinerPropRulesTest）。 */
    private static Set<LinerProp.Part> reachable(LinerProp.Part from, Direction facing, Set<LinerProp.Part> parts) {
        Set<LinerProp.Part> seen = EnumSet.of(from);
        java.util.ArrayDeque<LinerProp.Part> todo = new java.util.ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            LinerProp.Part here = todo.pop();
            for (Direction d : Direction.values()) {
                LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                if (p != null && seen.add(p)) {
                    todo.push(p);
                }
            }
        }
        return seen;
    }

    /** 方块状态里那一个叫 part 的属性（各种件的 part 属性取值不一样，按名字找）。 */
    private static final class EnumPropertyOf {
        @SuppressWarnings("unchecked")
        static net.minecraft.state.property.EnumProperty<LinerProp.Part> part(BlockState s) {
            return (net.minecraft.state.property.EnumProperty<LinerProp.Part>) s.getProperties().stream()
                    .filter(p -> p.getName().equals("part")).findFirst().orElseThrow();
        }
    }
}
