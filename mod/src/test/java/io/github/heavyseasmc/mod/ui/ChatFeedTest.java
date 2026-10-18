package io.github.heavyseasmc.mod.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对局面板里的聊天窄条与未读角标（2026-10-13 用户定 A1 · B）：计数 · 取哪两句 · 角标写什么 · 窄条摆在哪。
 * 期望值取自样张 {@code 2026-10-13-chat-mockups/src/build.py}（1280×720 稿子像素），不抄 {@link SheetLayout} 的常量。
 */
class ChatFeedTest {

    private static ChatFeed.Entry line(String who, String body, long at) {
        return new ChatFeed.Entry(who, body, at);
    }

    @Test
    @DisplayName("未读：界面开着时来的才算；看过清零；界面外来的不算")
    void unreadCountsOnlyWhileTheScreenIsOpen() {
        ChatFeed feed = new ChatFeed();
        feed.arrive(line("水手", "世界里说的", 0), false);
        assertEquals(0, feed.unread(), "界面没开时来的，聊天栏自己就画着 —— 不算未读");
        feed.arrive(line("船长", "一", 10), true);
        feed.arrive(line(null, "截图已保存", 20), true);
        feed.arrive(line("小孩", "好。", 30), true);
        assertEquals(2, feed.unread(), "系统句不算");
        feed.seen();
        assertEquals(0, feed.unread(), "打开过聊天：清零");
        feed.arrive(line("医生", "又一句", 40), true);
        assertEquals(1, feed.unread());
        feed.clear();
        assertEquals(0, feed.unread());
        assertEquals(List.of(), feed.strip(1000, false));
    }

    @Test
    @DisplayName("窄条：最近两句、旧的在上；新的一句淡入期间多摆退场的那一句；减少动效时直接换")
    void stripTakesTheNewestTwo() {
        ChatFeed feed = new ChatFeed();
        assertEquals(List.of(), feed.strip(0, false));
        feed.arrive(line("珠宝商", "我这还有两张水", 0), true);
        assertEquals(List.of("我这还有两张水"), bodies(feed.strip(1000, false)), "只有一句就摆一句");
        feed.arrive(line("水手", "舵手是谁？", 1000), true);
        feed.arrive(line("船长", "数到三一起划", 2000), true);
        assertEquals(List.of("舵手是谁？", "数到三一起划"), bodies(feed.strip(5000, false)));
        feed.arrive(line("小孩", "好。", 6000), true);
        // 刚到 100 ms：三句，最前面那句正在退场
        assertEquals(List.of("舵手是谁？", "数到三一起划", "好。"), bodies(feed.strip(6100, false)));
        assertEquals(0.4f, feed.slide(6100, false), 1e-6f);
        assertEquals(List.of("数到三一起划", "好。"), bodies(feed.strip(6100, true)), "减少动效：不摆退场那一句");
        assertEquals(1f, feed.slide(6100, true));
        assertEquals(List.of("数到三一起划", "好。"), bodies(feed.strip(6000 + ChatFeed.SLIDE_MS, false)));
        assertEquals(1f, feed.slide(6000 + ChatFeed.SLIDE_MS, false));
    }

    @Test
    @DisplayName("窄条只放人说的话：系统句（截图已保存 · 谁上了艇）不进窄条、也不挤掉人话，与未读同一个口径")
    void systemLinesStayOutOfTheStrip() {
        // 2026-10-13 总截图：每按一次 F2 落一句「已将截图保存为…」，两句窄条被它占满，人话一句都看不见
        ChatFeed feed = new ChatFeed();
        feed.arrive(line("水手", "先别吵，今天舵手是谁？", 0), true);
        feed.arrive(line("船长", "我数到三就一起划", 1000), true);
        feed.arrive(line(null, "已将截图保存为 2026-10-13_21.35.png", 2000), false);
        feed.arrive(line(null, "已将截图保存为 2026-10-13_21.36.png", 3000), true);
        assertEquals(List.of("先别吵，今天舵手是谁？", "我数到三就一起划"), bodies(feed.strip(9000, false)));
        assertEquals(1f, feed.slide(3100, false), "系统句不算「新来一句」：人话那两行不因它重新淡入");
        assertEquals(2, feed.unread(), "系统句即使调用方说算，也不进未读");
    }

    private static List<String> bodies(List<ChatFeed.Entry> entries) {
        return entries.stream().map(ChatFeed.Entry::body).toList();
    }

    @Test
    @DisplayName("角标文本：0 不画 · 3 · 12 · 99 照写 · 100 起「99+」")
    void badgeText() {
        assertEquals("", ChatFeed.badge(0));
        assertEquals("", ChatFeed.badge(-1));
        assertEquals("3", ChatFeed.badge(3));
        assertEquals("12", ChatFeed.badge(12));
        assertEquals("99", ChatFeed.badge(99));
        assertEquals("99+", ChatFeed.badge(100));
        assertEquals("99+", ChatFeed.badge(12345));
    }

