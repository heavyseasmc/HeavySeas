package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大厅房主阵容面板：三套预设只是快捷键，八个角色都可自由勾选（对方 Wiki ADR-0033 §7.2）。
 *
 * <h2>与对局里的每一面同一个世界</h2>
 * 这是房主看到的<b>第一个</b> HeavySeas 界面。原先它是一张 Minecraft 自带的 {@code Screen}（自带的按钮、自带的底、纯白字），
 * 一点之后的补给箱却是 ADR-0018 那套语言 —— 同一局里两个视觉世界（2026-09-18 审查抓到的）。
 * 现在它与其余各面共用 {@link GameScreen}：同一层底、同一种按钮、焦点金框与「抬」、方向键与回车。
 *
 * <h2>勾选与焦点是两件事</h2>
 * 焦点 = 「你指着的那个」，金框（金 = 你）；勾选 = 「这个角色上船」，铜绿字（可选 · 安全），
 * 没勾的用次要色。「敲铃开航」在人数对不上时按不动：底与字一起淡下去，与行动一面按不动的那几件同一个样子。
 *
 * <h2>客户端选择不是事实</h2>
 * 服务端收到包后重新检查大厅船、发起者、人数、角色与对局状态才开局；这一面只负责把意图发出去。
 */
public final class RosterScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 八个角色排四列（两行）。{@link GameScreen#layoutButtonGrid} 在一行放不下四个时会自己减到三列、两列，
     * 所以这里给的是上限。❗第一版写的两列：1280×720 实拍，八行加预设一排之后，「敲铃开航 / 取消」那一排掉到窗口外面。
     */
    private static final int COLUMNS = 4;
    private static final int[] PRESETS = {6, 7, 8};

    private final RosterConfigS2C config;
    private final Set<String> selected = new LinkedHashSet<>();
    /** 焦点在整片按钮里的下标：角色格 · 三个预设 · 开航 · 取消，依次排。 */
    private int focus;
    private final float[] lift;
    /** 上一帧排出来的全部按钮（与 {@link #focus} 同一套下标），点击与悬停按它判。 */
    private List<Box> boxes = List.of();

    public RosterScreen(RosterConfigS2C config) {
        super(Text.translatable("heavyseas.roster.title"));
        this.config = config;
        selected.addAll(preset(config.players()));
        this.lift = new float[config.characters().size() + PRESETS.length + 2];
    }

    private int characterCount() {
        return config.characters().size();
    }

    private int startIndex() {
        return characterCount() + PRESETS.length;
    }

    private int cancelIndex() {
        return startIndex() + 1;
    }

    private boolean canStart() {
        return selected.size() == config.players() && selected.size() >= 6 && selected.size() <= 8;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        int fh = textH();

        drawLine(context, title, width / 2, TOP_BAND_Y, GuiLanguage.INK);
        drawLine(context, Text.translatable("heavyseas.roster.detail", config.players(), selected.size()),
                width / 2, TOP_BAND_Y + fh + 3, GuiLanguage.MUTED);

        // 三片按钮自上而下：角色格 · 预设 · 开航与取消。每片顶上都留出「抬」的高度。
        List<Text> characterLabels = new ArrayList<>();
        for (String id : config.characters()) {
            characterLabels.add(label(id));
        }
        int gridTop = TOP_BAND_Y + 2 * (fh + 3) + BTN_GAP + buttonLiftRoom();
        List<Box> grid = layoutButtonGrid(characterLabels, gridTop, BTN_GAP, COLUMNS);
        List<Text> presetLabels = new ArrayList<>();
        for (int players : PRESETS) {
            presetLabels.add(Text.translatable("heavyseas.roster.preset", players));
        }
        int presetsTop = gridTop + rowHeight(grid) + BTN_GAP + buttonLiftRoom();
        List<Box> presets = layoutButtonRow(presetLabels, presetsTop, BTN_GAP);
        List<Text> actionLabels = List.of(Text.translatable("heavyseas.roster.start"), Text.translatable("gui.cancel"));
        int actionsTop = presetsTop + rowHeight(presets) + BTN_GAP + buttonLiftRoom();
        List<Box> actions = layoutButtonRow(actionLabels, actionsTop, BTN_GAP);

        List<Box> all = new ArrayList<>(grid);
        all.addAll(presets);
        all.addAll(actions);
        boxes = all;

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = indexAt(all, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }

        for (int i = 0; i < all.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            Text text;
            int color;
            int fill = GuiLanguage.GROUND;
            if (i < characterCount()) {
                text = characterLabels.get(i);
                color = selected.contains(config.characters().get(i)) ? GuiLanguage.VERDIGRIS : GuiLanguage.MUTED;
            } else if (i < startIndex()) {
                text = presetLabels.get(i - characterCount());
                color = GuiLanguage.INK;
            } else if (i == startIndex()) {
                text = actionLabels.get(0);
                // 人数对不上时按不动：底与字一起淡下去（只淡字与本来就淡字的「取消」分不开）。
                color = canStart() ? GuiLanguage.VERDIGRIS : withAlpha(GuiLanguage.VERDIGRIS, DISABLED_TEXT_ALPHA);
                fill = canStart() ? GuiLanguage.GROUND : withAlpha(GuiLanguage.GROUND, DISABLED_FILL_ALPHA);
            } else {
                text = actionLabels.get(1);
                color = GuiLanguage.MUTED;
            }
            drawButton(context, all.get(i), text, i == focus, color, fill, -lift[i], 1f);
        }
    }

    private void toggle(String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
    }

    private void applyPreset(List<String> ids) {
        selected.clear();
        selected.addAll(ids);
    }

    private List<String> preset(int players) {
        return switch (players) {
            case 6 -> config.preset6();
            case 7 -> config.preset7();
            case 8 -> config.preset8();
            default -> List.of();
        };
    }

    private Text label(String id) {
        Text name = Text.translatable("heavyseas.character." + id);
        return selected.contains(id) ? Text.literal("✓ ").append(name) : name;
    }

    /** 按下焦点所在的那个按钮。 */
    private void activate(int index) {
        if (index < 0 || index >= boxes.size()) {
            return;
        }
        if (index < characterCount()) {
            toggle(config.characters().get(index));
        } else if (index < startIndex()) {
            applyPreset(preset(PRESETS[index - characterCount()]));
        } else if (index == startIndex()) {
            start();
        } else {
            close();
        }
    }

    private void start() {
        if (!canStart()) {
            return;                           // 按不动：人数对不上，上面那行字已经在说
        }
        List<String> ordered = config.characters().stream().filter(selected::contains).toList();
        ClientPlayNetworking.send(new StartVoyageC2S(config.anchor(), ordered));
        // 与语言无关的一行：验收靠它判「阵容真的发出去了」。
        LOGGER.info("阵容：敲铃开航 {} 人 {}", ordered.size(), ordered);
        close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = indexAt(boxes, (int) mouseX, (int) mouseY);
        if (i >= 0) {
            focus = i;
            activate(i);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int last = Math.max(0, boxes.size() - 1);
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                focus = Math.max(0, focus - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                focus = Math.min(last, focus + 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                // 角色格里上下是一列；出了格子就一片一片地退。
                focus = Math.max(0, focus - (focus < characterCount() ? COLUMNS : 1));
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                focus = Math.min(last, focus + (focus + COLUMNS < characterCount() ? COLUMNS : 1));
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                activate(focus);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
