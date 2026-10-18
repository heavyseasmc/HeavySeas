package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.net.LiftFloorsS2C;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 电梯楼层面板（{@code LiftScreen}）上的按钮：楼层表 → 一列竖排的按钮，与面板怎么画无关，有单测（{@code LiftPanelTest}）。
 *
 * <p>排法照电梯里的按钮：高的在上。楼层表从低到高来（{@link LiftFloorsS2C}），按钮从上到下 = 楼层从高到低。
 * 当前那一层标出来、按不动；数字键 n 就是从下往上第 n 层（1 = 最低那一层），只到 9。
 */
public final class LiftPanel {

    private LiftPanel() {
    }

    /**
     * 一颗按钮。
     *
     * @param floor 这是楼层表里第几层（从 0 数、从下往上）—— 发请求时就发它
     * @param y     那一层的门在哪个 y（请求里一起发，服务端认出「面板开着时那一列变了」）
     * @param deck  甲板名（{@code none} = 照位置编号）
     * @param here  就是现在这一层：标出来、按不动
     * @param digit 选它的数字键（1–9）；没有是 0
     */
    public record Button(int floor, int y, String deck, boolean here, int digit) {
    }

    /** 楼层表里 {@code none} 那一种甲板名（{@code LinerLift.Deck.NONE}）。 */
    public static final String UNNAMED = "none";

    /**
     * 楼层名：写明了哪一层甲板就叫那个名字（{@code heavyseas.lift.deck.<名>}），{@code none} 照位置编号
     * （从下往上第几扇门，从 1 数 —— 只有手放的门才是 {@code none}）。认不出的名字也当 {@code none}，不让键名露在屏幕上。
     */
    public static Text label(String deck, int floor) {
        return UNNAMED.equals(deck) || deck == null || !deck.matches("[a-z]{1,8}")
                ? Text.translatable("heavyseas.lift.floor", floor + 1)
                : Text.translatable("heavyseas.lift.deck." + deck);
    }

    /** 从上到下的按钮。 */
    public static List<Button> buttons(List<LiftFloorsS2C.Stop> stops, int current) {
        List<Button> out = new ArrayList<>(stops.size());
        for (int i = stops.size() - 1; i >= 0; i--) {
            LiftFloorsS2C.Stop s = stops.get(i);
            out.add(new Button(i, s.y(), s.deck(), i == current, i < 9 ? i + 1 : 0));
        }
        return List.copyOf(out);
    }

    /** 按了数字键 {@code digit}：是哪一颗（按钮表里的下标）；没有这个键的、或那一颗就是当前层的，是 -1。 */
    public static int byDigit(List<Button> buttons, int digit) {
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            if (b.digit() == digit && digit > 0) {
                return b.here() ? -1 : i;
            }
        }
        return -1;
    }

    /** 打开面板时焦点落在哪一颗：当前层的上面那一颗（往上走是更常见的那一趟 —— E 甲板去艇甲板）；当前就是最高层时落在下面那一颗。 */
    public static int initialFocus(List<Button> buttons) {
        int here = -1;
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).here()) {
                here = i;
            }
        }
        if (here < 0) {
            return 0;
        }
        if (here > 0) {
            return here - 1;
        }
        return buttons.size() > 1 ? 1 : 0;
    }

    /** 方向键挪焦点：跳过当前层那一颗（它按不动）；到头就停在原处。{@code step} −1 = 往上（更高的楼层），+1 = 往下。 */
    public static int move(List<Button> buttons, int focus, int step) {
        int i = focus + step;
        while (i >= 0 && i < buttons.size()) {
            if (!buttons.get(i).here()) {
                return i;
            }
            i += step;
        }
        return focus;
    }
}
