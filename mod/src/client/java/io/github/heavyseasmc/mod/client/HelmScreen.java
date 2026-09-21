package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.net.HelmActionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 舵手挑牌：划船堆里的牌只摊给舵手，挑 1 张执行，其余面朝下塞回牌堆底（决策 ⑭ · ADR-0018 §7.4）。
 *
 * <h2>没人划船时这一面不出现</h2>
 * 服务端当场翻顶牌，这一面连开都不开 —— ADR-0018 §7.4：不要做成只有一张牌的选择界面。
 * 客户端不自己判断：划船堆的牌只在该开的时候才进舵手那一包（{@code GameComponent#writeView}）。
 *
 * <h2>12 秒，超时认高亮，用「顿」</h2>
 * 用户 2026-09-15 定，与补给箱同一条规则：高亮一进界面就在第一张（它是「你的默认答案」），每次移动都上报；
 * 超时时服务端单发一包说替你挑的是第几张，这一面先把高亮挪过去、「顿」一下，播完再关。手动挑的点完就关。
 *
 * <h2>界面不能揭穿舵手</h2>
 * 这一面只在舵手自己的屏幕上。别人的屏幕上只有「划船堆 N 张 · 舵手 X · 倒计时」（HUD），
 * 结算后只公开被执行的那一张 —— 没挑中的几张不会出现在任何人的屏幕上。
 *
 * <h2>牌面是贴图</h2>
 * 三档按物理像素选（{@link CardTexture#drawNav}）；一排好几张时牌很窄，字会小到读不清 ——
 * 所以高亮那一张下面另有一行完整说明（{@link NavCardText#describe}），与补给箱「说明只跟高亮走」同一个做法。
 */
public final class HelmScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 说明两行之间。 */
    private static final int LINE_GAP = 3;

    private HudView view;
    /** 打开时的划船堆。结算那一刻投影里就没有了，而「顿」还要画在它上面 —— 所以留一份。 */
    private final List<NavCardView> offer;
    /** 这一个挑牌窗口的超时时刻（服务端时钟）。 */
    private final long deadlineMs;
    private final float[] lift;
    private final long dealAt;
    /** 高亮。一进来就在第一张，与服务端开窗时的默认一致。 */
    private int highlight;
    /** 服务端替你挑的那张；-1 表示没被替挑。 */
    private int snapIndex = -1;
    private long snapAt;
    /** 你自己挑定了（包已发出，等服务端结算）。 */
    private boolean committed;

    /** 一帧的版面，全部以 GUI 单位计。 */
    private record Layout(int w, int h, int left, int cardsTop, int barY, int barX, int barW,
                          int countdownY, int hintY, int keepY, int identityY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    public HelmScreen(HudView view) {
        super(Text.translatable("heavyseas.helm.title"));
        this.view = view;
        this.offer = view.sea().helmOffer();
        this.deadlineMs = view.sea().helmDeadlineMs();
        this.lift = new float[offer.size()];
        this.dealAt = System.currentTimeMillis();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 挑牌不能逃 —— 逃了也只是超时替你挑
    }

    /**
     * 服务端替你挑了一张（ADR-0018 §6 清单第 4 条：这一下不能省）。
     *
     * <p>先把高亮挪到<b>服务端说的那一张</b>再播：服务端拿的是它最后收到的高亮，玩家最后一次移动可能还在路上。
     */
    public void autoPicked(int index) {
        if (index < 0 || index >= offer.size()) {
            return;
        }
        highlight = index;
        snapIndex = index;
        snapAt = System.currentTimeMillis();
        // 验收靠这一行：超时有、手动没有。抓帧看不清 396ms 时，它是第二个来源。
        LOGGER.info("舵手：第 {} 张是替你挑的（{}），播一次「顿」", index + 1, offer.get(index).id());
    }

    private boolean decided() {
        return committed || snapAt > 0L;
    }

    private boolean snapping() {
        return snapAt > 0L && GuiLanguage.snap(System.currentTimeMillis(), snapAt) < 1f;
    }

    /** 窗口关了（结算了）就收；「顿」还没播完时再挂一会儿。收界面只在 tick 里做。 */
    @Override
    public void tick() {
        view = projection();
        if (!view.active()) {
            close();
            return;
        }
        if (snapping()) {
            return;
        }
        if (!view.myHelmPick() || view.sea().helmDeadlineMs() != deadlineMs) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        if (offer.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Layout l = layout();
        float snapP = GuiLanguage.snap(now, snapAt);

        drawPublicBand(context, view, TOP_BAND_Y);
        drawSeaLine(context, view, SEA_LINE_Y, offer.size());

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向）。定了之后鼠标与键盘都不再改高亮。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided()) {
            int hovered = indexAt(mouseX, mouseY, l);
            if (hovered >= 0) {
                setHighlight(hovered);
            }
        }

        for (int i = 0; i < offer.size(); i++) {
            float in = GuiLanguage.deal(now, dealAt, i);
            if (in <= 0f) {
                continue;
            }
            boolean hi = i == highlight;
            lift[i] = GuiLanguage.approach(lift[i], hi ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = (1f - in) * GuiLanguage.DEAL_RISE;
            float scale = GuiLanguage.dealScale(in);
            if (i == snapIndex) {
                rise += GuiLanguage.snapRise(snapP);
                scale *= GuiLanguage.snapScale(snapP);
            }
            context.getMatrices().push();
            context.getMatrices().translate(l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h() - lift[i] + rise, 0);
            context.getMatrices().scale(scale, scale, 1f);
            context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
            CardTexture.drawNav(context, offer.get(i).id(), 0, 0, l.w(), l.h());
            if (hi) {
                drawCardFrame(context, l.w(), l.h());
            }
            context.getMatrices().pop();
        }

        drawCountdown(context, now, deadlineMs, NavigationPhase.PICK_MILLIS,
                l.barX(), l.barY(), l.barW(), l.countdownY());
        drawHint(context, l);
        drawIdentity(context, view, l.identityY());
    }

    /**
     * 这一帧的版面：牌吃掉上带与下面几行字之间剩下的高度，与它们一起居中。
     *
     * <p>牌顶留的空把「顿」算进去（{@link #snapRoom}），理由同补给箱那一面。
     */
    private Layout layout() {
        int n = Math.max(1, offer.size());
        int fh = textH();
        int lineH = fh + 1;
        int identityY = identityY();
        int below = BELOW_CARDS + BAR_H + BAR_TO_TEXT + fh + HINT_GAP + 2 * lineH + LINE_GAP + fh;
        int top = SEA_LINE_Y + fh;
        int avail = identityY - HINT_GAP - top - below;
        // 先按偏大的卡算出留空，再据此定卡高：留空只会偏大一点，卡绝不会反过来压上上带那一行。
        int room = snapRoom(cardHeightFor(n, avail - snapRoom(0)));
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = top + room + Math.max(0, (avail - room - h) / 2);
        int barY = cardsTop + h + BELOW_CARDS;
        int countdownY = barY + BAR_H + BAR_TO_TEXT;
        int hintY = countdownY + fh + HINT_GAP;
        int rowW = cardRowWidth(n, w);
        int barW = countdownWidth(rowW);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, barY, (width - barW) / 2, barW,
                countdownY, hintY, hintY + 2 * lineH + LINE_GAP, identityY);
    }

    /** 说明只跟高亮走：那张牌的完整内容（牌面上的字可能小到读不清），下面一行说这一面要你做什么。 */
    private void drawHint(DrawContext context, Layout l) {
        if (highlight < 0 || highlight >= offer.size()) {
            return;
        }
        drawParagraph(context, NavCardText.describe(offer.get(highlight), view.seats()), l.hintY(), 2, GuiLanguage.INK);
        drawLine(context, Text.translatable("heavyseas.helm.pick_one", offer.size()),
                width / 2, l.keepY(), GuiLanguage.MUTED);
    }

    private int indexAt(int mouseX, int mouseY, Layout l) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), offer.size());
    }

    /** 移高亮并上报：超时认的是它，服务端不知道的话只能乱挑。 */
    private void setHighlight(int index) {
        if (index == highlight || index < 0 || index >= offer.size()) {
            return;
        }
        highlight = index;
        ClientPlayNetworking.send(new HelmActionC2S(index, false));
    }

    private void commit() {
        if (decided()) {
            return;
        }
        committed = true;
        ClientPlayNetworking.send(new HelmActionC2S(highlight, true));
        // 验收靠这一行与服务端那行「舵手（界面）」对上。
        LOGGER.info("舵手：确认第 {} 张（{}）", highlight + 1, offer.get(highlight).id());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!decided()) {
            int i = indexAt((int) mouseX, (int) mouseY, layout());
            if (i >= 0) {
                setHighlight(i);
                commit();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (decided()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                setHighlight(Math.max(0, highlight - 1));
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                setHighlight(Math.min(offer.size() - 1, highlight + 1));
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                commit();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
