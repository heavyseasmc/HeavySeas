package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对局各面那副骨架对样张 b-3（1280×720）：期望值是 2026-09-30 在样张 PNG 上逐像素量的，不是抄 {@link SheetLayout} 的常量。
 *
 * <p>量法：板 —— 沿 y=360 / x=640 找外圈那两道（3 px {@code #1c1007} 深、2 px {@code #5a3d22} 木）；
 * 阶段 —— 此刻那一格（补给）铜绿圆底的左右上下沿；座位 —— 第一座（你 · 正轮到）铜绿圈的外沿；
 * 倒计时 —— 液面左端与外圈；按钮 —— 金圈里那道 2 px 搪瓷深边。允许差 1 像素。
 */
class SheetLayoutTest {

    private static final SheetLayout DESIGN = SheetLayout.of(1280, 720);

    private static void near(int expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= 1, what + "：样张上是 " + expected + "，版面给的是 " + actual);
    }

    @Test
    @DisplayName("1280×720 · 界面尺寸 3：板 · 上带 · 座位 · 倒计时 · 提示那一行与样张 b-3 重合（±1）")
    void matchesTheDesign() {
        Rect sheet = DESIGN.sheet();
        near(50, sheet.x(), "板 左沿（盒子）");
        near(1230, sheet.right(), "板 右沿（盒子）");
        near(34, sheet.y(), "板 上沿");
        near(686, sheet.bottom(), "板 下沿");
        Rect provision = DESIGN.phase(1);
        near(545, provision.centerX(), "上带「补给」那一格中心 x");
        near(75, provision.centerY(), "上带那一排中心 y");
        Rect seat = DESIGN.seatToken(0, 8);
        near(208, seat.centerX(), "第一座头像中心 x");
        near(141, seat.centerY(), "第一座头像中心 y");
        Rect bar = DESIGN.countBar();
        near(370, bar.x(), "倒计时横杠 左端");
        near(832, bar.right(), "倒计时横杠 右端");
        near(574, bar.y(), "倒计时横杠 上沿");
        near(617, DESIGN.hintsTop(), "提示那一行（按钮）上沿");
    }

    private static final int[][] WINDOWS = {
            {1280, 720, 3}, {854, 480, 2}, {1536, 864, 3}, {1920, 1080, 4}, {1920, 1080, 2}, {1024, 768, 3},
            {1280, 1024, 4}, {800, 600, 2}, {640, 480, 1}, {2560, 1440, 6}, {1707, 1067, 3}, {1920, 800, 3}};

    @Test
    @DisplayName("各档窗口：上带 · 座位轨 · 牌那一排 · 倒计时 · 提示自上而下不相压，都在板里")
    void bandsStackInsideTheSheet() {
        List<String> problems = new ArrayList<>();
        for (int[] win : WINDOWS) {
            SheetLayout l = SheetLayout.of(win[0], win[1]);
            String at = win[0] + "×" + win[1] + "@" + win[2];
            Rect sheet = l.sheet();
            int hdBottom = l.phase(0).bottom();
            Rect firstSeat = l.seatToken(0, 8);
            if (firstSeat.y() + l.len(-20) < hdBottom) {
                problems.add(at + "：座位轨头上的箱（" + (firstSeat.y() + l.len(-20)) + "）压到上带（" + hdBottom + "）");
            }
            if (l.cardsTop() - l.len(SheetLayout.LIFT) < l.railBottom()) {
                problems.add(at + "：牌抬起来会压到座位轨的名字");
            }
            if (l.countBar().y() <= l.cardsTop()) {
                problems.add(at + "：倒计时顶到了牌那一排的顶");
            }
            if (l.hintsTop() < l.countBar().bottom()) {
                problems.add(at + "：提示那一行压到倒计时");
            }
            for (Rect r : new Rect[]{l.phase(3), l.gull(3), l.seatToken(7, 8), l.countBar(), l.countSeconds()}) {
                if (!sheet.contains(r)) {
                    problems.add(at + "：" + r + " 出了板 " + sheet);
                }
            }
            if (l.seatToken(1, 8).x() < l.seatToken(0, 8).right()) {
                problems.add(at + "：相邻两座头像相压");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("整页与 HUD 采用相同窗口比例，小窗口也不会被界面倍率额外缩小")
    void windowScaleMatchesHud() {
        for (int[] win : WINDOWS) {
            assertEquals(HudLayout.of(win[0], win[1]).k(), SheetLayout.of(win[0], win[1]).k(), 1e-9);
        }
        assertEquals(0.5, SheetLayout.of(640, 480).k(), 1e-9);
        assertEquals(1.5, SheetLayout.of(1920, 1080).k(), 1e-9);
        assertEquals(new Rect(50, 34, 1180, 652), SheetLayout.of(1280, 720).sheet());
    }
}
