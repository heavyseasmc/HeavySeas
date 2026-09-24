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
 * <h2>一张水，没有按钮</h2>
 * 舞台上是一张水牌：按下去时它「抬」起来（正在送出去），服务端认下之后落回来、再镶上金框（还能再给一张）。
 * 能按的那一件是下面那一栏的搪瓷按钮（Enter），鼠标点牌也是同一件事 —— 与口渴一面同一个做法。
 *
 * <p>❗2026-09-25 第一次实拍（四面补拍）：舞台里原先还有一颗「给 1 张水」按钮，而下面那一栏又写着「Enter 给水」——
 * 同一件事画了两遍；按钮与两行说明从上往下各占一截之后，牌只剩 44 个单位高（1280×720 下约 130 像素），
 * 舞台一大半空着。与 ADR-0037 §7.8「牌是主体」、§7.12「一屏只该有一件东西看上去能按」都不合。
 * 现在两行状态贴舞台底边（与口渴一面相同），牌拿剩下的全部高度。
 */
public final class WaterDonationScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    private long deadlineMs;
    private int seenMine;
    private boolean awaiting;
    private float cardLift;
    /** 这一帧牌画在哪（点牌 = 给水）。 */
    private Box card = new Box(0, 0, 0, 0);

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
        // 两行状态贴舞台底边（替谁打 · 还差几张），与口渴一面同一个位置：换面时下半屏不跳。
        int titleY = footerTop(b, 2);
        drawLine(context, Text.translatable("heavyseas.donate.title", nameOf(prompt.who())),
                width / 2, titleY, GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.donate.detail", still, prompt.donated(), available),
                width / 2, titleY + lineStep(), GuiLanguage.muted());

        // 舞台里剩下的全部高度给那一张水（§7.8）。牌上面留出「抬」的高度。
        int room = liftRoom();
        int bottom = titleY - HINT_GAP;
        int cardH = cardHeightFor(1, bottom - b.stageTop() - room);
        int cardW = GuiLanguage.cardWidth(cardH);
        int cardY = cardsTopIn(b.stageTop(), bottom, cardH, room);
        int cardX = (width - cardW) / 2;
        cardLift = GuiLanguage.approach(cardLift, awaiting ? GuiLanguage.LIFT_PX : 0f, dt);
        int drawY = Math.round(cardY - cardLift);
        CardTexture.drawProvision(context, Session.WATER, cardX, drawY, cardW, cardH);
        card = new Box(cardX, cardY, cardW, cardH);
        if (!awaiting) {
            context.drawBorder(cardX - CARD_FRAME, drawY - CARD_FRAME, cardW + 2 * CARD_FRAME, cardH + 2 * CARD_FRAME,
                    GuiLanguage.gold());
        }
        drawFootBand(context, b, List.of(), List.of(primary("give", "Enter"), keys("cancel", "Esc")), now,
                new Countdown(deadlineMs, ThirstPhase.CHOOSE_MILLIS, cardW));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int buttonCode) {
        if (!awaiting && indexAt(List.of(card), (int) mouseX, (int) mouseY) == 0) {
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
