package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.LiftFloorsS2C;
import io.github.heavyseasmc.mod.net.LiftTravelC2S;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.LiftPanel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 北辰号电梯的楼层面板：右键轿厢里的按钮面板，服务端发来这一列的楼层表（{@link LiftFloorsS2C}），这里一列竖排、高的在上，点一层就去。
 *
 * <p>与设置菜单、阵容面板同一副骨架（{@link GameScreen}：板 · 上带写标题 · 切角米色牌按钮 · 最下面一行按键提示）。
 * 当前那一层标出来、按不动（底与字一起淡下去，与阵容面板按不动的「开航」同一个样子）；座位轨那一条带写「当前 X」。
 * 按钮怎么从楼层表排出来、方向键与数字键落在哪一颗都在 {@link LiftPanel} 里、有单测；这里只翻译按键、照它画。
 *
 * <h2>客户端选择不是事实</h2>
 * 点了只发「去第几层」（{@link LiftTravelC2S}），服务端从头核对那扇门还在、人在轿厢里、轿厢停在这一层、那一层还在同一列里，才关门出发。
 * 路上画面渐暗轻晃（{@link LiftRideEffect}），到站那一句由服务端发到快捷栏上方。
 */
public final class LiftScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private final LiftFloorsS2C floors;
    private final List<LiftPanel.Button> buttons;
    private final List<Text> labels;
    private int focus;
    private final float[] lift;
    /** 上一帧排出来的按钮（与 {@link #buttons} 同一套下标），点击与悬停按它判。 */
    private List<Box> boxes = List.of();

    public LiftScreen(LiftFloorsS2C floors) {
        super(Text.translatable("heavyseas.lift.title"));
        this.floors = floors;
        this.buttons = LiftPanel.buttons(floors.stops(), floors.current());
        List<Text> names = new ArrayList<>();
        for (LiftPanel.Button b : buttons) {
            names.add(LiftPanel.label(b.deck(), b.floor()));
        }
        this.labels = List.copyOf(names);
        this.focus = LiftPanel.initialFocus(buttons);
        this.lift = new float[buttons.size()];
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Bands b = drawChrome(context, projection());

        // 一列竖排、高的在上（电梯里的按钮）；舞台放不下就排成几列，读的次序仍是从高到低
        int n = buttons.size();
        int h = buttonHeight();
        int step = h + BTN_GAP + buttonLiftRoom();
        int room = Math.max(h, b.stageH() - buttonLiftRoom());
        int perColumn = Math.max(1, (room + BTN_GAP + buttonLiftRoom()) / step);
        int columns = Math.max(1, (n + perColumn - 1) / perColumn);
        List<Box> laid;
        if (columns == 1) {
            int w = 0;
            for (Text label : labels) {
                w = Math.max(w, buttonWidth(label));
            }
            int total = n * h + (n - 1) * (BTN_GAP + buttonLiftRoom());
            int top = b.stageTop() + buttonLiftRoom() + Math.max(0, (room - total) / 2);
            laid = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                laid.add(new Box((paneW() - w) / 2, top + i * step, w, h));
            }
        } else {
            laid = layoutButtonGrid(labels, b.stageTop() + buttonLiftRoom(), BTN_GAP, columns);
        }
        boxes = laid;

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，GameScreen#mouseActuallyMoved）；当前那一层不接焦点
        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = indexAt(laid, mouseX, mouseY);
            if (hovered >= 0 && !buttons.get(hovered).here()) {
                focus = hovered;
            }
        }

        for (int i = 0; i < n; i++) {
            boolean here = buttons.get(i).here();
            lift[i] = GuiLanguage.approach(lift[i], i == focus && !here ? GuiMetrics.units(GuiLanguage.LIFT_PX) : 0f, dt);
            int color = here ? withAlpha(GuiLanguage.ink(), DISABLED_TEXT_ALPHA) : GuiLanguage.ink();
            int fill = here ? withAlpha(GuiLanguage.ground(), DISABLED_FILL_ALPHA) : GuiLanguage.ground();
            drawButton(context, laid.get(i), labels.get(i), i == focus && !here, color, fill, -lift[i], 1f);
        }
        drawFootBand(context, b, List.of(keys("select", "↑", "↓")),
                List.of(primary("go", "Enter"), keys("close", "Esc")), now, null);
    }

    /** 上带写这一面的标题。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, title, paneW() / 2, b.topY(), GuiLanguage.ink());
    }

    /** 没有座位，这一条带上写当前在哪一层。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
        int current = floors.current();
        if (current < 0 || current >= floors.stops().size()) {
            return;
        }
        Text here = LiftPanel.label(floors.stops().get(current).deck(), current);
        drawLine(context, Text.translatable("heavyseas.lift.current", here), paneW() / 2, b.railY(), GuiLanguage.secondary());
    }

    /** 按下第几颗（{@link #buttons} 的下标）：当前那一层按不动。 */
    private void go(int index) {
        if (index < 0 || index >= buttons.size() || buttons.get(index).here()) {
            return;
        }
        LiftPanel.Button target = buttons.get(index);
        ClientPlayNetworking.send(new LiftTravelC2S(floors.gate(), target.floor(), target.y()));
        // 与语言无关的一行：验收靠它判「请求真的发出去了」
        LOGGER.info("电梯：选了第 {} 层（y {}）", target.floor() + 1, target.y());
        close();
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        int i = indexAt(boxes, (int) mouseX, (int) mouseY);
        if (i >= 0 && !buttons.get(i).here()) {
            focus = i;
            go(i);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT -> {
                focus = LiftPanel.move(buttons, focus, -1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT -> {
                focus = LiftPanel.move(buttons, focus, 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                go(focus);
                return true;
            }
            default -> {
                if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
                    int i = LiftPanel.byDigit(buttons, keyCode - GLFW.GLFW_KEY_0);
                    if (i >= 0) {
                        focus = i;
                        go(i);
                    }
                    return true;
                }
                return super.onKey(keyCode, scanCode, modifiers);
            }
        }
    }
}
