package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.ThirstActionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 口渴：喝几张水（ADR-0021）。
 *
 * <h2>这一面问的是一个数，不是一张牌</h2>
 * 手上三张水完全等价，没有哪一张值得挑。所以中带摆的是<b>你所有的水</b>，
 * 左边 N 张抬起来、镶金边 —— 那 N 张就是要喝掉的；右边几张留在原位，是留着的。
 * 左右键调这个数，看得见「喝几张、挨几点」同时在变。
 *
 * <h2>默认答案是「刚好够」</h2>
 * 另外三面的高亮默认落在第一张，因为没有更好的猜测；这一面有 ——
 * <b>口渴的伤害是必然的，而水的唯一用途就是挡它</b>。所以一进来就预选「刚好够」，
 * 想省水的人自己往下调。默认 0 的话，挂机的人会一边攥着水一边掉血，那不是他的默认答案。
 *
 * <h2>没得选的时候这一面不出现</h2>
 * 一张水都没有、或者昏迷着（自己打不出水），服务端根本不开窗口 —— 与「没人划船时不开舵手一面」同一条。
 */
public final class ThirstScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int SIDE = 20;
    private static final int TOP_BAND_Y = 12;
    private static final int SEA_LINE_Y = 38;
    private static final int GAP = 6;
    private static final int BELOW_CARDS = 6;
    private static final int BAR_TO_TEXT = 3;
    private static final int HINT_GAP = 5;
    private static final int BORDER_ROOM = 2 + 4;
    private static final int MIN_CARD_H = 24;
    private static final float MAX_CARD_H_RATIO = 0.5f;

    private HudView view;
    /** 打开时手上有几张水。结算那一刻投影里牌就没了，而这一面还要画完最后一帧。 */
    private final int waters;
    /** 还需要化解几次。 */
    private final int remaining;
    private final long deadlineMs;
    private final long dealAt;
    private long lastFrameMs;
    private final float[] lift;
    /** 打算喝几张。一进来就是「刚好够」。 */
    private int chosen;
    private boolean committed;

    public ThirstScreen(HudView view) {
        super(Text.translatable("heavyseas.thirst.title"));
        this.view = view;
        this.waters = view.myWaters();
        this.remaining = view.thirstPrompt().remaining();
        this.deadlineMs = view.thirstPrompt().deadlineMs();
        this.lift = new float[Math.max(1, waters)];
        this.chosen = Math.min(remaining, waters);
        this.dealAt = System.currentTimeMillis();
        this.lastFrameMs = dealAt;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 逃了也只是超时按当前的数喝
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.active() || !view.myThirstChoice()
                || view.thirstPrompt().deadlineMs() != deadlineMs) {
            close();
        }
    }

    /** 一帧的版面，全部以 GUI 单位计。 */
    private record Layout(int w, int h, int left, int cardsTop, int barY, int barX, int barW,
                          int countdownY, int titleY, int detailY, int hintY, int keysY, int identityY) {

        int cardX(int i) {
            return left + i * (w + GAP);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));
        lastFrameMs = now;
        Layout l = layout();

        drawPublicBand(context, view, TOP_BAND_Y);
        drawSeaLine(context, view, SEA_LINE_Y);

        for (int i = 0; i < waters; i++) {
            float in = GuiLanguage.deal(now, dealAt, i);
            if (in <= 0f) {
                continue;
            }
            boolean drinking = i < chosen;
            lift[i] = GuiLanguage.approach(lift[i], drinking ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = (1f - in) * GuiLanguage.DEAL_RISE;
            float scale = GuiLanguage.dealScale(in);
            context.getMatrices().push();
            context.getMatrices().translate(l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h() - lift[i] + rise, 0);
            context.getMatrices().scale(scale, scale, 1f);
            context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
            CardTexture.drawProvision(context, Session.WATER, 0, 0, l.w(), l.h());
            if (drinking) {
                context.drawBorder(-2, -2, l.w() + 4, l.h() + 4, GuiLanguage.GOLD);
            }
            context.getMatrices().pop();
        }

        drawCountdown(context, now, deadlineMs, ThirstPhase.CHOOSE_MILLIS,
                l.barX(), l.barY(), l.barW(), l.countdownY());
        int hurt = Math.max(0, remaining - chosen);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.thirst.title", remaining), width / 2, l.titleY(),
                GuiLanguage.INK);
        HudView.Thirst prompt = view.thirstPrompt();
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.thirst.detail", prompt.sources(), prompt.covered(), prompt.shared()),
                width / 2, l.detailY(), GuiLanguage.MUTED);
        context.drawCenteredTextWithShadow(textRenderer, chosen == 0
                        ? Text.translatable("heavyseas.thirst.none")
                        : Text.translatable("heavyseas.thirst.drink", chosen, hurt),
                width / 2, l.hintY(), hurt > 0 ? GuiLanguage.CINNABAR : GuiLanguage.VERDIGRIS);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.thirst.hint"),
                width / 2, l.keysY(), GuiLanguage.MUTED);
        drawIdentity(context, view, l.identityY());
    }

    private Layout layout() {
        int n = Math.max(1, waters);
        int fh = textRenderer.fontHeight;
        int lineH = fh + 2;
        int identityY = height - Math.max(8, Math.round(height * 0.05f)) - fh;
        // ❗倒计时之下有**四行**：还需化解几次 · 来源明细 · 喝几张挨几点 · 键位。
        //   1280x720 实拍：这里原先只留了三行，键位那一行画到了身份那一行上面，两行字叠在一起。
        int below = BELOW_CARDS + BAR_H + BAR_TO_TEXT + fh + HINT_GAP + 4 * lineH;
        int top = SEA_LINE_Y + fh;
        int avail = identityY - HINT_GAP - top - below;
        int room = (int) Math.ceil(GuiLanguage.LIFT_PX) + BORDER_ROOM;
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = top + room + Math.max(0, (avail - room - h) / 2);
        int barY = cardsTop + h + BELOW_CARDS;
        int countdownY = barY + BAR_H + BAR_TO_TEXT;
        int titleY = countdownY + fh + HINT_GAP;
        int rowW = n * w + (n - 1) * GAP;
        int barW = Math.min(width - 2 * SIDE, Math.max(160, rowW));
        return new Layout(w, h, (width - rowW) / 2, cardsTop, barY, (width - barW) / 2, barW,
                countdownY, titleY, titleY + lineH, titleY + 2 * lineH, titleY + 3 * lineH, identityY);
    }

    private int cardHeightFor(int n, int availH) {
        int byWidth = GuiLanguage.cardHeight((width - 2 * SIDE - (n - 1) * GAP) / n);
        int sharp = sharpCardHeight();
        return Math.max(MIN_CARD_H, Math.min(Math.min(Math.min(availH, byWidth), sharp),
                Math.round(height * MAX_CARD_H_RATIO)));
    }

    /** 改张数并上报：超时认的是它，服务端不知道的话只能按开窗时那个默认值算。 */
    private void setChosen(int next) {
        int clamped = MathHelper.clamp(next, 0, Math.min(remaining, waters));
        if (clamped == chosen) {
            return;
        }
        chosen = clamped;
        ClientPlayNetworking.send(new ThirstActionC2S(chosen, false));
    }

    private void commit() {
        if (committed) {
            return;
        }
        committed = true;
        ClientPlayNetworking.send(new ThirstActionC2S(chosen, true));
        // 验收靠这一行与服务端那行「口渴（界面）」对上。
        LOGGER.info("口渴：确认喝 {} 张（还需化解 {} 次）", chosen, remaining);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout l = layout();
        if (mouseY >= l.cardsTop() - GuiLanguage.LIFT_PX && mouseY <= l.cardsTop() + l.h()) {
            for (int i = 0; i < waters; i++) {
                int x = l.cardX(i);
                if (mouseX >= x && mouseX < x + l.w()) {
                    // 点第 i 张 = 「喝到这一张为止」。再点同一张就是取消它。
                    setChosen(chosen == i + 1 ? i : i + 1);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (committed) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                setChosen(chosen - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                setChosen(chosen + 1);
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
