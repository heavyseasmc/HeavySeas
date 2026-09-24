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

/** 医疗箱的第二步：从当前仍可治疗的人里挑一个，含自己。 */
public final class ProvisionTargetScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    /** 目标最多排几列（放不下时 {@link GameScreen#layoutButtonGrid} 自己减）。八个人全伤时两行排完。 */
    private static final int TARGET_COLUMNS = 4;

    private HudView view;
    private int focus;
    private List<Box> boxes = List.of();
    private float[] lift;
    private boolean committed;

    public ProvisionTargetScreen(HudView view) {
        super(Text.translatable("heavyseas.target.title", Text.empty()));
        this.view = view;
        this.lift = new float[Math.max(1, view.provisionTargets().size())];
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
            return;
        }
        if (lift.length != view.provisionTargets().size()) {
            lift = new float[Math.max(1, view.provisionTargets().size())];
            focus = Math.min(focus, view.provisionTargets().size() - 1);
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


        List<Text> labels = new ArrayList<>();
        for (HudView.MedicalTarget target : view.provisionTargets()) {
            // 清醒的人只写体力；昏迷才多写一截 —— 「清醒」写在每一格上是废话，还把一排撑到放不下
            labels.add(target.condition() == io.github.heavyseasmc.engine.state.Condition.CONSCIOUS
                    ? Text.translatable("heavyseas.target.entry", nameOf(target.id()),
                            target.health(), target.maxHealth())
                    : Text.translatable("heavyseas.target.entry_down", nameOf(target.id()),
                            target.health(), target.maxHealth(), conditionName(target.condition())));
        }
        // 按钮排成一片（一行放不下就折成几列），在题头与舞台底边之间居中。
        // ❗2026-09-25 第一次实拍（四面补拍）：原先排成一行、放不下就一行一个 —— 四个人受伤时竖着叠四个，
        //   最后一个压过舞台的下沿线，落进倒计时那一栏。
        int bottom = b.stageBottom();
        int regionTop = titleY + lineStep() + BTN_GAP + buttonLiftRoom();
        int blockH = labels.isEmpty() ? textH() : rowHeight(layoutButtonGrid(labels, 0, BTN_GAP, TARGET_COLUMNS));
        int buttonsTop = regionTop + Math.max(0, (bottom - regionTop - blockH) / 2);
        boxes = layoutButtonGrid(labels, buttonsTop, BTN_GAP, TARGET_COLUMNS);
        if (labels.isEmpty()) {
            drawLine(context, Text.translatable("heavyseas.command.nobody_wounded"),
                    width / 2, buttonsTop, GuiLanguage.muted());
        }
        if (mouseActuallyMoved(mouseX, mouseY) && !committed) {
            int hovered = indexAt(boxes, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }
        focus = Math.max(0, Math.min(focus, Math.max(0, labels.size() - 1)));
        for (int i = 0; i < labels.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            drawButton(context, boxes.get(i), labels.get(i), i == focus,
                    i == focus ? GuiLanguage.verdigris() : GuiLanguage.ink(), lift[i]);
        }

        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(confirm("Enter"), keys("cancel", "Esc")), now, null);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!committed) {
            int picked = indexAt(boxes, (int) mouseX, (int) mouseY);
            if (picked >= 0) {
                focus = picked;
                commit();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            ClientPlayNetworking.send(UseProvisionC2S.cancel());
            LOGGER.info("特殊物资：取消挑目标");
            return true;
        }
        if (!committed) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT -> {
                    focus = Math.max(0, focus - 1);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT -> {
                    focus = Math.min(view.provisionTargets().size() - 1, focus + 1);
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    commit();
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void commit() {
        if (committed || focus < 0 || focus >= view.provisionTargets().size()) {
            return;
        }
        committed = true;
        String target = view.provisionTargets().get(focus).id();
        ClientPlayNetworking.send(UseProvisionC2S.target(view.provisionTargetCard(), target));
        LOGGER.info("特殊物资：{} 挑了目标 {}", view.provisionTargetCard(), target);
    }
}
