package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大楼梯一族「看邻居」的纯规则（ADR 草稿 stairs）。{@code liner_build.py} 里有同一套规则的 Python 抄本，
 * 两边对照的是同一组答案（这里每一例的期望值，就是生成器摆出来、游戏放结构时再算一遍要对得上的那一个）。
 */
final class LinerStairRulesTest {

    @Test
    void balustradeJoinsRailingsAndPostsButOnlyTheFlatEndOfASlope() {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            assertTrue(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.BALUSTRADE, null, null, d));
            assertTrue(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.NEWEL, null, null, d));
            assertTrue(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.NEWEL_LAMP, null, null, d));
            assertFalse(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.WELL_TRIM, null, null, d), "收口不接扶手");
        }
        // 斜栏杆朝东（往东是上坡），它放平的那一头在东边格边上：只有它东边那一格（往西看它）才接
        assertTrue(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.TOP, Direction.WEST),
                "顶段放平的那一头正对着这一格");
        assertTrue(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.SINGLE, Direction.WEST));
        assertFalse(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.TOP, Direction.EAST),
                "在它下坡那一头：那一头往回伸到起步柱，不是平的");
        assertFalse(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.TOP, Direction.NORTH),
                "在它旁边");
        assertFalse(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.MIDDLE, Direction.WEST),
                "中段两头都是斜的");
        assertFalse(LinerStairPiece.Rules.joins(LinerStairPiece.Kind.SLOPE, Direction.EAST, LinerStairPiece.SlopePart.BOTTOM, Direction.WEST));
    }

    @Test
    void slopePartFollowsTheDiagonalNeighbours() {
        assertEquals(LinerStairPiece.SlopePart.MIDDLE, LinerStairPiece.Rules.slopePart(true, true));
        assertEquals(LinerStairPiece.SlopePart.BOTTOM, LinerStairPiece.Rules.slopePart(false, true), "下面没有、上面有：起步那一格");
        assertEquals(LinerStairPiece.SlopePart.TOP, LinerStairPiece.Rules.slopePart(true, false), "上面没有：到顶放平");
        assertEquals(LinerStairPiece.SlopePart.SINGLE, LinerStairPiece.Rules.slopePart(false, false));
    }

    @Test
    void trimSplitsIntoUpperAndLowerHalvesWhenStacked() {
        assertEquals(LinerStairPiece.TrimPart.SINGLE, LinerStairPiece.Rules.trimPart(false, false));
        assertEquals(LinerStairPiece.TrimPart.TOP, LinerStairPiece.Rules.trimPart(false, true), "下面还有一格：上一层楼板那一格，只要压边");
        assertEquals(LinerStairPiece.TrimPart.BOTTOM, LinerStairPiece.Rules.trimPart(true, false), "平顶那一格，只要下沿线脚");
        assertEquals(LinerStairPiece.TrimPart.MIDDLE, LinerStairPiece.Rules.trimPart(true, true));
    }

    @Test
    void maskNamesMatchTheSixteenTemplates() {
        Set<String> all = new HashSet<>();
        for (int m = 0; m < 16; m++) {
            all.add(LinerStairPiece.Rules.mask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0));
        }
        // 与 liner_stairs.py 的 MASKS 一一对应（模板名 balustrade_<这几个字母> · newel_<…>）
        assertEquals(Set.of("none", "n", "e", "ne", "s", "ns", "es", "nes", "w", "nw", "ew", "new", "sw", "nsw", "esw", "nesw"), all);
        assertEquals("ns", LinerStairPiece.Rules.mask(true, false, true, false));
        assertEquals("ew", LinerStairPiece.Rules.mask(false, true, false, true));
    }

    @Test
    void slopeRailMeetsTheFlatRailAtTheTopAndKeepsRisingInTheMiddle() {
        // 顶段放平之后与平台的扶手同高（扶手中心 14.5 + 1.5 = 顶 16）；中段在格边上比下一格高 16
        assertEquals(16.0, LinerStairPiece.Rules.railTop(16, LinerStairPiece.SlopePart.TOP), 1e-9);
        assertEquals(LinerStairPiece.Rules.railTop(0, LinerStairPiece.SlopePart.MIDDLE) + 16,
                LinerStairPiece.Rules.railTop(16, LinerStairPiece.SlopePart.MIDDLE), 1e-9);
    }

    /** ADR-0093 B4：16 种接法里只有南北、东西两种画栏杆，其余 14 种（拐角 · 端头 · 丁字 · 十字 · 孤零零一格）立起步柱。 */
    @Test
    void onlyStraightRunsDrawTheRailingTheRestRaiseANewel() {
        int straight = 0;
        for (int bits = 0; bits < 16; bits++) {
            String m = LinerStairPiece.Rules.mask((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
            if (LinerStairPiece.Rules.straight(m)) {
                straight++;
            }
        }
        assertEquals(2, straight);
        assertTrue(LinerStairPiece.Rules.straight("ns"));
        assertTrue(LinerStairPiece.Rules.straight("ew"));
        assertFalse(LinerStairPiece.Rules.straight("ne"), "拐角");
        assertFalse(LinerStairPiece.Rules.straight("n"), "端头");
        assertFalse(LinerStairPiece.Rules.straight("nes"), "丁字");
        assertFalse(LinerStairPiece.Rules.straight("none"), "孤零零一格");
    }
}
