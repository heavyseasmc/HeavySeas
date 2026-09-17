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

/** 旁人的口渴窗口：每按一次，替当前角色打 1 张水。Esc 可以放弃并回到世界。 */
public final class WaterDonationScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final int TOP_BAND_Y = 12;
    private static final int SEA_LINE_Y = 38;
    private static final int GAP = 6;

    private HudView view;
    private long deadlineMs;
    private int seenMine;
    private boolean awaiting;
    private float lift;
    private long lastFrameMs = System.currentTimeMillis();
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
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));
        lastFrameMs = now;
        drawPublicBand(context, view, TOP_BAND_Y);
        drawSeaLine(context, view, SEA_LINE_Y);

        HudView.Thirst prompt = view.thirstPrompt();
        int still = Math.max(0, prompt.remaining() * prompt.waterPerSource() - prompt.donated());
        int available = Math.max(0, view.myWaters() - view.myDonatedWater());
        int fh = textRenderer.fontHeight;
        int titleY = 62;
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.donate.title", nameOf(prompt.who())),
                width / 2, titleY, GuiLanguage.INK);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.donate.detail", still, prompt.donated(), available),
                width / 2, titleY + fh + 3, GuiLanguage.MUTED);

        int cardH = Math.max(48, Math.min(sharpCardHeight(), Math.round(height * 0.34f)));
        int cardW = GuiLanguage.cardWidth(cardH);
        int cardY = titleY + 2 * (fh + 3) + 8;
        int cardX = (width - cardW) / 2;
        lift = GuiLanguage.approach(lift, awaiting ? GuiLanguage.LIFT_PX : 0f, dt);
        CardTexture.drawProvision(context, Session.WATER, cardX, Math.round(cardY - lift), cardW, cardH);
        if (!awaiting) {
            context.drawBorder(cardX - 1, Math.round(cardY - lift) - 1, cardW + 2, cardH + 2, GuiLanguage.GOLD);
        }

        int barY = cardY + cardH + 8;
        int barW = Math.min(width - 40, Math.max(160, cardW));
        drawCountdown(context, now, deadlineMs, ThirstPhase.CHOOSE_MILLIS,
                (width - barW) / 2, barY, barW, barY + BAR_H + 3);

        Text label = Text.translatable(awaiting ? "heavyseas.donate.sending" : "heavyseas.donate.give");
        int buttonY = barY + BAR_H + 3 + fh + 8;
        button = layoutButtonRow(java.util.List.of(label), buttonY, GAP).getFirst();
        drawButton(context, button, label, !awaiting, GuiLanguage.VERDIGRIS, awaiting ? 0f : GuiLanguage.LIFT_PX);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.donate.hint"),
                width / 2, buttonY + button.h() + GAP, GuiLanguage.MUTED);
        drawIdentity(context, view, height - Math.max(8, Math.round(height * 0.05f)) - fh);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int buttonCode) {
        if (!awaiting && button.contains((int) mouseX, (int) mouseY)) {
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
