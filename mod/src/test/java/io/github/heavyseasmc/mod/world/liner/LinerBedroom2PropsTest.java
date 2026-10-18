package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
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
 * 客房第三版的卧室 / 门厅杂件（2026-10-16）：床尾凳两格宽 · 五斗柜一格 · 穿衣镜两格高 · 衣帽架两格高 · 旅行皮箱一格。
 * 纯规则（整件往哪边长、搭档、拆一格整件没靠的那条链、镜像）+ 方块的默认状态 + 轮廓。样子与模型的判据在 liner_props_bedroom2.py。
 */
final class LinerBedroom2PropsTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final List<LinerProp.Kind> TALL = List.of(LinerProp.Kind.CHEVAL_MIRROR, LinerProp.Kind.COAT_STAND);
    private static final List<LinerProp.Kind> SINGLE = List.of(LinerProp.Kind.CHEST_OF_DRAWERS, LinerProp.Kind.STEAMER_TRUNK);
    private static final List<LinerProp.Kind> ALL = List.of(LinerProp.Kind.BED_BENCH, LinerProp.Kind.CHEST_OF_DRAWERS,
            LinerProp.Kind.CHEVAL_MIRROR, LinerProp.Kind.COAT_STAND, LinerProp.Kind.STEAMER_TRUNK);

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void benchGrowsLikeTheWritingTableAndTheTallOnesLikeTheWardrobe() {
        // 人站在南边、面朝北摆（正面朝着人 = 朝南）：床尾凳点中的是人左手那一块（EAST），往人的右手（东）长一格
        BlockPos at = new BlockPos(5, 64, -9);
        Direction facing = Direction.SOUTH;
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.WRITING_TABLE), LinerProp.Rules.parts(LinerProp.Kind.BED_BENCH));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.WRITING_TABLE), LinerProp.Rules.anchor(LinerProp.Kind.BED_BENCH));
        assertEquals(LinerProp.Part.EAST, LinerProp.Rules.anchor(LinerProp.Kind.BED_BENCH));
        Set<BlockPos> bench = new HashSet<>();
        for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.BED_BENCH)) {
            bench.add(LinerProp.Rules.offset(at, LinerProp.Part.EAST, p, facing));
        }
        assertEquals(Set.of(at, at.east()), bench);
        assertEquals(at.east(), LinerProp.Rules.offset(at, LinerProp.Part.EAST, LinerProp.Part.WEST, facing));
        // 两格高的两件：点中的是下面那一格，往上长一格（同衣柜）
        for (LinerProp.Kind k : TALL) {
            assertEquals(LinerProp.Rules.parts(LinerProp.Kind.WARDROBE), LinerProp.Rules.parts(k), k + " 同衣柜那两块");
            assertEquals(LinerProp.Part.LOWER, LinerProp.Rules.anchor(k), k + " 点中的是下面那一格");
            for (Direction f : HORIZONTAL) {
                assertEquals(at.up(), LinerProp.Rules.offset(at, LinerProp.Part.LOWER, LinerProp.Part.UPPER, f), k + " 朝 " + f);
            }
        }
        for (LinerProp.Kind k : SINGLE) {
            assertEquals(Set.of(), LinerProp.Rules.parts(k), k + " 一格");
        }
        for (LinerProp.Kind k : ALL) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertEquals(false, LinerProp.Rules.hanging(k), k + " 不挂天花");
            // ADR-0109 ①：床尾凳右键坐（两座）；别的右键没有自己的那条路
            boolean sit = k == LinerProp.Kind.BED_BENCH;
            assertEquals(sit ? LinerProp.Use.SIT : LinerProp.Rules.opens(k) ? LinerProp.Use.CABINET : LinerProp.Use.OTHER, LinerProp.Rules.use(k),       // §2：五斗柜、皮箱开合
                    k + (sit ? " 有座位，右键坐" : " 右键没有自己的那条路"));
        }
    }

    @Test
    void breakingAnyCellTakesTheWholePieceAndMirroringKeepsItWhole() {
        // 拆一格整件没：每一格认得自己的搭档该在哪一格（一格传一格），所以从锚点经搭档走得到每一块；每一块挨着几块就是几个搭档
        for (LinerProp.Kind k : List.of(LinerProp.Kind.BED_BENCH, LinerProp.Kind.CHEVAL_MIRROR, LinerProp.Kind.COAT_STAND)) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(k);
            assertEquals(2, parts.size(), k + " 两块");
            for (Direction facing : HORIZONTAL) {
                assertEquals(parts, reachable(LinerProp.Rules.anchor(k), facing, parts), k + " 朝 " + facing + " 断成了几截");
                for (LinerProp.Part here : parts) {
                    int partners = 0;
                    for (Direction d : Direction.values()) {
                        LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (p != null) {
                            partners++;
                            assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing), k + " · " + here + " 朝 " + facing);
                        }
                    }
                    assertEquals(1, partners, k + " · " + here + " 朝 " + facing + " 只挨着另一块");
                }
                Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
                for (LinerProp.Part a : parts) {
                    assertTrue(parts.contains(LinerProp.Rules.mirrored(a)), k + " · " + a + " 照镜子之后不在这一件里");
                    for (LinerProp.Part b : parts) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Rules.mirrored(a),
                                LinerProp.Rules.mirrored(b), mirroredFacing), k + " · " + a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
        // 床尾凳照镜子左右对调；两格高的上下不换
        assertEquals(LinerProp.Part.WEST, LinerProp.Rules.mirrored(LinerProp.Part.EAST));
        assertNotEquals(LinerProp.Part.EAST, LinerProp.Rules.mirrored(LinerProp.Part.EAST));
        assertEquals(LinerProp.Part.UPPER, LinerProp.Rules.mirrored(LinerProp.Part.UPPER));
    }

    @Test
    void defaultStatesFaceNorthAndStartFromTheAnchor() {
        // 方块的默认状态（物品栏里拿出来、/setblock 不带属性时就是它）：朝北、点中的那一块；没有灯、桌子那几个属性
        for (LinerProp b : List.of(LinerProps.BED_BENCH, LinerProps.CHEVAL_MIRROR, LinerProps.COAT_STAND)) {
            BlockState s = b.getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), b + " 默认朝北");
            assertEquals(LinerProp.Rules.anchor(b.spec().kind()), s.get(partOf(s)), b + " 默认是点中的那一块");
            assertEquals(2, s.getProperties().size(), b + " 只有朝向与哪一块两个属性");
        }
        for (LinerProp b : List.of(LinerProps.CHEST_OF_DRAWERS, LinerProps.STEAMER_TRUNK)) {
            BlockState s = b.getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), b + " 默认朝北");
            assertEquals(1, s.getProperties().size() - (s.contains(LinerProp.OPEN) ? 1 : 0), b + " 只有朝向一个属性（柜子另有开合，ADR-0109 §2）");
        }
    }

    @Test
    void outlinesStayInsideTheCellAndLeaveTheTallOnesReachableFromBothCells() {
        for (LinerProp.Kind k : ALL) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(k);
            for (LinerProp.Part part : parts.isEmpty() ? java.util.Collections.<LinerProp.Part>singletonList(null) : parts) {
                List<double[]> boxes = LinerPropShapes.boxes(k, part);
                assertTrue(!boxes.isEmpty(), k + " · " + part + " 没有轮廓");
                for (double[] b : boxes) {
                    for (int i = 0; i < 3; i++) {
                        assertTrue(0 <= b[i] && b[i] < b[i + 3] && b[i + 3] <= 16, k + " · " + part + " 的盒子越出这一格：" + java.util.Arrays.toString(b));
                    }
                }
            }
        }
        // 五斗柜：台面上的首饰盒与相框不碰格子顶（台面高 14、摆件到 15.75）；旅行皮箱连手提箱不到一格高
        double chestTop = LinerPropShapes.boxes(LinerProp.Kind.CHEST_OF_DRAWERS, null).stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertEquals(15.75, chestTop, 1e-9);
        double trunkTop = LinerPropShapes.boxes(LinerProp.Kind.STEAMER_TRUNK, null).stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertTrue(trunkTop < 16, "旅行皮箱的轮廓伸到了上面一格");
        // 穿衣镜、衣帽架上面那一格也有轮廓，而且从这一格的底边接着下面那一格（整件是连着的，不是两截）
        for (LinerProp.Kind k : TALL) {
            double low = LinerPropShapes.boxes(k, LinerProp.Part.UPPER).stream().mapToDouble(b -> b[1]).min().orElseThrow();
            assertEquals(0, low, 1e-9, k + " 上面那一格的轮廓没接着下面那一格");
        }
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

    @SuppressWarnings("unchecked")
    private static net.minecraft.state.property.EnumProperty<LinerProp.Part> partOf(BlockState s) {
        return (net.minecraft.state.property.EnumProperty<LinerProp.Part>) s.getProperties().stream()
                .filter(p -> p.getName().equals("part")).findFirst().orElseThrow();
    }
}
