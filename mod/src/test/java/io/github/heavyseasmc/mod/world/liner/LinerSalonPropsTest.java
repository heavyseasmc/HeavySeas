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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客房第三版的起居室 / 餐室家具（2026-10-16）：餐椅 · 餐边柜 2 宽 · 落地钟两格高 · 茶几 · 立式钢琴 2 宽 × 2 高 · 贵妃椅 1 × 2。
 * 纯规则（整件往哪边长、搭档、镜像）+ 方块的默认状态 + 样子指向的模板 + 多格件缺一块整件拆掉 + 轮廓只在这一格里。
 * 样子与模型的判据在 liner_props_salon.py。
 */
final class LinerSalonPropsTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    /** 跨格的四件：种类 → 点中的那一块。 */
    private static final Map<LinerProp.Kind, LinerProp.Part> ANCHORS = Map.of(
            LinerProp.Kind.SIDEBOARD, LinerProp.Part.EAST,
            LinerProp.Kind.LONGCASE_CLOCK, LinerProp.Part.LOWER,
            LinerProp.Kind.UPRIGHT_PIANO, LinerProp.Part.EAST,
            LinerProp.Kind.CHAISE_LONGUE, LinerProp.Part.FOOT);

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void piecesGrowTheSameWayAsTheirOlderSiblings() {
        // 餐边柜同写字台（两格宽）· 落地钟同衣柜 · 钢琴同书柜 · 贵妃椅同床：块与点中的那一块一模一样，船体生成器那一侧不用新的偏移表
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.WRITING_TABLE), LinerProp.Rules.parts(LinerProp.Kind.SIDEBOARD));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.WRITING_TABLE), LinerProp.Rules.anchor(LinerProp.Kind.SIDEBOARD));
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.WARDROBE), LinerProp.Rules.parts(LinerProp.Kind.LONGCASE_CLOCK));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.WARDROBE), LinerProp.Rules.anchor(LinerProp.Kind.LONGCASE_CLOCK));
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.BOOKCASE), LinerProp.Rules.parts(LinerProp.Kind.UPRIGHT_PIANO));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.BOOKCASE), LinerProp.Rules.anchor(LinerProp.Kind.UPRIGHT_PIANO));
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.BED), LinerProp.Rules.parts(LinerProp.Kind.CHAISE_LONGUE));
        assertEquals(LinerProp.Rules.anchor(LinerProp.Kind.BED), LinerProp.Rules.anchor(LinerProp.Kind.CHAISE_LONGUE));
        assertEquals(Set.of(), LinerProp.Rules.parts(LinerProp.Kind.DINING_CHAIR), "餐椅一格");
        assertEquals(Set.of(), LinerProp.Rules.parts(LinerProp.Kind.TEA_TABLE), "茶几一格");
        // 人站在南边、面朝北摆（正面朝着人 = 朝南）：点中的那一格往人的右手（东）、上面、远处（北）长
        BlockPos at = new BlockPos(5, 64, -9);
        Direction facing = Direction.SOUTH;
        Map<LinerProp.Kind, Set<BlockPos>> want = Map.of(
                LinerProp.Kind.SIDEBOARD, Set.of(at, at.east()),
                LinerProp.Kind.LONGCASE_CLOCK, Set.of(at, at.up()),
                LinerProp.Kind.UPRIGHT_PIANO, Set.of(at, at.east(), at.up(), at.east().up()),
                LinerProp.Kind.CHAISE_LONGUE, Set.of(at, at.north()));
        for (Map.Entry<LinerProp.Kind, LinerProp.Part> e : ANCHORS.entrySet()) {
            LinerProp.Kind k = e.getKey();
            assertEquals(e.getValue(), LinerProp.Rules.anchor(k), k + " 点中的那一块");
            Set<BlockPos> got = new HashSet<>();
            for (LinerProp.Part p : LinerProp.Rules.parts(k)) {
                got.add(LinerProp.Rules.offset(at, e.getValue(), p, facing));
            }
            assertEquals(want.get(k), got, k + " 整件长在哪几格");
            for (Direction f : HORIZONTAL) {
                assertEquals(LinerProp.Rules.parts(k), reachable(e.getValue(), f, LinerProp.Rules.parts(k)), k + " 朝 " + f + " 断成了几截");
            }
        }
        // 贵妃椅的头端（卷起来的那一头）在脚的远处（同床，liner_build.HEAD_DIR）
        assertEquals(at.north(), LinerProp.Rules.offset(at, LinerProp.Part.FOOT, LinerProp.Part.HEAD, facing));
        for (LinerProp.Kind k : List.of(LinerProp.Kind.DINING_CHAIR, LinerProp.Kind.SIDEBOARD, LinerProp.Kind.LONGCASE_CLOCK,
                LinerProp.Kind.TEA_TABLE, LinerProp.Kind.UPRIGHT_PIANO, LinerProp.Kind.CHAISE_LONGUE)) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertEquals(false, LinerProp.Rules.hanging(k), k + " 不挂天花");
            // ADR-0109 ①：餐椅、贵妃椅右键坐；餐边柜、落地钟、茶几、钢琴右键没有坐这一条路
            boolean sit = k == LinerProp.Kind.DINING_CHAIR || k == LinerProp.Kind.CHAISE_LONGUE;
            assertEquals(sit ? LinerProp.Use.SIT : LinerProp.Rules.opens(k) ? LinerProp.Use.CABINET : LinerProp.Use.OTHER, LinerProp.Rules.use(k),       // §2：餐边柜开合
                    k + (sit ? " 有座位，右键坐" : " 右键没有坐这一条路"));
        }
    }

    @Test
    void everyPartFindsItsPartnersAndMirroringKeepsThePieceWhole() {
        for (Map.Entry<LinerProp.Kind, LinerProp.Part> e : ANCHORS.entrySet()) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(e.getKey());
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
                    assertEquals(touching, partners, e.getKey() + " · " + here + " 朝 " + facing);
                    assertEquals(e.getKey() == LinerProp.Kind.UPRIGHT_PIANO ? 2 : 1, partners, e.getKey() + " · " + here + " 的搭档数");
                }
                Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
                for (LinerProp.Part a : parts) {
                    assertTrue(parts.contains(LinerProp.Rules.mirrored(a)), a + " 照镜子之后不在这一件里");
                    for (LinerProp.Part b : parts) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Rules.mirrored(a),
                                LinerProp.Rules.mirrored(b), mirroredFacing), e.getKey() + " · " + a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
        // 两格宽的那两件照镜子要左右对调；竖着长、往远处长的那两件照镜子不换块
        assertNotEquals(LinerProp.Part.EAST, LinerProp.Rules.mirrored(LinerProp.Part.EAST));
        assertEquals(LinerProp.Part.LOWER, LinerProp.Rules.mirrored(LinerProp.Part.LOWER));
        assertEquals(LinerProp.Part.FOOT, LinerProp.Rules.mirrored(LinerProp.Part.FOOT));
    }

    @Test
    void defaultStatesFaceNorthAndStartFromTheAnchor() {
        // 方块的默认状态（物品栏里拿出来、/setblock 不带属性时就是它）：朝北、点中的那一块；没有灯的属性
        Map<LinerProp, LinerProp.Part> multi = Map.of(LinerProps.SIDEBOARD, LinerProp.Part.EAST, LinerProps.LONGCASE_CLOCK, LinerProp.Part.LOWER,
                LinerProps.UPRIGHT_PIANO, LinerProp.Part.EAST, LinerProps.CHAISE_LONGUE, LinerProp.Part.FOOT);
        for (Map.Entry<LinerProp, LinerProp.Part> e : multi.entrySet()) {
            BlockState s = e.getKey().getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), e.getKey().spec().model() + " 默认朝北");
            assertEquals(e.getValue(), s.get(part(s)), e.getKey().spec().model() + " 默认是点中的那一块");
            assertEquals(false, s.contains(LinerProp.LIT), e.getKey().spec().model() + " 不是灯，没有 lit");
        }
        for (LinerProp p : List.of(LinerProps.DINING_CHAIR, LinerProps.TEA_TABLE)) {
            BlockState s = p.getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING));
            assertEquals(1, s.getProperties().size(), p.spec().model() + " 一格，只有朝向一个属性");
        }
    }

    @Test
    void looksPointAtTheirOwnTemplatesAndTexture() {
        // 每一块的模板名 = 生成器写出的那一份（liner_props_salon.py 的 <件>_<格>），贴图整件一张
        assertLooks(LinerProps.SIDEBOARD, "prop/rig_sideboard_east", "prop/rig_sideboard_west");   // 能开合：方块只画柜身（ADR-0109 §2 第二版）
        assertLooks(LinerProps.LONGCASE_CLOCK, "prop/longcase_clock_lower", "prop/longcase_clock_upper");
        assertLooks(LinerProps.UPRIGHT_PIANO, "prop/upright_piano_east", "prop/upright_piano_upper_east",
                "prop/upright_piano_upper_west", "prop/upright_piano_west");
        assertLooks(LinerProps.CHAISE_LONGUE, "prop/chaise_longue_foot", "prop/chaise_longue_head");
        assertLooks(LinerProps.DINING_CHAIR, "prop/dining_chair");
        assertLooks(LinerProps.TEA_TABLE, "prop/tea_table");
        for (LinerProp p : List.of(LinerProps.SIDEBOARD, LinerProps.LONGCASE_CLOCK, LinerProps.UPRIGHT_PIANO, LinerProps.CHAISE_LONGUE)) {
            assertNotNull(p.itemLook(), p.spec().model() + " 跨格，物品是整件缩小的那一块");
            assertEquals("prop/" + p.spec().model() + "_item", p.itemLook().template());
        }
        assertEquals(null, LinerProps.DINING_CHAIR.itemLook(), "餐椅一格，物品用方块模型");
        assertEquals(null, LinerProps.TEA_TABLE.itemLook(), "茶几一格，物品用方块模型");
    }

    @Test
    void losingAnyPartTakesTheWholePieceDown() {
        // 多格件：任何一块的搭档不在了（被拆 / 换掉），这一块就变成空气；搭档还在（同一件、同朝向、是该在那里的那一块）就不动
        for (Map.Entry<LinerProp.Kind, LinerProp.Part> e : ANCHORS.entrySet()) {
            LinerProp block = blockOf(e.getKey());
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(e.getKey());
            int checked = 0;
            for (Direction facing : HORIZONTAL) {
                for (LinerProp.Part here : parts) {
                    BlockState s = block.getDefaultState().with(LinerProp.FACING, facing).with(part(block.getDefaultState()), here);
                    for (Direction d : Direction.values()) {
                        LinerProp.Part partner = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (partner == null) {
                            continue;
                        }
                        BlockPos at = BlockPos.ORIGIN.offset(d);
                        BlockState gone = block.getStateForNeighborUpdate(s, d, Blocks.AIR.getDefaultState(), null, BlockPos.ORIGIN, at);
                        assertTrue(gone.isAir(), e.getKey() + " · " + here + " 朝 " + facing + "：" + d + " 那一块没了，这一块还在");
                        BlockState mate = s.with(part(s), partner);
                        assertSame(s, block.getStateForNeighborUpdate(s, d, mate, null, BlockPos.ORIGIN, at),
                                e.getKey() + " · " + here + "：搭档好好的，这一块却变了");
                        BlockState wrong = s.with(part(s), here);
                        assertTrue(block.getStateForNeighborUpdate(s, d, wrong, null, BlockPos.ORIGIN, at).isAir(),
                                e.getKey() + " · " + here + "：那一格是同一种方块、却不是该在那里的那一块，也要拆");
                        checked++;
                    }
                }
            }
            assertTrue(checked >= 4 * parts.size(), e.getKey() + " 只核对了 " + checked + " 处 —— 没在查");
        }
    }

    @Test
    void outlinesStayInsideTheirOwnCell() {
        // 餐椅的冠头、贵妃椅的卷枕、餐边柜上的烛台伸进上一格的那一截只在模型里：轮廓（也是碰撞箱）一律只到这一格顶
        for (LinerProp.Kind k : List.of(LinerProp.Kind.DINING_CHAIR, LinerProp.Kind.SIDEBOARD, LinerProp.Kind.LONGCASE_CLOCK,
                LinerProp.Kind.TEA_TABLE, LinerProp.Kind.UPRIGHT_PIANO, LinerProp.Kind.CHAISE_LONGUE)) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(k);
            for (LinerProp.Part p : parts.isEmpty() ? java.util.Collections.<LinerProp.Part>singletonList(null) : parts) {
                for (double[] b : LinerPropShapes.boxes(k, p)) {
                    assertTrue(b[4] <= 16 && b[1] >= 0, k + " · " + p + " 的盒子越出这一格：" + java.util.Arrays.toString(b));
                }
            }
        }
        // 正向对照：餐椅靠背那一块确实顶到这一格的顶（16），不是被别处截掉的
        double top = LinerPropShapes.boxes(LinerProp.Kind.DINING_CHAIR, null).stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertEquals(16.0, top, 1e-9);
    }

    private static LinerProp blockOf(LinerProp.Kind k) {
        return switch (k) {
            case SIDEBOARD -> LinerProps.SIDEBOARD;
            case LONGCASE_CLOCK -> LinerProps.LONGCASE_CLOCK;
            case UPRIGHT_PIANO -> LinerProps.UPRIGHT_PIANO;
            case CHAISE_LONGUE -> LinerProps.CHAISE_LONGUE;
            default -> throw new IllegalArgumentException(k.toString());
        };
    }

    private static void assertLooks(LinerProp block, String... want) {
        Set<String> got = new java.util.TreeSet<>();
        for (BlockState s : block.getStateManager().getStates()) {
            if (s.get(LinerProp.FACING) == Direction.NORTH) {
                LinerLooks.Look l = block.look(s);
                got.add(l.template());
                String rig = LinerProp.Rules.opens(block.spec().kind()) ? "rig_" : "";     // 柜子的柜身用 rig_ 那一张（另见 LinerCabinetsTest）
                assertEquals("prop/" + rig + block.spec().texture(), l.textures().get("b"), block.spec().model());
            }
        }
        assertEquals(new java.util.TreeSet<>(List.of(want)), got, block.spec().model());
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
    @SuppressWarnings("unchecked")
    private static net.minecraft.state.property.EnumProperty<LinerProp.Part> part(BlockState s) {
        return (net.minecraft.state.property.EnumProperty<LinerProp.Part>) s.getProperties().stream()
                .filter(p -> p.getName().equals("part")).findFirst().orElseThrow();
    }
}
