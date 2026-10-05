package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一排可以叠起来的牌：补给箱 · 手牌 · 别人面前三面共用（ADR-0049，用户 2026-10-01 看过样图定的）。
 *
 * <ul>
 *   <li><b>间距</b>：放得下就照样张的缝并排；放不下才叠，叠多少由张数定 —— 张数少了自己散开，直到不重叠。</li>
 *   <li><b>抽出来</b>：选中那张以牌底中点为轴往左转 {@link #PULL_DEG}°、往上提，像从一叠里抽出来。
 *       进度就是各面原来那条「抬」（{@link GuiLanguage#approach}），不另起一套动效。</li>
 *   <li><b>一叠当一件东西</b>：叠着时每张左边缘一道细影，分得出是几张。<b>没选中的不压暗</b>：
 *       用户 2026-10-05 推翻了 ADR-0049 的「压暗一点」——「玩家会感觉不可用，形成视觉误导」。
 *       选中靠抽出来与金框；压暗只留给真按不动的那张（{@link ChoiceCards#OFF}）。</li>
 *   <li><b>张数一变就滑过去</b>：按「哪一张」认，不按下标 —— 中间少了一张，后面的不该先跳一格再滑。</li>
 * </ul>
 *
 * <p>❗样图（{@code evidence/2026-10-01-demo/tip-mock/}）是 144 宽、提 30；游戏里补给箱的牌宽<b>按剩下的空间算</b>：
 * 超时那一下「顿」还要再往上弹一截，样图没给它留地方，照搬会压上座位轨的名字（snap_test 的判据）。
 */
final class CardRow {

    /** 抽出来往左转几度（上端往左歪；以牌底中点为轴）。 */
    static final float PULL_DEG = 6f;
    /**
     * 抽出来往上提多少（稿子像素）。样张 .lifted 是 16；要看得出离开了那一叠。
     *
     * <p>ADR-0049 第一版是 24（给「顿」留地方）；用户 2026-10-01 看过游戏里的 136 宽之后定「宁可牌大、『顿』小一点」，
     * 回到样图 F 的 30，腾地方的是 {@link GuiLanguage} 里「顿」往上弹的幅度（ADR-0050 §3）。
     */
    static final double PULL_LIFT = 30;
    /** 叠着时左边缘那道细影多宽（稿子像素）、多深。 */
    static final double EDGE = 2;
    private static final int EDGE_ALPHA = 0x46;
    /** 选中那一圈金框往牌外扩多少（样张 .sel：外 3 深金 · 4 金 · 1 深金）。 */
    static final double FRAME_OUT = 8;
    /** 牌的宽高比（{@link GuiLanguage#cardHeight}）。 */
    private static final double ASPECT = (double) GuiLanguage.CARD_H / GuiLanguage.CARD_W;

    private CardRow() {
    }

    /**
     * 相邻两张之间挪多远：放得下就 {@code cardW + gap} 并排，放不下就叠。
     *
     * <p>❗<b>不设「最小间距」下限</b>：牌多到连下限都摆不下时，右边几张会落到外面 ——
     * 画到外面与没画长得一模一样，玩家只会觉得「我的牌少了几张」。
     *
     * @param room 这一排最多占多宽（与 {@code cardW} 同一单位）
     */
    static int step(int count, int cardW, int gap, int room) {
        int loose = cardW + gap;
        if (count <= 1) {
            return loose;
        }
        return Math.max(1, Math.min(loose, (room - cardW) / (count - 1)));
    }

    /** 以牌底中点为轴转 {@code deg} 度之后，最高点比没转时高出多少（右上角升得最高）。 */
    static double rotationRise(double w, double h, double deg) {
        double t = Math.toRadians(deg);
        return Math.max(0, w / 2 * Math.sin(t) + h * Math.cos(t) - h);
    }

    /** 抽出来那张的最高点（连金框）比牌排顶高出多少：提 + 转出来的那一截 + 金框。单位同入参。 */
    static double pullRoom(double lift, double w, double h, double frame) {
        return lift + rotationRise(w, h, PULL_DEG) + frame;
    }

    /** 在牌自己的矩阵里转：调用方已经 translate 到牌底中点，{@code pull} 是抽出来的进度（0 = 平放）。 */
    static void rotate(DrawContext context, float pull) {
        if (pull > 0f) {
            context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-PULL_DEG * Math.min(1f, pull)));
        }
    }

    /**
     * 叠着时左边缘那道细影，落在左边那张（下面那张）上。在牌自己的坐标里画（{@code (0,0)} 是左上角，GUI 单位），
     * 换到物理像素画，免得一个 GUI 单位就是三像素粗。
     */
    static void edgeShadow(DrawContext context, int cardH, int guiScale, double k) {
        float f = 1f / guiScale;
        context.getMatrices().push();
        context.getMatrices().scale(f, f, 1f);
        int e = Math.max(1, (int) Math.round(EDGE * k));
        context.fill(-e, 0, 0, cardH * guiScale, EDGE_ALPHA << 24);
        context.getMatrices().pop();
    }

    /**
     * 指针落在第几张上：选中那张压在最上面，先问它；其余右边的压着左边的，从右往左问。
     *
     * @param liftRoom 上边界往上放宽多少（抽出来那一截）
     */
    static int indexAt(float mouseX, float mouseY, float left, float top, float step, int w, int h, int count,
                       int selected, float liftRoom) {
        if (mouseY < top - liftRoom || mouseY > top + h) {
            return -1;
        }
        if (selected >= 0 && selected < count) {
            float x = left + selected * step;
            if (mouseX >= x && mouseX < x + w) {
                return selected;
            }
        }
        for (int i = count - 1; i >= 0; i--) {
            float x = left + i * step;
            if (mouseX >= x && mouseX < x + w) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 张数一变，各张从原来的位置滑到新位置。按「哪一张」认：牌 id 加它是第几张同名的。
     * 第一次见到的那张直接落在目标上（它有自己的「发」）。
     */
    static final class Slide {
        private final Map<String, Float> at = new HashMap<>();

        float[] positions(List<String> ids, float[] targets, long dtMs) {
            Map<String, Integer> seen = new HashMap<>();
            Map<String, Float> next = new HashMap<>();
            float[] out = new float[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                int nth = seen.merge(ids.get(i), 1, Integer::sum);
                String key = ids.get(i) + "#" + nth;
                Float cur = at.get(key);
                float x = cur == null ? targets[i] : GuiLanguage.approach(cur, targets[i], dtMs);
                next.put(key, x);
                out[i] = x;
            }
            at.clear();
            at.putAll(next);
            return out;
        }

        void reset() {
            at.clear();
        }
    }

    // ---------------------------------------------------------------- 补给箱那一排（物理像素）

    /** 补给箱的牌最宽多少（稿子像素）：样图 F 的 144。空间更多时也不再放大。 */
    static final double PROVISION_MAX_W = 144;
    /** 满 8 张时相邻两张的间距是牌宽的几成（样图 F：144 宽叠 30）。这一排最宽 = 牌宽 + 7 × 它 × 牌宽。 */
    static final double FULL_STEP = 114.0 / 144.0;
    /** 抽出来 + 「顿」的最高点离座位轨名字至少多远；说明签的下沿离倒计时外圈至少多远（稿子像素）。 */
    static final double RAIL_CLEAR = 4;
    static final double BAR_CLEAR = 6;
    /** 倒计时横杠外面那两圈（样张 .dir-b .count .bar 的 box-shadow 0 0 0 2 / 5 / 6）。 */
    static final double BAR_RIM = 6;
    /** 说明签：尖角 9、尖角离牌底 8；内边距 10 / 16 / 12；题头 12 × 1.4 · 牌名 22 粗 × 1.35 · 效果 15 × 1.6、上边 3。 */
    static final double TIP_PTR = 9;
    static final double TIP_GAP = 8;
    static final double TIP_PAD_T = 10;
    static final double TIP_PAD_X = 16;
    static final double TIP_PAD_B = 12;
    static final double TIP_CAP_PX = 12;
    static final double TIP_CAP_LINE = 12 * 1.4;
    static final double TIP_NAME_PX = 22;
    static final double TIP_NAME_LINE = 22 * 1.35;
    static final double TIP_BODY_PX = 15;
    static final double TIP_BODY_LINE = 15 * 1.6;
    static final double TIP_BODY_GAP = 3;

    /** 说明签有几行效果时多高（稿子像素）。 */
    static double tipHeight(int bodyLines) {
        return TIP_PAD_T + TIP_CAP_LINE + TIP_NAME_LINE + TIP_BODY_GAP + bodyLines * TIP_BODY_LINE + TIP_PAD_B;
    }

    /**
     * 补给箱这一帧的几何，全部是物理像素。
     *
     * @param w       牌宽
     * @param h       牌高
     * @param top     牌排顶（没抽出来的那几张）
     * @param left    第一张的左沿
     * @param step    相邻两张的间距
     * @param tipTop  说明签的上沿（尖角在它上面）
     */
    record Provision(int w, int h, int top, int left, int step, int tipTop, int tipH) {

        int bottom() {
            return top + h;
        }

        int cardX(int i) {
            return left + i * step;
        }
    }

    /**
     * 补给箱：座位轨名字的下沿与倒计时的外圈之间，自上而下是「抽出来 + 顿」要的空 · 牌排 · 说明签。
     * 牌宽由剩下的高度算（最宽 {@link #PROVISION_MAX_W}）；空间更多时整块竖向居中。
     *
     * @param snapRise  「顿」峰值往上多少（物理像素）
     * @param snapScale 「顿」峰值放大多少（1.07 → 0.07）；牌绕底边放大，长出来的全在上面
     * @param tipLines  说明签效果那一段留几行（取这一箱里最长的那条，整箱不变 —— 签子一高一矮牌会跟着跳）
     */
    static Provision provision(SheetLayout l, int count, double snapRise, double snapScale, int tipLines) {
        int n = Math.max(1, count);
        double k = l.k();
        double railBottom = l.railBottom() + RAIL_CLEAR * k;
        double floor = l.countBar().y() - (BAR_RIM + BAR_CLEAR) * k;
        double tipH = tipHeight(tipLines) * k;
        double below = (TIP_GAP + TIP_PTR) * k + tipH;
        double fixed = (PULL_LIFT + FRAME_OUT) * k + snapRise;
        // 牌高 h 时竖向要的：fixed + 转出来的一截 + 顿放大的一截 + h + below。转出来的那一截对 h 是线性的。
        double t = Math.toRadians(PULL_DEG);
        double perH = 1 + snapScale + Math.sin(t) / (2 * ASPECT) - (1 - Math.cos(t));
        double hFit = (floor - railBottom - fixed - below) / perH;
        int w = (int) Math.max(8, Math.min(Math.round(PROVISION_MAX_W * k), Math.floor(hFit / ASPECT)));
        int h = GuiLanguage.cardHeight(w);
        double above = fixed + rotationRise(w, h, PULL_DEG) + snapScale * h;
        double spare = Math.max(0, floor - railBottom - (above + h + below));
        int top = (int) Math.round(railBottom + spare / 2 + above);

        int gap = l.len(SheetLayout.CARD_GAP);
        int room = (int) Math.min(w + (N_FULL - 1) * FULL_STEP * w, l.sheet().w() - 2 * l.len(40));
        int step = step(n, w, gap, room);
        int rowW = (n - 1) * step + w;
        int left = l.width() / 2 - rowW / 2;
        int tipTop = (int) Math.round(top + h + (TIP_GAP + TIP_PTR) * k);
        return new Provision(w, h, top, left, step, tipTop, (int) Math.round(tipH));
    }

    /** 满箱几张（8 人局）。 */
    private static final int N_FULL = 8;
}
