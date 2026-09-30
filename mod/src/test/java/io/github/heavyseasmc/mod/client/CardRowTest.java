package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可以叠的那一排牌（ADR-0049）：间距规则、补给箱的几何、张数变了按「哪一张」滑。
 */
class CardRowTest {

    @Test
    @DisplayName("间距：放得下就并排留缝，放不下才叠；张数少了散开直到不重叠")
    void stepSpreadsAsCardsLeave() {
        int w = 144;
        int gap = 13;
        int room = 942;                   // 样图 F：8 张 144 宽叠 30
        assertEquals(114, CardRow.step(8, w, gap, room));
        assertEquals(133, CardRow.step(7, w, gap, room));
        assertEquals(w + gap, CardRow.step(6, w, gap, room));
        assertEquals(w + gap, CardRow.step(1, w, gap, room));
        assertEquals(1, CardRow.step(40, w, gap, 100), "再挤也不画到外面去（间距可以小到 1）");
        for (int n = 8; n > 1; n--) {
            assertTrue(CardRow.step(n - 1, w, gap, room) >= CardRow.step(n, w, gap, room), n + " 张 → " + (n - 1) + " 张只会散开");
        }
    }

    @Test
    @DisplayName("往左转：右上角升得最高，升起的量与样图算的一致")
    void rotationRiseMatchesTheMock() {
        assertEquals(6.4, CardRow.rotationRise(144, 144 * 1.4, 6), 0.1);
        assertEquals(0.0, CardRow.rotationRise(144, 201.6, 0), 1e-9);
    }

    private static final int[][] WINDOWS = {
            {1280, 720, 3}, {854, 480, 2}, {1536, 864, 3}, {1920, 1080, 4}, {1920, 1080, 2}, {1024, 768, 3},
            {1280, 1024, 4}, {800, 600, 2}, {640, 480, 1}, {2560, 1440, 6}, {2560, 1494, 6}, {1707, 1067, 3},
            {1920, 800, 3}};

    @Test
    @DisplayName("补给箱各档窗口 × 1–8 张 × 说明签 1–3 行：抽出来再「顿」到最高也不压座位轨名字，说明签不压倒计时，整排在板里")
    void provisionFitsBetweenRailAndCountdown() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (int[] win : WINDOWS) {
            SheetLayout l = SheetLayout.of(win[0], win[1], win[2]);
            int s = win[2];
            double k = l.k();
            double snapRise = GuiLanguage.SNAP_PEAK_RISE * s;
            double snapScale = GuiLanguage.SNAP_PEAK_SCALE - 1f;
            for (int lines = 1; lines <= 3; lines++) {
                for (int n = 1; n <= 8; n++) {
                    CardRow.Provision p = CardRow.provision(l, n, snapRise, snapScale, lines);
                    String at = win[0] + "×" + win[1] + "@" + s + " · " + n + " 张 · 签 " + lines + " 行";
                    // 判据只用返回的几何 + 「顿」的峰值现算，不信 provision 自己留没留
                    double peak = p.top() - CardRow.pullRoom(CardRow.PULL_LIFT * k, p.w(), p.h(), CardRow.FRAME_OUT * k)
                            - snapRise - snapScale * p.h();
                    if (peak < l.railBottom()) {
                        problems.add(at + "：抽出来再顿到最高 " + Math.round(peak) + " 压上座位轨名字（" + l.railBottom() + "）");
                    }
                    int rim = (int) Math.round(l.countBar().y() - CardRow.BAR_RIM * k);
                    if (p.tipTop() + p.tipH() > rim) {
                        problems.add(at + "：说明签下沿 " + (p.tipTop() + p.tipH()) + " 压到倒计时外圈（" + rim + "）");
                    }
                    if (p.tipTop() - CardRow.TIP_PTR * k < p.bottom()) {
                        problems.add(at + "：说明签的尖角戳进了牌排");
                    }
                    Rect sheet = l.sheet();
                    int right = p.cardX(n - 1) + p.w();
                    if (p.left() < sheet.x() || right > sheet.right()) {
                        problems.add(at + "：整排 " + p.left() + "–" + right + " 出了板 " + sheet);
                    }
                    if (p.w() > Math.round(CardRow.PROVISION_MAX_W * k)) {
                        problems.add(at + "：牌宽 " + p.w() + " 超过上限");
                    }
                    checked++;
                }
            }
        }
        assertEquals(WINDOWS.length * 3 * 8, checked, "没按预期把每一档都查一遍");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("1280×720：8 张叠、6 张不叠；牌比样张 b-3 的 124 大")
    void provisionAtTheDesignSize() {
        SheetLayout l = SheetLayout.of(1280, 720, 3);
        double rise = GuiLanguage.SNAP_PEAK_RISE * 3;
        double scale = GuiLanguage.SNAP_PEAK_SCALE - 1f;
        CardRow.Provision eight = CardRow.provision(l, 8, rise, scale, 1);
        CardRow.Provision six = CardRow.provision(l, 6, rise, scale, 1);
        assertTrue(eight.step() < eight.w(), "8 张要叠：间距 " + eight.step() + " · 牌宽 " + eight.w());
        assertEquals(six.w() + l.len(SheetLayout.CARD_GAP), six.step(), "6 张并排留缝");
        assertTrue(eight.w() > 124, "牌宽 " + eight.w() + " 不比样张 b-3 大");
    }

    @Test
    @DisplayName("1280×720 · 说明签 1 行：补给箱的牌回到样图 F 的 144 宽（用户 2026-10-01：宁可牌大、「顿」小一点）")
    void provisionIsTheMockWidthAtTheDesignSize() {
        // ADR-0050 §3：ADR-0049 §8 第 1 条（136 宽 · 提 24）被用户推翻；腾地方的是「顿」往上弹的幅度，不是牌。
        SheetLayout l = SheetLayout.of(1280, 720, 3);
        CardRow.Provision p = CardRow.provision(l, 8, GuiLanguage.SNAP_PEAK_RISE * 3, GuiLanguage.SNAP_PEAK_SCALE - 1f, 1);
        assertEquals(144, p.w(), "牌宽");
        assertEquals(30.0, CardRow.PULL_LIFT, 1e-9, "抽出来提多少（样图 F）");
    }

    @Test
    @DisplayName("张数变了按「哪一张」滑：中间少了一张，后面那张从它原来的位置滑过去，不先跳一格")
    void slideFollowsTheCardNotTheIndex() {
        CardRow.Slide slide = new CardRow.Slide();
        slide.positions(List.of("water", "cash", "knife"), new float[]{0, 100, 200}, 16);
        float[] after = slide.positions(List.of("water", "knife"), new float[]{0, 100}, 16);
        assertEquals(0f, after[0], 1e-3);
        assertTrue(after[1] > 150f && after[1] < 200f, "短刀这一帧应当还在 200 附近往 100 滑，实际 " + after[1]);
        float[] fresh = slide.positions(List.of("water", "knife", "rum"), new float[]{0, 100, 200}, 16);
        assertEquals(200f, fresh[2], 1e-3, "新来的一张直接落在目标上（它有自己的「发」）");
    }
}
