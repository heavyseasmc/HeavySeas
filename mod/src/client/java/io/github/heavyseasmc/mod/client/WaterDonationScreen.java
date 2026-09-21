package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.WaterDonationC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 旁人的口渴窗口：每按一次，替当前角色打 1 张水。Esc 可以放弃并回到世界。
 *
 * <h2>一张水、一个按钮</h2>
 * 舞台上是一张水牌：按下去时它「抬」起来（正在送出去），服务端认下之后落回来、再镶上金框（还能再给一张）。
 * 按钮与别的面同一个样子：「抬」走 {@link GuiLanguage#approach} 插值，命中把抬起的那几像素算进去
 * —— 硬切换加只认没抬起的框的那一版，点在画出来的按钮顶上那一截会没反应（审查抓到的）。
 */
public final class WaterDonationScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    private long deadlineMs;
    private int seenMine;
    private boolean awaiting;
    private float cardLift;
    private float buttonLift;
    private Box button = new Box(0, 0, 0, 0);

    public WaterDonationScreen(HudView view) {
        super(Text.translatable("heavyseas.donate.title", Text.empty()));
        this.view = view;
        this.deadlineMs = view.thirstPrompt().deadlineMs();
        this.seenMine = view.myDonatedWater();
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.active() || view.thirstPrompt().deadlineMs() != deadlineMs || !view.myWaterDonation()) {
            close();
            return;
        }
        if (view.myDonatedWater() > seenMine) {
            seenMine = view.myDonatedWater();
            awaiting = false;                 // 服务端认下上一张，若还有水便可再给
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        drawPublicBand(context, view, TOP_BAND_Y);
        drawSeaLine(context, view, SEA_LINE_Y);

        HudView.Thirst prompt = view.thirstPrompt();
        int still = Math.max(0, prompt.remaining() * prompt.waterPerSource() - prompt.donated());
        int available = Math.max(0, view.myWaters() - view.myDonatedWater());
        int fh = textH();
        int titleY = SEA_LINE_Y + fh + HINT_GAP + 8;
        drawLine(context, Text.translatable("heavyseas.donate.title", nameOf(prompt.who())),
                width / 2, titleY, GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.donate.detail", still, prompt.donated(), available),
                width / 2, titleY + fh + 3, GuiLanguage.muted());

        // 舞台：一张水。上面留出「抬」的高度，下面留出横杠 · 秒数 · 按钮（连它的抬）· 一行说明。
        int identityY = identityY();
        int top = titleY + 2 * (fh + 3) + 8 + liftRoom();
        int below = BELOW_CARDS + BAR_H + BAR_TO_TEXT + fh + HINT_GAP + buttonLiftRoom() + buttonHeight()
                + BTN_GAP + fh;
        int avail = identityY - HINT_GAP - top - below;
        int cardH = cardHeightFor(1, avail);
        int cardW = GuiLanguage.cardWidth(cardH);
        int cardY = top + Math.max(0, (avail - cardH) / 2);
        int cardX = (width - cardW) / 2;
        cardLift = GuiLanguage.approach(cardLift, awaiting ? GuiLanguage.LIFT_PX : 0f, dt);
        int drawY = Math.round(cardY - cardLift);
        CardTexture.drawProvision(context, Session.WATER, cardX, drawY, cardW, cardH);
        if (!awaiting) {
            context.drawBorder(cardX - CARD_FRAME, drawY - CARD_FRAME, cardW + 2 * CARD_FRAME, cardH + 2 * CARD_FRAME,
                    GuiLanguage.gold());
        }

        int barY = cardY + cardH + BELOW_CARDS;
        int barW = countdownWidth(cardW);
        drawCountdown(context, now, deadlineMs, ThirstPhase.CHOOSE_MILLIS,
                (width - barW) / 2, barY, barW, barY + BAR_H + BAR_TO_TEXT);

        Text label = Text.translatable(awaiting ? "heavyseas.donate.sending" : "heavyseas.donate.give");
        int buttonY = barY + BAR_H + BAR_TO_TEXT + fh + HINT_GAP + buttonLiftRoom();
        button = layoutButtonRow(List.of(label), buttonY, BTN_GAP).getFirst();
        buttonLift = GuiLanguage.approach(buttonLift, awaiting ? 0f : GuiLanguage.LIFT_PX, dt);
        drawButton(context, button, label, !awaiting, GuiLanguage.verdigris(), buttonLift);
        drawLine(context, Text.translatable("heavyseas.donate.hint"),
                width / 2, buttonY + button.h() + BTN_GAP, GuiLanguage.muted());
        drawIdentity(context, view, identityY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int buttonCode) {
        if (!awaiting && indexAt(List.of(button), (int) mouseX, (int) mouseY) == 0) {
            donate();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, buttonCode);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!awaiting && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE)) {
            donate();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void donate() {
        awaiting = true;
        ClientPlayNetworking.send(new WaterDonationC2S(1));
        LOGGER.info("口渴：替 {} 打 1 张水", view.thirstPrompt().who());
    }
}
