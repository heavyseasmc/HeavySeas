package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 「选一个人」的那一排大头像：医疗箱挑目标 · 赠送给谁两面共用（ADR-0050，用户 2026-10-01 看样图定「头像版」）。
 *
 * <p>左边是要用的那张牌、一个箭头，右边一排人：头像 · 外圈 · 与座位轨同一套公开状态（伤势压暗 · 「剩 / 体型」印章 ·
 * 「昏」签，{@link SeatMarks}）· 名字；医疗箱那一面名字下再一排体力点（治的就是它）。
 * 选中那位往上提一截、换金圈 —— 与牌「抽出来」同一个意思。原先医疗箱是「名字 · 体力 3/4」一排按钮，
 * 赠送是一次只看一个人、按 ← → 翻。
 *
 * <p>几何（{@link #layout}）是纯函数、按物理像素算；画的时候整段都在物理像素里（{@code pxBegin}）。
 */
final class PortraitPick {

    /** 牌宽 · 箭头那一截 · 名字字号 · 体力点直径与间距 · 头像下的缝 · 选中往上提多少（稿子像素，样图）。 */
    static final double CARD_W = 150;
    static final double ARROW_GAP = 66;
    static final double NAME_PX = 19;
    static final double PIP = 13;
    static final double PIP_GAP = 4;
    static final double UNDER = 6;
    static final double LIFT = 16;
    /**
     * 名字那一行在一格里左右各让出多少（稿子像素）：相邻两格的字不贴在一起。原先名字铺满整格，
     * 整批那一格截断的名单「Jeweller · Col···」与旁边「Do nothing」贴成一串（2026-10-13 验收 L08）。
     */
    static final double NAME_GUTTER = 8;

    private static int gutter(double kk) {
        return Math.max(1, (int) Math.round(NAME_GUTTER * kk));
    }

    /**
     * 一排两头各让出多少（稿子像素，从板的外沿量）。原先 40、与牌排同一个数 —— 可板的木框加铜圈就占了约 33，
     * 人一多、整排等比缩到撑满时，最右那个头像贴着板右框（2026-10-13 总截图 09，钉住日志时医疗箱 7 人）。
     */
    static final double SIDE_ROOM = 72;

    /** 一个人：id，以及要不要画体力点（{@code max <= 0} = 不画）。 */
    record Person(String id, int hp, int max, List<String> members) {

        Person(String id, int hp, int max) {
            this(id, hp, max, List.of());
        }

        Person {
            members = List.copyOf(members);
        }

        static Person allSwimmers(List<String> members) {
            return new Person("", 0, 0, members);
        }

        boolean pips() {
            return max > 0;
        }

        /** 一排最后那一格「什么也不做」（{@link #PASS}）。 */
        static Person pass() {
            return new Person(PASS, 0, 0);
        }

        boolean isPass() {
            return PASS.equals(id) && members.isEmpty();
        }
    }

    /**
     * 「什么也不做」那一格的 id（不是任何角色）：落海那一窗的「不用」与行动面最后那一张同一张牌、同一套键 ——
     * ← → 挑到它、Enter / 点它就定（用户 2026-10-07：已有的键复用，不另起一个；Esc 只收起）。
     */
    static final String PASS = "";

    /**
     * 这一帧的几何，全部是物理像素。
     *
     * @param d     头像直径
     * @param col   一格多宽（头像 + 两边的空）
     * @param x     每一格的左沿
     * @param top   头像的顶（没提起时）
     * @param block 头像到名字（及体力点）底一共多高
     */
    record Geometry(int cardX, int cardY, int cardW, int cardH, int arrowX, int arrowY, int arrowPx,
                    int d, int col, int[] x, int top, int block, int lift) {
    }

    /**
     * 排一排：题头之下、倒计时外圈之上，牌与那一排人整块竖向居中；放不下就整块等比缩（头像 · 牌 · 字一起缩）。
     *
     * @param token 头像直径（稿子像素）：医疗箱 96，赠送 80（人多一些）
     * @param cell  一格多宽（稿子像素）
     * @param groupLines 这一排里有「整批」那一格时名字底下的名单排几行（{@link NameList}）；没有那一格是 0
     */
    static Geometry layout(SheetLayout l, int count, double top0, double token, double cell, boolean pips,
                           int groupLines) {
        int n = Math.max(1, count);
        double k = l.k();
        double floor = l.countBar().y() - (CardRow.BAR_RIM + CardRow.BAR_CLEAR) * k;
        double room = l.sheet().w() - 2 * SIDE_ROOM * k;
        double want = (CARD_W + ARROW_GAP + n * cell) * k;
        double f = Math.min(1.0, room / want);
        double kk = k * f;
        int cardW = (int) Math.round(CARD_W * kk);
        int d = (int) Math.round(token * kk);
        int col = (int) Math.round(cell * kk);
        int lift = (int) Math.round(LIFT * kk);
        int nameLine = GuiText.linePxAt(Math.max(1, (int) Math.round(NAME_PX * kk)), true);
        int block = d + (int) Math.round(UNDER * kk) + nameLine
                + (pips ? (int) Math.round((UNDER + PIP) * kk) : 0)
                + groupLines * GuiText.linePxAt(Math.max(1, (int) Math.round(GROUP_LIST_PX * kk)), false);
        int cardH = GuiLanguage.cardHeight(cardW);
        // 竖向：牌高与「提起 + 那一排人」取高的那个，在 top0 与 floor 之间居中；实在放不下就把牌缩到放得下
        double avail = floor - top0;
        if (cardH > avail) {
            cardH = (int) Math.max(8, avail);
            cardW = GuiLanguage.cardWidth(cardH);
        }
        int blockH = Math.max(cardH, lift + block);
        int blockTop = (int) Math.round(top0 + Math.max(0, (avail - blockH) / 2));
        int cardY = blockTop + (blockH - cardH) / 2;
        int top = blockTop + (blockH - block) / 2 + lift / 2;
        int arrowGap = (int) Math.round(ARROW_GAP * kk);
        int total = cardW + arrowGap + n * col;
        int left = l.width() / 2 - total / 2;
        int[] x = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = left + cardW + arrowGap + i * col;
        }
        int arrowPx = Math.max(1, (int) Math.round(40 * kk));
        return new Geometry(left, cardY, cardW, cardH, left + cardW + (arrowGap - arrowPx) / 2,
                top + d / 2 - GuiText.linePxAt(arrowPx, false) / 2, arrowPx, d, col, x, top, block, lift);
    }

    // ---------------------------------------------------------------- 实例

    private final GameScreen screen;
    private final double token;
    private final double cell;
    private int focus;
    private float[] lift = new float[0];
    private Geometry geo;

    PortraitPick(GameScreen screen, double token, double cell) {
        this.screen = screen;
        this.token = token;
        this.cell = cell;
    }

    int focus() {
        return focus;
    }

    void focus(int i) {
        focus = i;
    }

    /**
     * 画这一帧。
     *
     * @param drawCard 左边那张牌怎么画（物资牌，GUI 单位的左上角与宽高）
     */
    void render(DrawContext context, int mouseX, int mouseY, long dt, double top0, List<Person> people,
                CardDrawer drawCard) {
        SheetLayout l = screen.sheet();
        int s = screen.guiScale();
        boolean pips = !people.isEmpty() && people.getFirst().pips();
        List<String> members = people.stream().filter(p -> !p.members().isEmpty()).findFirst()
                .map(Person::members).orElse(List.of());
        geo = layout(l, people.size(), top0, token, cell, pips, members.isEmpty() ? 0 : 1);
        groupList = members.isEmpty() ? List.of() : groupLines(members, geo.d() / token);
        if (groupList.size() > 1) {
            geo = layout(l, people.size(), top0, token, cell, pips, groupList.size());   // 名单多几行：整块重新竖向居中
            groupList = groupLines(members, geo.d() / token);
        }
        if (lift.length != people.size()) {
            lift = new float[people.size()];
        }
        focus = Math.max(0, Math.min(focus, people.size() - 1));
        if (screen.mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = indexAt(mouseX, mouseY, people.size());
            if (hovered >= 0) {
                focus = hovered;
            }
        }
        drawCard.draw(context, geo.cardX() / s, geo.cardY() / s, geo.cardW() / s, geo.cardH() / s);
        double kk = geo.d() / token;                       // 这一排实际的系数（放不下时整块等比缩过）
        screen.pxBegin(context);
        GuiText.drawPx(context, "→", geo.arrowX(), geo.arrowY(), geo.arrowPx() * 2, geo.arrowPx(), false,
                GuiLanguage.secondary(), GuiText.Align.LEFT, 0);
        for (int i = 0; i < people.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? geo.lift() : 0f, dt);
            drawPerson(context, l, i, people.get(i), i == focus, kk);
        }
        screen.pxEnd(context);
    }

    private void drawPerson(DrawContext context, SheetLayout l, int i, Person p, boolean on, double kk) {
        if (!p.members().isEmpty()) {
            drawGroup(context, i, p.members(), on, kk);
            return;
        }
        if (p.isPass()) {
            drawPass(context, i, on, kk);
            return;
        }
        int d = geo.d();
        int tx = geo.x()[i] + (geo.col() - d) / 2;
        int ty = geo.top() - Math.round(lift[i]);
        Rect t = new Rect(tx, ty, d, d);
        var seat = SeatMarks.seat(p.id());
        GuiMaterial.portrait(context, p.id(), tx, ty, d, 1f);
        seat.ifPresent(st -> SeatMarks.shade(context, t, SeatMarks.Kit.TOKEN46, st, d / (double) HudPart.TOK46_PLAIN.w()));
        HudPart ring = on ? HudPart.TOK48_YOU : HudPart.TOK48_PLAIN;
        GuiMaterial.hudPart(context, ring, tx, ty, d, d, d / (double) ring.w());
        // 印章与签不跟头像放大（ADR-0048 §8 第 1 条：用户 2026-10-01 认了）
        seat.ifPresent(st -> SeatMarks.marks(context, t, st, l.k()));
        int namePx = Math.max(1, (int) Math.round(NAME_PX * kk));
        int y = ty + d + (int) Math.round(UNDER * kk);
        GuiText.drawPx(context, screen.nameOf(p.id()).getString(), geo.x()[i] + gutter(kk), y, geo.col() - 2 * gutter(kk), namePx, true,
                on ? GuiLanguage.ink() : GuiLanguage.secondary(), GuiText.Align.CENTER, 0);
        if (p.pips()) {
            int pip = Math.max(1, (int) Math.round(PIP * kk));
            int gap = Math.max(1, (int) Math.round(PIP_GAP * kk));
            int w = p.max() * pip + (p.max() - 1) * gap;
            int px = geo.x()[i] + (geo.col() - w) / 2;
            int py = y + GuiText.linePxAt(namePx, true) + (int) Math.round(UNDER * kk);
            for (int j = 0; j < p.max(); j++) {
                GuiMaterial.hudPart(context, j < p.hp() ? HudPart.PIP_ON : HudPart.PIP_OFF, px + j * (pip + gap), py,
                        pip, pip, kk);
            }
        }
    }

    /**
     * 整批落海者共用一格（血饵一类「作用于这一批所有人」的牌）：重叠的头像、一句「这一批所有人」、下面一行短名单。
     *
     * <p>头像最多叠 {@link #GROUP_FACES} 个，再多就在最后一个上压一枚「+N」—— 原先多出来的人一声不吭地没画，
     * 看上去像只影响三个人（2026-10-13 验收）。名单按名字整个换行、至多三行，再多写「+N」（{@link io.github.heavyseasmc.mod.ui.NameList}；
     * 原先一行写全、放不下截断，8 人全落海时只剩「珠宝商 · 收…」，2026-10-13 总截图 08）。
     */
    private void drawGroup(DrawContext context, int i, List<String> members, boolean on, double kk) {
        int d = geo.d();
        int x = geo.x()[i] + (geo.col() - d) / 2;
        int y = geo.top() - Math.round(lift[i]);
        int count = Math.min(GROUP_FACES, members.size());
        int small = Math.max(1, Math.round(d * 0.65f));
        int offset = count <= 1 ? 0 : (d - small) / (count - 1);
        int px = x;
        int py = y;
        for (int j = 0; j < count; j++) {
            px = x + (count == 1 ? (d - small) / 2 : j * offset);
            py = y + j * offset;
            GuiMaterial.portrait(context, members.get(j), px, py, small, 1f);
            HudPart ring = on ? HudPart.TOK48_YOU : HudPart.TOK48_PLAIN;
            GuiMaterial.hudPart(context, ring, px, py, small, small, small / (double) ring.w());
        }
        int more = members.size() - count;
        if (more > 0) {
            // 「+N」压在最后一个头像的右下角，一块签子底（跟主题）上写字
            int morePx = Math.max(1, (int) Math.round(MORE_PX * kk));
            String label = "+" + more;
            int lw = GuiText.widthPx(label, morePx, true, 0) + 2 * Math.max(1, (int) Math.round(MORE_PAD * kk));
            int lh = GuiText.linePxAt(morePx, true);
            int lx = px + small - lw;
            int ly = py + small - lh;
            GuiMaterial.hudPart(context, HudPart.KEY, lx, ly, lw, lh, kk);
            GuiText.drawPx(context, label, lx, ly, lw, morePx, true, GuiLanguage.Hud.ENAMEL_LINE, GuiText.Align.CENTER, 0);
        }
        int namePx = Math.max(1, (int) Math.round(NAME_PX * kk));
        int labelY = y + d + (int) Math.round(UNDER * kk);
        GuiText.drawPx(context, net.minecraft.text.Text.translatable("heavyseas.overboard.all_targets").getString(),
                geo.x()[i] + gutter(kk), labelY, geo.col() - 2 * gutter(kk), namePx, true,
                on ? GuiLanguage.ink() : GuiLanguage.secondary(), GuiText.Align.CENTER, 0);
        // 受影响的是谁：名字按整个换行（「 · 」隔开），至多 GROUP_LIST_LINES 行，再多写「+N」（NameList）
        int listPx = Math.max(1, (int) Math.round(GROUP_LIST_PX * kk));
        int listY = labelY + GuiText.linePxAt(namePx, true);
        for (String line : groupList) {
            GuiText.drawPx(context, line, geo.x()[i] + gutter(kk), listY, geo.col() - 2 * gutter(kk), listPx, false,
                    GuiLanguage.secondary(), GuiText.Align.CENTER, 0);
            listY += GuiText.linePxAt(listPx, false);
        }
    }

    /** 这一帧整批那一格底下的名单，一行一项。 */
    private List<String> groupList = List.of();

    /** 名单至多几行：再多写「+N」。 */
    static final int GROUP_LIST_LINES = 3;

    private List<String> groupLines(List<String> members, double kk) {
        List<String> names = new java.util.ArrayList<>(members.size());
        for (String member : members) {
            names.add(screen.nameOf(member).getString());
        }
        int listPx = Math.max(1, (int) Math.round(GROUP_LIST_PX * kk));
        return io.github.heavyseasmc.mod.ui.NameList.pack(names, " · ", GROUP_LIST_LINES, geo.col() - 2 * gutter(kk),
                s -> GuiText.widthPx(s, listPx, false, 0));
    }

    /** 一格里最多叠几个头像；多出来的写「+N」。 */
    static final int GROUP_FACES = 3;
    /** 「+N」那枚小签的字号与左右留白、整批那一格底下名单的字号（稿子像素）。 */
    static final double MORE_PX = 14;
    static final double MORE_PAD = 4;
    static final double GROUP_LIST_PX = 14;

    /** 「什么也不做」那一格：一张与头像同高的小牌（行动面那一张），选中时描金，名字写在下面 —— 与人那几格同一个高度与名字行。 */
    private void drawPass(DrawContext context, int i, boolean on, double kk) {
        int h = geo.d();
        int w = GuiLanguage.cardWidth(h);
        int tx = geo.x()[i] + (geo.col() - w) / 2;
        int ty = geo.top() - Math.round(lift[i]);
        CardTexture.drawAction(context, io.github.heavyseasmc.mod.card.ActionCard.PASS, tx, ty, w, h);
        if (on) {
            int t = Math.max(1, (int) Math.round(3 * kk));
            for (int k = 1; k <= t; k++) {
                context.drawBorder(tx - k, ty - k, w + 2 * k, h + 2 * k, GuiLanguage.gold());
            }
        }
        int namePx = Math.max(1, (int) Math.round(NAME_PX * kk));
        int y = ty + h + (int) Math.round(UNDER * kk);
        GuiText.drawPx(context, net.minecraft.text.Text.translatable(
                        io.github.heavyseasmc.mod.card.ActionCard.PASS.titleKey()).getString(),
                geo.x()[i] + gutter(kk), y, geo.col() - 2 * gutter(kk), namePx, true,
                on ? GuiLanguage.ink() : GuiLanguage.secondary(), GuiText.Align.CENTER, 0);
    }

    /** 指针落在第几格（GUI 单位）：一格从提起后的头像顶到名字（体力点）底。 */
    int indexAt(double mouseX, double mouseY, int count) {
        if (geo == null) {
            return -1;
        }
        int s = screen.guiScale();
        double px = mouseX * s;
        double py = mouseY * s;
        if (py < geo.top() - geo.lift() || py > geo.top() + geo.block()) {
            return -1;
        }
        for (int i = 0; i < Math.min(count, geo.x().length); i++) {
            if (px >= geo.x()[i] && px < geo.x()[i] + geo.col()) {
                return i;
            }
        }
        return -1;
    }

    /** ←→ 挪一格。返回 {@code true} = 认了这个键。 */
    boolean keyPressed(int keyCode, int count) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP) {
            focus = Math.max(0, focus - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN) {
            focus = Math.min(count - 1, focus + 1);
            return true;
        }
        return false;
    }

    /** 左边那张牌怎么画（GUI 单位）。 */
    @FunctionalInterface
    interface CardDrawer {
        void draw(DrawContext context, int x, int y, int w, int h);
    }
}
