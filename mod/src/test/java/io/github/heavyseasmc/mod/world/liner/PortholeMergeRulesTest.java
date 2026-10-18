package io.github.heavyseasmc.mod.world.liner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 舷窗拼大（ADR-0093 B12）的纯规则：状态名、窗洞的阶梯圆、哪几片有窗圈与生成器那一份逐个对得上；拼法从下往上、从 u 小的一头起，
 * 先试最大的完整正方形。答案取自 {@code liner_hull.py}（MERGE_VALUES · disc_rows · 写出的窗圈贴图名），不从这边的实现里抄。
 */
final class PortholeMergeRulesTest {

    /** liner_hull.py 的 MERGE_VALUES（同一份名字，逐个对照）。 */
    private static final List<String> GENERATOR_NAMES = List.of("single", "locked",
            "g2_00", "g2_01", "g2_10", "g2_11",
            "g3_00", "g3_01", "g3_02", "g3_10", "g3_11", "g3_12", "g3_20", "g3_21", "g3_22",
            "g4_00", "g4_01", "g4_02", "g4_03", "g4_10", "g4_11", "g4_12", "g4_13", "g4_20", "g4_21", "g4_22", "g4_23",
            "g4_30", "g4_31", "g4_32", "g4_33");

    /** liner_hull.py --write 写出的拼大窗圈贴图（brass_ring_ 后面那一截）：只有这几片有窗圈。 */
    private static final Set<String> GENERATOR_RING_PIECES = new TreeSet<>(List.of("2_00", "2_01", "2_10", "2_11",
            "3_00", "3_01", "3_02", "3_10", "3_12", "3_20", "3_21", "3_22",
            "4_01", "4_02", "4_10", "4_11", "4_12", "4_13", "4_20", "4_21", "4_22", "4_23", "4_31", "4_32"));

    @Test
    void stateNamesMatchTheGenerator() {
        List<String> names = new ArrayList<>();
        for (LinerHull.Merge m : LinerHull.Merge.values()) {
            names.add(m.asString());
        }
        assertEquals(GENERATOR_NAMES, names);
        assertEquals(LinerHull.Merge.G3_21, LinerHull.Merge.of(3, 2, 1));
        assertEquals(LinerHull.Merge.SINGLE, LinerHull.Merge.of(1, 0, 0));
    }

    @Test
    void ringPiecesMatchTheGenerator() {
        Set<String> got = new TreeSet<>();
        for (LinerHull.Merge m : LinerHull.Merge.values()) {
            if (m.merged() && LinerHull.Rules.hasRing(m)) {
                got.add(m.piece());
            }
        }
        assertEquals(GENERATOR_RING_PIECES, got, "4 × 4 的四个角、3 × 3 的正中没有窗圈");
    }

    /** disc_rows 的几行（窗洞最上那一行 · 中间附近 · 窗圈最上那一行）。 */
    @Test
    void steppedCircleMatchesTheGenerator() {
        assertEquals(3.0, LinerHull.Rules.rowHalf(10, 2, 6));
        assertEquals(9.5, LinerHull.Rules.rowHalf(10, 2, 13));
        assertEquals(10.0, LinerHull.Rules.rowHalf(10, 2, 16));
        assertEquals(3.5, LinerHull.Rules.rowHalf(12, 2, 4));
        assertEquals(4.0, LinerHull.Rules.rowHalf(15, 3, 9));
        assertEquals(17.0, LinerHull.Rules.rowHalf(17, 3, 23));
        assertEquals(4.5, LinerHull.Rules.rowHalf(20, 4, 12));
        assertEquals(22.0, LinerHull.Rules.rowHalf(22, 4, 31));
        assertEquals(0.0, LinerHull.Rules.rowHalf(20, 4, 11), "窗洞外面那一行");
    }

    private static Set<Long> rect(int u0, int v0, int w, int h) {
        Set<Long> out = new HashSet<>();
        for (int u = u0; u < u0 + w; u++) {
            for (int v = v0; v < v0 + h; v++) {
                out.add(LinerHull.Rules.key(u, v));
            }
        }
        return out;
    }

