package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 划船：抽到的两张，逐张决定留进划船堆还是塞回牌堆底（决策 ⑭ · ADR-0018 §7.4）。
 *
 * <h2>三带</h2>
 * <ul>
 *   <li><b>上带（公开）</b>：回合 · 阶段 · 海鸥，下面一行划船堆几张、舵手是谁 —— 全船都知道的数。</li>
 *   <li><b>中带（待决）</b>：两张牌。高亮一进来就在第一张；两个去向的按钮只跟高亮那一张走。</li>
 *   <li><b>下带（私有）</b>：高亮那张的完整说明（牌面上的字可能小到读不清），以及你是谁、还剩多少。</li>
 * </ul>
 *
 * <h2>不计时</h2>
 * 用户 2026-09-15 定。所以舞台下面没有那条细横杠；Esc 可以收起来回世界里谈，按行动键再开 —— 与行动一面同一个理由。
 *
 * <h2>两个去向：同一个动词，两个方向</h2>
 * ADR-0018 §7.4：留进划船堆往上「飞」（飞向上带那行划船堆的张数），塞回牌堆底往下「飞」出屏幕。
 * 飞的含义是「归属变了」—— 这张牌离开了你的手。
 *
 * <h2>按下就飞，不等服务端</h2>
 * 一张一个包（{@link RowDecisionC2S}）。服务端只会在「不是你在划船」「这张已经定过」时忽略它，
 * 而那两种情况在这一面上本来就按不到。
 */
