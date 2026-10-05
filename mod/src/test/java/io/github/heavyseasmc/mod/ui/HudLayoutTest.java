package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主画面 HUD 的几何对样张（用户 2026-09-30：「以设计稿为唯一基准……组件边界重合、中心点一致」）。
 *
 * <h2>期望值的来源</h2>
 * 下面的数字<b>不是从 {@link HudLayout} 的常量抄的</b>，是 2026-09-30 在样张 PNG
 * （内部归档 {@code 美术与稿件/UI质感_20260921/样张/png/b-1-hud.png}，1280×720）上逐像素量出来的外框：
 * 沿一行 / 一列找那一圈最深的描边色 {@code #1c1007} 第一次与最后一次出现的位置。判据若与被测对象同源，
 * 就什么也证明不了（证伪表：尺寸检查的期望值与渲染用的是同一个写死值）。
 * 外框 = 盒子 + 外圈；Chrome 把小数边吸到整像素，所以允许差 1 像素。
 *
 * <h2>几个窗口</h2>
 * 除了样张那一档，还在一串窗口尺寸 × 界面尺寸下核对「谁都不压着谁」「谁都不出窗口」——
 * 用户同一天指出的四处重叠里，舵轮盖住头像 · 眼睛与 R 压在包角上两处都是几何，都该在这里红。
 */
class HudLayoutTest {

    @Test
    @DisplayName("窗口支持边界：宽高都达标才显示内容，单独拉宽或拉高不能掩盖不足")
    void minimumWindowRequiresBothDimensions() {
        assertTrue(HudLayout.supportsWindow(1067, 600));
        assertTrue(HudLayout.supportsWindow(1280, 720));
        assertTrue(!HudLayout.supportsWindow(1066, 600));
        assertTrue(!HudLayout.supportsWindow(1067, 599));
        assertTrue(!HudLayout.supportsWindow(3840, 480));
        assertTrue(!HudLayout.supportsWindow(854, 2160));
        assertTrue(!HudLayout.supportsWindow(0, 0));
    }

    private static final HudLayout DESIGN = HudLayout.of(1280, 720);

    @Test
    @DisplayName("旧字号与动效长度按窗口换算，保留设计窗口的物理尺寸")
    void legacyUnitsFollowWindow() {
        assertEquals(27.0, HudLayout.of(1280, 720).designGuiPixels(9), 1e-9);
        assertEquals(22.5, HudLayout.of(1067, 600).designGuiPixels(9), 1e-9);
        assertEquals(54.0, HudLayout.of(2560, 1440).designGuiPixels(9), 1e-9);
    }
    private static final int TOLERANCE = 1;

    private static void near(int expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= TOLERANCE,
                what + "：样张上是 " + expected + "，版面给的是 " + actual);
    }

    /** 外框的四条边（含右、下那一列像素，与 PNG 上量的写法一致）。 */
    private static void outer(Rect box, int ring, int x0, int y0, int x1Inclusive, int y1Inclusive, String what) {
        Rect o = box.grow(ring);
        near(x0, o.x(), what + " 左沿");
        near(y0, o.y(), what + " 上沿");
        near(x1Inclusive, o.right() - 1, what + " 右沿");
        near(y1Inclusive, o.bottom() - 1, what + " 下沿");
    }

    @Test
    @DisplayName("1280×720 · 界面尺寸 3：各件外框与样张 b-1 逐像素重合（±1）")
    void matchesTheDesignAtItsOwnSize() {
        assertEquals(1.0, DESIGN.k(), 1e-9);
        outer(DESIGN.plaque(), DESIGN.len(HudLayout.RING), 17, 15, 338, 163, "状态牌");
        outer(DESIGN.rail(8), DESIGN.len(HudLayout.RING), 367, 13, 874, 79, "座位轨（八座）");
        outer(DESIGN.medal(), 10, 1184, 10, 1267, 93, "天候舷窗");
        outer(DESIGN.logTab(), DESIGN.len(HudLayout.RING), 1196, 91, 1255, 150, "日志页签");
        Rect key = DESIGN.logKey(0);
        near(1213, key.x(), "日志键 左沿");
        near(158, key.y(), "日志键 上沿");
        near(1238, key.right() - 1, "日志键 右沿");
        Rect count = DESIGN.logCount(0);
        near(89, count.y(), "未读数 上沿");
        near(108, count.bottom() - 1, "未读数 下沿");
        near(1257, count.right() - 1, "未读数 右沿");
        Rect ribbon = DESIGN.ribbon();
        near(52, ribbon.x(), "金签 左沿");
        near(143, ribbon.right() - 1, "金签 右沿");
        near(208, ribbon.bottom() - 1, "金签 下沿");
        // 舵轮那枚：稿子里挂在第八座（小孩）身上，数字「2」13px 粗体约 7.5 宽
        Rect badge = DESIGN.helmBadge(7, 7.5);
        near(833, badge.x(), "舵轮 左沿");
        near(867, badge.right() - 1, "舵轮 右沿");
        near(51, badge.y(), "舵轮 上沿");
        // 样张 b-2：展开的天候卡 910..1257
        Rect card = DESIGN.drawerCard();
        near(910, card.x(), "展开的天候卡 左沿");
        near(1257, card.right() - 1, "展开的天候卡 右沿");
        near(20, card.y(), "展开的天候卡 上沿");
    }

    @Test
    @DisplayName("状态牌上各件的中心与样张一致（±1）：头像 · 四枚体力点 · 四格阶段 · 罗马数字 · 海鸥 · 键帽")
    void plaqueItemsSitWhereTheDesignPutsThem() {
        near(61, DESIGN.token().centerX(), "头像中心 x");
        near(67, DESIGN.token().centerY(), "头像中心 y");
        int[] pipCx = {108, 126, 144, 162};
        for (int i = 0; i < 4; i++) {
            near(pipCx[i], DESIGN.pip(i).centerX(), "第 " + (i + 1) + " 枚体力点中心 x");
        }
        near(199, DESIGN.drop(4).centerX(), "水滴中心 x");
        near(228, DESIGN.eye(4).centerX(), "眼睛中心 x");
        int[] phaseCx = {121, 165, 209, 253};
        for (int i = 0; i < 4; i++) {
            near(phaseCx[i], DESIGN.phase(i).centerX(), "第 " + (i + 1) + " 格阶段中心 x");
        }
        near(82, DESIGN.phase(0).centerY(), "阶段那一排中心 y");
        near(61, DESIGN.roman().centerX(), "罗马数字中心 x");
        int[] gullCx = {113, 144, 175, 206};
        for (int i = 0; i < 4; i++) {
            near(gullCx[i], DESIGN.gull(i).centerX(), "第 " + (i + 1) + " 只海鸥中心 x");
        }
        near(304, DESIGN.handKey(0).centerX(), "手牌键中心 x");
        near(129, DESIGN.handKey(0).centerY(), "手牌键中心 y");
        near(82, DESIGN.ribbonBell().centerX(), "金签里的铃中心 x");
        near(114, DESIGN.ribbonKey().centerX(), "金签里的键中心 x");
        int[] seatCx = {411, 471, 531, 591, 651, 711, 771, 831};
        for (int i = 0; i < 8; i++) {
            near(seatCx[i], DESIGN.seatToken(i).centerX(), "第 " + (i + 1) + " 座头像中心 x");
        }
        near(46, DESIGN.seatToken(0).centerY(), "座位头像中心 y");
    }

    /** 各档窗口：宽 × 高 × 界面尺寸。含样张那一档、用户比对用的 1536×864、以及比稿子窄的几档。 */
    private static final int[][] WINDOWS = {
            {1280, 720, 3}, {854, 480, 2}, {1536, 864, 3}, {1920, 1080, 4}, {1920, 1080, 2},
            {1024, 768, 3}, {1280, 1024, 4}, {800, 600, 2}, {640, 480, 1}, {2560, 1440, 6},
            {1707, 1067, 3}, {3840, 2160, 9}, {1280, 720, 4}, {1366, 768, 3}};

    @Test
    @DisplayName("各档窗口：收着时状态牌 · 金签 · 座位轨 · 舷窗一列两两不相压，也都不出窗口、不压热栏")
    void closedPiecesNeverOverlap() {
        List<String> problems = new ArrayList<>();
        for (int[] win : WINDOWS) {
            HudLayout l = HudLayout.of(win[0], win[1]);
            int ring = l.len(HudLayout.RING);
            Rect plaque = l.plaque().grow(ring);
            Rect ribbon = l.ribbon();
            Rect dock = union(l.medal().grow(l.len(HudLayout.MEDAL_RING)), l.logTab().grow(ring), l.logKey(0));
            Rect hotbar = hotbar(win);
            for (int seats = 6; seats <= 8; seats++) {
                Rect rail = l.rail(seats).grow(ring);
                String at = win[0] + "×" + win[1] + "@" + win[2] + " · " + seats + " 座";
                check(problems, at, "状态牌", plaque, "座位轨", rail);
                check(problems, at, "金签", ribbon, "座位轨", rail);
                check(problems, at, "座位轨", rail, "舷窗一列", dock);
                inside(problems, at, "座位轨", rail, win);
            }
            String at = win[0] + "×" + win[1] + "@" + win[2];
            check(problems, at, "状态牌", plaque, "舷窗一列", dock);
            check(problems, at, "金签", ribbon, "热栏", hotbar);
            check(problems, at, "舷窗一列", dock, "热栏", hotbar);
            inside(problems, at, "状态牌", plaque, win);
            inside(problems, at, "金签", ribbon, win);
            inside(problems, at, "舷窗一列", dock, win);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("各档窗口：展开的天候卡不压座位轨，也不压状态牌")
    void drawerLeavesTheRailAlone() {
        List<String> problems = new ArrayList<>();
        for (int[] win : WINDOWS) {
            HudLayout l = HudLayout.of(win[0], win[1]);
            int ring = l.len(HudLayout.RING);
            Rect card = l.drawerCard();
            String at = win[0] + "×" + win[1] + "@" + win[2];
            check(problems, at, "展开的天候卡", card, "座位轨", l.rail(8).grow(ring));
            check(problems, at, "展开的天候卡", card, "状态牌", l.plaque().grow(ring));
            inside(problems, at, "展开的天候卡", card, win);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("状态牌里的每一件都在内边距之内：体力点 3–6 枚、手牌张数到两位数都不出界、不压海鸥")
    void plaqueContentStaysInsideItsPadding() {
        List<String> problems = new ArrayList<>();
        for (int[] win : WINDOWS) {
            HudLayout l = HudLayout.of(win[0], win[1]);
            Rect inner = l.plaqueInner();
            String at = win[0] + "×" + win[1] + "@" + win[2];
            for (int pips = 3; pips <= 6; pips++) {
                inside(problems, at + " · " + pips + " 点", "眼睛", l.eye(pips), inner);
                inside(problems, at + " · " + pips + " 点", "第 " + pips + " 枚体力点", l.pip(pips - 1), inner);
            }
            inside(problems, at, "头像", l.token(), inner);
            inside(problems, at, "第四格阶段", l.phase(3), inner);
            inside(problems, at, "第四只海鸥", l.gull(3), inner);
            for (double numW : new double[]{7.5, 22}) {
                inside(problems, at, "手牌键", l.handKey(0), inner);
                check(problems, at, "手牌图标（张数 " + numW + " 宽）", l.handIcon(0, numW), "第四只海鸥", l.gull(3));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("舵轮那枚只压头像的右下角：不盖住头像中心、压住的不到四分之一，也不出座位轨")
    void helmBadgeOnlyTouchesTheCorner() {
        List<String> problems = new ArrayList<>();
        for (int[] win : WINDOWS) {
            HudLayout l = HudLayout.of(win[0], win[1]);
            for (int seats = 6; seats <= 8; seats++) {
                Rect rail = l.rail(seats);
                for (int i = 0; i < seats; i++) {
                    for (double numW : new double[]{7.5, 15}) {
                        Rect badge = l.helmBadge(i, numW);
                        Rect token = l.seatToken(i);
                        String at = win[0] + "×" + win[1] + "@" + win[2] + " · 第 " + (i + 1) + "/" + seats + " 座 · 字宽 " + numW;
                        if (badge.contains(new Rect(token.centerX(), token.centerY(), 1, 1))) {
                            problems.add(at + "：舵轮盖住了头像中心");
                        }
                        if (badge.overlapArea(token) * 4 > (long) token.w() * token.h()) {
                            problems.add(at + "：舵轮压住头像 " + badge.overlapArea(token) + " 像素²，超过四分之一");
                        }
                        inside(problems, at, "舵轮", badge, rail);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("缩放只由窗口决定：按宽高较小比例适配，包括矮窗口与高分辨率")
    void scaleFollowsWindowAndShrinksToFit() {
        assertEquals(1.0, HudLayout.of(1280, 720).k(), 1e-9);
        assertEquals(2.0 / 3, HudLayout.of(854, 480).k(), 1e-9);
        assertEquals(1.2, HudLayout.of(1536, 864).k(), 1e-9);
        assertEquals(1.5, HudLayout.of(1920, 1080).k(), 1e-9);
        assertEquals(0.8, HudLayout.of(1024, 768).k(), 1e-9);
        assertEquals(1.0, HudLayout.of(1280, 1024).k(), 1e-9);
        assertEquals(0.5, HudLayout.of(640, 480).k(), 1e-9);
        assertEquals(2.0 / 3, HudLayout.of(1920, 480).k(), 1e-9, "宽而矮时高度限制缩放");
        assertEquals(3.0, HudLayout.of(3840, 2160).k(), 1e-9);
    }

    // ---------------------------------------------------------------- 小件

    /** Minecraft 的热栏：182×22 个 GUI 单位，贴底居中。 */
    private static Rect hotbar(int[] win) {
        int s = win[2];
        return new Rect(win[0] / 2 - 91 * s, win[1] - 22 * s, 182 * s, 22 * s);
    }

    private static Rect union(Rect... rs) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (Rect r : rs) {
            x0 = Math.min(x0, r.x());
            y0 = Math.min(y0, r.y());
            x1 = Math.max(x1, r.right());
            y1 = Math.max(y1, r.bottom());
        }
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private static void check(List<String> problems, String at, String a, Rect ra, String b, Rect rb) {
        if (ra.intersects(rb)) {
            problems.add(at + "：" + a + " " + ra + " 压着 " + b + " " + rb);
        }
    }

    private static void inside(List<String> problems, String at, String what, Rect r, int[] win) {
        inside(problems, at, what, r, new Rect(0, 0, win[0], win[1]));
    }

    private static void inside(List<String> problems, String at, String what, Rect r, Rect box) {
        if (!box.contains(r)) {
            problems.add(at + "：" + what + " " + r + " 出了 " + box);
        }
    }
}
