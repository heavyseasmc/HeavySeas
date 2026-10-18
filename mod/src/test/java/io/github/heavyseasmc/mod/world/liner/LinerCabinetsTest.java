package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 柜子开合（ADR-0109 §2）：衣柜 · 五斗柜 · 餐边柜 · 床头柜 · 旅行皮箱 · 梳妆台右键开 / 合（只有样子与状态，不储物）。
 * 纯规则（哪几种开合、右键走哪条路）+ 方块状态（多了 open、默认关着）+ 开着时样子指向 docs 出的那一套（open_ 模板与贴图，文件都在）。
 * 开着的模型本身（不穿进邻格与墙 · 门不插进柜身 · 动的那几块还连着柜身）由 docs 的 liner_props_cabinets.py 判。
 */
final class LinerCabinetsTest {

    private static final Set<LinerProp.Kind> CABINETS = EnumSet.of(LinerProp.Kind.WARDROBE, LinerProp.Kind.CHEST_OF_DRAWERS,
            LinerProp.Kind.SIDEBOARD, LinerProp.Kind.NIGHTSTAND, LinerProp.Kind.STEAMER_TRUNK, LinerProp.Kind.DRESSING_TABLE);
    /** 六件的方块：不能放进静态字段 —— 类初始化在 {@link #bootstrap} 之前，单独跑这一个类时会「Not bootstrapped」（红测时撞上的）。 */
    private static List<LinerProp> blocks() {
        return List.of(LinerProps.WARDROBE, LinerProps.CHEST_OF_DRAWERS, LinerProps.SIDEBOARD,
                LinerProps.NIGHTSTAND, LinerProps.STEAMER_TRUNK, LinerProps.DRESSING_TABLE);
    }
    /** 模组资源（测试从 mod/ 起跑）：模板与贴图由 liner_props.py --write 写在这里。 */
    private static final Path RES = Path.of("src/main/resources/assets/heavyseas");

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void exactlyTheSixCabinetsOpenAndTheyRouteToTheCabinetPath() {
        Set<LinerProp.Kind> got = EnumSet.noneOf(LinerProp.Kind.class);
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            if (LinerProp.Rules.opens(k)) {
                got.add(k);
                assertEquals(LinerProp.Use.CABINET, LinerProp.Rules.use(k), k + " 右键开合");
                assertTrue(LinerProp.Rules.seats(k).isEmpty(), k + " 是柜子，不该有座位（两条路会抢）");
            } else {
                assertFalse(LinerProp.Rules.use(k) == LinerProp.Use.CABINET, k + " 不是柜子，却走开合那条路");
            }
        }
        assertEquals(CABINETS, got);
    }

    @Test
    void cabinetsCarryAnOpenStateThatDefaultsToClosed() {
        for (LinerProp b : blocks()) {
            BlockState s = b.getDefaultState();
            assertTrue(s.contains(LinerProp.OPEN), b.spec().model() + " 有 open 属性");
            // 布尔属性的默认值是 true（ENGINEERING 证伪表：新加一个布尔方块属性，默认就是 false —— 错的）：这里核构造器确实明写了关着
            assertEquals(false, s.get(LinerProp.OPEN), b.spec().model() + " 默认关着（物品栏拿出来、/setblock 不带属性、结构里没写时）");
        }
        // 别的家具没有这个属性（空心桅杆的门另算）
        for (LinerProp b : List.of(LinerProps.BOOKCASE, LinerProps.WASHSTAND, LinerProps.LONGCASE_CLOCK, LinerProps.BED)) {
            assertFalse(b.getDefaultState().contains(LinerProp.OPEN), b.spec().model() + " 不开合");
        }
    }

    @Test
    void bothStatesShowTheCarcassAndTheGeckoLibFilesExist() {
        int checked = 0;
        for (LinerProp b : blocks()) {
            String m = b.spec().model();
            for (BlockState s : b.getStateManager().getStates()) {
                LinerLooks.Look l = b.look(s);
                LinerLooks.Look other = b.look(s.with(LinerProp.OPEN, !s.get(LinerProp.OPEN)));
                // 开合只换 GeckoLib 那几根骨骼的姿势：方块模型（柜身）两种状态一模一样 —— 不再换模型，也就不会一开一合闪一下
                assertEquals(other, l, m + "：开着与关着是同一块柜身");
                assertEquals("prop/rig_" + b.spec().texture(), l.textures().get("b"), m + " 柜身用 rig_ 那一张贴图");
                assertTrue(l.template().startsWith("prop/rig_" + m), m + " 柜身模板 " + l.template());
                assertTrue(Files.isRegularFile(RES.resolve("models/" + LinerLooks.TEMPLATE_DIR + l.template() + ".json")),
                        l.template() + " 的模板文件不在（没跑 liner_props.py --write？）");
                checked++;
            }
            assertTrue(Files.isRegularFile(RES.resolve("textures/" + LinerLooks.TEXTURE_DIR + "prop/rig_" + b.spec().texture() + ".png")), m + " 柜身贴图不在");
            assertTrue(Files.isRegularFile(RES.resolve("geo/block/liner/" + m + ".geo.json")), m + " 的 GeckoLib 模型不在");
            assertTrue(Files.isRegularFile(RES.resolve("animations/block/liner/" + m + ".animation.json")), m + " 的 GeckoLib 动画不在");
        }
        assertTrue(checked >= 6 * 8, "只核对了 " + checked + " 个状态 —— 没在查");
    }

    @Test
    void theAnimationsTheBlockEntityAsksForAreInTheFiles() throws java.io.IOException {
        java.util.Set<String> wanted = new TreeSet<>();
        for (boolean first : new boolean[]{true, false}) {
            for (boolean open : new boolean[]{true, false}) {
                for (boolean tight : new boolean[]{true, false}) {
                    LinerCabinetEntity.pick(first, open, tight).getAnimationStages().forEach(st -> wanted.add(st.animationName()));
                }
            }
        }
        assertEquals(Set.of("open", "close", "opened", "closed", "open_tight", "close_tight", "opened_tight"), wanted);
        for (LinerProp b : blocks()) {
            String text = Files.readString(RES.resolve("animations/block/liner/" + b.spec().model() + ".animation.json"));
            for (String a : wanted) {
                assertTrue(text.contains("\"" + a + "\""), b.spec().model() + " 的动画文件里没有 " + a);
            }
        }
    }

    @Test
    void onlyThePieceOriginCarriesTheBlockEntity() {
        int with = 0;
        for (LinerProp b : blocks()) {
            for (BlockState s : b.getStateManager().getStates()) {
                LinerProp.Part p = b.partOf(s);
                boolean origin = p == null || (p.x == 0 && p.y == 0 && p.z == 0);
                assertEquals(origin, b.carriesBlockEntity(s),
                        b.spec().model() + " · " + p + "：方块实体只挂在原点那一格");
                with += origin ? 1 : 0;
            }
        }
        assertTrue(with > 0);
        assertFalse(LinerProps.BOOKCASE.carriesBlockEntity(LinerProps.BOOKCASE.getDefaultState()), "不开合的家具不挂方块实体");
        assertEquals(null, LinerProps.BOOKCASE.createBlockEntity(net.minecraft.util.math.BlockPos.ORIGIN, LinerProps.BOOKCASE.getDefaultState()));
    }

    @Test
    void swingBoxesTurnWithTheFacingLikeTheBlockModel() {
        // 衣柜开到底伸进前面那一格（像素 z −3…0）：朝北摆时在原点北边那一格，朝东摆时在东边那一格
        double[] b = LinerProp.Rules.swingBoxes(LinerProp.Kind.WARDROBE).get(0);
        net.minecraft.util.math.Box north = LinerProp.Rules.pieceBoxToWorld(b, net.minecraft.util.math.BlockPos.ORIGIN, Direction.NORTH);
        net.minecraft.util.math.Box east = LinerProp.Rules.pieceBoxToWorld(b, net.minecraft.util.math.BlockPos.ORIGIN, Direction.EAST);
        assertTrue(north.maxZ <= 1.0e-9 && north.minZ < 0, "朝北：在北边那一格 " + north);
        assertTrue(east.minX >= 1.0 - 1.0e-9 && east.maxX > 1.0, "朝东：在东边那一格 " + east);
        assertEquals(north.getLengthY(), east.getLengthY(), 1.0e-9);
        for (LinerProp blk : blocks()) {
            for (double[] box : LinerProp.Rules.swingBoxes(blk.spec().kind())) {
                assertEquals(6, box.length);
                assertTrue(box[0] < box[3] && box[1] < box[4] && box[2] < box[5], blk.spec().model() + " 的盒子是空的");
            }
        }
    }

    @Test
    void openingKeepsThePieceTogether() {
        // 开合只翻 open：整件的每一块认搭档时不看 open（一格开着、一格还没刷到的那一刻，也不能被当成「搭档不在了」拆掉）
        for (LinerProp b : List.of(LinerProps.WARDROBE, LinerProps.SIDEBOARD, LinerProps.DRESSING_TABLE)) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(b.spec().kind());
            BlockState base = b.getDefaultState();
            for (LinerProp.Part here : parts) {
                BlockState s = base.with(LinerProp.FACING, Direction.EAST).with(LinerProp.OPEN, true);
                s = s.with(partOf(s), here);
                for (Direction d : Direction.values()) {
                    LinerProp.Part partner = LinerProp.Rules.partnerAt(here, d, Direction.EAST, parts);
                    if (partner == null) {
                        continue;
                    }
                    BlockState mate = s.with(partOf(s), partner).with(LinerProp.OPEN, false);
                    assertEquals(s, b.getStateForNeighborUpdate(s, d, mate, null, net.minecraft.util.math.BlockPos.ORIGIN,
                            net.minecraft.util.math.BlockPos.ORIGIN.offset(d)), b.spec().model() + " · " + here + "：搭档关着、自己开着，不该拆");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.state.property.EnumProperty<LinerProp.Part> partOf(BlockState s) {
        for (net.minecraft.state.property.Property<?> p : s.getProperties()) {
            if (p.getName().equals("part")) {
                return (net.minecraft.state.property.EnumProperty<LinerProp.Part>) p;
            }
        }
        throw new IllegalArgumentException(s.toString());
    }
}