public final class RowScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int SIDE = 20;
    private static final int TOP_BAND_Y = 12;
    /** 上带下面那一行（划船堆 · 舵手），与行动一面的座位轨同一个高度。 */
    private static final int SEA_LINE_Y = 38;
    private static final int GAP = 14;
    private static final int BUTTON_GAP = 10;
    private static final int BUTTON_SPACING = 6;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 5;
    private static final int HINT_GAP = 6;
    private static final int MIN_CARD_H = 40;
    /** 两张牌不必撑满：最高占屏幕高的一半，留出上下带。 */
    private static final float MAX_CARD_H_RATIO = 0.5f;
    /** 两个去向，次序即按钮次序：0 = 留进划船堆，1 = 塞回牌堆底。 */
    private static final String[] BUTTONS = {"heavyseas.row.keep", "heavyseas.row.return"};

    private HudView view = HudView.IDLE;
    /** 这一次抽到的牌，按抽出顺序。打开时从投影里拿一次：服务端划完之后投影里就没有了，而最后一张还在飞。 */
    private List<NavCardView> cards = List.of();
    private Session.RowFate[] fates = new Session.RowFate[0];
    /** 每张起飞的时刻；0 表示没在这一面上飞过（还没定，或者是重开之前定的）。 */
    private long[] flyAt = new long[0];
    private float[] lift = new float[0];
    /** 高亮。一进来就在第一张还没定的牌上；全定完时为 -1。 */
    private int focus = -1;
    private long dealAt;
    private long lastFrameMs = System.currentTimeMillis();
    private boolean opened;
    /** 上一帧排出来的版面，点击按它判。 */
    private Layout layout;

    /** 一帧的版面，全部以 GUI 单位计。 */
    private record Layout(int w, int h, int left, int cardsTop, int buttonsY, int buttonH,
                          int[] buttonX, int[] buttonW, int hintY, int identityY) {

        int cardX(int i) {
            return left + i * (w + GAP);
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
        lastFrameMs = dealAt;
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
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));   // 掉帧时别让插值一步跳到底
        lastFrameMs = now;
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
        }

        for (int i = 0; i < cards.size(); i++) {
            drawCard(context, now, dt, l, i);
        }
        if (focus >= 0) {
            drawButtons(context, l);
            drawHint(context, l);
        }
        drawIdentity(context, view, l.identityY());
    }

    /**
     * 这一帧的版面。每帧按当前的 {@code width}/{@code height} 重算：窗口与界面尺寸随时会变。
     *
     * <p>自下而上排：身份一行 · 说明两行 · 按钮 · 牌；牌吃掉剩下的高度，与按钮一起在上带与说明之间居中。
     */
    private Layout layout() {
        int fh = textRenderer.fontHeight;
        int lineH = fh + 1;
        int identityY = height - Math.max(8, Math.round(height * 0.05f)) - fh;
        int hintY = identityY - HINT_GAP - 2 * lineH;
        int buttonH = fh + 2 * PAD_Y;
        int top = SEA_LINE_Y + fh + (int) Math.ceil(GuiLanguage.LIFT_PX) + 2 + 4;   // 抬起来的牌连同金框不碰上带那一行
        int bottomLimit = hintY - HINT_GAP;
        int n = Math.max(1, cards.size());
        int byHeight = bottomLimit - top - BUTTON_GAP - buttonH;
        int byWidth = GuiLanguage.cardHeight((width - 2 * SIDE - (n - 1) * GAP) / n);
        int h = Math.max(MIN_CARD_H, Math.min(Math.min(byHeight, byWidth), Math.round(height * MAX_CARD_H_RATIO)));
        int w = GuiLanguage.cardWidth(h);
        int block = h + BUTTON_GAP + buttonH;
        int cardsTop = top + Math.max(0, (bottomLimit - top - block) / 2);
        int rowW = n * w + (n - 1) * GAP;
        int left = (width - rowW) / 2;

        int[] buttonW = new int[BUTTONS.length];
        int total = -BUTTON_SPACING;
        for (int b = 0; b < BUTTONS.length; b++) {
            buttonW[b] = textRenderer.getWidth(Text.translatable(BUTTONS[b])) + 2 * PAD_X;
            total += buttonW[b] + BUTTON_SPACING;
        }
        // 按钮压在高亮那张牌正下方：去向说的是「这一张」，不是这一排。
        int center = left + Math.max(0, focus) * (w + GAP) + w / 2;
        int x = Math.max(SIDE, Math.min(width - SIDE - total, center - total / 2));
        int[] buttonX = new int[BUTTONS.length];
        for (int b = 0; b < BUTTONS.length; b++) {
            buttonX[b] = x;
            x += buttonW[b] + BUTTON_SPACING;
        }
        return new Layout(w, h, left, cardsTop, cardsTop + h + BUTTON_GAP, buttonH, buttonX, buttonW, hintY, identityY);
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
            float targetBottom = keep ? SEA_LINE_Y + textRenderer.fontHeight : height + l.h();
            cx += (targetX - cx) * p;
            bottom += (targetBottom - bottom) * p - GuiLanguage.flyArc(p);
            scale *= 1f - (keep ? 0.75f : 0.3f) * p;
        }
        context.getMatrices().push();
        // 绕底边中点缩放，与卡的其余几面同一个做法。
        context.getMatrices().translate(cx, bottom - lift[i] + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
        NavCardFace.draw(context, textRenderer, cards.get(i), view.seats(), 0, 0, l.w(), l.h());
        if (undecided && i == focus) {
            // 金 =「你 · 你选的那个」。框画在同一个矩阵里，跟着牌一起抬、一起发。
            context.drawBorder(-2, -2, l.w() + 4, l.h() + 4, GuiLanguage.GOLD);
        }
        context.getMatrices().pop();
    }

    private void drawButtons(DrawContext context, Layout l) {
        for (int b = 0; b < BUTTONS.length; b++) {
            int x = l.buttonX()[b];
            int y = l.buttonsY();
            int w = l.buttonW()[b];
            context.fill(x, y, x + w, y + l.buttonH(), GuiLanguage.GROUND);
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable(BUTTONS[b]), x + w / 2,
                    y + (l.buttonH() - textRenderer.fontHeight) / 2 + 1, GuiLanguage.INK);
        }
    }

    /** 说明只跟高亮走：那张牌的完整内容；一行放得下时，第二行说键位。 */
    private void drawHint(DrawContext context, Layout l) {
        List<OrderedText> lines = textRenderer.wrapLines(
                NavCardText.describe(cards.get(focus), view.seats()), width - 2 * SIDE);
        int lineH = textRenderer.fontHeight + 1;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            context.drawCenteredTextWithShadow(textRenderer, lines.get(i), width / 2, l.hintY() + i * lineH,
                    GuiLanguage.INK);
        }
        if (lines.size() < 2) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.row.keys"),
                    width / 2, l.hintY() + lineH, GuiLanguage.MUTED);
        }
    }

    private int cardAt(int mouseX, int mouseY, Layout l) {
        if (mouseY < l.cardsTop() - GuiLanguage.LIFT_PX || mouseY > l.cardsTop() + l.h()) {
            return -1;
        }
        for (int i = 0; i < cards.size(); i++) {
            int x = l.cardX(i);
            if (mouseX >= x && mouseX < x + l.w()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout l = layout;
        if (l != null && focus >= 0) {
            for (int b = 0; b < BUTTONS.length; b++) {
                int x = l.buttonX()[b];
                if (mouseX >= x && mouseX < x + l.buttonW()[b]
                        && mouseY >= l.buttonsY() && mouseY < l.buttonsY() + l.buttonH()) {
                    decide(focus, b == 0);
                    return true;
                }
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
                case GLFW.GLFW_KEY_UP -> {
                    decide(focus, true);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    decide(focus, false);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void decide(int i, boolean keep) {
        if (i < 0 || i >= cards.size() || fates[i] != Session.RowFate.UNDECIDED) {
            return;
        }
        fates[i] = keep ? Session.RowFate.KEPT : Session.RowFate.RETURNED;
        flyAt[i] = System.currentTimeMillis();
        ClientPlayNetworking.send(new RowDecisionC2S(i, keep));
        // 验收靠这一行与服务端那行「划船（界面）」对上：客户端按了、服务端认了，两个来源。
        LOGGER.info("划船：第 {} 张{}（{}）", i + 1, keep ? "留进划船堆" : "塞回牌堆底", cards.get(i).id());
        focus = nextUndecided(0, 1);
    }

    /** 从 {@code from} 起朝 {@code step} 方向找第一张还没定的；没有就是 -1。 */
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
