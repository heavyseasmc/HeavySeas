package io.github.heavyseasmc.mod.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 对局面板里那条聊天窄条与「聊天」小签上的未读数（2026-10-13 用户定 A1 · B，样张 {@code 2026-10-13-chat-mockups}）。
 *
 * <h2>未读怎么数</h2>
 * 对局界面开着期间来的聊天，每来一条加一；玩家在聊天界面里看过（按 T 打开聊天）就清零。
 * 世界视图里来的不算 —— 那时聊天栏自己画在屏幕左下，人已经看见了；聊天界面开着时来的也不算。
 * 窄条里看得到的也算未读：窄条只放两句、一行一句截断，读不全。
 * 只数人说的话：系统句（说话的人为 {@code null}）一律不算；自己说的那句也不算（调用方定 {@code counts}，{@code ChatSkin#arrived}）。
 *
 * <p><b>窄条也只放人说的话</b>，与未读同一个口径（2026-10-13 总截图：每按一次 F2 落一句「已将截图保存为…」，
 * 两句窄条被它占满、人话一句都看不见）。系统句在按 T 打开的聊天页里照样看得到。
 *
 * <h2>窄条放哪几句</h2>
 * 最近两句，旧的在上、新的在下。新来一句时：旧的上一行上移一行并淡出，新的一行淡入，约 250 ms（{@link #SLIDE_MS}）；
 * 「减少动效」开着时直接换。
 *
 * <p>纯数据：不引用客户端类，单测拿它核对计数、取哪两句、角标写什么。
 */
public final class ChatFeed {

    /** 一句聊天：说话的人（系统句为 {@code null}）· 正文 · 收到的时刻（毫秒）。 */
    public record Entry(String speaker, String body, long arrivedMs) {
        public boolean system() {
            return speaker == null;
        }
    }

    /** 窄条放几句。 */
    public static final int STRIP_LINES = 2;
    /** 新的一行淡入（旧的上一行上移淡出）多久。 */
    public static final long SLIDE_MS = 250;
    /** 角标最多写到几；再多写「99+」。 */
    public static final int BADGE_MAX = 99;
    /** 留多少句：窄条只要 {@link #STRIP_LINES} + 1（动画里那一句正在退场）。 */
    private static final int KEEP = STRIP_LINES + 1;

    private final Deque<Entry> recent = new ArrayDeque<>();
    private int unread;

    /**
     * 来了一句。
     *
     * @param counts 这一句算不算未读：此刻开着的是对局界面才算
     */
    public void arrive(Entry entry, boolean counts) {
        if (entry.system()) {
            return;                       // 系统句不进窄条也不进未读：F2 那一句曾把两句人话整个挤出窄条（2026-10-13 总截图）
        }
        recent.addLast(entry);
        while (recent.size() > KEEP) {
            recent.removeFirst();
        }
        if (counts && unread < Integer.MAX_VALUE) {
            unread++;
        }
    }

    /** 玩家在聊天界面里看过了。 */
    public void seen() {
        unread = 0;
    }

    /** 换世界、断线：前一处的话不带过来。 */
    public void clear() {
        recent.clear();
        unread = 0;
    }

    public int unread() {
        return unread;
    }

    /** 窄条此刻要摆的几句，旧的在前：平时最多两句；新的一句还在淡入时多一句（最前面那句正在退场）。 */
    public List<Entry> strip(long nowMs, boolean reducedMotion) {
        List<Entry> all = new ArrayList<>(recent);
        int want = slide(nowMs, reducedMotion) < 1f && all.size() > STRIP_LINES ? STRIP_LINES + 1 : STRIP_LINES;
        return List.copyOf(all.subList(Math.max(0, all.size() - want), all.size()));
    }

    /** 最新那一句淡入到几成：0 刚到，1 已到位。「减少动效」时恒为 1。 */
    public float slide(long nowMs, boolean reducedMotion) {
        Entry last = recent.peekLast();
        if (last == null || reducedMotion) {
            return 1f;
        }
        long age = nowMs - last.arrivedMs();
        if (age >= SLIDE_MS) {
            return 1f;
        }
        return Math.max(0f, age / (float) SLIDE_MS);
    }

    // ---------------------------------------------------------------- 窄条给底栏让位

    /** 窄条右沿与底栏最左那一件之间至少留多少（稿子像素）。 */
    private static final double YIELD_GAP = 12;
    /** 让完之后窄条至少还要多宽（稿子像素）：再窄一行只剩人名，不如不摆。 */
    private static final double MIN_STRIP_W = 170;

    /**
     * 窄条（骨架给的那张纸签）给底栏让位：底栏最左那一件从 {@code footLeft} 起（物理像素；{@link Integer#MAX_VALUE} = 这一面没有底栏）。
     * 碰不到就原样；碰得到就把右沿收到它左边；收完窄过 {@link #MIN_STRIP_W} 就返回 {@code null}（这一面不摆）。
     */
    public static HudLayout.Rect yieldTo(HudLayout.Rect strip, int footLeft, double k) {
        int gap = (int) Math.round(YIELD_GAP * k);
        if (footLeft == Integer.MAX_VALUE || strip.right() + gap <= footLeft) {
            return strip;
        }
        int w = footLeft - gap - strip.x();
        if (w < (int) Math.round(MIN_STRIP_W * k)) {
            return null;
        }
        return new HudLayout.Rect(strip.x(), strip.y(), w, strip.h());
    }

    // ---------------------------------------------------------------- 角标的几何（样张 B，稿子像素 × k）

    /** 角标高 14、两头半圆；宽 = 字宽 + 8，至少是个圆。左缘落在小签右沿往里 7（让开「天」的最后一笔），上沿高出小签 6。 */
    private static final double BADGE_H = 14;
    private static final double BADGE_PAD = 8;
    private static final double BADGE_INSET = 7;
    private static final double BADGE_RISE = 6;
    /** 角标右沿与下一枚签之间至少留多少。 */
    private static final double BADGE_GAP = 4;
    /**
     * 角标里的数字：粗体 14（样张是粗体 11；我们的粗体梯子最低一级就是 14）。第一版用常规体 12 实拍：
     * 朱砂底上的细笔画发虚、一眼读不出（2026-10-13 演示服截图），数字只占圆片中间那 10 像素，粗体放得下。
     */
    public static final double BADGE_PX = 14;

    /**
     * 角标的盒子（物理像素）。
     *
     * @param tagRight 「聊天」小签的右沿
     * @param tagTop   小签的上沿
     * @param labelPx  角标里那串字的宽（物理像素）
     */
    public static HudLayout.Rect badgeBox(int tagRight, int tagTop, int labelPx, double k) {
        return badgeBox(tagRight, tagTop, labelPx, 0, k);
    }

    /**
     * 角标的盒子（物理像素），按数字那一行实际有多高放大。
     *
     * @param lineH 角标里那串字画出来一行多高（物理像素）：字号梯子有下限，窗口小时字不跟着 k 缩
     */
    public static HudLayout.Rect badgeBox(int tagRight, int tagTop, int labelPx, int lineH, double k) {
        // 至少装得下数字那一行：圆片按 14k 画、数字按梯子最低 14 号画，窗口小时数字伸出圆片、贴顶时伸出窗口（2026-10-13 总截图）
        int h = Math.max((int) Math.round(BADGE_H * k), lineH);
        int w = Math.max(h, labelPx + (int) Math.round(BADGE_PAD * k));
        return new HudLayout.Rect(tagRight - (int) Math.round(BADGE_INSET * k),
                Math.max(0, tagTop - (int) Math.round(BADGE_RISE * k)), w, h);
    }

    /** 角标里那串字的上沿（物理像素）：在盒子里竖向居中，数字比汉字矮，再往上提 1。 */
    public static int badgeTextTop(HudLayout.Rect badge, int lineH, double k) {
        return Math.max(badge.y(), badge.y() + (badge.h() - lineH) / 2 - (int) Math.round(k));
    }

    /** 角标比小签宽出去的那一截：后面的签往右让到这里（没有角标时原样返回 {@code nextX}）。 */
    public static int nextAfterBadge(int nextX, HudLayout.Rect badge, double k) {
        return badge == null ? nextX : Math.max(nextX, badge.right() + (int) Math.round(BADGE_GAP * k));
    }

    /** 「聊天」小签上的角标写什么：0 不画（空串）· 1–99 照写 · 100 起「99+」。 */
    public static String badge(int unread) {
        if (unread <= 0) {
            return "";
        }
        return unread > BADGE_MAX ? BADGE_MAX + "+" : Integer.toString(unread);
    }
}
