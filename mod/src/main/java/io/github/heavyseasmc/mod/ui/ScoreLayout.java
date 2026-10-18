package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 计分面板的版面（样张 b-5，ADR-0022）：上面两行字，左边全船一列（桂冠 · 头像 · 名字 · 合计），
 * 右边一张账（头像与名字 · 四项 · 一道线 · 合计），左下一行小字（几秒后返航 · 调试改动）。
 *
 * <p>与 {@link SheetLayout} 同一个坐标系：常量是稿子像素（1280×720 下的物理像素），返回的都是物理像素。
 * 这里不碰 Minecraft —— 一行字多高由调用方给（{@link Lines}，客户端照字号梯子算），
 * 所以各种窗口 × 两档阅读字号下「不出板、不相叠」能在单测里排一遍（{@code ScoreLayoutTest}）。
 *
 * <h2>放不下时先让什么</h2>
 * 窗口小、阅读字号大时字有地板、不跟着缩，按稿子的间距排会戳到下面那行小字上（证伪表「有下限的东西不跟着缩」）。
 * 让的顺序：先把左边每一行压到装得下字为止；右边那张账先把留白收紧、顶边提到与左边第一行齐，最后才把四项的行距压到只够字。
 */
public final class ScoreLayout {

    /** 一行字多高（物理像素）：{@code px} 是想要的字号（稿子像素乘过系数），{@code bold} 粗体。 */
    @FunctionalInterface
    public interface Lines {
        int of(int px, boolean bold);
    }

    // 样张 b-5 的几何（稿子像素，相对板）：.sc-title { top: 30 } 22px + 小一行 14px ·
    // .rank { left: 86; top: 116; width: 520 } 一行 58 高：桂冠一格 26 · 头像 42 · 名字 19px · 合计 28px 粗，间距 14 ·
    // .mine { right: 86; top: 130; width: 440; padding: 22 28 24 }：头像 48 · 小标题 14px · 名字 22px 粗；
    // 四项一行 50 高、19px / 24px；合计上面 2px 一道线、21px · 40px 粗。
    public static final double TITLE_PX = 22;
    public static final double SUB_PX = 14;
    public static final double NAME_PX = 19;
    public static final double TOTAL_PX = 28;
    public static final double CAPTION_PX = 14;
    public static final double ACCOUNT_NAME_PX = 22;
    public static final double LABEL_PX = 19;
    public static final double NOTE_PX = 14;
    public static final double VALUE_PX = 24;
    public static final double SUM_LABEL_PX = 21;
    public static final double SUM_PX = 40;
    public static final double FOOT_PX = 13;

    private static final double TITLE_Y = 30;
    private static final double TITLE_INSET = 40;
    private static final double RANK_X = 86;
    private static final double RANK_Y = 116;
    private static final double RANK_W = 520;
    private static final double RANK_ROW = 58;
    private static final double LAUREL_CELL = 26;
    private static final double LAUREL = 24;
    private static final double AVATAR = 42;
    private static final double GAP = 14;
    private static final double TOTAL_W = 60;
    private static final double ACCOUNT_RIGHT = 86;
    private static final double ACCOUNT_Y = 130;
    private static final double ACCOUNT_W = 440;
    private static final double PAD_X = 28;
    private static final double PAD_TOP = 22;
    private static final double PAD_BOTTOM = 24;
    private static final double HEAD_AVATAR = 48;
    private static final double ITEM_H = 50;
    private static final double ICON = 24;
    private static final double VALUE_W = 48;
    private static final double SUM_W = 96;
    /** 左边一列与左下那行小字之间至少留多少。 */
    private static final double FOOT_GAP = 10;

    /** 左边一行：整行 · 桂冠 · 头像 · 名字 · 合计。 */
    public record Row(Rect row, Rect laurel, Rect avatar, Rect name, Rect total) {
    }

    /** 账上的一项：整行 · 图标 · 标签与来源小字（同一行，标签在前）· 数。 */
    public record Item(Rect row, Rect icon, Rect text, Rect value) {
    }

