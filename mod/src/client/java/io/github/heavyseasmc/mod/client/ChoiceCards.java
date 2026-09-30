package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.card.ActionCard;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * 「选一件事」的那一排牌：行动 · 表态 · 站队三面共用（ADR-0050，用户 2026-10-01 看样图定的 B 形态）。
 *
 * <ul>
 *   <li><b>牌放大、不挂说明签</b>：一排最多五张，横向宽得很，纵向才是紧的那一维 —— 签子挂在下面会把牌压到 132 宽。
 *       说明留给 U（查看态：牌收成一叠，右边一块说明板，与补给箱同一个机制）。</li>
 *   <li><b>抽出来</b>：与 {@link CardRow} 同一组数（往左转 · 往上提 · 没选中的压暗 · 金框），选中哪张就抽哪张。</li>
 *   <li><b>最后一张可以小一号、退到一边</b>（样图 C）：「什么也不做」是超时的默认，不该与四件真事并排抢眼。</li>
 *   <li><b>按不动的压得更暗</b>，焦点跳过它（无风那天的划船）。按不动的事不发包 —— 界面不给出一件必然失败的事。</li>
 *   <li><b>确认那一下「顿」</b>：与补给箱同一个动词、同一组数。补给箱里它说「替你定了」，这里说「你定了」。</li>
 * </ul>
 *
 * <p>几何（{@link #layout}）是纯函数、按物理像素算（{@code ChoiceCardsTest} 各档窗口都查）；
 * 画牌时换成 GUI 单位。实例这一半记焦点 · 抬起 · 「顿」，由所在那一面每帧喂进来。
 */
final class ChoiceCards {

    private static final Logger LOGGER = LoggerFactory.getLogger(io.github.heavyseasmc.mod.HeavySeasMod.MOD_ID);

    /** 牌最宽多少（稿子像素）：样图 B 的 190。空间更多时也不再放大。 */
    static final double MAX_W = 190;
    /** 小一号的那张是正常牌宽的几成（样图 C）。 */
    static final double SMALL = 0.82;
    /** 小一号的那张与前一张之间多隔多少（稿子像素，在正常的缝之外）。 */
    static final double SEP = 46;
    /** 一排两头各让出多少（稿子像素）：与补给箱那一排同一个边距。 */
    static final double SIDE_ROOM = 40;
    /** 按不动的那张压到几成亮（没选中的是 {@link CardRow#DIM}）。 */
    static final float OFF = 0.5f;

    /**
     * 这一帧的几何，全部是物理像素。牌底对齐：小一号那张的顶比别的低一截。
     *
     * @param w    正常牌宽
     * @param h    正常牌高
     * @param top  正常牌的顶（没抽出来时）
     * @param x    每张的左沿
     * @param cw   每张的宽
     */
    record Geometry(int w, int h, int top, int[] x, int[] cw) {

        int bottom() {
            return top + h;
        }

        int cardH(int i) {
            return GuiLanguage.cardHeight(cw[i]);
        }

        int cardTop(int i) {
            return bottom() - cardH(i);
        }

        int right() {
            return x[x.length - 1] + cw[cw.length - 1];
        }
    }

    /**
     * 排一排：座位轨（或题头）之下、倒计时外圈之上，自上而下留「抽出来 + 顿」的空 · 牌 · {@code below}；
     * 空间多了整块竖向居中。牌宽取三者最小：{@link #MAX_W} · 高度放得下 · 宽度放得下。
     *
     * @param top0      牌区从哪儿起（座位轨名字下沿，或题头那一行下沿）
     * @param below     牌下面还要挂多高的东西（站队一面两边的人）
     * @param snapRise  「顿」峰值往上多少（物理像素）
     * @param snapScale 「顿」峰值放大多少（1.04 → 0.04）；牌绕底边放大，长出来的全在上面
     */
    static Geometry layout(SheetLayout l, int count, boolean lastSmall, double top0, double below,
                           double snapRise, double snapScale) {
        int n = Math.max(1, count);
        boolean small = lastSmall && n > 1;
        double k = l.k();
        double floor = l.countBar().y() - (CardRow.BAR_RIM + CardRow.BAR_CLEAR) * k;
        double aspect = (double) GuiLanguage.CARD_H / GuiLanguage.CARD_W;
        double fixed = (CardRow.PULL_LIFT + CardRow.FRAME_OUT) * k + snapRise;
        double t = Math.toRadians(CardRow.PULL_DEG);
        double perH = 1 + snapScale + Math.sin(t) / (2 * aspect) - (1 - Math.cos(t));
        double hFit = (floor - top0 - fixed - below) / perH;
        double gap = l.len(SheetLayout.CARD_GAP);
        double sep = small ? SEP * k : 0;
        double room = l.sheet().w() - 2 * SIDE_ROOM * k;
        // 宽度：n 张（最后一张按 SMALL 算）+ (n−1) 道缝 + 小一号那张前多出的一截 ≤ room
        double wFit = (room - (n - 1) * gap - sep) / (small ? n - 1 + SMALL : n);
        int w = (int) Math.max(8, Math.min(Math.round(MAX_W * k), Math.floor(Math.min(hFit / aspect, wFit))));
        int h = GuiLanguage.cardHeight(w);
        double above = fixed + CardRow.rotationRise(w, h, CardRow.PULL_DEG) + snapScale * h;
        double spare = Math.max(0, floor - top0 - (above + h + below));
        int top = (int) Math.round(top0 + spare / 2 + above);

        int[] cw = new int[n];
        for (int i = 0; i < n; i++) {
            cw[i] = small && i == n - 1 ? (int) Math.round(w * SMALL) : w;
        }
        int rowW = (int) Math.round((n - 1) * gap + sep);
        for (int c : cw) {
            rowW += c;
        }
        int[] x = new int[n];
        int at = l.width() / 2 - rowW / 2;
        for (int i = 0; i < n; i++) {
            if (small && i == n - 1) {
                at += (int) Math.round(sep);
            }
            x[i] = at;
            at += cw[i] + (int) Math.round(gap);
        }
        return new Geometry(w, h, top, x, cw);
    }

    // ---------------------------------------------------------------- 实例：焦点 · 抬起 · 顿

    private final GameScreen screen;
    private final List<ActionCard> cards;
    private final boolean lastSmall;
    private final IntPredicate enabled;
    private final Function<ActionCard, Text> effect;
    private int focus;
    private final float[] lift;
    private int snapIndex = -1;
    private long snapAt;
    private Geometry geo;
    private String logged = "";

    /**
     * @param focus   一进来焦点在哪（各面的「什么都不按会发生的事」）
     * @param enabled 第 i 张此刻按不按得动
     * @param effect  说明板上那一句（按不动的那张要说为什么按不动，所以由各面给）
     */
    ChoiceCards(GameScreen screen, List<ActionCard> cards, boolean lastSmall, int focus, IntPredicate enabled,
                Function<ActionCard, Text> effect) {
        this.screen = screen;
        this.cards = List.copyOf(cards);
        this.lastSmall = lastSmall;
        this.focus = focus;
        this.enabled = enabled;
        this.effect = effect;
        this.lift = new float[cards.size()];
    }

    int focus() {
        return focus;
    }

    ActionCard focused() {
        return cards.get(focus);
    }

    boolean decided() {
        return snapAt > 0L;
    }

    /** 「顿」还在播：播完之前这一面不收（收掉的话那一下就白放了）。 */
    boolean snapping() {
        return decided() && GuiLanguage.snap(System.currentTimeMillis(), snapAt) < 1f;
    }

    /** 焦点停在按不动的那张上（开面那一下，或天候变了之后）：挪到第一张按得动的。 */
    private void keepFocusEnabled() {
        if (!enabled.test(focus)) {
            for (int i = 0; i < cards.size(); i++) {
                if (enabled.test(i)) {
                    focus = i;
                    return;
                }
            }
        }
    }

    /**
     * 画这一帧。所在那一面先画好共有的带，再调它。
     *
     * @param top0  牌区从哪儿起（物理像素）
     * @param below 牌下面还要挂多高（物理像素）
     * @return 这一帧的几何（物理像素），牌下还要画东西的那一面用它
     */
    Geometry render(DrawContext context, GameScreen.Bands b, int mouseX, int mouseY, long now, long dt,
                    double top0, double below) {
        SheetLayout l = screen.sheet();
        int s = screen.guiScale();
        geo = layout(l, cards.size(), lastSmall, top0, below, GuiLanguage.SNAP_PEAK_RISE * s, GuiLanguage.SNAP_PEAK_SCALE - 1f);
        logLayout();
        if (!decided()) {
            keepFocusEnabled();
        }
        float g = screen.gathered(now);
        // 鼠标真的动了才把焦点带过去（停着的指针不算指向）；查看态里牌收成一叠，不按位置认。
        // ❗每帧都要调一次 mouseActuallyMoved：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        boolean moved = screen.mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided() && !screen.inspecting()) {
            int hovered = indexAt(mouseX, mouseY);
            if (hovered >= 0 && enabled.test(hovered)) {
                focus = hovered;
            }
        }
        GameScreen.Inspect in = screen.inspect(b);
        float liftMax = (float) (CardRow.PULL_LIFT * l.k() / s);
        float snapP = GuiLanguage.snap(now, snapAt);
        for (int i = 0; i < cards.size(); i++) {
            if (i != focus) {
                drawOne(context, i, s, in, g, dt, liftMax, snapP);
            }
        }
        drawOne(context, focus, s, in, g, dt, liftMax, snapP);
        if (g > 0f) {
            ActionCard c = cards.get(focus);
            screen.drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    Text.translatable(c.group().captionKey()), Text.translatable(c.titleKey()), effect.apply(c));
        }
        return geo;
    }

    private void drawOne(DrawContext context, int i, int s, GameScreen.Inspect in, float g, long dt,
                         float liftMax, float snapP) {
        boolean hi = i == focus;
        lift[i] = GuiLanguage.approach(lift[i], hi ? liftMax : 0f, dt);
        float pull = liftMax > 0f ? lift[i] / liftMax * (1f - g) : 0f;
        float w = geo.cw()[i] / (float) s;
        float h = geo.cardH(i) / (float) s;
        int depth = hi ? 0 : 1 + Math.abs(i - focus);
        GameScreen.CardPose pose = screen.cardPose(in, g, geo.x()[i] / (float) s + w / 2f, geo.bottom() / (float) s,
                Math.round(w), Math.round(h), depth);
        float rise = 0f;
        float scale = 1f;
        if (i == snapIndex) {
            rise = GuiLanguage.snapRise(snapP);
            scale = GuiLanguage.snapScale(snapP);
        }
        context.getMatrices().push();
        context.getMatrices().translate(pose.cx(), pose.bottom() - lift[i] * (1f - g) + rise, 0);
        CardRow.rotate(context, pull);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-pose.w() / 2f, -pose.h(), 0);
        screen.drawCardShadow(context, pose.w(), pose.h(), Math.abs(rise) + lift[i] * (1f - g));
        boolean off = !enabled.test(i);
        if (off) {
            context.setShaderColor(OFF, OFF, OFF, 1f);
        } else if (!hi) {
            CardRow.dim(context);
        }
        CardTexture.drawAction(context, cards.get(i), 0, 0, pose.w(), pose.h());
        CardRow.undim(context);
        if (hi) {
            GameScreen.drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    /**
     * 这一排的版面记一行（与语言无关，物理像素），变了才记。客户端回归（click_test）要从这一行知道往哪儿点 ——
     * 按窗口比例估的点，换一档窗口就落到牌外面去了（ADR-0049 同一条教训）。
     */
    private void logLayout() {
        StringBuilder xs = new StringBuilder();
        for (int i = 0; i < geo.x().length; i++) {
            xs.append(i == 0 ? "" : ",").append(geo.x()[i]).append('+').append(geo.cw()[i]);
        }
        String key = screen.getClass().getSimpleName() + "/" + xs + "/" + geo.top() + "/" + geo.h();
        if (!key.equals(logged)) {
            logged = key;
            LOGGER.info("选牌版面：{} {} 张 · 左沿+宽 {} · 顶 {} · 牌高 {}（物理像素）",
                    screen.getClass().getSimpleName(), geo.x().length, xs, geo.top(), geo.h());
        }
    }

    /** 指针落在第几张上（GUI 单位）：选中那张先问（它抽出来、压在最上面）。查看态里一律 -1。 */
    int indexAt(double mouseX, double mouseY) {
        if (geo == null || screen.inspecting()) {
            return -1;
        }
        int s = screen.guiScale();
        double px = mouseX * s;
        double py = mouseY * s;
        double room = CardRow.pullRoom(CardRow.PULL_LIFT * screen.sheet().k(), geo.w(), geo.h(), 0);
        if (hit(focus, px, py, room)) {
            return focus;
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (hit(i, px, py, 0)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hit(int i, double px, double py, double room) {
        return px >= geo.x()[i] && px < geo.x()[i] + geo.cw()[i] && py >= geo.cardTop(i) - room && py <= geo.bottom();
    }

    /** 往一边挪一格，跳过按不动的；挪不动就留在原地（「按了没反应」与「界面卡住了」长得一样）。 */
    private void step(int dir) {
        for (int i = focus + dir; i >= 0 && i < cards.size(); i += dir) {
            if (enabled.test(i)) {
                focus = i;
                return;
            }
        }
    }

    /**
     * 键盘。U / Tab / 查看态里的 Esc 交给查看态；←→ 挪焦点；Enter / 空格确认。定了之后一概不认。
     *
     * @param confirm 确认第 i 张：返回 {@code true} 就播一次「顿」（打开手牌那一件不播 —— 它还没定）
     */
    boolean keyPressed(int keyCode, Predicate<Integer> confirm) {
        if (decided()) {
            return false;
        }
        if (screen.inspectKey(keyCode)) {
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                step(-1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                step(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                confirm(confirm);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 左键：点在牌上 = 选中并确认。查看态里不认位置（牌收成了一叠）。 */
    boolean leftClick(double mouseX, double mouseY, Predicate<Integer> confirm) {
        if (decided()) {
            return false;
        }
        int i = indexAt(mouseX, mouseY);
        if (i < 0 || !enabled.test(i)) {
            return false;
        }
        focus = i;
        confirm(confirm);
        return true;
    }

    /** 右键 = 看（ADR-0043 §7.0）：先把焦点挪到点中的那张，再进查看态。永远不确认。 */
    boolean rightClick(double mouseX, double mouseY) {
        if (decided()) {
            return false;
        }
        return screen.inspectClick(indexAt(mouseX, mouseY), i -> focus = i);
    }

    private void confirm(Predicate<Integer> confirm) {
        if (!enabled.test(focus)) {
            return;                       // 按不动的不发包、不「顿」
        }
        if (screen.inspecting()) {
            screen.toggleInspect();       // 在查看态里按 Enter：定的是堆顶那张，先摊回去再「顿」
        }
        if (confirm.test(focus)) {
            snapIndex = focus;
            snapAt = System.currentTimeMillis();
            GuiSound.snapped(snapAt);
        }
    }
}
