package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 装修锤的轮换次序（ADR-0093 B15）：每一样都是一个圈 —— 从放下时的默认起，一直敲下去每种形态恰好走到一次、再回到起点。
 * 只测纯规则（不碰方块注册，不起 Minecraft）。
 */
final class DecorHammerRulesTest {

    @Test
    void sillCyclesThroughAllFourStartingFromInside() {
        Set<LinerGlass.Sill> seen = EnumSet.noneOf(LinerGlass.Sill.class);
        LinerGlass.Sill s = LinerGlass.Sill.INSIDE;
        for (int i = 0; i < LinerGlass.Sill.values().length; i++) {
            seen.add(s);
            s = DecorHammer.Rules.nextSill(s);
        }
        assertEquals(LinerGlass.Sill.INSIDE, s, "敲四下回到放下时的默认");
        assertEquals(EnumSet.allOf(LinerGlass.Sill.class), seen);
        assertEquals(LinerGlass.Sill.OUTSIDE, DecorHammer.Rules.nextSill(LinerGlass.Sill.INSIDE), "屋里之后是屋外（北辰号上的那一种）");
    }

    @Test
    void pilasterSideCyclesLeftCentreRight() {
        assertEquals(LinerBlocks.PilasterSide.CENTER, DecorHammer.Rules.nextSide(LinerBlocks.PilasterSide.LEFT));
        assertEquals(LinerBlocks.PilasterSide.RIGHT, DecorHammer.Rules.nextSide(LinerBlocks.PilasterSide.CENTER));
        assertEquals(LinerBlocks.PilasterSide.LEFT, DecorHammer.Rules.nextSide(LinerBlocks.PilasterSide.RIGHT));
    }

    @Test
    void sitterVisitsEveryCharacterOnce() {
        Set<LinerProp.Sitter> seen = EnumSet.noneOf(LinerProp.Sitter.class);
        LinerProp.Sitter s = LinerProp.Sitter.JEWELER;
        for (int i = 0; i < LinerProp.Sitter.values().length; i++) {
            seen.add(s);
            s = DecorHammer.Rules.nextSitter(s);
        }
        assertEquals(LinerProp.Sitter.JEWELER, s);
        assertEquals(EnumSet.allOf(LinerProp.Sitter.class), seen);
    }

    /** 吸顶灯四种形态：格数 1 · 2 · 2 · 4，都从西北角那一格起；认形态（格数 + 是不是东西排）与摆格子对得上。 */
    @Test
    void lampFormsCoverTheirCellsFromTheNorthWestCorner() {
        BlockPos o = new BlockPos(10, 64, 20);
        assertEquals(List.of(o), DecorHammer.Rules.LampForm.SINGLE.cells(o));
        assertEquals(List.of(o, o.east()), DecorHammer.Rules.LampForm.PAIR_X.cells(o));
        assertEquals(List.of(o, o.south()), DecorHammer.Rules.LampForm.PAIR_Z.cells(o));
        assertEquals(4, DecorHammer.Rules.LampForm.QUAD.cells(o).size());
        for (DecorHammer.Rules.LampForm f : DecorHammer.Rules.LampForm.values()) {
            List<BlockPos> cells = f.cells(o);
            boolean alongX = cells.stream().anyMatch(p -> p.getX() != o.getX());
            assertEquals(f, DecorHammer.Rules.LampForm.of(cells.size(), alongX), "认不回 " + f);
        }
        DecorHammer.Rules.LampForm f = DecorHammer.Rules.LampForm.SINGLE;
        for (int i = 0; i < 4; i++) {
            f = f.next();
        }
        assertEquals(DecorHammer.Rules.LampForm.SINGLE, f, "敲四下回到一格");
    }
}