    private final SheetLayout sheet;
    private final Rect title;
    private final Rect subtitle;
    private final List<Row> rows;
    private final Rect card;
    private final Rect headAvatar;
    private final Rect caption;
    private final Rect headName;
    private final List<Item> items;
    private final Rect rule;
    private final Rect sumLabel;
    private final Rect sum;
    private final Rect foot;

    private ScoreLayout(SheetLayout sheet, Lines fixed, Lines reading, int people) {
        this.sheet = sheet;
        Rect s = sheet.sheet();
        int contentBottom = foot0(sheet, reading).y() - len(FOOT_GAP);

        // 上面两行
        int titleH = fixed.of(len(TITLE_PX), false);
        title = new Rect(s.x() + len(TITLE_INSET), s.y() + len(TITLE_Y), s.w() - 2 * len(TITLE_INSET), titleH);
        int subH = reading.of(len(SUB_PX), false);
        subtitle = new Rect(title.x(), title.bottom() + len(4), title.w(), subH);
        int top = Math.max(s.y() + len(RANK_Y), subtitle.bottom() + len(GAP));

        // 左边一列：一行放不下八个人就压，压到装得下字为止
        int n = Math.max(1, people);
        int nameH = reading.of(len(NAME_PX), false);
        int totalH = fixed.of(len(TOTAL_PX), true);
        int minRow = Math.max(nameH, totalH) + len(6);
        int rowH = Math.max(minRow, Math.min(len(RANK_ROW), (contentBottom - top) / n));
        int x0 = s.x() + len(RANK_X);
        int w = len(RANK_W);
        int avatar = Math.max(1, Math.min(len(AVATAR), rowH - len(8)));
        int laurel = Math.max(1, Math.min(len(LAUREL), rowH - len(8)));
        List<Row> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int y = top + i * rowH;
            int cy = y + rowH / 2;
            Rect laurelBox = new Rect(x0 + len(10) + (len(LAUREL_CELL) - laurel) / 2, cy - laurel / 2, laurel, laurel);
            int ax = x0 + len(10) + len(LAUREL_CELL) + len(GAP);
            Rect avatarBox = new Rect(ax, cy - avatar / 2, avatar, avatar);
            int totalW = len(TOTAL_W);
            Rect totalBox = new Rect(x0 + w - len(GAP) - totalW, cy - totalH / 2, totalW, totalH);
            int nx = avatarBox.right() + len(GAP);
            Rect nameBox = new Rect(nx, cy - nameH / 2, Math.max(1, totalBox.x() - len(10) - nx), nameH);
            list.add(new Row(new Rect(x0, y, w, rowH), laurelBox, avatarBox, nameBox, totalBox));
        }
        rows = List.copyOf(list);