    @Test
    @DisplayName("角标几何对样张 B：小签右沿往里 7 · 高出 6 · 高 14 · 至少是个圆；后面的签往右让到角标右沿 + 4")
    void badgeGeometry() {
        // 样张：「聊天」小签 x 100–144、上沿 7；一位数字宽约 6 → 圆 14；两位约 13 → 胶囊 21
        Rect one = ChatFeed.badgeBox(144, 7, 6, 1.0);
        assertEquals(new Rect(137, 1, 14, 14), one);
        Rect two = ChatFeed.badgeBox(144, 7, 13, 1.0);
        assertEquals(new Rect(137, 1, 21, 14), two);
        assertEquals(162, ChatFeed.nextAfterBadge(150, two, 1.0), "胶囊右沿 158 + 4");
        assertEquals(170, ChatFeed.nextAfterBadge(170, two, 1.0), "本来就够远：不动");
        assertEquals(150, ChatFeed.nextAfterBadge(150, null, 1.0), "没有角标：不动");
        assertEquals(0, ChatFeed.badgeBox(144, 2, 6, 1.0).y(), "小签贴着窗口顶时角标不出屏");
        Rect big = ChatFeed.badgeBox(432, 21, 18, 3.0);
        assertEquals(42, big.h());
        assertEquals(432 - 21, big.x());
    }

    /** 粗体字号梯子（与 GuiText.BOLD_PX 同一套；客户端类，测试里另写一份）挑出来的那一级，一行高 = 字号 × 1.25 向上取整。 */
    private static int boldLineH(int wantPx) {
        int[] ladder = {14, 16, 18, 20, 24, 28, 32, 40, 48, 64};
        int px = ladder[0];
        for (int s : ladder) {
            if (s <= wantPx) {
                px = s;
            }
        }
        return (int) Math.ceil(px * 1.25);
    }

