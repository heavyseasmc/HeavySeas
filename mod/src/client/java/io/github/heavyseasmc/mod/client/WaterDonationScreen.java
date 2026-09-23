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
        // 「划船堆 N 张 · 舵手 X」这一行在这一面去掉了：替人打水与它无关（§7.10 第 3 条：字太多）。
        Bands b = drawChrome(context, view);

        HudView.Thirst prompt = view.thirstPrompt();
        int still = Math.max(0, prompt.remaining() * prompt.waterPerSource() - prompt.donated());
        int available = Math.max(0, view.myWaters() - view.myDonatedWater());
        int titleY = b.stageTop();
        drawLine(context, Text.translatable("heavyseas.donate.title", nameOf(prompt.who())),
                width / 2, titleY, GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.donate.detail", still, prompt.donated(), available),
                width / 2, titleY + lineStep(), GuiLanguage.muted());

        // 舞台里：一张水，下面是按钮与一行说明。牌上面留出「抬」的高度。
        int bottom = b.stageBottom();
        int top = titleY + 2 * lineStep() + liftRoom();
        int below = buttonLiftRoom() + buttonHeight() + BTN_GAP;
        int avail = bottom - top - below;
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

        Text label = Text.translatable(awaiting ? "heavyseas.donate.sending" : "heavyseas.donate.give");
        int buttonY = cardY + cardH + buttonLiftRoom();
        button = layoutButtonRow(List.of(label), buttonY, BTN_GAP).getFirst();
        buttonLift = GuiLanguage.approach(buttonLift, awaiting ? 0f : GuiLanguage.LIFT_PX, dt);
        drawButton(context, button, label, !awaiting, GuiLanguage.verdigris(), buttonLift);
        drawFootBand(context, b, List.of(), List.of(keys("give", "Enter"), keys("cancel", "Esc")), now,
                new Countdown(deadlineMs, ThirstPhase.CHOOSE_MILLIS, cardW));
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
