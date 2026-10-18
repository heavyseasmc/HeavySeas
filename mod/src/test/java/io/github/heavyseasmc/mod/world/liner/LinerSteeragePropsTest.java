package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客房第三版的三等舱家具（2026-10-16）：折叠桌 · 凳子 · 挂钩板。纯规则（一格、不是灯、挂在墙上的是哪几种）+ 方块的默认状态（物品图标、
 * /setblock 不带属性时就是它）+ 样子指向的模板 + 轮廓与碰撞（面高、挂钩板不挡路）。样子与模型的判据在 liner_props_steerage.py。
 */
final class LinerSteeragePropsTest {

    private static final List<LinerProp> ALL = List.of(LinerProps.FOLDING_TABLE, LinerProps.STOOL, LinerProps.WALL_HOOKS);

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void defaultStateIsOneCellFacingNorthWithoutSwitches() {
        for (LinerProp p : ALL) {
            BlockState s = p.getDefaultState();
            assertEquals(Direction.NORTH, s.get(LinerProp.FACING), p.spec().model());
            assertTrue(s.getProperties().stream().noneMatch(q -> q.getName().equals("part")), p.spec().model() + " 一格，不该有 part");
            assertFalse(s.contains(LinerProp.LIT), p.spec().model() + " 不是灯，没有开关");
            assertFalse(s.contains(LinerProp.ON_TABLE), p.spec().model());
        }
    }

    @Test
    void looksPointAtTheirOwnTemplateAndTexture() {
        for (LinerProp p : ALL) {
            for (BlockState s : p.getStateManager().getStates()) {
                LinerLooks.Look l = p.look(s);
                assertEquals("prop/" + p.spec().model(), l.template(), p.spec().model());
                assertEquals("prop/" + p.spec().texture(), l.textures().get("b"), p.spec().model());
            }
            assertNull(p.itemLook(), p.spec().model() + " 一格，物品用方块模型");
        }
        assertEquals("prop/folding_table", LinerProps.FOLDING_TABLE.look(LinerProps.FOLDING_TABLE.getDefaultState()).template());
        assertEquals("prop/stool", LinerProps.STOOL.look(LinerProps.STOOL.getDefaultState()).template());
        assertEquals("prop/wall_hooks", LinerProps.WALL_HOOKS.look(LinerProps.WALL_HOOKS.getDefaultState()).template());
    }

    @Test
    void rulesOneCellNotLampOnlyHooksAndSconceGoOnTheWall() {
        for (LinerProp.Kind k : List.of(LinerProp.Kind.FOLDING_TABLE, LinerProp.Kind.STOOL, LinerProp.Kind.WALL_HOOKS)) {
            assertNull(LinerProp.Rules.anchor(k), k + " 一格，没有点中的那一块");
            assertTrue(LinerProp.Rules.parts(k).isEmpty(), k + " 一格");
            assertFalse(LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertFalse(LinerProp.Rules.hanging(k), k + " 不挂天花");
            // ADR-0109 ①：凳子右键坐；折叠桌、挂钩板右键没有自己的那条路
            boolean sit = k == LinerProp.Kind.STOOL;
            assertEquals(sit ? LinerProp.Use.SIT : LinerProp.Use.OTHER, LinerProp.Rules.use(k),
                    k + (sit ? " 有座位，右键坐" : " 右键没有自己的那条路"));
        }
        // 挂在墙上的恰好是壁灯与挂钩板（摆法贴墙、没有碰撞箱）；正向对照 + 全表反向
        EnumSet<LinerProp.Kind> onWall = EnumSet.noneOf(LinerProp.Kind.class);
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            if (LinerProp.Rules.onWall(k)) {
                onWall.add(k);
            }
        }
        assertEquals(EnumSet.of(LinerProp.Kind.SCONCE, LinerProp.Kind.WALL_HOOKS), onWall);
    }

    @Test
    void shapesKeepTheTableAndStoolHeightsAndTheHooksAgainstTheWall() {
        // 折叠桌桌面高 12、凳面高 7（低于一步迈得上去的 9.6）—— 与 liner_props_steerage.check_steerage 量模型的那两个数一致
        double table = LinerPropShapes.boxes(LinerProp.Kind.FOLDING_TABLE, null).stream()
                .filter(b -> b[0] <= 8 && 8 <= b[3] && b[2] <= 9 && 9 <= b[5]).mapToDouble(b -> b[4]).max().orElseThrow();
        assertEquals(12.0, table, 1e-9, "折叠桌桌面正中的高");
        double stool = LinerPropShapes.boxes(LinerProp.Kind.STOOL, null).stream().mapToDouble(b -> b[4]).max().orElseThrow();
        assertEquals(7.0, stool, 1e-9, "凳面高");
        // 挂钩板：轮廓贴着墙（模型 z 16）、整块悬着（摆在离地第二格，这一格里不碰地）
        List<double[]> hooks = LinerPropShapes.boxes(LinerProp.Kind.WALL_HOOKS, null);
        assertEquals(16.0, hooks.stream().mapToDouble(b -> b[5]).max().orElseThrow(), 1e-9, "挂钩板贴墙");
        assertTrue(hooks.stream().allMatch(b -> b[1] >= 0.5), "挂钩板悬着，不碰这一格的地面");
    }

    @Test
    void hooksHaveAnOutlineButNoCollisionWhileTheStoolIsSolid() {
        for (Direction f : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockState hooks = LinerProps.WALL_HOOKS.getDefaultState().with(LinerProp.FACING, f);
            assertFalse(LinerProps.WALL_HOOKS.getOutlineShape(hooks, null, BlockPos.ORIGIN, ShapeContext.absent()).isEmpty(),
                    "挂钩板点得中（朝 " + f + "）");
            assertTrue(LinerProps.WALL_HOOKS.getCollisionShape(hooks, null, BlockPos.ORIGIN, ShapeContext.absent()).isEmpty(),
                    "挂钩板不挡路（朝 " + f + "）");
            // 板贴在朝向反方向那一面墙上：轮廓伸到那一面的格子边
            Box b = LinerProps.WALL_HOOKS.getOutlineShape(hooks, null, BlockPos.ORIGIN, ShapeContext.absent()).getBoundingBox();
            Direction wall = f.getOpposite();
            double edge = switch (wall) {
                case SOUTH -> b.maxZ;
                case NORTH -> 1 - b.minZ;
                case EAST -> b.maxX;
                default -> 1 - b.minX;
            };
            assertEquals(1.0, edge, 1e-9, "朝 " + f + " 时板贴在 " + wall + " 那一面墙上");
        }
        BlockState stool = LinerProps.STOOL.getDefaultState();
        assertFalse(LinerProps.STOOL.getCollisionShape(stool, null, BlockPos.ORIGIN, ShapeContext.absent()).isEmpty(), "凳子是实的");
    }
}
