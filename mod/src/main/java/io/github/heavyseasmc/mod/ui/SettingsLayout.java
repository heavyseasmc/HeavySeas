package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;

/**
 * 设置菜单的版面（ADR-0099 版式 A：账本式 —— 左栏分组签与说明签，右栏一行一项），全部是<b>物理像素</b>。
 *
 * <h2>与对局各面同一副骨架</h2>
 * 板 · 上带（标题在阶段那一格的位置）· 最下面那一行按键提示，都取自 {@link SheetLayout}；这里只分中间那一块。
 * 常量是<b>稿子像素</b>（1280×720、界面尺寸 3 下的物理像素），乘 {@code k} 就是屏幕上的物理像素。
 *
 * <h2>字按行高排，其余从剩下的空间里算</h2>
 * 字号在小窗口下有地板（梯子最低一级），一行字比按比例算出来的高（证伪表「版面写死 GUI 单位的上限」）。
 * 所以凡含一行字的高度都取「稿子上的高度」与「这一级字真实的行高 + 留白」里大的那个；
 * 行放不下就滚动、签放不下就只露选中的那一段、说明签放不下就少给几行 —— 绝不压到别的东西上。
 * {@code SettingsLayoutTest} 在一串窗口尺寸上核「不重叠 · 不出界 · 说明签放得下」。
 *
 * <p>纯几何，不碰 Minecraft：字的行高由调用方按字号梯子给（{@link LineHeights}，客户端传 {@code GuiText::linePxAt}）。
 */
public final class SettingsLayout {

    /** 一级字号（物理像素）的一行有多高：客户端按字号梯子算（与真正画字的是同一个函数）。 */
    @FunctionalInterface
    public interface LineHeights {
        int linePx(int sizePx, boolean bold);
    }

    // ---- 字号（稿子像素）：界面画字也取这几个，版面与字只有一处定义
    public static final double TAG_PX = 18;
    public static final double ROW_PX = 18;
    public static final double NOTE_PX = 14;
    public static final double WIDGET_PX = 16;
    public static final double DETAIL_TITLE_PX = 18;
    public static final double DETAIL_PX = 15;
    public static final double LOCK_PX = 14;

    // ---- 版面（稿子像素）
    private static final double CONTENT_PAD_X = 28;
    private static final double CONTENT_TOP_GAP = 14;
    private static final double CONTENT_BOTTOM_GAP = 12;
    private static final double LEFT_SHARE = 0.27;
    private static final double LEFT_MIN = 200;
    private static final double LEFT_MAX = 340;
    private static final double COLUMN_GAP = 24;
    private static final double TAG_H = 46;
    private static final double TAG_PAD_Y = 8;
    private static final double TAG_GAP = 10;
    private static final double TAG_TO_DETAIL = 16;
    public static final double DETAIL_PAD = 12;
    public static final double DETAIL_GAP = 4;
    /** 说明签至少要放得下几行（不算名字那一行）：一句说明 · 默认与范围 · 什么时候生效。 */
    public static final int DETAIL_MIN_LINES = 3;
    private static final double HEADER_PAD_Y = 4;
    private static final double HEADER_GAP = 6;
    private static final double LOCK_W = 190;
    private static final double LOCK_PAD_X = 10;
    private static final double ROW_H = 58;
    private static final double ROW_PAD_X = 14;
    private static final double ROW_PAD_Y = 8;
    private static final double NAME_TO_WIDGET = 16;
    private static final double WIDGET_SHARE = 0.46;
    private static final double WIDGET_MIN = 200;
    private static final double WIDGET_MAX = 420;
    private static final double WIDGET_H = 36;
    private static final double WIDGET_PAD_Y = 6;
    private static final double SEGMENT_W = 84;
    private static final double CYCLE_VALUE_MAX = 200;
    private static final double NUMBER_W = 86;
    private static final double GAUGE_H = 18;
    private static final double PART_GAP = 8;
    private static final double SCROLLBAR_W = 6;
    private static final double SCROLLBAR_GAP = 8;

    private final SheetLayout sheet;
    private final int categories;
    private final int rowCount;
    private final int tagLine;
    private final int rowLine;
    private final int noteLine;
    private final int widgetLine;
    private final int detailTitleLine;
    private final int detailLine;
    private final int lockLine;

