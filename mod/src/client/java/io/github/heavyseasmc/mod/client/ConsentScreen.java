package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 表态：有人冲着你来了 —— 同意，还是打一架（ADR-0023 · 规则 §9.1）。
 *
 * <h2>这一面是整场战斗唯一的入口</h2>
 * 规则里战斗的<b>触发条件唯一</b>：换座位或抢夺的目标清醒且不同意。所以按下「战斗」的那一下，
 * 是全局唯一一处「打起来」的来源 —— 不能因为讨厌某人就主动开战。
 *
 * <h2>默认答案是「同意」，所以焦点一进来就在它上面</h2>
 * 超时按同意算（ADR-0023 §7.2）。默认「战斗」会给挂机的人<b>凭空制造伤害</b> ——
 * 那不是他的默认答案，那是替他做了一个没人会做的选择（与口渴那一面同一条理由）。
 * 焦点就是那个默认答案，所以它一开始就在「同意」上，不等玩家先动一下。
 *
 * <h2>「战斗」是朱砂的</h2>
 * 它会让人受伤，而且不可逆 —— 朱砂只给紧迫与伤害（ADR-0018 §7.3）。
 */
public final class ConsentScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    /**
     * 开这一面时的那个窗口。
     *
     * <p>❗收界面认<b>投影</b>，不认「还剩几秒」：客户端自己数秒的话，改过的客户端可以永远不超时，
     * 而真正的判定在服务端（{@code ContestPhase#tick}）。
     */
    private final long deadlineMs;
    private final Contest.Kind kind;
    private final String attacker;

    /** 焦点。<b>一进来就在「同意」</b> —— 超时算的就是它。 */
    private int focus;
    private boolean committed;
    private final float[] lift = new float[2];
    private List<Box> boxes = List.of();

    public ConsentScreen(HudView view) {
        super(Text.translatable("heavyseas.consent.title"));
        this.view = view;
        this.deadlineMs = view.contest().deadlineMs();
        this.kind = view.contest().kind();
        this.attacker = view.contest().attacker();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 逃了也只是超时按「同意」算 —— 那是替你做的，不是你做的
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.myConsent() || view.contest().deadlineMs() != deadlineMs) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        int fh = textRenderer.fontHeight;

        drawPublicBand(context, view, TOP_BAND_Y);
        int askY = TOP_BAND_Y + 30;
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable(switch (kind) {
                    case SWAP -> "heavyseas.consent.swap";
                    case STEAL -> "heavyseas.consent.steal";
                    case RATION -> "heavyseas.consent.ration";
                }, nameOf(attacker)),
                width / 2, askY, GuiLanguage.INK);

        List<Text> labels = List.of(Text.translatable("heavyseas.consent.agree"),
                Text.translatable("heavyseas.consent.fight"));
        // 按钮顶上留出「抬」的高度，免得抬起来的金框切进上面那行字。
        int top = askY + fh + 2 * BTN_GAP + buttonLiftRoom();
        boxes = layoutButtonRow(labels, top, BTN_GAP);

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗每帧都要调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !committed) {
            int hovered = indexAt(boxes, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            drawButton(context, boxes.get(i), labels.get(i), i == focus,
                    i == 1 ? GuiLanguage.CINNABAR : GuiLanguage.INK, lift[i]);
        }

        int barW = countdownWidth(rowWidth(boxes));
        int barY = top + rowHeight(boxes) + 2 * BTN_GAP;
        int countdownY = barY + BAR_H + BAR_TO_TEXT;
        drawCountdown(context, now, deadlineMs, view.contest().windowMs(),
                (width - barW) / 2, barY, barW, countdownY);
        int hintY = countdownY + fh + HINT_GAP;
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable(focus == 1 ? "heavyseas.consent.fight_hint" : "heavyseas.consent.agree_hint"),
                width / 2, hintY, GuiLanguage.MUTED);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.consent.timeout"),
                width / 2, hintY + fh + 2, GuiLanguage.DIM);
        drawIdentity(context, view, identityY());
    }

    /**
     * 表态。
     *
     * <p>❗定了就不再改：再点再按都不作数，否则会往服务端发第二个表态 —— 而第二个会撞上
     * 「现在不是等表态的时候」，在日志上与一个真正的 bug 长得一样。
     */
    private void confirm() {
        if (committed) {
            return;
        }
        committed = true;
        boolean fight = focus == 1;
        ClientPlayNetworking.send(ContestActionC2S.of(fight
                ? ContestActionC2S.Kind.CONSENT_FIGHT : ContestActionC2S.Kind.CONSENT_AGREE));
        // 与语言无关的一行：验收靠它与服务端那行「表态（界面）」对上 —— 客户端按了、服务端认了，两个来源。
        LOGGER.info("表态：确认「{}」", fight ? "FIGHT" : "AGREE");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!committed) {
            int i = indexAt(boxes, (int) mouseX, (int) mouseY);
            if (i >= 0) {
                focus = i;
                confirm();
                return true;
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
                focus = 0;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                focus = 1;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                confirm();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
