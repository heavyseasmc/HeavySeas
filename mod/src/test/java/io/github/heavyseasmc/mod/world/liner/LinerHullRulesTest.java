package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 船壳板的板列（ADR-0069 §2 ④）：纯规则，与 {@code liner_hull.py} 的 Python 抄本对照同一份答案。放下去对不对，在游戏里逐格读回。 */
final class LinerHullRulesTest {

    /** 与 {@code liner_hull.py} 的 GOLDEN 同一份：(x, y, z) → cell。改规则要两边一起改。 */
    private static final int[][] GOLDEN = {
            {0, 13, 0, 8}, {3, 14, 5, 5}, {4, 12, 9, 1}, {2, 15, 0, 2}, {-1, 13, 7, 4}, {-6, 0, -3, 1}, {26, 36, 1, 15}, {12, 27, -1, 2}};

    @Test
    void cellMatchesThePythonCopy() {
        for (int[] g : GOLDEN) {
            assertEquals(g[3], LinerHull.Rules.cell(g[0], g[1], g[2]), "cell(" + g[0] + ", " + g[1] + ", " + g[2] + ")");
        }
    }

    @Test
    void platesAreFourOrSixLongAndNeighbouringStrakesAreStaggered() {
        for (int s = -6; s < 30; s++) {
            List<Integer> butts = butts(s);
            Set<Integer> lengths = new HashSet<>();
            for (int i = 1; i < butts.size(); i++) {
                lengths.add(butts.get(i) - butts.get(i - 1));
            }
            assertEquals(Set.of(4, 6), lengths, "第 " + s + " 列的板长");
            int gap = Integer.MAX_VALUE;
            for (int a : butts) {
                for (int b : butts(s + 1)) {
                    gap = Math.min(gap, Math.abs(a - b));
                }
            }
            assertTrue(gap >= 2, "第 " + s + " 列与上一列的竖缝只差 " + gap + " 格");
        }
    }

    @Test
    void waterlineAndEveryDeckFloorSitOnALap() {
        // 水线 13 · F 15 · E 19 · D 23 · 层高 8：C 31 · B 35 · A 39 · 艇甲板 47；层高 6：C 29 · B 33 · A 37 · 艇甲板 43
        for (int y : new int[]{13, 15, 19, 23, 31, 35, 39, 47, 29, 33, 37, 43}) {
            assertEquals(0, LinerHull.Rules.row(y), "y " + y + " 应在一列板的下沿（换颜色的地方就是一道横缝）");
        }
        assertEquals(1, LinerHull.Rules.row(12), "水线下面紧挨的那一格是上一列的上面那一格（水线那一道画在它的上沿）");
    }

    @Test
    void negativeCoordinatesRepeatLikePositiveOnes() {
        // ❗floorMod：写成 % 时负坐标的竖缝会在 0 那条线两边错位
        for (int x = -25; x < 25; x++) {
            assertEquals(LinerHull.Rules.cell(x + 20, 14, 3), LinerHull.Rules.cell(x, 14, 3), "x 每 10 格一个周期（列号同奇偶）");
        }
        assertEquals(LinerHull.Rules.cell(5, 14 + 4, 5), LinerHull.Rules.cell(5, 14, 5), "y 每两列（4 格）一个周期");
        assertEquals(LinerHull.Rules.cell(5, -10, 5), LinerHull.Rules.cell(5, -6, 5));
    }

    @Test
    void eachButtIsDrawnOnceWhicheverFaceYouLookAt() {
        // 站在四个朝向的墙前从左往右走一遍：每一道板缝恰好在一格的贴图左边画一条线（l），不会两格各画一条、也不会落到右边
        int y = 14;
        int s = LinerHull.Rules.strake(y);
        for (Direction face : new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST}) {
            for (int i = 0; i < 40; i++) {
                boolean lowIsLeft = face == Direction.SOUTH || face == Direction.WEST;
                int h = lowIsLeft ? i : 40 - i;
                int x = face.getAxis() == Direction.Axis.Z ? h : 0;
                int z = face.getAxis() == Direction.Axis.Z ? 0 : h;
                int leftEdge = lowIsLeft ? h : h + 1;                       // 看的人左手边那条格线
                String seam = LinerHull.Rules.seam(LinerHull.Rules.cell(x, y, z), face);
                assertEquals(LinerHull.Rules.buttBefore(leftEdge, s), seam.equals("l"), face + " 第 " + i + " 格");
            }
        }
    }

    @Test
    void cellCoversAllEighteenCombinationsAndNamesTheTextures() {
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 20; z++) {
                    seen.add(LinerHull.Rules.cell(x, y, z));
                }
            }
        }
        // 两个方向的竖缝不会同时落在同一格的两条边上（板长 ≥ 4），所以 18 种组合（2 × 3 × 3）都出现、也只有这 18 种
        assertEquals(LinerHull.Rules.CELLS, seen.size());
        assertEquals("hull/black_0l", LinerHull.Rules.plateTexture("black", LinerHull.Rules.cell(0, 13, 0), Direction.SOUTH));
        assertEquals("hull/black_0r", LinerHull.Rules.plateTexture("black", LinerHull.Rules.cell(0, 13, 0), Direction.NORTH));
    }

    @Test
    void funnelPlatesHaveLapsButNoButts() {
        // 烟囱板只认 y：四个侧面同一张、不带 l / r（竖缝）；黄褐与黑顶的分界（liner_ship 的烟囱：顶上 4 格黑，y 69 起）落在横缝上
        assertEquals("hull/buff_0", LinerHull.Rules.funnelTexture("buff", LinerHull.Rules.row(69)));
        assertEquals("hull/black_1", LinerHull.Rules.funnelTexture("black", LinerHull.Rules.row(70)));
        for (int y = -5; y < 90; y++) {
            String t = LinerHull.Rules.funnelTexture("buff", LinerHull.Rules.row(y));
            assertTrue(t.matches("hull/buff_[01]"), t);
        }
    }

    private static List<Integer> butts(int strake) {
        List<Integer> out = new ArrayList<>();
        for (int h = -40; h < 80; h++) {
            if (LinerHull.Rules.buttBefore(h, strake)) {
                out.add(h);
            }
        }
        return out;
    }
}
