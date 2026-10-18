package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 浴室道具（客房第二版，2026-10-15）登记出来的方块：默认状态（物品图标、/setblock 不带属性时就是它）与样子指向的模板。 */
class LinerBathPropsTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void defaultStateIsTheAnchorPartFacingNorth() {
        Map<LinerProp, LinerProp.Part> want = Map.of(
                LinerProps.BATHTUB, LinerProp.Part.FOOT,
                LinerProps.SHOWER, LinerProp.Part.LOWER,
                LinerProps.TOILET, LinerProp.Part.LOWER,
                LinerProps.BASIN, LinerProp.Part.LOWER,
                LinerProps.WC_STALL, LinerProp.Part.LOWER);
        for (Map.Entry<LinerProp, LinerProp.Part> e : want.entrySet()) {
            BlockState s = e.getKey().getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), e.getKey().spec().model());
            LinerProp.Part part = null;
            for (var p : s.getProperties()) {
                if (p.getName().equals("part")) {
                    part = (LinerProp.Part) s.get(p);
                }
            }
            assertEquals(e.getValue(), part, e.getKey().spec().model() + " 默认是点中的那一块");
            assertFalse(s.contains(LinerProp.LIT), e.getKey().spec().model() + " 不是灯，没有开关");
        }
        // 毛巾架一格：没有 part 属性
        BlockState rail = LinerProps.TOWEL_RAIL.getDefaultState();
        assertEquals(Direction.NORTH, rail.get(LinerProp.FACING));
        assertTrue(rail.getProperties().stream().noneMatch(p -> p.getName().equals("part")), "毛巾架一格，不该有 part");
    }

    @Test
    void looksPointAtTheirOwnTemplatesAndTexture() {
        // 每一块的模板名 = 生成器写出的那一份（liner_props.py 的 <件>_<格>），贴图整件一张
        assertLook(LinerProps.BATHTUB, "prop/bathtub_foot", "prop/bathtub_head");
        assertLook(LinerProps.SHOWER, "prop/shower_lower", "prop/shower_upper");
        assertLook(LinerProps.TOILET, "prop/toilet_lower", "prop/toilet_upper");
        assertLook(LinerProps.BASIN, "prop/basin_lower", "prop/basin_upper");
        assertLook(LinerProps.WC_STALL, "prop/wc_stall_lower", "prop/wc_stall_upper");
        assertEquals("prop/towel_rail", LinerProps.TOWEL_RAIL.look(LinerProps.TOWEL_RAIL.getDefaultState()).template());
        for (LinerProp p : new LinerProp[]{LinerProps.BATHTUB, LinerProps.SHOWER, LinerProps.TOILET, LinerProps.BASIN, LinerProps.WC_STALL}) {
            assertNotNull(p.itemLook(), p.spec().model() + " 跨格，物品是整件缩小的那一块");
            assertEquals("prop/" + p.spec().model() + "_item", p.itemLook().template());
        }
        assertEquals(null, LinerProps.TOWEL_RAIL.itemLook(), "毛巾架一格，物品用方块模型");
        // 两种瓷砖是整块（大邮轮那一族的方块，不是道具）
        assertSame(LinerBlocks.TILE_WALL, LinerBlocks.all().get("liner_tile_wall"));
        assertSame(LinerBlocks.TILE_FLOOR, LinerBlocks.all().get("liner_tile_floor"));
    }

    private static void assertLook(LinerProp block, String first, String second) {
        java.util.Set<String> got = new java.util.TreeSet<>();
        for (BlockState s : block.getStateManager().getStates()) {
            if (s.get(LinerProp.FACING) == Direction.NORTH) {
                LinerLooks.Look l = block.look(s);
                got.add(l.template());
                assertEquals("prop/" + block.spec().texture(), l.textures().get("b"), block.spec().model());
            }
        }
        assertEquals(new java.util.TreeSet<>(java.util.List.of(first, second)), got, block.spec().model());
    }
}
