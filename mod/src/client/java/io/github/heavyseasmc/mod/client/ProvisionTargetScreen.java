package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 医疗箱的第二步：从当前仍可治疗的人里挑一个，含自己。
 *
 * <p>头像版（ADR-0050，用户 2026-10-01 看样图定）：左边是那张医疗箱，右边只列能治的人 —— 大头像、
 * 与座位轨同一套公开状态（伤势压暗 · 「剩 / 体型」印章 · 「昏」签）、名字、一排体力点。
 * 原先是「名字 · 体力 3/4」一排文字按钮，四个人受伤时还要折成两行（{@link PortraitPick}）。
 */
public final class ProvisionTargetScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    /** 头像直径 · 一格多宽（稿子像素，样图）。 */
    private static final double TOKEN = 96;
    private static final double CELL = 132;

    private HudView view;
    private final PortraitPick pick = new PortraitPick(this, TOKEN, CELL);
    private boolean committed;

    public ProvisionTargetScreen(HudView view) {
        super(Text.translatable("heavyseas.target.title", Text.empty()));
        this.view = view;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // Esc 要先通知服务端收起那笔待选状态
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.myProvisionTarget()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        Bands b = drawChrome(context, view);
        int titleY = b.stageTop();
        drawLine(context, Text.translatable("heavyseas.target.title", provisionName(view.provisionTargetCard())),
                width / 2, titleY, GuiLanguage.ink());
        List<PortraitPick.Person> people = new ArrayList<>();
        for (HudView.MedicalTarget target : view.provisionTargets()) {
            people.add(new PortraitPick.Person(target.id(), target.health(), target.maxHealth()));
        }
        if (people.isEmpty()) {
            drawLine(context, Text.translatable("heavyseas.command.nobody_wounded"),
                    width / 2, titleY + lineStep() + HINT_GAP, GuiLanguage.muted());
        } else {
            var l = sheet();
            double top0 = Math.max(l.railBottom() + CardRow.RAIL_CLEAR * l.k(),
                    (titleY + lineStep() + HINT_GAP) * (double) guiScale());
            String card = view.provisionTargetCard();
            pick.render(context, mouseX, mouseY, dt, top0, people,
                    (ctx, x, y, w, h) -> CardTexture.drawProvision(ctx, card, x, y, w, h));
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(confirm("Enter"), keys("cancel", "Esc")), now, null);
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        if (!committed) {
            int picked = pick.indexAt(mouseX, mouseY, view.provisionTargets().size());
            if (picked >= 0) {
                pick.focus(picked);
                commit();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            ClientPlayNetworking.send(UseProvisionC2S.cancel());
            LOGGER.info("特殊物资：取消挑目标");
            return true;
        }
        if (!committed) {
            if (pick.keyPressed(keyCode, view.provisionTargets().size())) {
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                commit();
                return true;
            }
        }
        return super.onKey(keyCode, scanCode, modifiers);
    }

    private void commit() {
        int focus = pick.focus();
        if (committed || focus < 0 || focus >= view.provisionTargets().size()) {
            return;
        }
        committed = true;
        String target = view.provisionTargets().get(focus).id();
        ClientPlayNetworking.send(UseProvisionC2S.target(view.provisionTargetCard(), target));
        LOGGER.info("特殊物资：{} 挑了目标 {}", view.provisionTargetCard(), target);
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return committed ? null : Text.translatable("heavyseas.cue.medical");
    }
}