    @Test
    @DisplayName("角标整个落在窗口里：小签贴着窗口顶（钉住时板上移）也不裁；数字那一行装得进圆片")
    void badgeNeverLeavesTheWindow() {
        // 2026-10-13 总截图：1280×720 与英文 1067×600 钉住日志时，角标上半截被窗口上沿裁掉 ——
        // 圆片按 14k 画（k≈0.67 时 9 像素），粗体梯子最低 14 号、一行 18 像素的数字居中在它上面，伸出窗口顶
        int checked = 0;
        for (int[] w : WINDOWS) {
            double k = SheetLayout.of(w[0], w[1]).k();
            int lineH = boldLineH((int) Math.round(ChatFeed.BADGE_PX * k));
            for (int tagTop = 1; tagTop <= (int) Math.round(30 * k); tagTop++) {
                for (int labelPx : new int[]{6, 13, 22}) {
                    Rect b = ChatFeed.badgeBox(500, tagTop, labelPx, lineH, k);
                    int textTop = ChatFeed.badgeTextTop(b, lineH, k);
                    String at = w[0] + "×" + w[1] + " k " + k + " 小签上沿 " + tagTop + " → " + b + " 字顶 " + textTop;
                    assertTrue(b.y() >= 0, "圆片出了窗口顶：" + at);
                    assertTrue(textTop >= 0, "数字出了窗口顶：" + at);
                    assertTrue(b.h() >= lineH, "数字一行 " + lineH + " 比圆片高：" + at);
                    assertTrue(textTop >= b.y() && textTop + lineH <= b.bottom(), "数字出了圆片：" + at);
                    assertTrue(b.w() >= b.h(), "至少是个圆：" + at);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "只核了 " + checked + " 种");
    }

    @Test
    @DisplayName("窄条在 1280×720 下落在样张那一块：x 74–352 · y 576–642")
    void stripMatchesTheDesign() {
        SheetLayout l = SheetLayout.of(1280, 720);
        assertEquals(new Rect(74, 576, 278, 66), l.chatStrip());
        assertEquals(new Rect(89, 589, 248, 40), l.chatStripText());
    }

    @Test
    @DisplayName("窄条给底栏让位：碰不到原样 · 碰得到收右沿 · 收得太窄就不摆")
    void stripYieldsToTheFooter() {
        Rect strip = new Rect(74, 576, 278, 66);
        assertEquals(strip, ChatFeed.yieldTo(strip, Integer.MAX_VALUE, 1.0), "这一面没有底栏");
        assertEquals(strip, ChatFeed.yieldTo(strip, 376, 1.0), "样张最靠左的底栏 376：352 + 12 = 364，碰不到");
        // 实拍：行动一面打头那句「选一件事」从 325 起 —— 右沿收到 313
        assertEquals(new Rect(74, 576, 239, 66), ChatFeed.yieldTo(strip, 325, 1.0));
        assertEquals(null, ChatFeed.yieldTo(strip, 74 + 12 + 169, 1.0), "让完只剩 169 宽：不摆");
        assertEquals(new Rect(74, 576, 170, 66), ChatFeed.yieldTo(strip, 74 + 12 + 170, 1.0), "刚好 170：还摆");
        assertEquals(new Rect(148, 1152, 478, 132), ChatFeed.yieldTo(new Rect(148, 1152, 556, 132), 650, 2.0), "k = 2 时间距与下限都按 2 倍算");
    }

    private static final int[][] WINDOWS = {
            {854, 480}, {1067, 600}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440},
            {3840, 2160}, {1280, 1024}, {1024, 768}, {2560, 1080}, {3440, 1440}};

    @Test
    @DisplayName("窄条不越界：各档窗口 × 日志钉住 / 不钉住 × 日志正文字号，都在板里、碰不到倒计时、不进日志那一栏")
    void stripStaysInsideTheBoard() {
        int checked = 0;
        int nearFloor = 0;
        for (int[] w : WINDOWS) {
            for (boolean pinned : new boolean[]{false, true}) {
                // 栏宽随日志正文字号变（PaneWindow#logTextPx 照阅读字号梯子挑，那是客户端的类）：这里把可能的字号整段扫一遍
                for (int logPx = 8; logPx <= 64; logPx++) {
                    PaneBudget b = PaneBudget.of(w[0], w[1], pinned, logPx);
                    String where = w[0] + "×" + w[1] + (pinned ? " 钉住 · 日志字 " + logPx : "");
                    assertInside(b.sheet(), b.logColumn(), where);
                    if (pinned && b.logShown()) {
                        checked++;
                        if (b.paneWidth() < HudLayout.MIN_WINDOW_W) {
                            nearFloor++;
                        }
                    }
                }
            }
        }
        // 正向对照：钉住且真的让出了一栏的那几档至少要有几档，否则「钉住」那一半什么都没测
        assertTrue(checked >= 4, "钉住且让出日志栏的情形只有 " + checked + " 种 —— 钉住那一半没在测");
        // 钉住时界面那一块可以窄到 PaneBudget.MIN_PINNED_PANE_W（比 ADR-0088 的 1067 宽松）：那一段要真的扫到
        assertTrue(nearFloor >= 4, "界面那一块落在 1000–1066 之间的情形只有 " + nearFloor + " 种 —— 新下限那一段没在测");
    }

    @Test
    @DisplayName("钉住时的新下限：界面那一块恰好 1000 宽、高 600–2160，窄条照样在板里、碰不到倒计时")
    void stripStaysInsideTheBoardAtThePinnedFloor() {
        int floor = 1000;                          // 用户 2026-10-13 给的数，写死在这里，不取被测对象的常量
        assertTrue(PaneBudget.supportsPane(floor, HudLayout.MIN_WINDOW_H), "下限挪了：这一条要跟着改");
        int checked = 0;
        for (int h = HudLayout.MIN_WINDOW_H; h <= 2160; h += 8) {
            SheetLayout l = SheetLayout.of(floor, h);
            assertInside(l, new Rect(floor, 0, 0, h), floor + "×" + h + "（界面那一块）");
            checked++;
        }
        // 1280×720 常规字号钉住时界面那一块约 1013 宽：落在新下限那一段里，并且窄条确实往左收了（随板走）
        PaneBudget pinned = null;
        for (int logPx = 8; logPx <= 40 && pinned == null; logPx++) {
            PaneBudget b = PaneBudget.of(1280, 720, true, logPx);
            if (b.logShown() && b.paneWidth() < HudLayout.MIN_WINDOW_W) {
                pinned = b;
            }
        }
        assertTrue(pinned != null, "1280×720 钉住时没有一种字号让出了日志栏 —— 新下限那一段没扫到");
        Rect full = SheetLayout.of(1280, 720).chatStrip();
        Rect narrow = pinned.sheet().chatStrip();
        assertTrue(narrow.x() < full.x() && narrow.w() < full.w(),
                "板收窄了窄条却没跟着走：整窗 " + full + " · 钉住 " + narrow);
        assertTrue(checked > 100, "只扫了 " + checked + " 档高度");
    }

    /** 窄条与字框在板里、碰不到倒计时横杠、离板底留出框的厚度、不伸进日志那一栏（{@code logColumn} 的左沿）。 */
    private static void assertInside(SheetLayout l, Rect logColumn, String at) {
        Rect s = l.sheet();
        Rect strip = l.chatStrip();
        Rect text = l.chatStripText();
        Rect bar = l.countBar();
        String where = at + "（板 " + s + "，窄条 " + strip + "）";
        assertTrue(strip.x() > s.x() && strip.right() < s.right(), "横向出了板：" + where);
        assertTrue(strip.y() > s.y() && strip.bottom() < s.bottom(), "竖向出了板：" + where);
        assertTrue(strip.right() < bar.x(), "压到倒计时横杠（" + bar + "）：" + where);
        assertTrue(strip.bottom() <= s.bottom() - (int) Math.floor(40 * l.k()), "贴到板底的框上：" + where);
        assertTrue(text.x() > strip.x() && text.right() < strip.right()
                && text.y() > strip.y() && text.bottom() < strip.bottom(), "字框出了纸签：" + where);
        assertTrue(strip.right() <= logColumn.x(), "伸进日志那一栏：" + where);
    }
}