    private static int[] at(Map<Long, int[]> tiles, int u, int v) {
        return tiles.get(LinerHull.Rules.key(u, v));
    }

    @Test
    void completeSquaresMerge() {
        for (int n = 2; n <= 4; n++) {
            Map<Long, int[]> t = LinerHull.Rules.tile(rect(-7, 40, n, n));
            for (int du = 0; du < n; du++) {
                for (int dv = 0; dv < n; dv++) {
                    assertArrayEquals(new int[]{n, du, dv}, at(t, -7 + du, 40 + dv), n + " × " + n + " 的第 " + du + " 列第 " + dv + " 行");
                }
            }
        }
    }

    /** 3 宽 × 2 高：u 小的那一头拼 2 × 2，剩下那一列两格单着；5 × 5：左下 4 × 4，其余单格（拼法只试以这一格为左下角的正方形）。 */
    @Test
    void leftoversStaySingle() {
        Map<Long, int[]> t = LinerHull.Rules.tile(rect(0, 0, 3, 2));
        assertArrayEquals(new int[]{2, 0, 0}, at(t, 0, 0));
        assertArrayEquals(new int[]{2, 1, 1}, at(t, 1, 1));
        assertArrayEquals(new int[]{1, 0, 0}, at(t, 2, 0));
        assertArrayEquals(new int[]{1, 0, 0}, at(t, 2, 1));
        Map<Long, int[]> big = LinerHull.Rules.tile(rect(0, 0, 5, 5));
        assertArrayEquals(new int[]{4, 3, 3}, at(big, 3, 3));
        assertArrayEquals(new int[]{1, 0, 0}, at(big, 4, 4));
        assertArrayEquals(new int[]{1, 0, 0}, at(big, 0, 4));
        assertEquals(25, big.size());
    }

    /** 缺一格（锁住的、拆掉的）就拼不成那一块：2 × 2 缺右上那一格 → 三格单着。 */
    @Test
    void aHoleBreaksTheSquare() {
        Set<Long> cells = rect(0, 0, 2, 2);
        cells.remove(LinerHull.Rules.key(1, 1));
        Map<Long, int[]> t = LinerHull.Rules.tile(cells);
        for (int[] v : t.values()) {
            assertEquals(1, v[0]);
        }
        assertEquals(3, t.size());
    }

    @Test
    void mirroringFlipsTheColumnOnly() {
        assertEquals(LinerHull.Merge.G4_21, LinerHull.Merge.G4_11.mirrored());
        assertEquals(LinerHull.Merge.G3_02, LinerHull.Merge.G3_22.mirrored());
        for (LinerHull.Merge m : LinerHull.Merge.values()) {
            assertEquals(m, m.mirrored().mirrored());
        }
        assertEquals(LinerHull.Merge.LOCKED, LinerHull.Merge.LOCKED.mirrored());
    }

    /** 碰撞箱：有窗洞的那几片不是整块（后退的玻璃才取这一格自己的光）；窗洞那一截有玻璃挡着（大窗洞人钻得过去）；3 × 3 正中只剩玻璃。 */
    @Test
    void mergedCollisionKeepsTheGlassAndTheHole() {
        double[][] centre = LinerHull.Rules.mergedCollision(LinerHull.Merge.G3_11, 13);
        for (double[] b : centre) {
            assertEquals(12.5, b[2], "3 × 3 正中整格是窗洞：只有玻璃那一片 " + Arrays.toString(b));
            assertEquals(13.0, b[5]);
        }
        assertEquals(16, centre.length);
        double[][] corner = LinerHull.Rules.mergedCollision(LinerHull.Merge.G4_00, 13);
        for (double[] b : corner) {
            assertArrayEquals(new double[]{0, b[1], 0, 16, b[1] + 1, 16}, b, "4 × 4 的角上没有窗洞：整格实心");
        }
        boolean glass = false;
        for (double[] b : LinerHull.Rules.mergedCollision(LinerHull.Merge.G2_00, 13)) {
            glass |= b[2] == 12.5 && b[5] == 13.0;
        }
        assertTrue(glass, "2 × 2 那一片窗洞里要有玻璃挡着");
    }
}
