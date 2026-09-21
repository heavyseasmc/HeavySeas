package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 划船：查看抽到的牌，选择一张放进划船堆，其余自动塞回牌堆底（决策 ⑭ · ADR-0018 §7.4）。
 *
 * <h2>三带</h2>
 * <ul>
 *   <li><b>上带（公开）</b>：回合 · 阶段 · 海鸥，下面一行划船堆几张、舵手是谁 —— 全船都知道的数。</li>
 *   <li><b>中带（待决）</b>：抽到的牌。高亮一进来就在第一张；「使用」按钮只跟高亮那一张走。</li>
 *   <li><b>下带（私有）</b>：高亮那张的完整说明（牌面上的字可能小到读不清），以及你是谁、还剩多少。</li>
 * </ul>
 *
 * <h2>短倒计时</h2>
 * 划船决定给 20 秒；超时会把这一组牌全部塞回牌堆底。
 * Esc 可以收起来，服务端倒计时仍继续，按行动键可再开。
 *
 * <h2>一次选择，两种去向</h2>
 * 选中的牌往上「飞」进划船堆，其余牌同时往下「飞」回牌堆底。
 *
 * <h2>按下就飞，不等服务端</h2>
 * 一次选择只发一个包（{@link RowDecisionC2S}）。服务端按同一条规则把未选中的牌全部放回。
 *
 * <h2>一个按钮与别的面同一个样子</h2>
 * 走 {@link GameScreen#drawButton}：悬停时抬起、镶金框。←→ 换牌，Enter/Space 使用高亮牌。
 */
public final class RowScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 牌与它下面那排按钮之间。 */
    private static final int CARD_TO_BUTTONS = 10;
    private static final String[] BUTTONS = {"heavyseas.row.use"};

    private HudView view = HudView.IDLE;
    /** 这一次抽到的牌，按抽出顺序。打开时从投影里拿一次：服务端划完之后投影里就没有了，而最后一张还在飞。 */
    private List<NavCardView> cards = List.of();
    private Session.RowFate[] fates = new Session.RowFate[0];
    /** 每张起飞的时刻；0 表示没在这一面上飞过（还没选择，或者是重开之前归位的）。 */
    private long[] flyAt = new long[0];
    private float[] lift = new float[0];
    /** 使用按钮的抬起量。 */
    private final float[] buttonLift = new float[BUTTONS.length];
    /** 指针停在哪个按钮上；-1 表示都不在。只认真的移动。 */
    private int buttonFocus = -1;
    /** 高亮。一进来就在第一张待选牌上；选择完成时为 -1。 */
    private int focus = -1;
    private long dealAt;
    private boolean opened;
    /** 上一帧排出来的版面，点击按它判。 */
    private Layout layout;

    /** 一帧的版面，全部以 GUI 单位计。 */
    private record Layout(int w, int h, int left, int cardsTop, List<Box> buttons,
                          int barX, int barY, int barW, int countdownY,
                          int hintY, int timeoutY, int identityY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    public RowScreen() {
        super(Text.translatable("heavyseas.row.title"));
    }

    /** ❗{@code init} 在窗口改尺寸时还会再调：牌与高亮只在第一次打开时取，否则拖一下窗口就重新发一遍牌。 */
    @Override
    protected void init() {
        super.init();
        if (opened) {
            return;
        }
        opened = true;
        view = projection();
        List<HudView.Sea.RowCard> rowing = view.sea().rowing();
        cards = rowing.stream().map(HudView.Sea.RowCard::card).toList();
        fates = new Session.RowFate[cards.size()];
        for (int i = 0; i < fates.length; i++) {
            fates[i] = rowing.get(i).fate();
        }
        flyAt = new long[cards.size()];
        lift = new float[cards.size()];
        focus = nextUndecided(0, 1);
        dealAt = System.currentTimeMillis();
    }

    /** 每帧对一次投影。服务端那边已经定了、这边还没记上的（重开界面之前定过一张），以服务端为准。 */
    private void refresh() {
        view = projection();
        List<HudView.Sea.RowCard> server = view.sea().rowing();
        if (server.size() != cards.size()) {
            return;                           // 服务端已经划完（投影里空了）：以本地为准，等牌飞完再收
        }
        for (int i = 0; i < cards.size(); i++) {
            HudView.Sea.RowCard s = server.get(i);
            if (fates[i] == Session.RowFate.UNDECIDED && s.fate() != Session.RowFate.UNDECIDED
                    && s.card().id().equals(cards.get(i).id())) {
                fates[i] = s.fate();
                if (focus == i) {
                    focus = nextUndecided(0, 1);
                }
            }
        }
    }

    /** 收界面只在这里做（理由同行动一面：不在 render 里换屏）。 */
    @Override
    public void tick() {
        refresh();
        if (!view.active() || !view.seated()) {
            close();
            return;
        }
        if (flying()) {
            return;                           // 飞完再收：牌离开你手里的那一下不能被截断
        }
        if (focus < 0 || !view.myRowPending()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated() || cards.isEmpty()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Layout l = layout();
        layout = l;

        drawPublicBand(context, view, TOP_BAND_Y);
        drawSeaLine(context, view, SEA_LINE_Y);

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = cardAt(mouseX, mouseY, l);
            if (hovered >= 0 && fates[hovered] == Session.RowFate.UNDECIDED) {
                focus = hovered;
            }
            buttonFocus = focus >= 0 ? indexAt(l.buttons(), mouseX, mouseY) : -1;
        }

        for (int i = 0; i < cards.size(); i++) {
            drawCard(context, now, dt, l, i);
        }
        if (focus >= 0) {
            drawButtons(context, dt, l);
            drawHint(context, l);
        }
        drawCountdown(context, now, view.actionDeadlineMs(), view.actionWindowMs(),
                l.barX(), l.barY(), l.barW(), l.countdownY());
        drawLine(context, Text.translatable("heavyseas.row.timeout_hint"),
                width / 2, l.timeoutY(), GuiLanguage.dim());
        drawIdentity(context, view, l.identityY());
    }

    /**
     * 这一帧的版面。每帧按当前的 {@code width}/{@code height} 重算：窗口与界面尺寸随时会变。
     *
     * <p>自下而上排：身份一行 · 说明两行 · 按钮 · 牌；牌吃掉剩下的高度，与按钮一起在上带与说明之间居中。
     */
    private Layout layout() {
        int fh = textH();
        int lineH = fh + 1;
        int identityY = identityY();
        int timeoutY = identityY - HINT_GAP - fh;
        int hintY = timeoutY - HINT_GAP - 2 * lineH;
        int countdownY = hintY - HINT_GAP - fh;
        int barY = countdownY - BAR_TO_TEXT - BAR_H;
        int buttonH = buttonHeight();
        int top = SEA_LINE_Y + fh + liftRoom();          // 抬起来的牌连同金框不碰上带那一行
        int bottomLimit = barY - BELOW_CARDS;
        int n = Math.max(1, cards.size());
        int byHeight = bottomLimit - top - CARD_TO_BUTTONS - buttonH;
        int h = cardHeightFor(n, byHeight);
        int w = GuiLanguage.cardWidth(h);
        int block = h + CARD_TO_BUTTONS + buttonH;
        int cardsTop = top + Math.max(0, (bottomLimit - top - block) / 2);
        int rowW = cardRowWidth(n, w);
        int left = (width - rowW) / 2;

        // 按钮压在高亮那张牌正下方：使用的是「这一张」，不是这一排。
        int[] bw = new int[BUTTONS.length];
        int total = -BTN_GAP;
        for (int b = 0; b < BUTTONS.length; b++) {
            bw[b] = buttonWidth(Text.translatable(BUTTONS[b]));
            total += bw[b] + BTN_GAP;
        }
        int center = left + Math.max(0, focus) * (w + CARD_GAP) + w / 2;
        int x = Math.max(SIDE, Math.min(width - SIDE - total, center - total / 2));
        int buttonsY = cardsTop + h + CARD_TO_BUTTONS;
        List<Box> buttons = new ArrayList<>(BUTTONS.length);
        for (int b = 0; b < BUTTONS.length; b++) {
            buttons.add(new Box(x, buttonsY, bw[b], buttonH));
            x += bw[b] + BTN_GAP;
        }
        int barW = countdownWidth(rowW);
        return new Layout(w, h, left, cardsTop, buttons, (width - barW) / 2, barY, barW, countdownY,
                hintY, timeoutY, identityY);
    }

    private void drawCard(DrawContext context, long now, long dt, Layout l, int i) {
        float in = GuiLanguage.deal(now, dealAt, i);
        if (in <= 0f) {
            return;                           // 还没轮到它入场
        }
        boolean undecided = fates[i] == Session.RowFate.UNDECIDED;
        lift[i] = GuiLanguage.approach(lift[i], undecided && i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
        float cx = l.cardX(i) + l.w() / 2f;
        float bottom = l.cardsTop() + l.h();
        float scale = GuiLanguage.dealScale(in);
        float rise = (1f - in) * GuiLanguage.DEAL_RISE;
        if (!undecided) {
            if (flyAt[i] <= 0L) {
                return;                       // 重开这一面之前就定了：它已经不在你面前
            }
            float p = GuiLanguage.fly(now, flyAt[i]);
            if (p >= 1f) {
                return;
            }
            boolean keep = fates[i] == Session.RowFate.KEPT;
            // 留进划船堆：飞向上带那一行「划船堆 N 张」；塞回牌堆底：往下飞出屏幕。弧线两头为 0，中间抬起。
            float targetX = keep ? width / 2f : cx;
            float targetBottom = keep ? SEA_LINE_Y + textH() : height + l.h();
            cx += (targetX - cx) * p;
            bottom += (targetBottom - bottom) * p - GuiLanguage.flyArc(p);
            scale *= 1f - (keep ? 0.75f : 0.3f) * p;
        }
        context.getMatrices().push();
        // 绕底边中点缩放，与卡的其余几面同一个做法。
        context.getMatrices().translate(cx, bottom - lift[i] + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
        CardTexture.drawNav(context, cards.get(i).id(), 0, 0, l.w(), l.h());
        if (undecided && i == focus) {
            drawCardFrame(context, l.w(), l.h());
        }
        context.getMatrices().pop();
    }

    private void drawButtons(DrawContext context, long dt, Layout l) {
        for (int b = 0; b < BUTTONS.length; b++) {
            boolean focused = b == buttonFocus;
            buttonLift[b] = GuiLanguage.approach(buttonLift[b], focused ? GuiLanguage.LIFT_PX : 0f, dt);
            drawButton(context, l.buttons().get(b), Text.translatable(BUTTONS[b]), focused, GuiLanguage.ink(),
                    buttonLift[b]);
        }
    }

    /** 说明只跟高亮走：那张牌的完整内容；一行放得下时，第二行说键位。 */
    private void drawHint(DrawContext context, Layout l) {
        int lineH = textH() + 1;
        int lines = drawParagraph(context, NavCardText.describe(cards.get(focus), view.seats()), l.hintY(), 2, GuiLanguage.ink());
        if (lines < 2) {
            drawLine(context, Text.translatable("heavyseas.row.keys"),
                    width / 2, l.hintY() + lineH, GuiLanguage.muted());
        }
    }

    private int cardAt(int mouseX, int mouseY, Layout l) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), cards.size());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout l = layout;
        if (l != null && focus >= 0) {
            int b = indexAt(l.buttons(), (int) mouseX, (int) mouseY);
            if (b >= 0) {
                choose(focus);
                return true;
            }
            int card = cardAt((int) mouseX, (int) mouseY, l);
            if (card >= 0 && fates[card] == Session.RowFate.UNDECIDED) {
                focus = card;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 用哪个键开的，就用哪个键收起来（理由同行动一面：写死的键，玩家改了键位就收不起来）。
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (focus >= 0) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    int next = nextUndecided(focus + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1),
                            keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
                    if (next >= 0) {
                        focus = next;
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    choose(focus);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void choose(int i) {
        if (i < 0 || i >= cards.size() || fates[i] != Session.RowFate.UNDECIDED) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int card = 0; card < cards.size(); card++) {
            if (fates[card] == Session.RowFate.UNDECIDED) {
                fates[card] = card == i ? Session.RowFate.KEPT : Session.RowFate.RETURNED;
                flyAt[card] = now;
            }
        }
        ClientPlayNetworking.send(new RowDecisionC2S(i));
        // 验收靠这一行与服务端那行「划船（界面）」对上：客户端按了、服务端认了，两个来源。
        LOGGER.info("划船：选择第 {} 张留进划船堆（{}），其余 {} 张塞回牌堆底",
                i + 1, cards.get(i).id(), Math.max(0, cards.size() - 1));
        focus = -1;
    }

    /** 从 {@code from} 起朝 {@code step} 方向找第一张待选牌；没有就是 -1。 */
    private int nextUndecided(int from, int step) {
        for (int i = from; i >= 0 && i < fates.length; i += step) {
            if (fates[i] == Session.RowFate.UNDECIDED) {
                return i;
            }
        }
        return -1;
    }

    /** 还有牌在飞吗。舵手一面要等它飞完再弹，否则最后一张飞到一半就被顶掉。 */
    public boolean flying() {
        long now = System.currentTimeMillis();
        for (long at : flyAt) {
            if (GuiLanguage.flying(now, at)) {
                return true;
            }
        }
        return false;
    }
}