    private final Rect content;
    private final Rect left;
    private final Rect right;
    private final int tagH;
    private final int tagsVisible;
    private final Rect detailArea;
    private final Rect header;
    private final Rect lockTag;
    private final Rect status;
    private final Rect rowsArea;
    private final int rowH;
    private final int widgetH;
    private final int rowsVisible;

    private SettingsLayout(SheetLayout sheet, LineHeights lh, int categories, int rowCount) {
        this.sheet = sheet;
        this.categories = Math.max(1, categories);
        this.rowCount = Math.max(0, rowCount);
        this.tagLine = lh.linePx(sheet.len(TAG_PX), false);
        this.rowLine = lh.linePx(sheet.len(ROW_PX), false);
        this.noteLine = lh.linePx(sheet.len(NOTE_PX), false);
        this.widgetLine = lh.linePx(sheet.len(WIDGET_PX), false);
        this.detailTitleLine = lh.linePx(sheet.len(DETAIL_TITLE_PX), false);
        this.detailLine = lh.linePx(sheet.len(DETAIL_PX), false);
        this.lockLine = lh.linePx(sheet.len(LOCK_PX), false);

        Rect s = sheet.sheet();
        int x0 = s.x() + sheet.len(CONTENT_PAD_X);
        int y0 = sheet.phase(0).bottom() + sheet.len(CONTENT_TOP_GAP);
        int y1 = sheet.hintsTop() - sheet.len(CONTENT_BOTTOM_GAP);
        int x1 = s.right() - sheet.len(CONTENT_PAD_X);
        this.content = new Rect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));

        int leftW = clamp((int) Math.round(content.w() * LEFT_SHARE), sheet.len(LEFT_MIN), sheet.len(LEFT_MAX));
        leftW = Math.min(leftW, content.w() / 2);
        this.left = new Rect(content.x(), content.y(), leftW, content.h());
        int rx = left.right() + sheet.len(COLUMN_GAP);
        this.right = new Rect(rx, content.y(), Math.max(1, content.right() - rx), content.h());

        // 左栏：签从上往下排；签与说明签分高度时，说明签至少保住 DETAIL_MIN_LINES 行
        this.tagH = Math.max(sheet.len(TAG_H), tagLine + 2 * sheet.len(TAG_PAD_Y));
        int tagGap = sheet.len(TAG_GAP);
        int detailMin = detailHeight(DETAIL_MIN_LINES) + sheet.len(TAG_TO_DETAIL);
        int tagRoom = Math.max(tagH, left.h() - detailMin);
        this.tagsVisible = Math.max(1, Math.min(this.categories, (tagRoom + tagGap) / (tagH + tagGap)));
        int tagsBottom = left.y() + tagsVisible * tagH + (tagsVisible - 1) * tagGap;
        int dy = tagsBottom + sheet.len(TAG_TO_DETAIL);
        this.detailArea = new Rect(left.x(), dy, left.w(), Math.max(0, left.bottom() - dy));

        // 右栏：顶上一行（左边说状态、右头挂锁牌），下面是一行一项
        int lockH = lockLine + 2 * sheet.len(HEADER_PAD_Y);
        int headerH = Math.max(noteLine, lockH);
        this.header = new Rect(right.x(), right.y(), right.w(), headerH);
        int lockW = Math.min(sheet.len(LOCK_W), right.w() / 2);
        this.lockTag = new Rect(right.right() - lockW, right.y() + (headerH - lockH) / 2, lockW, lockH);
        this.status = new Rect(right.x(), right.y(), Math.max(1, lockTag.x() - sheet.len(PART_GAP) - right.x()), headerH);
        int ry = header.bottom() + sheet.len(HEADER_GAP);
        int rw = right.w() - sheet.len(SCROLLBAR_W) - sheet.len(SCROLLBAR_GAP);
        this.rowsArea = new Rect(right.x(), ry, Math.max(1, rw), Math.max(0, right.bottom() - ry));
        this.widgetH = Math.max(sheet.len(WIDGET_H), widgetLine + 2 * sheet.len(WIDGET_PAD_Y));
        int padY = sheet.len(ROW_PAD_Y);
        this.rowH = Math.max(sheet.len(ROW_H), Math.max(2 * padY + rowLine + noteLine, 2 * padY + widgetH));
        this.rowsVisible = Math.max(1, rowsArea.h() / rowH);
    }

    /**
     * @param categories 有几张签（一项都没有的组不算）
     * @param rows       当前这一组有几行
     */
    public static SettingsLayout of(int framebufferWidth, int framebufferHeight, LineHeights lineHeights,
                                    int categories, int rows) {
        return new SettingsLayout(SheetLayout.of(framebufferWidth, framebufferHeight), lineHeights, categories, rows);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public SheetLayout sheet() {
        return sheet;
    }

    /** 中间那一块（标题之下、按键提示那一行之上）。 */
    public Rect content() {
        return content;
    }

    public Rect left() {
        return left;
    }

    public Rect right() {
        return right;
    }

    // ---------------------------------------------------------------- 左栏

    /** 一屏露得下几张签：放不下全部时只露选中的那一段（{@link #tagScroll}）。 */
    public int tagsVisible() {
        return tagsVisible;
    }

    /** 选中第 {@code selected} 张时，露出来的第一张是第几张。 */
    public int tagScroll(int selected) {
        return io.github.heavyseasmc.mod.ui.SettingsMenuState.keepVisible(selected, 0, tagsVisible, categories);
    }

    /** 露出来的第 {@code i} 张签（0 起）。 */
    public Rect tag(int i) {
        return new Rect(left.x(), left.y() + i * (tagH + sheet.len(TAG_GAP)), left.w(), tagH);
    }

    /** 说明签能占的那一块（签下面一直到左栏底）。 */
    public Rect detailArea() {
        return detailArea;
    }

    /** 说明签里名字下面最多排几行。 */
    public int detailMaxLines() {
        int room = detailArea.h() - 2 * sheet.len(DETAIL_PAD) - detailTitleLine - sheet.len(DETAIL_GAP);
        return Math.max(0, room / Math.max(1, detailLine));
    }

    /** 名字下面排 {@code lines} 行的说明签（行数先夹到 {@link #detailMaxLines}）。 */
    public Rect detail(int lines) {
        int n = Math.min(Math.max(0, lines), detailMaxLines());
        return new Rect(detailArea.x(), detailArea.y(), detailArea.w(), Math.min(detailArea.h(), detailHeight(n)));
    }

    private int detailHeight(int lines) {
        return 2 * sheet.len(DETAIL_PAD) + detailTitleLine + sheet.len(DETAIL_GAP) + lines * detailLine;
    }

    public int detailTitleLine() {
        return detailTitleLine;
    }

    public int detailLine() {
        return detailLine;
    }

    // ---------------------------------------------------------------- 右栏

    /** 顶上那一行：左边是状态（「进存档后可改」「有改动没存」……），右头是锁牌。 */
    public Rect header() {
        return header;
    }

    /** 「只读 · 非管理员」那块牌。 */
    public Rect lockTag() {
        return lockTag;
    }

    /** 状态那一句占的格子（锁牌左边）。 */
    public Rect status() {
        return status;
    }

    public Rect rowsArea() {
        return rowsArea;
    }

    public int rowH() {
        return rowH;
    }

    /** 一屏放得下几行：放不下全部时滚动。 */
    public int rowsVisible() {
        return rowsVisible;
    }

    /** 屏幕上的第 {@code i} 行（0 起，已经扣掉滚动）。 */
    public Rect row(int i) {
        return new Rect(rowsArea.x(), rowsArea.y() + i * rowH, rowsArea.w(), rowH);
    }

    /** 滚动条的槽（行比一屏多时才画）。 */
    public Rect scrollTrack() {
        return new Rect(right.right() - sheet.len(SCROLLBAR_W), rowsArea.y(), sheet.len(SCROLLBAR_W),
                Math.min(rowsArea.h(), rowsVisible * rowH));
    }

    /** 滚动条上那一截（第一行是第 {@code scroll} 行时）。 */
    public Rect scrollThumb(int scroll) {
        Rect t = scrollTrack();
        int total = Math.max(1, rowCount);
        int h = Math.max(sheet.len(12), (int) Math.round(t.h() * Math.min(1.0, rowsVisible / (double) total)));
        h = Math.min(t.h(), h);
        int span = Math.max(1, total - rowsVisible);
        int y = t.y() + (int) Math.round((t.h() - h) * Math.min(1.0, Math.max(0, scroll) / (double) span));
        return new Rect(t.x(), y, t.w(), h);
    }

    /** 这一行里控件那一块（右对齐）。 */
    public Rect widget(Rect row) {
        int pad = sheet.len(ROW_PAD_X);
        int w = clamp((int) Math.round(row.w() * WIDGET_SHARE), sheet.len(WIDGET_MIN), sheet.len(WIDGET_MAX));
        w = Math.min(w, (row.w() - 2 * pad) * 3 / 5);
        return new Rect(row.right() - pad - w, row.y() + (row.h() - widgetH) / 2, w, widgetH);
    }

    /** 这一行的名字（左上）。 */
    public Rect name(Rect row) {
        int pad = sheet.len(ROW_PAD_X);
        int x1 = widget(row).x() - sheet.len(NAME_TO_WIDGET);
        int block = rowLine + noteLine;
        int y = row.y() + Math.max(sheet.len(ROW_PAD_Y), (row.h() - block) / 2);
        return new Rect(row.x() + pad, y, Math.max(1, x1 - row.x() - pad), rowLine);
    }

    /** 名字下面那一小行（「已改，未保存」「取值不对」）。 */
    public Rect note(Rect row) {
        Rect n = name(row);
        return new Rect(n.x(), n.bottom(), n.w(), noteLine);
    }

    /** 开关：[关][开] 两截，右对齐。 */
    public Rect[] toggle(Rect widget) {
        int w = Math.min(sheet.len(SEGMENT_W), widget.w() / 2);
        int x = widget.right() - 2 * w;
        return new Rect[]{new Rect(x, widget.y(), w, widget.h()), new Rect(x + w, widget.y(), w, widget.h())};
    }

    /** 选项：[←][值][→]，右对齐。 */
    public Rect[] cycle(Rect widget) {
        int key = Math.min(widget.h(), widget.w() / 4);
        int gap = sheet.len(PART_GAP);
        int value = Math.max(1, Math.min(sheet.len(CYCLE_VALUE_MAX), widget.w() - 2 * key - 2 * gap));
        int x = widget.right() - (2 * key + 2 * gap + value);
        return new Rect[]{new Rect(x, widget.y(), key, widget.h()),
                new Rect(x + key + gap, widget.y(), value, widget.h()),
                new Rect(x + key + gap + value + gap, widget.y(), key, widget.h())};
    }

    /** 数值：[数][液位管]，数在左、管子吃掉剩下的。 */
    public Rect[] gauge(Rect widget) {
        int number = Math.min(sheet.len(NUMBER_W), widget.w() / 3);
        int gap = sheet.len(PART_GAP);
        int barH = Math.min(widget.h(), sheet.len(GAUGE_H));
        int bx = widget.x() + number + gap;
        return new Rect[]{new Rect(widget.x(), widget.y(), number, widget.h()),
                new Rect(bx, widget.y() + (widget.h() - barH) / 2, Math.max(1, widget.right() - bx), barH)};
    }

    /** 文字与密钥：一整块输入框。 */
    public Rect field(Rect widget) {
        return widget;
    }

    // ---------------------------------------------------------------- 字号

    public int rowLine() {
        return rowLine;
    }

    public int noteLine() {
        return noteLine;
    }

    public int widgetLine() {
        return widgetLine;
    }

    public int tagLine() {
        return tagLine;
    }

    public int lockLine() {
        return lockLine;
    }

    /** 稿子像素换成物理像素（字号用）。 */
    public int len(double design) {
        return sheet.len(design);
    }

    public int padX() {
        return sheet.len(LOCK_PAD_X);
    }

    public int detailPad() {
        return sheet.len(DETAIL_PAD);
    }

    public int detailGap() {
        return sheet.len(DETAIL_GAP);
    }
}