        // 右边那张账：先照稿子排；放不下就收紧留白、顶边提到与左边第一行齐，最后把四项压到只够字
        int cw = len(ACCOUNT_W);
        int cx = s.right() - len(ACCOUNT_RIGHT) - cw;
        int captionH = reading.of(len(CAPTION_PX), false);
        int headNameH = fixed.of(len(ACCOUNT_NAME_PX), true);
        int labelH = reading.of(len(LABEL_PX), false);
        int valueH = fixed.of(len(VALUE_PX), true);
        int sumH = Math.max(fixed.of(len(SUM_PX), true), reading.of(len(SUM_LABEL_PX), false));
        int cardTop = top + len(ACCOUNT_Y - RANK_Y);
        int padTop = len(PAD_TOP);
        int padBottom = len(PAD_BOTTOM);
        int gap = len(10);
        int headAv = len(HEAD_AVATAR);
        int itemH = Math.max(len(ITEM_H), Math.max(labelH, valueH) + len(10));
        for (int pass = 0; pass < 3; pass++) {
            int headH = Math.max(headAv, captionH + len(2) + headNameH);
            int h = padTop + headH + gap + 4 * itemH + gap + Math.max(1, len(2)) + len(12) + sumH + padBottom;
            if (cardTop + h <= contentBottom) {
                break;
            }
            if (pass == 0) {
                cardTop = top;
                padTop = len(12);
                padBottom = len(12);
                gap = len(6);
            } else {
                itemH = Math.max(labelH, valueH) + len(4);
                headAv = Math.min(headAv, captionH + len(2) + headNameH);
            }
        }
        int headH = Math.max(headAv, captionH + len(2) + headNameH);
        int ruleH = Math.max(1, len(2));
        int cardH = padTop + headH + gap + 4 * itemH + gap + ruleH + len(12) + sumH + padBottom;
        card = new Rect(cx, cardTop, cw, cardH);
        int tx = cx + len(PAD_X);
        int inner = cw - 2 * len(PAD_X);
        int y = cardTop + padTop;
        headAvatar = new Rect(tx, y + (headH - headAv) / 2, headAv, headAv);
        int hx = headAvatar.right() + len(12);
        int textTop = y + (headH - captionH - len(2) - headNameH) / 2;
        caption = new Rect(hx, textTop, cx + cw - len(PAD_X) - hx, captionH);
        headName = new Rect(hx, caption.bottom() + len(2), caption.w(), headNameH);
        y += headH + gap;
        List<Item> its = new ArrayList<>();
        int icon = Math.min(len(ICON), itemH);
        for (int i = 0; i < 4; i++) {
            int iy = y + i * itemH;
            int icy = iy + itemH / 2;
            Rect valueBox = new Rect(tx + inner - len(VALUE_W), icy - valueH / 2, len(VALUE_W), valueH);
            Rect iconBox = new Rect(tx, icy - icon / 2, icon, icon);
            int lx = iconBox.right() + len(GAP);
            Rect textBox = new Rect(lx, icy - labelH / 2, Math.max(1, valueBox.x() - len(8) - lx), labelH);
            its.add(new Item(new Rect(tx, iy, inner, itemH), iconBox, textBox, valueBox));
        }
        items = List.copyOf(its);
        y += 4 * itemH + gap;
        rule = new Rect(tx, y, inner, ruleH);
        y += ruleH + len(12);
        sum = new Rect(tx + inner - len(SUM_W), y, len(SUM_W), sumH);
        sumLabel = new Rect(tx, y, Math.max(1, sum.x() - len(8) - tx), sumH);
        foot = foot0(sheet, reading);
    }

    /** 左下那行小字：倒计时横杠那一条（这一面不画倒计时）的高度上，左起与左边那一列齐，右到账的右沿。 */
    private static Rect foot0(SheetLayout sheet, Lines reading) {
        Rect s = sheet.sheet();
        int h = reading.of(sheet.len(FOOT_PX), false);
        int x = s.x() + sheet.len(RANK_X);
        int right = s.right() - sheet.len(ACCOUNT_RIGHT);
        return new Rect(x, sheet.countBar().y(), right - x, h);
    }

    public static ScoreLayout of(int framebufferWidth, int framebufferHeight, Lines fixed, Lines reading, int people) {
        return new ScoreLayout(SheetLayout.of(framebufferWidth, framebufferHeight), fixed, reading, people);
    }

    public int len(double design) {
        return sheet.len(design);
    }

    public double k() {
        return sheet.k();
    }

    public SheetLayout sheet() {
        return sheet;
    }

    public Rect title() {
        return title;
    }

    public Rect subtitle() {
        return subtitle;
    }

    public List<Row> rows() {
        return rows;
    }

    public Rect card() {
        return card;
    }

    public Rect headAvatar() {
        return headAvatar;
    }

    public Rect caption() {
        return caption;
    }

    public Rect headName() {
        return headName;
    }

    public List<Item> items() {
        return items;
    }

    public Rect rule() {
        return rule;
    }

    public Rect sumLabel() {
        return sumLabel;
    }

    public Rect sum() {
        return sum;
    }

    /** 左下那行小字的整条（返航倒计时靠左、调试改动靠右）。 */
    public Rect foot() {
        return foot;
    }
}
