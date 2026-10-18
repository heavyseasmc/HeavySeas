package io.github.heavyseasmc.mod.ui;

/**
 * 界面上自己拼的几种实心形状（圆片 · 圆环 · 矩形）的顶点，坐标是屏幕坐标（y 朝下）。
 *
 * <h2>❗绕向</h2>
 * 界面这一遍画的时候背面剔除是<b>开着</b>的（{@code CardComposite} 为此专门关过一次），所以三角形必须与 Minecraft
 * 自己的四边形同一个绕向 —— {@code DrawContext.fill} 的顶点顺序是 左上 → 左下 → 右下 → 右上，在 y 朝下的屏幕坐标里
 * {@link #cross} 为负。反过来绕的三角形整块被剔掉，<b>不报错、不留痕</b>：2026-10-13 第二轮真机截图里，
 * 座位轨的钟一次也没画出来（扇面从 12 点顺时针排点）、准星圆片里那一道圈也没有（圆环外 → 内交替），
 * 而同一枚圆片里逆时针排点的实心圆照常画了出来。
 *
 * <p>这里只放算顶点的部分，不碰 Minecraft 客户端类，单测直接对它（{@code ShapeMeshTest}）；画在 {@code GuiMaterial}。
 * 返回的都是交错的 {@code {x0, y0, x1, y1, ...}}。
 */
public final class ShapeMesh {

    private ShapeMesh() {
    }

    /** 实心圆：三角扇，第一个点是圆心。 */
    public static float[] disc(float cx, float cy, float r) {
        int n = segments(r);
        float[] out = new float[2 * (n + 2)];
        out[0] = cx;
        out[1] = cy;
        for (int i = 0; i <= n; i++) {
            // 角度往上走、y 取负：屏幕上是逆时针（与 fill 同绕向）
            double a = 2 * Math.PI * i / n;
            out[2 * (i + 1)] = (float) (cx + Math.cos(a) * r);
            out[2 * (i + 1) + 1] = (float) (cy - Math.sin(a) * r);
        }
        return out;
    }

    /** 圆环（内径 {@code r0} 到外径 {@code r1}）：三角带，每一对先内后外。 */
    public static float[] annulus(float cx, float cy, float r0, float r1) {
        int n = segments(r1);
        float inner = Math.max(0f, r0);
        float[] out = new float[4 * (n + 1)];
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            double c = Math.cos(a);
            double s = Math.sin(a);
            // ❗先内后外：2026-10-13 之前是先外后内，第一个三角形就是背面，整道圈被剔掉
            out[4 * i] = (float) (cx + c * inner);
            out[4 * i + 1] = (float) (cy - s * inner);
            out[4 * i + 2] = (float) (cx + c * r1);
            out[4 * i + 3] = (float) (cy - s * r1);
        }
        return out;
    }

    /** 矩形：三角扇，与 {@code fill} 同一个顶点顺序（左上 → 左下 → 右下 → 右上）。 */
    public static float[] rect(float x0, float y0, float x1, float y1) {
        return new float[]{x0, y0, x0, y1, x1, y1, x1, y0};
    }

    /**
     * 胶囊（两头半圆的横条；高 ≥ 宽时退化成圆片）：三角扇，第一个点是中心。聊天未读角标用它（样张 B）。
     * 一整块画，不拼「矩形 + 两个圆」：半透明的落影拼起来，重叠的地方会深一块。
     */
    public static float[] capsule(float x0, float y0, float x1, float y1) {
        float r = Math.min(x1 - x0, y1 - y0) / 2f;
        float cy = (y0 + y1) / 2f;
        float left = x0 + r;
        float right = Math.max(left, x1 - r);
        int n = (segments(r) / 2 + 1) & ~1;          // 偶数：半圆正中（最左 / 最右那一点）正好落在顶点上
        float[] out = new float[2 * (2 * (n + 1) + 2)];
        out[0] = (left + right) / 2f;
        out[1] = cy;
        int k = 2;
        // 与 disc 同向：角度往上走、y 取负。右半圆从正下方（−90°）到正上方（90°），左半圆从正上方到正下方
        for (int half = 0; half < 2; half++) {
            float cx = half == 0 ? right : left;
            for (int i = 0; i <= n; i++) {
                double a = -Math.PI / 2 + Math.PI * half + Math.PI * i / n;
                out[k++] = (float) (cx + Math.cos(a) * r);
                out[k++] = (float) (cy - Math.sin(a) * r);
            }
        }
        out[k++] = out[2];
        out[k] = out[3];
        return out;
    }

    /** 三角形 (a, b, c) 的有向面积的两倍（屏幕坐标，y 朝下）：负 = 与 {@code fill} 同绕向（正面）。 */
    public static double cross(float ax, float ay, float bx, float by, float cx, float cy) {
        return (double) (bx - ax) * (cy - ay) - (double) (by - ay) * (cx - ax);
    }

    private static int segments(float r) {
        return Math.max(16, Math.min(64, Math.round(r * 2)));
    }
}
