package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面上自己拼的形状必须与 {@code DrawContext.fill} 同绕向（{@link ShapeMesh}）：界面这一遍开着背面剔除，反绕的三角形不报错、
 * 只是整块不见。2026-10-13 第二轮真机截图：准星圆片里的那一道圈没有画出来，同一枚圆片里的实心圆却在。
 */
final class ShapeMeshTest {

    @Test
    @DisplayName("正向对照：fill 的顶点顺序（左上 → 左下 → 右下）在屏幕坐标里叉积为负 —— 「正面」的号以它为准")
    void fillOrderIsTheReference() {
        assertTrue(ShapeMesh.cross(0, 0, 0, 1, 1, 1) < 0, "fill 的第一个三角形");
        assertTrue(ShapeMesh.cross(0, 0, 1, 1, 1, 0) < 0, "fill 的第二个三角形（扇：左上 · 右下 · 右上）");
        assertTrue(ShapeMesh.cross(0, 0, 1, 0, 1, 1) > 0, "反过来绕就是正号：判据分得开两种绕向");
    }

    @Test
    @DisplayName("实心圆（三角扇）：每一个三角形都是正面")
    void discFacesTheScreen() {
        assertEquals(0, backFacesInFan(ShapeMesh.disc(10, 10, 6)));
        assertEquals(0, backFacesInFan(ShapeMesh.disc(100, 50, 40)));
    }

    @Test
    @DisplayName("圆环（三角带）：每一个三角形都是正面 —— 带子的奇数个三角形由显卡倒过来认，这里照它的规矩量")
    void annulusFacesTheScreen() {
        float[] ring = ShapeMesh.annulus(10, 10, 3, 5);
        assertTrue(ring.length >= 2 * 3 * 2, "正向对照：带子里得真有三角形");
        assertEquals(0, backFacesInStrip(ring));
        assertEquals(0, backFacesInStrip(ShapeMesh.annulus(60, 30, 0, 20)), "内径为 0 时退化成圆片");
    }

    @Test
    @DisplayName("矩形（三角扇）：与 fill 同一个顶点顺序")
    void rectFacesTheScreen() {
        assertEquals(0, backFacesInFan(ShapeMesh.rect(2, 3, 9, 7)));
    }

    @Test
    @DisplayName("胶囊（未读角标，三角扇）：每一个三角形都是正面；盒子四沿都摸得到、不出盒子")
    void capsuleFacesTheScreen() {
        for (float[] box : new float[][]{{137, 1, 158, 15}, {137, 1, 151, 15}, {0, 0, 63, 42}}) {
            float[] p = ShapeMesh.capsule(box[0], box[1], box[2], box[3]);
            assertTrue(p.length >= 2 * 10, "正向对照：扇里得真有三角形");
            assertEquals(0, backFacesInFan(p), "胶囊 " + java.util.Arrays.toString(box));
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 2; i < p.length; i += 2) {
                minX = Math.min(minX, p[i]);
                maxX = Math.max(maxX, p[i]);
                minY = Math.min(minY, p[i + 1]);
                maxY = Math.max(maxY, p[i + 1]);
            }
            assertEquals(box[0], minX, 0.01f);
            assertEquals(box[2], maxX, 0.01f);
            assertEquals(box[1], minY, 0.01f);
            assertEquals(box[3], maxY, 0.01f);
        }
    }

    /** 三角扇：(0, i, i+1)。退化的（叉积 0）不算背面。 */
    static int backFacesInFan(float[] p) {
        int back = 0;
        for (int i = 2; i + 3 < p.length; i += 2) {
            if (ShapeMesh.cross(p[0], p[1], p[i], p[i + 1], p[i + 2], p[i + 3]) > 0) {
                back++;
            }
        }
        return back;
    }

    /** 三角带：第 n 个三角形是 (n, n+1, n+2)，n 为奇数时显卡按 (n+1, n, n+2) 认绕向。 */
    static int backFacesInStrip(float[] p) {
        int back = 0;
        int count = p.length / 2;
        for (int n = 0; n + 2 < count; n++) {
            int a = n % 2 == 0 ? n : n + 1;
            int b = n % 2 == 0 ? n + 1 : n;
            int c = n + 2;
            if (ShapeMesh.cross(p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1], p[2 * c], p[2 * c + 1]) > 0) {
                back++;
            }
        }
        return back;
    }
}
